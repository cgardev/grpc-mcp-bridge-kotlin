package io.github.cgardev.library.protobuf.jsonschema

/**
 * Controls how a Protobuf descriptor is translated into a JSON Schema document.
 *
 * Defaults mirror the behaviour of [com.google.protobuf.util.JsonFormat]:
 *  - field names are emitted in `jsonName` form (camelCase, or the value of `[json_name=...]`);
 *  - unknown JSON properties are rejected, matching the strict default of `JsonFormat.parser()`;
 *  - the root schema references a `$defs` entry to handle recursion and shared messages cleanly;
 *  - enums accept only the value name as a string, which is the safest contract for LLM consumers
 *    while still matching the output of `JsonFormat.printer()` (which prints names by default).
 */
data class Options(
    val fieldNamePolicy: FieldNamePolicy = FieldNamePolicy.JSON_NAME,
    val additionalProperties: Boolean = false,
    val emitDollarSchema: Boolean = true,
    val rootStyle: RootStyle = RootStyle.REF_TO_DEFS,
    val enumPolicy: EnumPolicy = EnumPolicy.NAME_ONLY,
) {
    companion object {
        val DEFAULT: Options = Options()
    }
}

/**
 * Determines which name from a Protobuf field descriptor is used as the JSON property name.
 */
enum class FieldNamePolicy {
    /** Use `FieldDescriptor.jsonName`. Matches `JsonFormat.printer()`'s default. */
    JSON_NAME,

    /** Use `FieldDescriptor.name` (the raw `snake_case` proto name). */
    PROTO_NAME,
}

/**
 * Controls how the root of the generated schema is shaped.
 */
enum class RootStyle {
    /** Root document is `{"$schema":..., "$ref":"#/$defs/<fqn>", "$defs": {...}}`. */
    REF_TO_DEFS,

    /** Root document is the inlined message schema; `$defs` only holds shared or recursive references. */
    INLINE,
}

/**
 * Controls how enum-typed fields are emitted in the generated schema.
 */
enum class EnumPolicy {
    /** Schema accepts only the value name (string). Recommended for LLM consumption via MCP. */
    NAME_ONLY,

    /** Schema accepts the name (string) or the tag (integer). Matches `JsonFormat.parser()`'s permissive mode. */
    NAME_OR_INTEGER,

    /** Schema accepts only the tag (integer). Useful with `JsonFormat.printer().printingEnumsAsInts()`. */
    INTEGER_ONLY,
}
