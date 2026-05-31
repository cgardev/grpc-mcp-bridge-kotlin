package io.github.cgardev.library.mcp.grpc.bridge

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.DescriptorProtos
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.modelcontextprotocol.common.McpTransportContext
import io.modelcontextprotocol.spec.McpSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [GrpcMcpToolFactory]. The request/response proto type is a `DynamicMessage` built
 * from a hand-assembled descriptor, so the tests need no generated proto code: the message has a
 * single `string value` field, which is enough to exercise schema generation, argument parsing,
 * response serialisation and the context bridge.
 */
class GrpcMcpToolFactoryTest {

    private val factory = GrpcMcpToolFactory()
    private val jsonMapper = ObjectMapper()

    private val descriptor: Descriptors.Descriptor = buildEchoDescriptor()
    private val valueField: Descriptors.FieldDescriptor = descriptor.findFieldByName("value")
    private val prototype: DynamicMessage = DynamicMessage.getDefaultInstance(descriptor)

    private fun echo(value: String): DynamicMessage =
        DynamicMessage.newBuilder(descriptor).setField(valueField, value).build()

    private fun valueOf(message: DynamicMessage): String = message.getField(valueField) as String

    @Test
    fun `tool exposes name, description and inline-root object schema`() {
        val spec = factory.create<DynamicMessage, DynamicMessage>(
            name = "echo",
            description = "Echo the value back.",
            requestPrototype = prototype,
            invoke = { it },
        )

        val tool = spec.tool()
        assertEquals("echo", tool.name())
        assertEquals("Echo the value back.", tool.description())
        val schema = tool.inputSchema()
        assertEquals("object", schema.type(), "inputSchema root must be object, was: $schema")
        assertNotNull(schema.properties())
        assertTrue(
            schema.properties().containsKey("value"),
            "INLINE root should expose request fields directly, was: ${schema.properties()}",
        )
    }

    @Test
    fun `invokes target with parsed proto request and returns serialised response`() {
        var received: DynamicMessage? = null
        val spec = factory.create<DynamicMessage, DynamicMessage>(
            name = "echo",
            description = "d",
            requestPrototype = prototype,
            invoke = { request ->
                received = request
                echo("got: ${valueOf(request)}")
            },
        )

        val result = spec.callHandler().apply(
            McpTransportContext.EMPTY,
            McpSchema.CallToolRequest("echo", mapOf("value" to "hi")),
        )

        assertEquals("hi", received?.let { valueOf(it) })
        val text = (result.content().first() as McpSchema.TextContent).text()
        assertEquals("got: hi", jsonMapper.readTree(text).path("value").asText())
    }

    @Test
    fun `empty arguments produce a default proto request`() {
        var received: DynamicMessage? = null
        val spec = factory.create<DynamicMessage, DynamicMessage>(
            name = "echo",
            description = "d",
            requestPrototype = prototype,
            invoke = { request ->
                received = request
                prototype
            },
        )

        spec.callHandler().apply(
            McpTransportContext.EMPTY,
            McpSchema.CallToolRequest("echo", null as Map<String, Any>?),
        )

        assertEquals("", received?.let { valueOf(it) })
    }

    @Test
    fun `unknown JSON fields are ignored by the lenient parser`() {
        var received: DynamicMessage? = null
        val spec = factory.create<DynamicMessage, DynamicMessage>(
            name = "echo",
            description = "d",
            requestPrototype = prototype,
            invoke = { request ->
                received = request
                prototype
            },
        )

        val result = spec.callHandler().apply(
            McpTransportContext.EMPTY,
            McpSchema.CallToolRequest("echo", mapOf("value" to "hello", "thisFieldDoesNotExist" to 123)),
        )

        assertEquals(false, result.isError() ?: false)
        assertEquals("hello", received?.let { valueOf(it) })
    }

    @Test
    fun `exception from invoke is captured as an isError result`() {
        val spec = factory.create<DynamicMessage, DynamicMessage>(
            name = "echo",
            description = "d",
            requestPrototype = prototype,
            invoke = { throw IllegalStateException("boom") },
        )

        val result = spec.callHandler().apply(
            McpTransportContext.EMPTY,
            McpSchema.CallToolRequest("echo", emptyMap()),
        )

        assertEquals(true, result.isError())
        val text = (result.content().first() as McpSchema.TextContent).text()
        assertTrue(text.contains("boom"), "Error text should include the cause message, was: $text")
        assertTrue(text.contains("echo"), "Error text should mention the tool name, was: $text")
    }

    @Test
    fun `context bridge wraps the invocation`() {
        val events = mutableListOf<String>()
        val bridge = object : McpCallContextBridge {
            override fun <T> around(context: McpTransportContext, block: () -> T): T {
                events += "before"
                return block().also { events += "after" }
            }
        }

        val spec = GrpcMcpToolFactory(bridge).create<DynamicMessage, DynamicMessage>(
            name = "echo",
            description = "d",
            requestPrototype = prototype,
            invoke = {
                events += "invoke"
                prototype
            },
        )

        spec.callHandler().apply(
            McpTransportContext.EMPTY,
            McpSchema.CallToolRequest("echo", emptyMap()),
        )

        assertEquals(listOf("before", "invoke", "after"), events)
    }

    private fun buildEchoDescriptor(): Descriptors.Descriptor {
        val file = DescriptorProtos.FileDescriptorProto.newBuilder()
            .setName("echo.proto")
            .setSyntax("proto3")
            .addMessageType(
                DescriptorProtos.DescriptorProto.newBuilder()
                    .setName("Echo")
                    .addField(
                        DescriptorProtos.FieldDescriptorProto.newBuilder()
                            .setName("value")
                            .setNumber(1)
                            .setType(DescriptorProtos.FieldDescriptorProto.Type.TYPE_STRING)
                            .setLabel(DescriptorProtos.FieldDescriptorProto.Label.LABEL_OPTIONAL),
                    ),
            )
            .build()
        return Descriptors.FileDescriptor.buildFrom(file, emptyArray()).messageTypes.first()
    }
}
