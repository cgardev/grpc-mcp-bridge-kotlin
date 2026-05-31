package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.fixtures.Fixtures
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DynamicMessage
import com.google.protobuf.util.JsonFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class RoundTripTest {

    @Test
    fun `proto to json to proto produces equal message for nested`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("Person") {
                    addField(Fixtures.field("name", 1, Type.TYPE_STRING))
                    addField(Fixtures.field("age", 2, Type.TYPE_INT32))
                    addField(
                        Fixtures.field(
                            "nicknames", 3, Type.TYPE_STRING,
                            label = FieldDescriptorProto.Label.LABEL_REPEATED,
                        ),
                    )
                    addField(
                        Fixtures.field(
                            "spouse", 4, Type.TYPE_MESSAGE, typeName = ".Person",
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Person")
        val nameField = descriptor.findFieldByName("name")
        val ageField = descriptor.findFieldByName("age")
        val nicknamesField = descriptor.findFieldByName("nicknames")
        val spouseField = descriptor.findFieldByName("spouse")

        val spouse = DynamicMessage.newBuilder(descriptor)
            .setField(nameField, "Jane")
            .setField(ageField, 30)
            .build()
        val originalBuilder = DynamicMessage.newBuilder(descriptor)
            .setField(nameField, "John")
            .setField(ageField, 31)
            .setField(spouseField, spouse)
        originalBuilder.addRepeatedField(nicknamesField, "Johnny")
        originalBuilder.addRepeatedField(nicknamesField, "JD")
        val original = originalBuilder.build()

        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val printed = JsonFormat.printer().print(original)
        val parsedNode = Fixtures.mapper.readTree(printed)
        Fixtures.assertValid(schema, parsedNode)

        val roundTripped = Fixtures.parseStrict(descriptor, printed)
        assertEquals(original, roundTripped)
    }

    @Test
    fun `large numeric values round trip via string form`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("Big") {
                    addField(Fixtures.field("i64", 1, Type.TYPE_INT64))
                    addField(Fixtures.field("u64", 2, Type.TYPE_UINT64))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Big")
        val original = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("i64"), Long.MIN_VALUE)
            .setField(descriptor.findFieldByName("u64"), -1L)
            .build()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val printed = JsonFormat.printer().print(original)
        Fixtures.assertValid(schema, Fixtures.mapper.readTree(printed))
        val parsed = Fixtures.parseStrict(descriptor, printed)
        assertEquals(original, parsed)
    }
}
