package io.github.cgardev.library.mcp.server

import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpStatelessServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import java.time.Duration

/**
 * Assembles a stateless MCP runtime and an embedded [NettyMcpServer] that exposes it over the
 * Streamable HTTP transport. The returned server is configured but not started; call
 * [NettyMcpServer.start] to bind it.
 */
object McpServerBuilder {

    /**
     * Builds an MCP server exposing [tools] over a Netty HTTP transport.
     *
     * @param port The TCP port to bind. Use `0` for an ephemeral port.
     * @param serverName The MCP server name advertised during the initialize handshake.
     * @param serverVersion The MCP server version advertised during the initialize handshake.
     * @param tools The stateless tool specifications to register (for example produced by the
     *   MCP-gRPC bridge).
     * @param instructions Optional server-wide instructions advertised to clients.
     * @param requestTimeout Per-request timeout applied to MCP dispatch.
     * @param mcpEndpoint The HTTP path under which the MCP transport is served.
     * @param instanceName A name used for logging and Netty thread names.
     * @param contextExtractor Optional hook building a per-request [McpTransportContext] from the
     *   request headers.
     * @param jsonMapper The MCP JSON mapper used by the transport.
     * @return The configured, not-yet-started server.
     */
    fun build(
        port: Int,
        serverName: String,
        serverVersion: String,
        tools: List<McpStatelessServerFeatures.SyncToolSpecification> = emptyList(),
        instructions: String? = null,
        requestTimeout: Duration = Duration.ofSeconds(20),
        mcpEndpoint: String = "/",
        instanceName: String = serverName,
        contextExtractor: ((headers: Map<String, String>) -> McpTransportContext)? = null,
        jsonMapper: McpJsonMapper = McpJsonMapper.getDefault(),
    ): NettyMcpServer {
        val transport = McpStreamableHttpTransport(jsonMapper, requestTimeout)

        val capabilities = McpSchema.ServerCapabilities.builder()
            .apply { if (tools.isNotEmpty()) tools(false) }
            .build()

        // Building the server wires the runtime's handler into the transport via setMcpHandler.
        val specification = McpServer.sync(transport)
            .serverInfo(serverName, serverVersion)
            .capabilities(capabilities)
            .requestTimeout(requestTimeout)
        if (tools.isNotEmpty()) specification.tools(tools)
        if (instructions != null) specification.instructions(instructions)
        val mcpServer = specification.build()

        return NettyMcpServer(
            port = port,
            mcpEndpoint = mcpEndpoint,
            transport = transport,
            mcpServer = mcpServer,
            instanceName = instanceName,
            shutdownGracePeriod = requestTimeout,
            contextExtractor = contextExtractor,
        )
    }
}
