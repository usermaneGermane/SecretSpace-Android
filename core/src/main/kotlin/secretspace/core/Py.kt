package secretspace.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal

/** A vault's data as the PC's code holds it: dicts, lists, str, int, float, bool, None. */
typealias Obj = MutableMap<String, Any?>

/**
 * The few Python behaviours the PC's vault code depends on, so the port can
 * be line for line: truthiness, ``str()``, and ``json.dumps`` byte for byte
 * (revision ids and signatures are hashes of it).
 */
object Py {
    fun obj(vararg pairs: Pair<String, Any?>): Obj = linkedMapOf(*pairs)

    @Suppress("UNCHECKED_CAST")
    fun asObj(value: Any?): Obj? = value as? MutableMap<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun asList(value: Any?): MutableList<Any?>? = value as? MutableList<Any?>

    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Long -> value != 0L
        is Int -> value != 0
        is Double -> value != 0.0
        is Map<*, *> -> value.isNotEmpty()
        is List<*> -> value.isNotEmpty()
        else -> true
    }

    /** ``str(value)``. */
    fun str(value: Any?): String = when (value) {
        null -> "None"
        is String -> value
        is Boolean -> if (value) "True" else "False"
        is Long, is Int -> value.toString()
        is Double -> floatRepr(value)
        else -> dumps(value)                       // a dict or list: never in a field the user typed
    }

    /** ``str(d.get(key, "") or "")``: the commonest line in the PC's vault code. */
    fun text(map: Map<String, Any?>?, key: String): String {
        val v = map?.get(key)
        return if (truthy(v)) str(v) else ""
    }

    /** ``str(d.get(key, ""))``, without the ``or``. */
    fun textNoOr(map: Map<String, Any?>, key: String): String = if (key in map) str(map[key]) else ""

    /** ``s[:n]`` (by code points, as Python counts). */
    fun take(s: String, n: Int): String =
        if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

    private fun floatRepr(d: Double): String = when {
        d.isNaN() -> "NaN"
        d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
        d == Math.floor(d) && kotlin.math.abs(d) < 1e16 -> BigDecimal(d).toPlainString() + ".0"
        else -> d.toString().replace("E", "e+").replace("e+-", "e-")
    }

    private fun quote(text: String, out: StringBuilder) {
        out.append('"')
        for (c in text) when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\u000c' -> out.append("\\f")
            c.code < 0x20 || c.code > 0x7e -> out.append("\\u%04x".format(c.code))
            else -> out.append(c)
        }
        out.append('"')
    }

    /** ``json.dumps(value, sort_keys=..., separators=...)`` with ensure_ascii, as the PC calls it. */
    fun dumps(value: Any?, sortKeys: Boolean = false, compact: Boolean = false): String =
        StringBuilder().also { write(value, sortKeys, if (compact) "," else ", ", if (compact) ":" else ": ", it) }
            .toString()

    private fun write(value: Any?, sortKeys: Boolean, item: String, key: String, out: StringBuilder) {
        when (value) {
            null -> out.append("null")
            is String -> quote(value, out)
            is Boolean -> out.append(if (value) "true" else "false")
            is Long, is Int -> out.append(value.toString())
            is Double -> out.append(floatRepr(value))
            is Map<*, *> -> {
                out.append('{')
                val entries = value.entries.map { it.key as String to it.value }
                    .let { if (sortKeys) it.sortedBy { e -> e.first } else it }
                entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) out.append(item)
                    quote(k, out)
                    out.append(key)
                    write(v, sortKeys, item, key, out)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) out.append(item)
                    write(v, sortKeys, item, key, out)
                }
                out.append(']')
            }
            else -> throw IllegalArgumentException("Not JSON: $value")
        }
    }

    // ------------------------------------------------------------------ to and from kotlinx
    fun native(element: JsonElement?): Any? = when (element) {
        null, JsonNull -> null
        is JsonObject -> element.entries.associateTo(linkedMapOf<String, Any?>()) { it.key to native(it.value) }
        is JsonArray -> element.mapTo(mutableListOf()) { native(it) }
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.booleanOrNull != null -> element.booleanOrNull
            element.longOrNull != null -> element.longOrNull
            else -> element.doubleOrNull
        }
    }

    fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key as String to json(it.value) })
        is List<*> -> JsonArray(value.map { json(it) })
        else -> throw IllegalArgumentException("Not JSON: $value")
    }

    /** A copy all the way down (``copy.deepcopy``). */
    fun deepCopy(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associateTo(linkedMapOf<String, Any?>()) { it.key as String to deepCopy(it.value) }
        is List<*> -> value.mapTo(mutableListOf()) { deepCopy(it) }
        else -> value
    }

    @Suppress("UNCHECKED_CAST")
    fun deepCopyObj(value: Map<String, Any?>): Obj = deepCopy(value) as Obj
}
