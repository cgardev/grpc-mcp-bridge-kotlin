package io.github.cgardev.library.mcp.server

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.server.McpStatelessSyncServer
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.EventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.concurrent.DefaultThreadFactory
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Embedded Netty HTTP/1.1 server that exposes a single MCP endpoint over the Streamable HTTP
 * transport. There is no servlet container and no Spring dependency: Netty terminates HTTP, and the
 * blocking MCP dispatch runs on a virtual-thread executor off the event loop so the event loop is
 * never blocked.
 *
 * @param port The TCP port to bind. Use `0` to bind an ephemeral port and read [boundPort] back.
 * @param mcpEndpoint The single HTTP path under which the MCP transport is served.
 * @param transport The transport that dispatches each request to the MCP runtime.
 * @param mcpServer The MCP runtime, closed gracefully on [stop].
 * @param instanceName A name used for log lines and Netty thread names.
 * @param shutdownGracePeriod How long to wait for the MCP runtime to close on shutdown.
 * @param contextExtractor Optional hook that builds the per-request [McpTransportContext] from the
 *   (lower-cased) request headers; typically used to surface an authenticated principal published
 *   by an upstream proxy to tool handlers.
 * @param maxRequestBytes The maximum aggregated request size accepted before a request is rejected.
 */
class NettyMcpServer(
    private val port: Int,
    private val mcpEndpoint: String,
    private val transport: McpStreamableHttpTransport,
    private val mcpServer: McpStatelessSyncServer,
    private val instanceName: String,
    private val shutdownGracePeriod: Duration = Duration.ofSeconds(20),
    private val contextExtractor: ((headers: Map<String, String>) -> McpTransportContext)? = null,
    private val maxRequestBytes: Int = 4 * 1024 * 1024,
) : AutoCloseable {

    private val running = AtomicBoolean(false)
    private val workExecutor = Executors.newVirtualThreadPerTaskExecutor()

    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null

    /** The port the server is actually bound to. Valid after a successful [start]. */
    val boundPort: Int
        get() = (channel?.localAddress() as? InetSocketAddress)?.port ?: port

    /** Binds the server and starts accepting connections. Idempotent: a second call is a no-op. */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        logger.info(
            "[{}] Starting MCP server (Netty HTTP transport) on port {} at '{}'",
            instanceName,
            port,
            mcpEndpoint,
        )
        val boss = NioEventLoopGroup(1, DefaultThreadFactory("mcp-$instanceName-boss"))
        val worker = NioEventLoopGroup(DefaultThreadFactory("mcp-$instanceName-worker"))
        bossGroup = boss
        workerGroup = worker
        try {
            val bootstrap = ServerBootstrap()
                .group(boss, worker)
                .channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline()
                            .addLast(HttpServerCodec())
                            .addLast(HttpObjectAggregator(maxRequestBytes))
                            .addLast(RequestHandler())
                    }
                })
            channel = bootstrap.bind(port).sync().channel()
            logger.info("[{}] MCP server listening on port {}", instanceName, boundPort)
        } catch (ex: Exception) {
            running.set(false)
            shutdownGroups()
            throw IllegalStateException("[$instanceName] Failed to start MCP server", ex)
        }
    }

    /** Whether the server is started and its listening channel is active. */
    fun isRunning(): Boolean = running.get() && channel?.isActive == true

    /** Closes the MCP runtime gracefully and shuts down Netty. Idempotent. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        logger.info("[{}] Stopping MCP server (grace period {})", instanceName, shutdownGracePeriod)
        try {
            mcpServer.closeGracefully().block(shutdownGracePeriod)
        } catch (ex: Exception) {
            logger.warn("[{}] MCP runtime did not shut down cleanly", instanceName, ex)
        }
        try {
            channel?.close()?.sync()
        } catch (ex: Exception) {
            logger.warn("[{}] Failed to close the server channel cleanly", instanceName, ex)
        } finally {
            shutdownGroups()
            workExecutor.shutdown()
            logger.info("[{}] MCP server stopped", instanceName)
        }
    }

    override fun close() = stop()

    private fun shutdownGroups() {
        bossGroup?.shutdownGracefully()
        workerGroup?.shutdownGracefully()
        bossGroup = null
        workerGroup = null
    }

    private inner class RequestHandler : SimpleChannelInboundHandler<FullHttpRequest>() {

        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            val keepAlive = HttpUtil.isKeepAlive(request)
            val path = request.uri().substringBefore('?')

            if (path != mcpEndpoint) {
                writeReply(ctx, McpHttpReply(404, APPLICATION_JSON, errorBody(404, "Not Found")), keepAlive)
                return
            }
            if (request.method() != HttpMethod.POST) {
                // The stateless transport offers no server-initiated SSE stream, so only POST is served.
                writeReply(ctx, McpHttpReply(405, APPLICATION_JSON, errorBody(405, "Method Not Allowed")), keepAlive)
                return
            }

            // Extract everything off the reference-counted request before going asynchronous, since
            // SimpleChannelInboundHandler releases it once this method returns.
            val body = request.content().toString(StandardCharsets.UTF_8)
            val headers = request.headers().associate { (name, value) -> name.lowercase() to value }
            val context = contextExtractor?.invoke(headers) ?: McpTransportContext.EMPTY

            workExecutor.execute {
                val reply = try {
                    transport.handle(body, context)
                } catch (ex: Exception) {
                    McpHttpReply(500, APPLICATION_JSON, errorBody(500, ex.message ?: "Internal error"))
                }
                // writeAndFlush is thread-safe; Netty hands the write off to the channel's event loop.
                writeReply(ctx, reply, keepAlive)
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            logger.warn("[{}] Channel error", instanceName, cause)
            ctx.close()
        }
    }

    private fun writeReply(ctx: ChannelHandlerContext, reply: McpHttpReply, keepAlive: Boolean) {
        val content = Unpooled.copiedBuffer(reply.body, StandardCharsets.UTF_8)
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.valueOf(reply.status),
            content,
        )
        if (reply.contentType != null) {
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "${reply.contentType}; charset=utf-8")
        }
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes())
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
            ctx.writeAndFlush(response)
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }
    }

    private fun errorBody(status: Int, message: String): String =
        """{"status":$status,"error":"$message"}"""

    companion object {
        private const val APPLICATION_JSON = McpStreamableHttpTransport.APPLICATION_JSON
        private val logger = LoggerFactory.getLogger(NettyMcpServer::class.java)
    }
}
