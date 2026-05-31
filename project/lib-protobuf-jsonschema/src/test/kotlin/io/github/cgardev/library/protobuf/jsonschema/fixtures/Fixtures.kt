package io.github.cgardev.library.protobuf.jsonschema.fixtures

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.EnumDescriptorProto
import com.google.protobuf.DescriptorProtos.EnumValueDescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.OneofDescriptorProto
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.util.JsonFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion

/**
 * Helpers to construct Protobuf descriptors programmatically and to validate JSON against
 * the schemas the library generates.
 */
object Fixtures {

    val mapper: ObjectMapper = ObjectMapper()
    private val schemaFactory: JsonSchemaFactory =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)

    fun fileDescriptor(
        name: String = "test.proto",
        wellKnownDependencies: List<Descriptors.FileDescriptor> = emptyList(),
        builder: FileDescriptorProto.Builder.() -> Unit,
    ): Descriptors.FileDescriptor {
        val protoBuilder = FileDescriptorProto.newBuilder()
            .setName(name)
            .setSyntax("proto3")
        wellKnownDependencies.forEach { dep -> protoBuilder.addDependency(dep.name) }
        protoBuilder.apply(builder)
        return Descriptors.FileDescriptor.buildFrom(
            protoBuilder.build(),
            wellKnownDependencies.toTypedArray(),
        )
    }

    fun message(
        name: String,
        build: DescriptorProto.Builder.() -> Unit,
    ): DescriptorProto = DescriptorProto.newBuilder().setName(name).apply(build).build()

    fun field(
        name: String,
        number: Int,
        type: FieldDescriptorProto.Type,
        label: FieldDescriptorProto.Label = FieldDescriptorProto.Label.LABEL_OPTIONAL,
        typeName: String? = null,
        jsonName: String? = null,
        oneofIndex: Int? = null,
        proto3Optional: Boolean = false,
    ): FieldDescriptorProto {
        val b = FieldDescriptorProto.newBuilder()
            .setName(name)
            .setNumber(number)
            .setType(type)
            .setLabel(label)
        typeName?.let { b.typeName = it }
        jsonName?.let { b.jsonName = it }
        oneofIndex?.let { b.oneofIndex = it }
        if (proto3Optional) b.proto3Optional = true
        return b.build()
    }

    fun enumProto(name: String, vararg values: Pair<String, Int>): EnumDescriptorProto {
        val b = EnumDescriptorProto.newBuilder().setName(name)
        for ((vname, num) in values) {
            b.addValue(
                EnumValueDescriptorProto.newBuilder().setName(vname).setNumber(num).build(),
            )
        }
        return b.build()
    }

    fun oneof(name: String): OneofDescriptorProto =
        OneofDescriptorProto.newBuilder().setName(name).build()

    fun parseSchema(node: JsonNode): JsonSchema = schemaFactory.getSchema(node)

    fun assertValid(schema: JsonSchema, json: JsonNode) {
        val errors = schema.validate(json)
        check(errors.isEmpty()) { "Expected valid JSON. Errors: $errors. JSON: $json" }
    }

    fun assertInvalid(schema: JsonSchema, json: JsonNode) {
        val errors = schema.validate(json)
        check(errors.isNotEmpty()) { "Expected invalid JSON but validation passed. JSON: $json" }
    }

    fun printToJson(message: com.google.protobuf.Message): JsonNode {
        val text = JsonFormat.printer().print(message)
        return mapper.readTree(text)
    }

    fun parseStrict(descriptor: Descriptors.Descriptor, json: String): DynamicMessage {
        val builder = DynamicMessage.newBuilder(descriptor)
        JsonFormat.parser().merge(json, builder)
        return builder.build()
    }
}
