package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.fixtures.Fixtures
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.DynamicMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class MapTest {

    /**
     * Builds the synthetic `<Name>Entry` nested message that the protobuf compiler emits for
     * a `map<K, V>` field, including the `map_entry = true` option that flips `isMapField`.
     */
    private fun mapEntry(
        name: String,
        keyType: Type,
        valueType: Type,
        valueTypeName: String? = null,
    ): DescriptorProto = DescriptorProto.newBuilder()
        .setName(name)
        .addField(
            Fixtures.field(
                "key", 1, keyType,
                label = FieldDescriptorProto.Label.LABEL_OPTIONAL,
            ),
        )
        .addField(
            Fixtures.field(
                "value", 2, valueType,
                label = FieldDescriptorProto.Label.LABEL_OPTIONAL,
                typeName = valueTypeName,
            ),
        )
        .setOptions(MessageOptions.newBuilder().setMapEntry(true))
        .build()

    @Test
    fun `map of string to int32`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addNestedType(mapEntry("CountsEntry", Type.TYPE_STRING, Type.TYPE_INT32))
                    addField(
                        Fixtures.field(
                            "counts", 1, Type.TYPE_MESSAGE,
                            typeName = ".M.CountsEntry",
                            label = FieldDescriptorProto.Label.LABEL_REPEATED,
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"counts":{"a":1,"b":2}}"""))
        val parsed = Fixtures.parseStrict(descriptor, """{"counts":{"a":1,"b":2}}""")
        val entries = parsed.getField(descriptor.findFieldByName("counts")) as List<*>
        assertEquals(2, entries.size)
    }

    @Test
    fun `map of int32 to string stringifies keys`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addNestedType(mapEntry("LabelsEntry", Type.TYPE_INT32, Type.TYPE_STRING))
                    addField(
                        Fixtures.field(
                            "labels", 1, Type.TYPE_MESSAGE,
                            typeName = ".M.LabelsEntry",
                            label = FieldDescriptorProto.Label.LABEL_REPEATED,
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val json = """{"labels":{"1":"one","2":"two"}}"""
        Fixtures.assertValid(schema, Fixtures.mapper.readTree(json))
        val parsed = Fixtures.parseStrict(descriptor, json)
        val printed = Fixtures.printToJson(parsed)
        Fixtures.assertValid(schema, printed)
    }

    @Test
    fun `map of string to nested message`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("Inner") {
                    addField(Fixtures.field("v", 1, Type.TYPE_STRING))
                },
            )
            addMessageType(
                Fixtures.message("Outer") {
                    addNestedType(
                        mapEntry(
                            "ItemsEntry", Type.TYPE_STRING, Type.TYPE_MESSAGE,
                            valueTypeName = ".Inner",
                        ),
                    )
                    addField(
                        Fixtures.field(
                            "items", 1, Type.TYPE_MESSAGE,
                            typeName = ".Outer.ItemsEntry",
                            label = FieldDescriptorProto.Label.LABEL_REPEATED,
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Outer")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val json = """{"items":{"k":{"v":"x"}}}"""
        Fixtures.assertValid(schema, Fixtures.mapper.readTree(json))
        val parsed = Fixtures.parseStrict(descriptor, json)
        val printed = Fixtures.printToJson(parsed)
        Fixtures.assertValid(schema, printed)
    }

    @Test
    fun `printed empty message validates with map field`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addNestedType(mapEntry("MEntry", Type.TYPE_STRING, Type.TYPE_INT32))
                    addField(
                        Fixtures.field(
                            "m", 1, Type.TYPE_MESSAGE,
                            typeName = ".M.MEntry",
                            label = FieldDescriptorProto.Label.LABEL_REPEATED,
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val empty = DynamicMessage.newBuilder(descriptor).build()
        Fixtures.assertValid(schema, Fixtures.printToJson(empty))
    }
}
