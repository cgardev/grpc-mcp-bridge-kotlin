package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.fixtures.Fixtures
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DynamicMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RecursiveTest {

    @Test
    fun `self recursive tree round trips`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("Tree") {
                    addField(Fixtures.field("name", 1, Type.TYPE_STRING))
                    addField(
                        Fixtures.field(
                            "children", 2, Type.TYPE_MESSAGE,
                            typeName = ".Tree",
                            label = FieldDescriptorProto.Label.LABEL_REPEATED,
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Tree")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val defs = schemaNode["\$defs"]
        assertNotNull(defs["Tree"])
        val childrenSchema = defs["Tree"]["properties"]["children"]
        assertEquals("array", childrenSchema["type"].asText())
        assertTrue(childrenSchema["items"]["\$ref"].asText().endsWith("Tree"))

        val schema = Fixtures.parseSchema(schemaNode)
        val nameField = descriptor.findFieldByName("name")
        val childrenField = descriptor.findFieldByName("children")
        val leaf = DynamicMessage.newBuilder(descriptor).setField(nameField, "leaf").build()
        val root = DynamicMessage.newBuilder(descriptor)
            .setField(nameField, "root")
            .addRepeatedField(childrenField, leaf)
            .build()
        val json = Fixtures.printToJson(root)
        Fixtures.assertValid(schema, json)
        val parsed = Fixtures.parseStrict(descriptor, json.toString())
        assertEquals(root, parsed)
    }

    @Test
    fun `mutually recursive A and B`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("A") {
                    addField(
                        Fixtures.field("b", 1, Type.TYPE_MESSAGE, typeName = ".B"),
                    )
                },
            )
            addMessageType(
                Fixtures.message("B") {
                    addField(
                        Fixtures.field("a", 1, Type.TYPE_MESSAGE, typeName = ".A"),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("A")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val defs = schemaNode["\$defs"]
        assertNotNull(defs["A"])
        assertNotNull(defs["B"])
        val schema = Fixtures.parseSchema(schemaNode)
        val json = Fixtures.mapper.readTree("""{"b":{"a":{}}}""")
        Fixtures.assertValid(schema, json)
        val parsed = Fixtures.parseStrict(descriptor, json.toString())
        assertEquals("""{"b":{"a":{}}}""".replace(" ", ""),
            com.google.protobuf.util.JsonFormat.printer().omittingInsignificantWhitespace().print(parsed))
    }

    @Test
    fun `shared sub-message appears once in defs`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("Shared") {
                    addField(Fixtures.field("v", 1, Type.TYPE_STRING))
                },
            )
            addMessageType(
                Fixtures.message("Outer") {
                    addField(Fixtures.field("first", 1, Type.TYPE_MESSAGE, typeName = ".Shared"))
                    addField(Fixtures.field("second", 2, Type.TYPE_MESSAGE, typeName = ".Shared"))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Outer")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val defs = schemaNode["\$defs"]
        assertNotNull(defs["Shared"])
        assertNotNull(defs["Outer"])
        val firstRef = defs["Outer"]["properties"]["first"]["\$ref"].asText()
        val secondRef = defs["Outer"]["properties"]["second"]["\$ref"].asText()
        assertEquals(firstRef, secondRef)
    }
}
