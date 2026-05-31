package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.fixtures.Fixtures
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DynamicMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class OneofTest {

    private fun oneofFile() = Fixtures.fileDescriptor {
        addMessageType(
            Fixtures.message("M") {
                addOneofDecl(Fixtures.oneof("choice"))
                addField(Fixtures.field("a", 1, Type.TYPE_STRING, oneofIndex = 0))
                addField(Fixtures.field("b", 2, Type.TYPE_INT32, oneofIndex = 0))
                addField(Fixtures.field("c", 3, Type.TYPE_BOOL, oneofIndex = 0))
            },
        )
    }

    @Test
    fun `zero variants set validates`() {
        val descriptor = oneofFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("{}"))
    }

    @Test
    fun `one variant set validates and parses`() {
        val descriptor = oneofFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val json = Fixtures.mapper.readTree("""{"a":"hi"}""")
        Fixtures.assertValid(schema, json)
        val parsed = Fixtures.parseStrict(descriptor, json.toString())
        assertEquals("hi", parsed.getField(descriptor.findFieldByName("a")))
    }

    @Test
    fun `two variants set is rejected by schema`() {
        val descriptor = oneofFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val invalid = Fixtures.mapper.readTree("""{"a":"hi","b":1}""")
        Fixtures.assertInvalid(schema, invalid)
    }

    @Test
    fun `printed oneof message validates`() {
        val descriptor = oneofFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val message = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("c"), true)
            .build()
        Fixtures.assertValid(schema, Fixtures.printToJson(message))
    }

    @Test
    fun `proto3 optional is not treated as oneof`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addOneofDecl(Fixtures.oneof("_v"))
                    addField(
                        Fixtures.field(
                            "v", 1, Type.TYPE_STRING,
                            oneofIndex = 0, proto3Optional = true,
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val defs = schemaNode["\$defs"]["M"]
        assertEquals(null, defs["allOf"], "synthetic oneof should not produce exclusivity constraints")
    }

    @Test
    fun `enum field forward and backward`() {
        val file = Fixtures.fileDescriptor {
            addEnumType(Fixtures.enumProto("Color", "RED" to 0, "GREEN" to 1, "BLUE" to 2))
            addMessageType(
                Fixtures.message("M") {
                    addField(Fixtures.field("c", 1, Type.TYPE_ENUM, typeName = ".Color"))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(
            ProtoJsonSchema.generate(descriptor, Options(enumPolicy = EnumPolicy.NAME_OR_INTEGER)),
        )
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"c":"GREEN"}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"c":2}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":"PURPLE"}"""))

        val parsedName = Fixtures.parseStrict(descriptor, """{"c":"BLUE"}""")
        val parsedInt = Fixtures.parseStrict(descriptor, """{"c":2}""")
        assertEquals(parsedName, parsedInt)
    }
}
