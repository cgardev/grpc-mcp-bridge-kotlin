package io.github.cgardev.library.mcp.server

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerHandler
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.spec.McpStatelessServerTransport
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Minimal HTTP reply produced by [McpStreamableHttpTransport.handle]: a status code, an optional
 * content type, and a UTF-8 body. [NettyMcpServer] writes it straight back to the client.
 *
 * @property status The HTTP status code.
 * @property contentType The response content type, or `null` when the body is empty.
 * @property body The UTF-8 response body.
 */
data class McpHttpReply(
    val status: Int,
    val contentType: String?,
    val body: String,
)

/**
 * MCP Streamable HTTP transport backed by a plain HTTP request/response exchange instead of a
 * servlet container. [NettyMcpServer] feeds each inbound HTTP POST body to [handle] and writes the
 * returned [McpHttpReply] back to the caller.
 *
 * The transport is stateless: every request is dispatched to the MCP runtime independently, with no
 * session affinity and no server-initiated streaming. That is the right model for a tool proxy,
 * where each call maps to a single JSON-RPC request and its response.
 *
 * @param jsonMapper The MCP JSON mapper used to parse inbound messages and serialise responses.
 * @param requestTimeout The maximum time to wait for the MCP runtime to produce a response.
 */
class McpStreamableHttpTransport(
    private val jsonMapper: McpJsonMapper = McpJsonMapper.getDefault(),
    private val requestTimeout: Duration = Duration.ofSeconds(20),
) : McpStatelessServerTransport {

    @Volatile
    private var handler: McpStatelessServerHandler? = null

    /**
     * Invoked by the MCP runtime when the server is built, supplying the handler that processes
     * JSON-RPC requests and notifications.
     */
    override fun setMcpHandler(handler: McpStatelessServerHandler) {
        this.handler = handler
    }

    /** Nothing to release: the transport holds no connections or sessions of its own. */
    override fun closeGracefully(): Mono<Void> = Mono.empty()

    /**
     * Dispatches a single MCP Streamable HTTP POST body and produces the reply to write back.
     *
     * @param body The raw JSON-RPC message sent by the client.
     * @param context Per-request transport context (for example values extracted from request headers).
     */
    fun handle(body: String, context: McpTransportContext): McpHttpReply {
        val mcpHandler = handler ?: return jsonError(503, "MCP server is not ready")
        return try {
            when (val message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body)) {
                is McpSchema.JSONRPCRequest -> {
                    val response = mcpHandler.handleRequest(context, message).block(requestTimeout)
                    McpHttpReply(200, APPLICATION_JSON, jsonMapper.writeValueAsString(response))
                }
                is McpSchema.JSONRPCNotification -> {
                    mcpHandler.handleNotification(context, message).block(requestTimeout)
                    McpHttpReply(202, null, "")
                }
                else -> jsonError(400, "Only JSON-RPC requests and notifications are accepted")
            }
        } catch (ex: Exception) {
            logger.warn("Failed to handle MCP request", ex)
            jsonError(500, ex.message ?: "Internal error")
        }
    }

    private fun jsonError(status: Int, message: String): McpHttpReply =
        McpHttpReply(status, APPLICATION_JSON, """{"status":$status,"error":${quote(message)}}""")

    companion object {
        const val APPLICATION_JSON: String = "application/json"

        private val logger = LoggerFactory.getLogger(McpStreamableHttpTransport::class.java)

        private fun quote(value: String): String = buildString(value.length + 2) {
            append('"')
            for (c in value) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}
