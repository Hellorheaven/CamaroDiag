package com.camarodiag.gen5.ui

import java.math.BigDecimal
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Strict, bounded JSON used only for offline J1979 catalogue files.
 * Android's JSONObject may silently replace repeated properties or coerce
 * strings to numbers. A catalogue must not change meaning by host platform.
 */
internal object J1979CatalogueJson {
    private const val MAX_DEPTH = 64
    private const val MAX_NODES = 2_000_000
    private const val MAX_TEXT_CHARS = 16 * 1024 * 1024
    private const val MAX_OUTPUT_CHARS = 32 * 1024 * 1024

    fun parse(raw: ByteArray, maxBytes: Int): Any? {
        require(raw.isNotEmpty() && raw.size <= maxBytes) { "JSON size limit" }
        val text = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw)).toString()
        return Parser(text).parse()
    }

    private class Parser(private val source: String) {
        private var at = 0
        private var nodes = 0
        private fun error(message: String): Nothing =
            throw IllegalArgumentException("$message at character $at")
        private fun whitespace() {
            while (at < source.length && source[at] in " \r\n\t") at++
        }
        private fun take(c: Char): Boolean {
            if (at < source.length && source[at] == c) { at++; return true }
            return false
        }
        private fun expect(c: Char) {
            if (!take(c)) error("Expected JSON delimiter")
        }
        fun parse(): Any? {
            whitespace()
            val value = value(0)
            whitespace()
            if (at != source.length) error("Trailing JSON content")
            return value
        }
        private fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH || ++nodes > MAX_NODES) error("JSON complexity limit")
            whitespace()
            if (at >= source.length) error("Truncated JSON")
            return when (source[at]) {
                '{' -> obj(depth)
                '[' -> list(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                '-', in '0'..'9' -> number()
                else -> error("Invalid JSON value")
            }
        }
        private fun literal(word: String, value: Any?): Any? {
            if (!source.startsWith(word, at)) error("Invalid literal")
            at += word.length
            return value
        }
        private fun obj(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            whitespace()
            if (take('}')) return result
            while (true) {
                whitespace()
                val key = string()
                if (key.length > 256 || result.containsKey(key)) error("Invalid or repeated JSON key")
                whitespace(); expect(':')
                result[key] = value(depth + 1)
                whitespace()
                if (take('}')) return result
                expect(',')
            }
        }
        private fun list(depth: Int): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            whitespace()
            if (take(']')) return result
            while (true) {
                result.add(value(depth + 1))
                whitespace()
                if (take(']')) return result
                expect(',')
            }
        }
        private fun hex4(): Char {
            if (at + 4 > source.length) error("Truncated Unicode escape")
            var value = 0
            repeat(4) {
                val c = source[at++]
                if (c !in '0'..'9' && c !in 'a'..'f' && c !in 'A'..'F') error("Invalid Unicode escape")
                val digit = c.digitToInt(16)
                value = value * 16 + digit
            }
            return value.toChar()
        }
        private fun string(): String {
            expect('"')
            val result = StringBuilder()
            while (at < source.length) {
                val c = source[at++]
                if (c == '"') {
                    val text = result.toString()
                    var i = 0
                    while (i < text.length) {
                        val ch = text[i++]
                        if (ch.isHighSurrogate()) {
                            if (i >= text.length || !text[i++].isLowSurrogate()) error("Invalid Unicode pair")
                        } else if (ch.isLowSurrogate()) error("Invalid Unicode pair")
                    }
                    return text
                }
                if (c == '\\') {
                    if (at >= source.length) error("Truncated escape")
                    result.append(when (source[at++]) {
                        '"' -> '"'
                        '\\' -> '\\'
                        '/' -> '/'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> hex4()
                        else -> error("Invalid escape")
                    })
                } else {
                    if (c < ' ') error("Control character in JSON string")
                    result.append(c)
                }
                if (result.length > MAX_TEXT_CHARS) error("JSON string too long")
            }
            error("Unterminated JSON string")
        }
        private fun number(): Number {
            val start = at
            take('-')
            if (take('0')) {
                if (at < source.length && source[at] in '0'..'9') error("Leading zero")
            } else {
                if (at >= source.length || source[at] !in '1'..'9') error("Invalid number")
                while (at < source.length && source[at] in '0'..'9') at++
            }
            var integral = true
            if (take('.')) {
                integral = false
                val before = at
                while (at < source.length && source[at] in '0'..'9') at++
                if (before == at) error("Missing decimal digits")
            }
            if (at < source.length && source[at] in "eE") {
                integral = false; at++
                if (!take('+')) take('-')
                val before = at
                while (at < source.length && source[at] in '0'..'9') at++
                if (before == at) error("Missing exponent digits")
            }
            if (at - start > 64) error("Number lexeme too long")
            val text = source.substring(start, at)
            return if (integral) BigInteger(text) else BigDecimal(text).also {
                if (it.scale() !in -1000..1000) error("Number exponent out of bounds")
            }
        }
    }

    fun encode(value: Any?): ByteArray {
        val out = StringBuilder()
        fun bounded() {
            require(out.length <= MAX_OUTPUT_CHARS) { "Projection size limit" }
        }
        fun quote(value: String) {
            out.append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (ch < ' ') out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                            else out.append(ch)
                }
                bounded()
            }
            out.append('"')
        }
        fun append(node: Any?, depth: Int) {
            require(depth <= MAX_DEPTH)
            when (node) {
                null -> out.append("null")
                is String -> quote(node)
                is Boolean -> out.append(node)
                is Int -> out.append(node)
                is Long -> out.append(node)
                is BigInteger -> out.append(node.toString())
                is Map<*, *> -> {
                    out.append('{')
                    node.entries.forEachIndexed { i, entry ->
                        if (i > 0) out.append(',')
                        quote(entry.key as? String ?: error("JSON key must be text"))
                        out.append(':'); append(entry.value, depth + 1)
                    }
                    out.append('}')
                }
                is Iterable<*> -> {
                    out.append('[')
                    node.forEachIndexed { i, item ->
                        if (i > 0) out.append(',')
                        append(item, depth + 1)
                    }
                    out.append(']')
                }
                else -> error("Unsupported JSON output value")
            }
            bounded()
        }
        append(value, 0)
        return out.toString().toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_OUTPUT_CHARS) { "Projection size limit" }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String }) { "Expected object" }
        return value as Map<String, Any?>
    }
    fun array(value: Any?, min: Int, max: Int): List<Any?> {
        require(value is List<*> && value.size in min..max) { "Invalid array size" }
        return value
    }
    fun fields(value: Any?, required: Set<String>, optional: Set<String> = emptySet()): Map<String, Any?> {
        val row = obj(value)
        require(row.keys.containsAll(required) && row.keys.all { it in required || it in optional }) {
            "Missing or unknown catalogue field"
        }
        return row
    }
    fun text(value: Any?): String = value as? String ?: throw IllegalArgumentException("Expected text")
    fun label(value: Any?, max: Int = 256): String = text(value).also {
        require(it.isNotEmpty() && it.codePointCount(0, it.length) <= max &&
            it == it.trim() && it.none { c -> c.code < 32 || c.code == 127 }) { "Invalid catalogue label" }
    }
    fun integer(value: Any?, low: BigInteger, high: BigInteger): BigInteger {
        require(value is BigInteger && value >= low && value <= high) { "Invalid integer range" }
        return value
    }
    fun int(value: Any?, low: Int, high: Int): Int =
        integer(value, BigInteger.valueOf(low.toLong()), BigInteger.valueOf(high.toLong())).toInt()
    fun bool(value: Any?): Boolean = value as? Boolean ?: throw IllegalArgumentException("Expected boolean")
    fun get(row: Map<String, Any?>, snake: String): Any? {
        val camel = Regex("_([a-z])").replace(snake) { it.groupValues[1].uppercase() }
        require(snake == camel || !row.containsKey(snake) || !row.containsKey(camel)) { "Ambiguous report property" }
        return if (row.containsKey(snake)) row[snake] else row[camel]
    }
}
