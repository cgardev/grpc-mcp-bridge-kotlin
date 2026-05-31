package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.fixtures.Fixtures
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.google.protobuf.ByteString
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DynamicMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProtoJsonSchemaTest {

    private val factory = JsonNodeFactory.instance

    private fun scalarFile(): com.google.protobuf.Descriptors.FileDescriptor =
        Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("Scalars") {
                    addField(Fixtures.field("i32", 1, Type.TYPE_INT32))
                    addField(Fixtures.field("u32", 2, Type.TYPE_UINT32))
                    addField(Fixtures.field("i64", 3, Type.TYPE_INT64))
                    addField(Fixtures.field("u64", 4, Type.TYPE_UINT64))
                    addField(Fixtures.field("s32", 5, Type.TYPE_SINT32))
                    addField(Fixtures.field("s64", 6, Type.TYPE_SINT64))
                    addField(Fixtures.field("fx32", 7, Type.TYPE_FIXED32))
                    addField(Fixtures.field("fx64", 8, Type.TYPE_FIXED64))
                    addField(Fixtures.field("sfx32", 9, Type.TYPE_SFIXED32))
                    addField(Fixtures.field("sfx64", 10, Type.TYPE_SFIXED64))
                    addField(Fixtures.field("f", 11, Type.TYPE_FLOAT))
                    addField(Fixtures.field("d", 12, Type.TYPE_DOUBLE))
                    addField(Fixtures.field("b", 13, Type.TYPE_BOOL))
                    addField(Fixtures.field("s", 14, Type.TYPE_STRING))
                    addField(Fixtures.field("by", 15, Type.TYPE_BYTES))
                },
            )
        }

    @Test
    fun `empty message serializes and validates against schema`() {
        val descriptor = scalarFile().findMessageTypeByName("Scalars")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val empty = DynamicMessage.newBuilder(descriptor).build()
        Fixtures.assertValid(schema, Fixtures.printToJson(empty))
    }

    @Test
    fun `populated scalars serialize and validate`() {
        val descriptor = scalarFile().findMessageTypeByName("Scalars")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val builder = DynamicMessage.newBuilder(descriptor)
        descriptor.fields.forEach { f ->
            when (f.name) {
                "i32" -> builder.setField(f, 42)
                "u32" -> builder.setField(f, -1)
                "i64" -> builder.setField(f, 1234567890123L)
                "u64" -> builder.setField(f, -1L)
                "s32" -> builder.setField(f, -7)
                "s64" -> builder.setField(f, -8L)
                "fx32" -> builder.setField(f, 9)
                "fx64" -> builder.setField(f, 10L)
                "sfx32" -> builder.setField(f, -11)
                "sfx64" -> builder.setField(f, -12L)
                "f" -> builder.setField(f, 1.5f)
                "d" -> builder.setField(f, 2.5)
                "b" -> builder.setField(f, true)
                "s" -> builder.setField(f, "hi")
                "by" -> builder.setField(f, ByteString.copyFromUtf8("hi"))
            }
        }
        val message = builder.build()
        val json = Fixtures.printToJson(message)
        Fixtures.assertValid(schema, json)
        val parsed = Fixtures.parseStrict(descriptor, json.toString())
        assertEquals(message, parsed)
    }

    @Test
    fun `int64 schema accepts both string and integer`() {
        val descriptor = scalarFile().findMessageTypeByName("Scalars")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val asString = Fixtures.mapper.readTree("""{"i64":"7"}""")
        val asInt = Fixtures.mapper.readTree("""{"i64":7}""")
        Fixtures.assertValid(schema, asString)
        Fixtures.assertValid(schema, asInt)
        val parsedString = Fixtures.parseStrict(descriptor, asString.toString())
        val parsedInt = Fixtures.parseStrict(descriptor, asInt.toString())
        assertEquals(7L, parsedString.getField(descriptor.findFieldByName("i64")))
        assertEquals(7L, parsedInt.getField(descriptor.findFieldByName("i64")))
    }

    @Test
    fun `float field accepts NaN as string`() {
        val descriptor = scalarFile().findMessageTypeByName("Scalars")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"f":"NaN"}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"f":"Infinity"}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"f":"-Infinity"}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"f":1.25}"""))
        val parsed = Fixtures.parseStrict(descriptor, """{"f":"NaN"}""")
        val value = parsed.getField(descriptor.findFieldByName("f")) as Float
        assertTrue(value.isNaN())
    }

    @Test
    fun `additionalProperties false rejects unknown fields`() {
        val descriptor = scalarFile().findMessageTypeByName("Scalars")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        val withExtra = Fixtures.mapper.readTree("""{"i32":1,"unknown":true}""")
        Fixtures.assertInvalid(schema, withExtra)
    }

    @Test
    fun `additionalProperties true accepts unknown fields`() {
        val descriptor = scalarFile().findMessageTypeByName("Scalars")
        val schema = Fixtures.parseSchema(
            ProtoJsonSchema.generate(descriptor, Options(additionalProperties = true)),
        )
        val withExtra = Fixtures.mapper.readTree("""{"i32":1,"unknown":true}""")
        Fixtures.assertValid(schema, withExtra)
    }

    @Test
    fun `repeated scalar field generates array schema`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("R") {
                    addField(
                        Fixtures.field(
                            "tags", 1, Type.TYPE_STRING,
                            label = FieldDescriptorProto.Label.LABEL_REPEATED,
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("R")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"tags":["a","b"]}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"tags":[1]}"""))

        val builder = DynamicMessage.newBuilder(descriptor)
        val f = descriptor.findFieldByName("tags")
        builder.addRepeatedField(f, "a")
        builder.addRepeatedField(f, "b")
        Fixtures.assertValid(schema, Fixtures.printToJson(builder.build()))
        Fixtures.assertValid(schema, Fixtures.printToJson(DynamicMessage.newBuilder(descriptor).build()))
    }

    @Test
    fun `nested message generates ref to defs`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("Outer") {
                    addField(
                        Fixtures.field(
                            "inner", 1, Type.TYPE_MESSAGE, typeName = ".Inner",
                        ),
                    )
                },
            )
            addMessageType(
                Fixtures.message("Inner") {
                    addField(Fixtures.field("value", 1, Type.TYPE_STRING))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Outer")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val defs = schemaNode["\$defs"]
        assertNotNull(defs)
        assertNotNull(defs["Outer"])
        assertNotNull(defs["Inner"])
        val schema = Fixtures.parseSchema(schemaNode)
        val nested = Fixtures.mapper.readTree("""{"inner":{"value":"hi"}}""")
        Fixtures.assertValid(schema, nested)
        val parsed = Fixtures.parseStrict(descriptor, nested.toString())
        assertEquals("hi", (parsed.getField(descriptor.findFieldByName("inner")) as DynamicMessage)
            .getField(descriptor.findFieldByName("inner").messageType.findFieldByName("value")))
    }

    @Test
    fun `jsonName policy default uses camelCase`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addField(Fixtures.field("user_id", 1, Type.TYPE_STRING))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val properties = schemaNode["\$defs"]["M"]["properties"]
        assertNotNull(properties["userId"])
    }

    @Test
    fun `jsonName option override is honored`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addField(Fixtures.field("user_id", 1, Type.TYPE_STRING, jsonName = "uid"))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val properties = schemaNode["\$defs"]["M"]["properties"]
        assertNotNull(properties["uid"])
    }

    @Test
    fun `protoName policy uses snake_case`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addField(Fixtures.field("user_id", 1, Type.TYPE_STRING))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schemaNode = ProtoJsonSchema.generate(
            descriptor,
            Options(fieldNamePolicy = FieldNamePolicy.PROTO_NAME),
        )
        val properties = schemaNode["\$defs"]["M"]["properties"]
        assertNotNull(properties["user_id"])
    }

    @Test
    fun `inline root style emits message inline at root`() {
        val descriptor = scalarFile().findMessageTypeByName("Scalars")
        val schemaNode = ProtoJsonSchema.generate(descriptor, Options(rootStyle = RootStyle.INLINE))
        assertEquals("object", schemaNode["type"].asText())
        assertNotNull(schemaNode["properties"])
    }

    @Test
    fun `generateAsString produces non-empty JSON`() {
        val descriptor = scalarFile().findMessageTypeByName("Scalars")
        val text = ProtoJsonSchema.generateAsString(descriptor)
        assertTrue(text.contains("\$defs"))
    }

    @Test
    fun `bytes field validates base64`() {
        val descriptor = scalarFile().findMessageTypeByName("Scalars")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"by":"aGVsbG8="}"""))
        val parsed = Fixtures.parseStrict(descriptor, """{"by":"aGVsbG8="}""")
        assertEquals("hello", (parsed.getField(descriptor.findFieldByName("by")) as ByteString).toStringUtf8())
    }
}
