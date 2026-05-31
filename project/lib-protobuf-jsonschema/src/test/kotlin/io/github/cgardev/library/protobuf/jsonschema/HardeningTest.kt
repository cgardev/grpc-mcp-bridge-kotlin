package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.fixtures.Fixtures
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DescriptorProtos.MessageOptions
import com.google.protobuf.DynamicMessage
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Timestamp
import com.google.protobuf.util.JsonFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Hardening tests: protect against regressions identified by coverage audit.
 * Each test pins behaviour that is otherwise indirectly tested or asymmetric with JsonFormat.
 */
class HardeningTest {

    /**
     * Builds the synthetic `<Name>Entry` nested message that the protobuf compiler emits for
     * a `map<K, V>` field, mirroring the helper from `MapTest`.
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
    fun `NAME_OR_INTEGER schema rejects unknown enum tag that JsonFormat parser accepts`() {
        val file = Fixtures.fileDescriptor {
            addEnumType(
                Fixtures.enumProto(
                    "E",
                    "A" to 0,
                    "B" to 1,
                    "C" to 2,
                ),
            )
            addMessageType(
                Fixtures.message("M") {
                    addField(Fixtures.field("c", 1, Type.TYPE_ENUM, typeName = ".E"))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(
            ProtoJsonSchema.generate(descriptor, Options(enumPolicy = EnumPolicy.NAME_OR_INTEGER)),
        )

        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"c":1}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"c":"B"}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":99}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":-1}"""))

        val builder = DynamicMessage.newBuilder(descriptor)
        JsonFormat.parser().merge("""{"c":99}""", builder)
        builder.build()
    }

    @Test
    fun `float field schema rejects arbitrary strings and case-incorrect NaN`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addField(Fixtures.field("f", 1, Type.TYPE_FLOAT))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))

        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"f":"NotANumber"}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"f":"nan"}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"f":"INFINITY"}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"f":"+Infinity"}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"f":"inf"}"""))

        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"f":"NaN"}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"f":"Infinity"}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"f":"-Infinity"}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"f":1.25}"""))
    }

    @Test
    fun `bytes schema is annotation-only for contentEncoding so invalid base64 still validates`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addField(Fixtures.field("by", 1, Type.TYPE_BYTES))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))

        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"by":"!!!"}"""))

        assertFailsWith<InvalidProtocolBufferException> {
            Fixtures.parseStrict(descriptor, """{"by":"!!!"}""")
        }
    }

    @Test
    fun `timestamp schema does not enforce format by default so non-rfc3339 still validates`() {
        val descriptor = Timestamp.getDescriptor()
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))

        Fixtures.assertValid(schema, Fixtures.mapper.readTree("\"not-a-date\""))

        assertFailsWith<InvalidProtocolBufferException> {
            val builder = Timestamp.newBuilder()
            JsonFormat.parser().merge("\"not-a-date\"", builder)
        }
    }

    @Test
    fun `inline root style omits root from defs even when nested refs exist and validates payload`() {
        val nestedFile = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("Outer") {
                    addField(Fixtures.field("inner", 1, Type.TYPE_MESSAGE, typeName = ".Inner"))
                },
            )
            addMessageType(
                Fixtures.message("Inner") {
                    addField(Fixtures.field("v", 1, Type.TYPE_STRING))
                },
            )
        }
        val outerDescriptor = nestedFile.findMessageTypeByName("Outer")
        val schemaNode = ProtoJsonSchema.generate(outerDescriptor, Options(rootStyle = RootStyle.INLINE))

        assertEquals("object", schemaNode["type"].asText())
        assertNotNull(schemaNode["properties"]["inner"])
        val defsNode = schemaNode["\$defs"]
        assertNotNull(defsNode)
        assertNull(defsNode["Outer"])
        assertNotNull(defsNode["Inner"])

        val schema = Fixtures.parseSchema(schemaNode)
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("{}"))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"inner":{"v":"hi"}}"""))

        val flatFile = Fixtures.fileDescriptor(name = "flat.proto") {
            addMessageType(
                Fixtures.message("Flat") {
                    addField(Fixtures.field("a", 1, Type.TYPE_STRING))
                },
            )
        }
        val flatDescriptor = flatFile.findMessageTypeByName("Flat")
        val flatNode = ProtoJsonSchema.generate(flatDescriptor, Options(rootStyle = RootStyle.INLINE))
        val flatDefs = flatNode["\$defs"]
        if (flatDefs != null) {
            assertNull(flatDefs["Flat"])
        }

        val flatSchema = Fixtures.parseSchema(flatNode)
        Fixtures.assertValid(flatSchema, Fixtures.mapper.readTree("{}"))
        Fixtures.assertValid(flatSchema, Fixtures.mapper.readTree("""{"a":"x"}"""))
    }

    @Test
    fun `proto3 optional field round trips both set and unset`() {
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
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))

        val withValue = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("v"), "x")
            .build()
        val withValueJson = Fixtures.printToJson(withValue)
        assertTrue(withValueJson.has("v"))
        Fixtures.assertValid(schema, withValueJson)
        val parsedWithValue = Fixtures.parseStrict(descriptor, withValueJson.toString())
        assertEquals(withValue, parsedWithValue)

        val empty = DynamicMessage.newBuilder(descriptor).build()
        val emptyJson = Fixtures.printToJson(empty)
        assertFalse(emptyJson.has("v"))
        Fixtures.assertValid(schema, emptyJson)
        val parsedEmpty = Fixtures.parseStrict(descriptor, emptyJson.toString())
        assertEquals(empty, parsedEmpty)
    }

    @Test
    fun `map of message places value type in defs and emits ref`() {
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
        val schemaNode = ProtoJsonSchema.generate(descriptor)

        assertNotNull(schemaNode["\$defs"]["Inner"])
        val itemsSchema = schemaNode["\$defs"]["Outer"]["properties"]["items"]
        assertEquals("object", itemsSchema["type"].asText())
        val additional = itemsSchema["additionalProperties"]
        val ref = additional["\$ref"].asText()
        assertTrue(ref.endsWith("/Inner"))

        val schema = Fixtures.parseSchema(schemaNode)
        val json = """{"items":{"k":{"v":"x"}}}"""
        Fixtures.assertValid(schema, Fixtures.mapper.readTree(json))
        val parsed = Fixtures.parseStrict(descriptor, json)
        Fixtures.assertValid(schema, Fixtures.printToJson(parsed))
    }

    @Test
    fun `enum with negative tags computes minimum maximum correctly`() {
        // proto3 requires the first declared enum value to have tag 0, so `ZERO` is declared first.
        // Bounds are derived from minOf/maxOf over the actual tags regardless of declaration order.
        val file = Fixtures.fileDescriptor {
            addEnumType(
                Fixtures.enumProto(
                    "E",
                    "ZERO" to 0,
                    "NEG_TWO" to -2,
                    "POS_FIVE" to 5,
                ),
            )
            addMessageType(
                Fixtures.message("M") {
                    addField(Fixtures.field("c", 1, Type.TYPE_ENUM, typeName = ".E"))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")

        val nameOrIntegerNode = ProtoJsonSchema.generate(
            descriptor,
            Options(enumPolicy = EnumPolicy.NAME_OR_INTEGER),
        )
        val nameOrIntegerSchema = nameOrIntegerNode["\$defs"]["M"]["properties"]["c"]
        val oneOf = nameOrIntegerSchema["oneOf"]
        val integerBranch = oneOf[1]
        assertEquals("integer", integerBranch["type"].asText())
        assertEquals(-2, integerBranch["minimum"].asInt())
        assertEquals(5, integerBranch["maximum"].asInt())
        assertEquals("ZERO=0, NEG_TWO=-2, POS_FIVE=5", nameOrIntegerSchema["description"].asText())

        val integerOnlyNode = ProtoJsonSchema.generate(
            descriptor,
            Options(enumPolicy = EnumPolicy.INTEGER_ONLY),
        )
        val integerOnlySchema = integerOnlyNode["\$defs"]["M"]["properties"]["c"]
        assertEquals("integer", integerOnlySchema["type"].asText())
        assertEquals(-2, integerOnlySchema["minimum"].asInt())
        assertEquals(5, integerOnlySchema["maximum"].asInt())
        assertEquals("ZERO=0, NEG_TWO=-2, POS_FIVE=5", integerOnlySchema["description"].asText())

        val schema = Fixtures.parseSchema(integerOnlyNode)
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":-3}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":6}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"c":-2}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"c":5}"""))
    }

    @Test
    fun `int32 schema rejects values outside int32 range and accepts boundaries`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addField(Fixtures.field("i32", 1, Type.TYPE_INT32))
                    addField(Fixtures.field("u", 2, Type.TYPE_UINT32))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))

        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"i32":2147483648}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"i32":-2147483649}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"i32":2147483647}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"i32":-2147483648}"""))

        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"u":-1}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"u":4294967296}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"u":0}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"u":4294967295}"""))
    }

    @Test
    fun `oneof with two variants emits exactly one exclusion constraint`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("M") {
                    addOneofDecl(Fixtures.oneof("choice"))
                    addField(Fixtures.field("a", 1, Type.TYPE_STRING, oneofIndex = 0))
                    addField(Fixtures.field("b", 2, Type.TYPE_INT32, oneofIndex = 0))
                },
            )
        }
        val descriptor = file.findMessageTypeByName("M")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val allOf = schemaNode["\$defs"]["M"]["allOf"]
        assertNotNull(allOf)
        assertEquals(1, allOf.size())

        val required = allOf[0]["not"]["required"]
        assertEquals(2, required.size())
        val names = listOf(required[0].asText(), required[1].asText()).sorted()
        assertEquals(listOf("a", "b"), names)
    }
}
