package com.app.youtube.lite.data.innertube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Tiny, allocation-light accessors over `kotlinx.serialization`'s JSON tree.
 *
 * InnerTube responses are deeply nested renderer trees with a dozen shapes for the same
 * concept ("text can be `simpleText` or `runs[].text`"). Modelling all of that with
 * `@Serializable` DTOs would mean hundreds of classes and a full tree copy per response;
 * walking the element tree directly costs nothing extra and tolerates YouTube adding fields.
 *
 * Every accessor is total: a missing key, a wrong type or an explicit `null` yields `null`
 * instead of throwing, because a partially-parsed feed must still render.
 */
internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.asArray(): JsonArray? = this as? JsonArray

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

/** First element of an array-valued key, when present. */
internal fun JsonObject.firstOf(key: String): JsonObject? = arr(key)?.firstOrNull()?.asObject()

/**
 * Reads either `{"simpleText":"…"}` or `{"runs":[{"text":"…"}]}` — InnerTube uses both for
 * the same logical value, sometimes within one response.
 */
internal fun JsonObject.text(key: String): String? {
    val value = obj(key) ?: return null
    value.str("simpleText")?.let { return it }
    val runs = value.arr("runs") ?: return null
    if (runs.isEmpty()) return null
    if (runs.size == 1) return runs.first().asObject()?.str("text")
    val builder = StringBuilder(32)
    for (run in runs) {
        builder.append(run.asObject()?.str("text") ?: continue)
    }
    return builder.toString().ifEmpty { null }
}

/** `{"accessibility":…,"label":"3:24"}` or a plain string. */
internal fun JsonObject.textOrLabel(key: String): String? = text(key) ?: obj(key)?.str("label")

/** Recursively yields every `(key, object)` pair in the tree, depth-first. */
internal fun JsonElement.walk(visit: (key: String, value: JsonObject) -> Unit) {
    when (this) {
        is JsonObject -> forEach { (key, value) ->
            if (value is JsonObject) {
                visit(key, value)
                value.walk(visit)
            } else if (value is JsonArray) {
                value.walk(visit)
            }
        }

        is JsonArray -> forEach { it.walk(visit) }
        else -> Unit
    }
}

/** Depth-first search for the first value stored under [key]. */
internal fun JsonElement.findFirst(key: String): JsonElement? {
    var found: JsonElement? = null
    walk { name, value -> if (found == null && name == key) found = value }
    return found
}
