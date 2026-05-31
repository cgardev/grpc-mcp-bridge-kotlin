package io.github.cgardev.library.mcp.grpc.bridge

import io.modelcontextprotocol.common.McpTransportContext

/**
 * Hook invoked around each bridged gRPC call. It gives callers a place to translate per-request MCP
 * transport state (for example an authenticated principal published by an upstream proxy into the
 * [McpTransportContext]) into whatever ambient context the downstream gRPC handler expects — such as
 * an `io.grpc.Context` — before [around] runs the call.
 *
 * The default [PASS_THROUGH] implementation runs the call unchanged, which is correct when no
 * context propagation is required. Because the single method is generic, this is a plain interface
 * rather than a `fun interface`: implement it with an object expression.
 */
interface McpCallContextBridge {

    /**
     * Runs [block] within whatever context is derived from [context], returning its result.
     */
    fun <T> around(context: McpTransportContext, block: () -> T): T

    companion object {
        /** Runs the call unchanged, deriving no additional context. */
        val PASS_THROUGH: McpCallContextBridge = object : McpCallContextBridge {
            override fun <T> around(context: McpTransportContext, block: () -> T): T = block()
        }
    }
}
