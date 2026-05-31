package io.github.cgardev.library.protobuf.jsonschema.internal

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Fixed JSON-Schema translations for protobuf well-known types.
 *
 * These mirror the wire-format that `com.google.protobuf.util.JsonFormat` produces for each WKT,
 * not the structural shape of the underlying `.proto`.
 */
internal object WellKnownTypes {

    private val nodes: JsonNodeFactory = JsonNodeFactory.instance

    private val table: Map<String, () -> ObjectNode> = mapOf(
        "google.protobuf.Timestamp" to {
            nodes.objectNode().apply {
                put("type", "string")
                put("format", "date-time")
            }
        },
        "google.protobuf.Duration" to {
            nodes.objectNode().apply {
                put("type", "string")
                put("pattern", "^-?\\d+(\\.\\d+)?s$")
            }
        },
        "google.protobuf.FieldMask" to {
            nodes.objectNode().apply { put("type", "string") }
        },
        "google.protobuf.Empty" to {
            nodes.objectNode().apply {
                put("type", "object")
                set<JsonNode>("properties", nodes.objectNode())
                put("additionalProperties", false)
            }
        },
        "google.protobuf.BoolValue" to {
            nodes.objectNode().apply { put("type", "boolean") }
        },
        "google.protobuf.StringValue" to {
            nodes.objectNode().apply { put("type", "string") }
        },
        "google.protobuf.BytesValue" to {
            nodes.objectNode().apply {
                put("type", "string")
                put("contentEncoding", "base64")
            }
        },
        "google.protobuf.Int32Value" to {
            nodes.objectNode().apply {
                put("type", "integer")
                put("minimum", Int.MIN_VALUE.toLong())
                put("maximum", Int.MAX_VALUE.toLong())
            }
        },
        "google.protobuf.UInt32Value" to {
            nodes.objectNode().apply {
                put("type", "integer")
                put("minimum", 0L)
                put("maximum", 4_294_967_295L)
            }
        },
        "google.protobuf.Int64Value" to ::int64Schema,
        "google.protobuf.UInt64Value" to ::uint64Schema,
        "google.protobuf.FloatValue" to ::floatLikeSchema,
        "google.protobuf.DoubleValue" to ::floatLikeSchema,
        "google.protobuf.Struct" to {
            nodes.objectNode().apply { put("type", "object") }
        },
        "google.protobuf.Value" to { nodes.objectNode() },
        "google.protobuf.ListValue" to {
            nodes.objectNode().apply { put("type", "array") }
        },
        "google.protobuf.Any" to {
            nodes.objectNode().apply {
                put("type", "object")
                val properties = nodes.objectNode()
                properties.set<JsonNode>(
                    "@type",
                    nodes.objectNode().apply { put("type", "string") },
                )
                set<JsonNode>("properties", properties)
                val required = nodes.arrayNode()
                required.add("@type")
                set<JsonNode>("required", required)
            }
        },
    )

    fun isWellKnown(fullName: String): Boolean = table.containsKey(fullName)

    fun schemaFor(fullName: String): ObjectNode? = table[fullName]?.invoke()

    private fun int64Schema(): ObjectNode = nodes.objectNode().apply {
        val types = nodes.arrayNode()
        types.add("string")
        types.add("integer")
        set<JsonNode>("type", types)
        put("pattern", "^-?\\d+$")
    }

    private fun uint64Schema(): ObjectNode = nodes.objectNode().apply {
        val types = nodes.arrayNode()
        types.add("string")
        types.add("integer")
        set<JsonNode>("type", types)
        put("pattern", "^\\d+$")
    }

    private fun floatLikeSchema(): ObjectNode = nodes.objectNode().apply {
        val oneOf = nodes.arrayNode()
        oneOf.add(nodes.objectNode().apply { put("type", "number") })
        oneOf.add(
            nodes.objectNode().apply {
                put("type", "string")
                val enum = nodes.arrayNode()
                enum.add("NaN")
                enum.add("Infinity")
                enum.add("-Infinity")
                set<JsonNode>("enum", enum)
            },
        )
        set<JsonNode>("oneOf", oneOf)
    }
}
