package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.fixtures.Fixtures
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto.Type
import com.google.protobuf.Descriptors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for the [EnumPolicy] option introduced to make enum schemas LLM-friendly by default.
 *
 * The default (`NAME_ONLY`) emits a `string`-only schema, while opt-in policies allow integer
 * tags either alongside names (`NAME_OR_INTEGER`) or exclusively (`INTEGER_ONLY`).
 */
class EnumPolicyTest {

    private fun colorFile(): Descriptors.FileDescriptor = Fixtures.fileDescriptor {
        addEnumType(
            Fixtures.enumProto(
                "Color",
                "NAME_A" to 0,
                "NAME_B" to 1,
                "NAME_C" to 2,
            ),
        )
        addMessageType(
            Fixtures.message("M") {
                addField(Fixtures.field("c", 1, Type.TYPE_ENUM, typeName = ".Color"))
            },
        )
    }

    private fun sparseFile(): Descriptors.FileDescriptor = Fixtures.fileDescriptor {
        addEnumType(
            Fixtures.enumProto(
                "Sparse",
                "S_A" to 0,
                "S_B" to 1,
                "S_C" to 5,
            ),
        )
        addMessageType(
            Fixtures.message("M") {
                addField(Fixtures.field("c", 1, Type.TYPE_ENUM, typeName = ".Sparse"))
            },
        )
    }

    private fun fiveValueFile(): Descriptors.FileDescriptor = Fixtures.fileDescriptor {
        addEnumType(
            Fixtures.enumProto(
                "Five",
                "ALPHA" to 0,
                "BETA" to 1,
                "GAMMA" to 2,
                "DELTA" to 3,
                "EPSILON" to 4,
            ),
        )
        addMessageType(
            Fixtures.message("M") {
                addField(Fixtures.field("c", 1, Type.TYPE_ENUM, typeName = ".Five"))
            },
        )
    }

    @Test
    fun `NAME_ONLY emits string-only schema with description`() {
        val descriptor = colorFile().findMessageTypeByName("M")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val enumSchema = schemaNode["\$defs"]["M"]["properties"]["c"]

        assertEquals("string", enumSchema["type"].asText())
        val names = enumSchema["enum"].map { it.asText() }
        assertEquals(listOf("NAME_A", "NAME_B", "NAME_C"), names)
        assertEquals("NAME_A=0, NAME_B=1, NAME_C=2", enumSchema["description"].asText())
        assertNull(enumSchema["oneOf"])
        assertTrue(enumSchema["enum"].all { it.isTextual })
    }

    @Test
    fun `NAME_ONLY rejects integer values`() {
        val descriptor = colorFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":1}"""))
    }

    @Test
    fun `NAME_ONLY accepts valid names`() {
        val descriptor = colorFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"c":"NAME_A"}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"c":"NAME_B"}"""))
        Fixtures.assertValid(schema, Fixtures.mapper.readTree("""{"c":"NAME_C"}"""))
    }

    @Test
    fun `NAME_ONLY rejects names not listed`() {
        val descriptor = colorFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(ProtoJsonSchema.generate(descriptor))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":"NAME_D"}"""))
    }

    @Test
    fun `NAME_OR_INTEGER emits oneOf with bounds derived from real tag values`() {
        val descriptor = sparseFile().findMessageTypeByName("M")
        val schemaNode = ProtoJsonSchema.generate(
            descriptor,
            Options(enumPolicy = EnumPolicy.NAME_OR_INTEGER),
        )
        val enumSchema = schemaNode["\$defs"]["M"]["properties"]["c"]
        val oneOf = enumSchema["oneOf"]
        assertEquals(2, oneOf.size())
        assertEquals("string", oneOf[0]["type"].asText())
        val integerBranch = oneOf[1]
        assertEquals("integer", integerBranch["type"].asText())
        assertEquals(0, integerBranch["minimum"].asInt())
        assertEquals(5, integerBranch["maximum"].asInt())
        assertEquals("S_A=0, S_B=1, S_C=5", enumSchema["description"].asText())
    }

    @Test
    fun `NAME_OR_INTEGER rejects integers outside the tag range`() {
        val descriptor = sparseFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(
            ProtoJsonSchema.generate(
                descriptor,
                Options(enumPolicy = EnumPolicy.NAME_OR_INTEGER),
            ),
        )
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":-1}"""))
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":6}"""))
    }

    @Test
    fun `NAME_OR_INTEGER round trips by name and by valid integer`() {
        val descriptor = colorFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(
            ProtoJsonSchema.generate(
                descriptor,
                Options(enumPolicy = EnumPolicy.NAME_OR_INTEGER),
            ),
        )
        val byName = Fixtures.mapper.readTree("""{"c":"NAME_B"}""")
        val byInt = Fixtures.mapper.readTree("""{"c":1}""")
        Fixtures.assertValid(schema, byName)
        Fixtures.assertValid(schema, byInt)

        val parsedName = Fixtures.parseStrict(descriptor, byName.toString())
        val parsedInt = Fixtures.parseStrict(descriptor, byInt.toString())
        assertEquals(parsedName, parsedInt)
    }

    @Test
    fun `INTEGER_ONLY emits integer-only schema with bounds`() {
        val descriptor = sparseFile().findMessageTypeByName("M")
        val schemaNode = ProtoJsonSchema.generate(
            descriptor,
            Options(enumPolicy = EnumPolicy.INTEGER_ONLY),
        )
        val enumSchema = schemaNode["\$defs"]["M"]["properties"]["c"]
        assertEquals("integer", enumSchema["type"].asText())
        assertEquals(0, enumSchema["minimum"].asInt())
        assertEquals(5, enumSchema["maximum"].asInt())
        assertNull(enumSchema["oneOf"])
        assertNull(enumSchema["enum"])
        assertEquals("S_A=0, S_B=1, S_C=5", enumSchema["description"].asText())
    }

    @Test
    fun `INTEGER_ONLY rejects string values`() {
        val descriptor = colorFile().findMessageTypeByName("M")
        val schema = Fixtures.parseSchema(
            ProtoJsonSchema.generate(
                descriptor,
                Options(enumPolicy = EnumPolicy.INTEGER_ONLY),
            ),
        )
        Fixtures.assertInvalid(schema, Fixtures.mapper.readTree("""{"c":"NAME_A"}"""))
    }

    @Test
    fun `description lists every NAME=tag pair in declaration order`() {
        val descriptor = fiveValueFile().findMessageTypeByName("M")
        val schemaNode = ProtoJsonSchema.generate(descriptor)
        val description = schemaNode["\$defs"]["M"]["properties"]["c"]["description"].asText()
        assertEquals(
            "ALPHA=0, BETA=1, GAMMA=2, DELTA=3, EPSILON=4",
            description,
        )
    }
}
