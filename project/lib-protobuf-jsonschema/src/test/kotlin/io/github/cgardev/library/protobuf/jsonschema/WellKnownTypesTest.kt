package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.fixtures.Fixtures
import com.google.protobuf.BoolValue
import com.google.protobuf.BytesValue
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.Duration
import com.google.protobuf.DynamicMessage
import com.google.protobuf.Empty
import com.google.protobuf.FieldMask
import com.google.protobuf.Int32Value
import com.google.protobuf.Int64Value
import com.google.protobuf.StringValue
import com.google.protobuf.Timestamp
import com.google.protobuf.UInt32Value
import com.google.protobuf.UInt64Value
import com.google.protobuf.util.Durations
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.util.Timestamps
import kotlin.test.Test
import kotlin.test.assertEquals

class WellKnownTypesTest {

    @Test
    fun `timestamp schema accepts rfc3339`() {
        val descriptor = Timestamp.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val message = Timestamps.fromMillis(1_700_000_000_000L)
        val ts = Timestamp.newBuilder().setSeconds(message.seconds).setNanos(message.nanos).build()
        val json = Fixtures.printToJson(ts)
        Fixtures.assertValid(schema, json)
    }

    @Test
    fun `duration schema accepts canonical form`() {
        val descriptor = Duration.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val d = Durations.fromMillis(1_500L)
        val dur = Duration.newBuilder().setSeconds(d.seconds).setNanos(d.nanos).build()
        Fixtures.assertValid(schema, Fixtures.printToJson(dur))
    }

    @Test
    fun `bool value serializes as boolean literal and validates`() {
        val descriptor = BoolValue.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val v = BoolValue.of(true)
        Fixtures.assertValid(schema, Fixtures.printToJson(v))
    }

    @Test
    fun `string value serializes as string and validates`() {
        val descriptor = StringValue.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.printToJson(StringValue.of("hello")))
    }

    @Test
    fun `int32 wrapper validates`() {
        val descriptor = Int32Value.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.printToJson(Int32Value.of(42)))
    }

    @Test
    fun `int64 wrapper validates as string`() {
        val descriptor = Int64Value.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.printToJson(Int64Value.of(9_999_999_999L)))
    }

    @Test
    fun `uint32 wrapper validates`() {
        val descriptor = UInt32Value.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.printToJson(UInt32Value.of(7)))
    }

    @Test
    fun `uint64 wrapper validates`() {
        val descriptor = UInt64Value.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.printToJson(UInt64Value.of(7L)))
    }

    @Test
    fun `bytes value validates as base64 string`() {
        val descriptor = BytesValue.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(
            schema,
            Fixtures.printToJson(BytesValue.of(com.google.protobuf.ByteString.copyFromUtf8("hi"))),
        )
    }

    @Test
    fun `empty validates against empty object`() {
        val descriptor = Empty.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("{}"))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"x":1}"""))
    }

    @Test
    fun `field mask validates as string`() {
        val descriptor = FieldMask.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val mask = FieldMask.newBuilder().addPaths("a.b").addPaths("c").build()
        Fixtures.assertValid(schema, Fixtures.printToJson(mask))
    }

    @Test
    fun `wkt field inside custom message inlines schema`() {
        val file = Fixtures.fileDescriptor(
            wellKnownDependencies = listOf(Timestamp.getDescriptor().file),
        ) {
            addMessageType(
                Fixtures.message("Event") {
                    addField(
                        Fixtures.field(
                            "when", 1, Type.TYPE_MESSAGE,
                            typeName = ".google.protobuf.Timestamp",
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Event")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val schema = Fixtures.parseSchema(schemaNode)
        val builder = DynamicMessage.newBuilder(descriptor)
        val ts = Timestamps.fromMillis(1_700_000_000_000L)
        builder.setField(descriptor.findFieldByName("when"), DynamicMessage.newBuilder(Timestamp.getDescriptor())
            .mergeFrom(Timestamp.newBuilder().setSeconds(ts.seconds).setNanos(ts.nanos).build().toByteArray())
            .build())
        val message = builder.build()
        val json = Fixtures.printToJson(message)
        Fixtures.assertValid(schema, json)
        val parsed = Fixtures.parseStrict(descriptor, json.toString())
        assertEquals(message, parsed)
        val whenSchema = schemaNode["\$defs"]["Event"]["properties"]["when"]
        assertEquals("string", whenSchema["type"].asText())
        assertEquals("date-time", whenSchema["format"].asText())
    }

    @Test
    fun `wkt parser also accepts integer for int64 wrapper`() {
        val descriptor = Int64Value.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("42"))
        val builder = Int64Value.newBuilder()
        JsonFormat.parser().merge("42", builder)
        assertEquals(42L, builder.value)
    }
}
