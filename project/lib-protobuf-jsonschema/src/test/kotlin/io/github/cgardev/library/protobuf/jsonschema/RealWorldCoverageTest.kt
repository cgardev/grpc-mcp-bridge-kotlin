package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.fixtures.Fixtures
import com.google.protobuf.Any
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.DescriptorProtos.FieldOptions
import com.google.protobuf.DynamicMessage
import com.google.protobuf.StringValue
import com.google.protobuf.Timestamp
import com.google.protobuf.UnknownFieldSet
import com.google.protobuf.util.JsonFormat
import com.google.protobuf.util.Timestamps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for patterns observed in real `.proto` files across the repository that were not
 * exercised by the original suite (oneof with many variants, `google.protobuf.Any` shapes,
 * nested enums, empty request/response messages and custom unknown field options).
 */
class RealWorldCoverageTest {

    @Test
    fun `oneof with 15 message variants emits 105 pairwise exclusions and validates`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(Fixtures.message("Leaf") { addField(Fixtures.field("v", 1, Type.TYPE_STRING)) })
            addMessageType(
                Fixtures.message("BigOneof") {
                    addOneofDecl(Fixtures.oneof("choice"))
                    for (i in 1..15) {
                        addField(
                            Fixtures.field(
                                "opt$i", i, Type.TYPE_MESSAGE,
                                typeName = ".Leaf",
                                oneofIndex = 0,
                            ),
                        )
                    }
                },
            )
        }
        val descriptor = file.findMessageTypeByName("BigOneof")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val allOf = schemaNode["\$defs"]["BigOneof"]["allOf"]
        assertNotNull(allOf)
        assertEquals(105, allOf.size())
        for (constraint in allOf) {
            val required = constraint["not"]["required"]
            assertEquals(2, required.size())
        }

        val schema = Fixtures.parseSchema(schemaNode)
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("{}"))

        val leafDescriptor = file.findMessageTypeByName("Leaf")
        val leaf = DynamicMessage.newBuilder(leafDescriptor)
            .setField(leafDescriptor.findFieldByName("v"), "hello")
            .build()
        val singleVariant = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("opt7"), leaf)
            .build()
        Fixtures.assertValid(schema, Fixtures.printToJson(singleVariant))

        val twoVariants = Fixtures.mapper.readTree(
            """{"opt1":{"v":"a"},"opt2":{"v":"b"}}""",
        )
        Fixtures.assertInvalid(schema, twoVariants)

        val parsed = Fixtures.parseStrict(descriptor, """{"opt7":{"v":"hello"}}""")
        val parsedLeaf = parsed.getField(descriptor.findFieldByName("opt7")) as DynamicMessage
        assertEquals("hello", parsedLeaf.getField(leafDescriptor.findFieldByName("v")))
    }

    @Test
    fun `Any field round trips and uses well known schema`() {
        val file = Fixtures.fileDescriptor(
            wellKnownDependencies = listOf(Any.getDescriptor().file),
        ) {
            addMessageType(
                Fixtures.message("TestOp") {
                    addField(
                        Fixtures.field(
                            "metadata", 1, Type.TYPE_MESSAGE,
                            typeName = ".google.protobuf.Any",
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("TestOp")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val metadataSchema = schemaNode["\$defs"]["TestOp"]["properties"]["metadata"]
        assertEquals("object", metadataSchema["type"].asText())
        assertNotNull(metadataSchema["properties"]["@type"])
        assertEquals("string", metadataSchema["properties"]["@type"]["type"].asText())
        val required = metadataSchema["required"]
        assertEquals(1, required.size())
        assertEquals("@type", required[0].asText())

        val ts = Timestamps.fromMillis(1_700_000_000_000L)
        val packed = Any.pack(Timestamp.newBuilder().setSeconds(ts.seconds).setNanos(ts.nanos).build())
        val packedDynamic = DynamicMessage.newBuilder(Any.getDescriptor())
            .mergeFrom(packed.toByteArray())
            .build()
        val message = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("metadata"), packedDynamic)
            .build()

        val schema = Fixtures.parseSchema(schemaNode)
        val typeRegistry = JsonFormat.TypeRegistry.newBuilder()
            .add(Timestamp.getDescriptor())
            .build()
        val printer = JsonFormat.printer().usingTypeRegistry(typeRegistry)
        val printedText = printer.print(message)
        val printedNode = Fixtures.mapper.readTree(printedText)
        Fixtures.assertValid(schema, printedNode)
        assertEquals(
            "type.googleapis.com/google.protobuf.Timestamp",
            printedNode["metadata"]["@type"].asText(),
        )

        val parserRegistry = JsonFormat.parser().usingTypeRegistry(typeRegistry)
        val builder = DynamicMessage.newBuilder(descriptor)
        parserRegistry.merge(printedText, builder)
        assertEquals(message, builder.build())

        Fixtures.assertInvalid(
            schema,
            Fixtures.mapper.readTree("""{"metadata":{"value":"x"}}"""),
        )
    }

    @Test
    fun `Any inside oneof with another message variant is pairwise exclusive`() {
        val file = Fixtures.fileDescriptor(
            wellKnownDependencies = listOf(Any.getDescriptor().file),
        ) {
            addMessageType(
                Fixtures.message("ErrorMsg") {
                    addField(Fixtures.field("code", 1, Type.TYPE_STRING))
                    addField(Fixtures.field("message", 2, Type.TYPE_STRING))
                },
            )
            addMessageType(
                Fixtures.message("Result") {
                    addOneofDecl(Fixtures.oneof("outcome"))
                    addField(
                        Fixtures.field(
                            "error", 1, Type.TYPE_MESSAGE,
                            typeName = ".ErrorMsg",
                            oneofIndex = 0,
                        ),
                    )
                    addField(
                        Fixtures.field(
                            "response", 2, Type.TYPE_MESSAGE,
                            typeName = ".google.protobuf.Any",
                            oneofIndex = 0,
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Result")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val resultDef = schemaNode["\$defs"]["Result"]
        assertNotNull(resultDef["properties"]["error"])
        assertNotNull(resultDef["properties"]["response"])
        val allOf = resultDef["allOf"]
        assertNotNull(allOf)
        assertEquals(1, allOf.size())
        val required = allOf[0]["not"]["required"]
        val names = listOf(required[0].asText(), required[1].asText()).sorted()
        assertEquals(listOf("error", "response"), names)

        val schema = Fixtures.parseSchema(schemaNode)

        val errorDescriptor = file.findMessageTypeByName("ErrorMsg")
        val error = DynamicMessage.newBuilder(errorDescriptor)
            .setField(errorDescriptor.findFieldByName("code"), "NOT_FOUND")
            .setField(errorDescriptor.findFieldByName("message"), "missing")
            .build()
        val withError = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("error"), error)
            .build()
        Fixtures.assertValid(schema, Fixtures.printToJson(withError))

        val packed = Any.pack(StringValue.of("ok"))
        val packedDynamic = DynamicMessage.newBuilder(Any.getDescriptor())
            .mergeFrom(packed.toByteArray())
            .build()
        val withResponse = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("response"), packedDynamic)
            .build()
        val typeRegistry = JsonFormat.TypeRegistry.newBuilder()
            .add(StringValue.getDescriptor())
            .build()
        val printedText = JsonFormat.printer().usingTypeRegistry(typeRegistry).print(withResponse)
        Fixtures.assertValid(schema, Fixtures.mapper.readTree(printedText))

        Fixtures.assertInvalid(
            schema,
            Fixtures.mapper.readTree(
                """{"error":{"code":"X","message":"y"},"response":{"@type":"type.googleapis.com/google.protobuf.StringValue","value":"ok"}}""",
            ),
        )
    }

    @Test
    fun `repeated Any field generates array of Any schemas`() {
        val file = Fixtures.fileDescriptor(
            wellKnownDependencies = listOf(Any.getDescriptor().file),
        ) {
            addMessageType(
                Fixtures.message("Status") {
                    addField(Fixtures.field("message", 1, Type.TYPE_STRING))
                    addField(
                        Fixtures.field(
                            "details", 2, Type.TYPE_MESSAGE,
                            typeName = ".google.protobuf.Any",
                            label = FieldDescriptorProto.Label.LABEL_REPEATED,
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Status")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val detailsSchema = schemaNode["\$defs"]["Status"]["properties"]["details"]
        assertEquals("array", detailsSchema["type"].asText())
        val items = detailsSchema["items"]
        assertEquals("object", items["type"].asText())
        assertNotNull(items["properties"]["@type"])
        assertEquals("@type", items["required"][0].asText())

        val schema = Fixtures.parseSchema(schemaNode)
        val typeRegistry = JsonFormat.TypeRegistry.newBuilder()
            .add(StringValue.getDescriptor())
            .build()

        val empty = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("message"), "ok")
            .build()
        Fixtures.assertValid(schema, Fixtures.printToJson(empty))

        val packed = Any.pack(StringValue.of("one"))
        val packedDynamic = DynamicMessage.newBuilder(Any.getDescriptor())
            .mergeFrom(packed.toByteArray())
            .build()
        val withOne = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("message"), "ok")
            .addRepeatedField(descriptor.findFieldByName("details"), packedDynamic)
            .build()
        Fixtures.assertValid(
            schema,
            Fixtures.mapper.readTree(JsonFormat.printer().usingTypeRegistry(typeRegistry).print(withOne)),
        )

        val withThree = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("message"), "ok")
            .addRepeatedField(descriptor.findFieldByName("details"), packedDynamic)
            .addRepeatedField(descriptor.findFieldByName("details"), packedDynamic)
            .addRepeatedField(descriptor.findFieldByName("details"), packedDynamic)
            .build()
        Fixtures.assertValid(
            schema,
            Fixtures.mapper.readTree(JsonFormat.printer().usingTypeRegistry(typeRegistry).print(withThree)),
        )

        Fixtures.assertInvalid(
            schema,
            Fixtures.mapper.readTree("""{"message":"x","details":[{"value":"x"}]}"""),
        )
    }

    @Test
    fun `enum nested inside message is inlined and round trips by name and integer`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("Outer") {
                    addEnumType(
                        Fixtures.enumProto(
                            "Inner",
                            "INNER_UNSPECIFIED" to 0,
                            "INNER_A" to 1,
                            "INNER_B" to 2,
                        ),
                    )
                    addField(
                        Fixtures.field(
                            "state", 1, Type.TYPE_ENUM,
                            typeName = ".Outer.Inner",
                        ),
                    )
                },
            )
        }
        val descriptor = file.findMessageTypeByName("Outer")
        val enumDescriptor = descriptor.findEnumTypeByName("Inner")

        // Default `NAME_ONLY` policy: only the value name is accepted; integers are rejected.
        val nameOnlyNode = ProtoJsonSchema.generate(descriptor)
        val nameOnlyStateSchema = nameOnlyNode["\$defs"]["Outer"]["properties"]["state"]
        assertEquals("string", nameOnlyStateSchema["type"].asText())
        assertNull(nameOnlyStateSchema["oneOf"])
        val nameOnlyEnumValues = nameOnlyStateSchema["enum"].map { it.asText() }
        assertEquals(listOf("INNER_UNSPECIFIED", "INNER_A", "INNER_B"), nameOnlyEnumValues)
        assertNull(nameOnlyNode["\$defs"]["Outer.Inner"])
        assertNull(nameOnlyNode["\$defs"]["pkg.Outer.Inner"])

        val nameOnlySchema = Fixtures.parseSchema(nameOnlyNode)
        val withA = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("state"), enumDescriptor.findValueByName("INNER_A"))
            .build()
        Fixtures.assertValid(nameOnlySchema, Fixtures.printToJson(withA))
        val unspecified = DynamicMessage.newBuilder(descriptor).build()
        Fixtures.assertValid(nameOnlySchema, Fixtures.printToJson(unspecified))
        Fixtures.assertValid(nameOnlySchema, Fixtures.mapper.readTree("""{"state":"INNER_B"}"""))
        Fixtures.assertInvalid(nameOnlySchema, Fixtures.mapper.readTree("""{"state":2}"""))

        // `NAME_OR_INTEGER` policy: schema accepts both name and integer, both round-trip via `JsonFormat`.
        val flexibleNode = ProtoJsonSchema.generate(
            descriptor,
            Options(enumPolicy = EnumPolicy.NAME_OR_INTEGER),
        )
        val flexibleStateSchema = flexibleNode["\$defs"]["Outer"]["properties"]["state"]
        val oneOf = flexibleStateSchema["oneOf"]
        assertNotNull(oneOf)
        assertEquals(2, oneOf.size())
        val stringVariant = oneOf[0]
        assertEquals("string", stringVariant["type"].asText())
        val enumValues = stringVariant["enum"].map { it.asText() }
        assertEquals(listOf("INNER_UNSPECIFIED", "INNER_A", "INNER_B"), enumValues)
        assertEquals("integer", oneOf[1]["type"].asText())

        val flexibleSchema = Fixtures.parseSchema(flexibleNode)
        Fixtures.assertValid(flexibleSchema, Fixtures.mapper.readTree("""{"state":"INNER_B"}"""))
        Fixtures.assertValid(flexibleSchema, Fixtures.mapper.readTree("""{"state":2}"""))

        val byName = Fixtures.parseStrict(descriptor, """{"state":"INNER_B"}""")
        val byInt = Fixtures.parseStrict(descriptor, """{"state":2}""")
        assertEquals(byName, byInt)
    }

    @Test
    fun `empty message rejects additional properties and round trips`() {
        val file = Fixtures.fileDescriptor {
            addMessageType(Fixtures.message("EmptyRequest") {})
        }
        val descriptor = file.findMessageTypeByName("EmptyRequest")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val def = schemaNode["\$defs"]["EmptyRequest"]
        assertEquals("object", def["type"].asText())
        assertEquals(0, def["properties"].size())
        assertFalse(def["additionalProperties"].asBoolean())

        val schema = Fixtures.parseSchema(schemaNode)
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("{}"))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"foo":"bar"}"""))

        val builder = DynamicMessage.newBuilder(descriptor)
        JsonFormat.parser().merge("{}", builder)
        assertEquals(0, builder.build().allFields.size)
    }

    @Test
    fun `unknown field options do not break schema generation`() {
        val unknown = UnknownFieldSet.newBuilder()
            .addField(
                70_000,
                UnknownFieldSet.Field.newBuilder()
                    .addLengthDelimited(com.google.protobuf.ByteString.copyFromUtf8("customJsonName"))
                    .build(),
            )
            .build()
        val fieldWithUnknownOption = FieldDescriptorProto.newBuilder()
            .setName("user_id")
            .setNumber(1)
            .setType(Type.TYPE_STRING)
            .setLabel(FieldDescriptorProto.Label.LABEL_OPTIONAL)
            .setOptions(
                FieldOptions.newBuilder()
                    .setDeprecated(true)
                    .setUnknownFields(unknown)
                    .build(),
            )
            .build()

        val file = Fixtures.fileDescriptor {
            addMessageType(
                Fixtures.message("WithOption") {
                    addField(fieldWithUnknownOption)
                },
            )
        }
        val descriptor = file.findMessageTypeByName("WithOption")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val properties = schemaNode["\$defs"]["WithOption"]["properties"]
        assertNotNull(properties["userId"])
        assertNull(properties["customJsonName"])
        assertNull(properties["user_id"])
        assertEquals("string", properties["userId"]["type"].asText())

        val schema = Fixtures.parseSchema(schemaNode)
        val message = DynamicMessage.newBuilder(descriptor)
            .setField(descriptor.findFieldByName("user_id"), "abc")
            .build()
        val printed = Fixtures.printToJson(message)
        Fixtures.assertValid(schema, printed)
        assertTrue(printed.has("userId"))
        val parsed = Fixtures.parseStrict(descriptor, printed.toString())
        assertEquals(message, parsed)
    }
}
