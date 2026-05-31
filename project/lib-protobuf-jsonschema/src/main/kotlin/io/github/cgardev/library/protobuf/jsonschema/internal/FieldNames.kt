package io.github.cgardev.library.protobuf.jsonschema.internal

import io.github.cgardev.library.protobuf.jsonschema.FieldNamePolicy
import com.google.protobuf.Descriptors

internal object FieldNames {
    fun of(field: Descriptors.FieldDescriptor, policy: FieldNamePolicy): String = when (policy) {
        FieldNamePolicy.JSON_NAME -> field.jsonName
        FieldNamePolicy.PROTO_NAME -> field.name
    }
}
