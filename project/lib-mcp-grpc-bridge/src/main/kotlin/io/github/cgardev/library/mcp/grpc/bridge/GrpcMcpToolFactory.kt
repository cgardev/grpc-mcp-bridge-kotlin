package io.github.cgardev.library.mcp.grpc.bridge

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Message
import com.google.protobuf.util.JsonFormat
import io.github.cgardev.library.protobuf.jsonschema.Options
import io.github.cgardev.library.protobuf.jsonschema.ProtoJsonSchema
import io.github.cgardev.library.protobuf.jsonschema.RootStyle
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.runBlocking

/**
 * Builds [SyncToolSpecification] instances that proxy a gRPC service method in-process: incoming
 * JSON arguments are parsed into the proto request via [JsonFormat], the request is forwarded to
 * the caller-provided suspend function (optionally wrapped by a [McpCallContextBridge]), and the
 * proto response is serialised back to JSON as a `TextContent` result.
 *
 * The input JSON schema is derived from the proto descriptor at tool-creation time, so it stays in
 * lock-step with the proto definition. The schema is emitted with [RootStyle.INLINE] so MCP clients
 * see `"type":"object"` at the root of `inputSchema`, which the protocol requires.
 *
 * @param contextBridge Hook wrapped around each invocation; defaults to a pass-through.
 */
class GrpcMcpToolFactory(
    private val contextBridge: McpCallContextBridge = McpCallContextBridge.PASS_THROUGH,
) {

    private val argumentMapper = ObjectMapper()
    private val jsonPrinter = JsonFormat.printer().omittingInsignificantWhitespace()
    private val jsonParser = JsonFormat.parser().ignoringUnknownFields()

    /**
     * Creates a [SyncToolSpecification] that bridges JSON tool arguments to a typed proto request
     * and forwards the call to [invoke].
     *
     * @param name MCP tool name (must be globally unique inside the server).
     * @param description Human-readable description shown to the model. Should explain what the
     *   tool does and how to populate the input schema.
     * @param requestPrototype Default instance of the proto request type. Used both to obtain the
     *   descriptor for schema generation and to create a fresh builder per invocation.
     * @param invoke Suspend function that performs the actual gRPC call (typically a method
     *   reference on a coroutine service implementation).
     */
    fun <REQ : Message, RES : Message> create(
        name: String,
        description: String,
        requestPrototype: REQ,
        invoke: suspend (REQ) -> RES,
    ): SyncToolSpecification {
        val schemaJson = ProtoJsonSchema.generateAsString(
            requestPrototype.descriptorForType,
            Options(rootStyle = RootStyle.INLINE),
        )
        val tool = McpSchema.Tool.builder()
            .name(name)
            .description(description)
            .inputSchema(McpJsonMapper.getDefault(), schemaJson)
            .build()
        return SyncToolSpecification.builder()
            .tool(tool)
            .callHandler { context, request ->
                try {
                    val argsJson = argumentMapper.writeValueAsString(
                        request.arguments() ?: emptyMap<String, Any>(),
                    )
                    val builder = requestPrototype.newBuilderForType()
                    jsonParser.merge(argsJson, builder)
                    @Suppress("UNCHECKED_CAST")
                    val typedRequest = builder.build() as REQ
                    val response = contextBridge.around(context) {
                        runBlocking { invoke(typedRequest) }
                    }
                    McpSchema.CallToolResult.builder()
                        .addContent(McpSchema.TextContent(jsonPrinter.print(response)))
                        .build()
                } catch (ex: Exception) {
                    McpSchema.CallToolResult.builder()
                        .isError(true)
                        .addContent(McpSchema.TextContent("$name failed: ${ex.message}"))
                        .build()
                }
            }
            .build()
    }
}
