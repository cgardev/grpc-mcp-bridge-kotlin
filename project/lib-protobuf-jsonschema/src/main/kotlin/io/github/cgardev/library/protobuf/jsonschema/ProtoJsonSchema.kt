package io.github.cgardev.library.protobuf.jsonschema

import io.github.cgardev.library.protobuf.jsonschema.internal.SchemaBuilder
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.Descriptors

/**
 * Runtime generator of JSON Schema (draft 2020-12) documents from Protobuf message descriptors.
 *
 * The generated schema is round-trip compatible with `com.google.protobuf.util.JsonFormat`:
 * the JSON produced by `JsonFormat.printer().print(message)` validates against the schema, and
 * any JSON that validates against the schema can be parsed back via `JsonFormat.parser().merge(...)`.
 */
object ProtoJsonSchema {

    private val mapper: ObjectMapper = ObjectMapper()

    /**
     * Generates a JSON Schema document as a Jackson [JsonNode].
     */
    fun generate(descriptor: Descriptors.Descriptor, options: Options = Options.DEFAULT): JsonNode =
        SchemaBuilder(options).build(descriptor)

    /**
     * Generates a pretty-printed JSON Schema document.
     */
    fun generateAsString(descriptor: Descriptors.Descriptor, options: Options = Options.DEFAULT): String =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsString(generate(descriptor, options))
}
