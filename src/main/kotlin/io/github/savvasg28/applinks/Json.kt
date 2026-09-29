package io.github.savvasg28.applinks

/**
 * Minimal JSON reader, enough for assetlinks.json and for writing reports. Kept dependency-free so the
 * plugin adds nothing to consumers' build classpaths. Values map to String, Double, Boolean, null,
 * List<Any?> and Map<String, Any?>.
 */
object Json {

    fun parse(text: String): Any? = Parser(text).document()

    fun quote(value: String): String {
        val sb = StringBuilder("\"")
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun write(value: Any?, indent: String = ""): String = when (value) {
        null -> "null"
        is String -> quote(value)
        is Boolean, is Number -> value.toString()
        is Map<*, *> -> if (value.isEmpty()) "{}" else value.entries.joinToString(
            separator = ",\n", prefix = "{\n", postfix = "\n$indent}"
        ) { (k, v) -> "$indent  ${quote(k.toString())}: ${write(v, "$indent  ")}" }
        is Iterable<*> -> if (!value.iterator().hasNext()) "[]" else value.joinToString(
            separator = ",\n", prefix = "[\n", postfix = "\n$indent]"
        ) { "$indent  ${write(it, "$indent  ")}" }
        else -> quote(value.toString())
    }

    class JsonException(message: String) : RuntimeException(message)

    private class Parser(private val s: String) {
        private var i = 0

        fun document(): Any? {
            val value = value()
            skipWhitespace()
            if (!atEnd()) fail("trailing characters")
            return value
        }

        private fun atEnd() = i >= s.length
        private fun fail(message: String): Nothing = throw JsonException("Invalid JSON at offset $i: $message")

        private fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun value(): Any? {
            skipWhitespace()
            if (atEnd()) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(): Map<String, Any?> {
            val map = linkedMapOf<String, Any?>()
            i++
            skipWhitespace()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("expected string key")
                val key = string()
                skipWhitespace()
                if (peek() != ':') fail("expected ':'")
                i++
                map[key] = value()
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return map }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun array(): List<Any?> {
            val list = mutableListOf<Any?>()
            i++
            skipWhitespace()
            if (peek() == ']') { i++; return list }
            while (true) {
                list += value()
                skipWhitespace()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return list }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) fail("bad escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad unicode escape")
                                sb.append((s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad unicode escape")).toChar())
                                i += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            if (start == i) fail("unexpected character '${s[i]}'")
            return s.substring(start, i).toDoubleOrNull() ?: fail("bad number")
        }

        private fun literal(text: String, value: Any?): Any? {
            if (!s.startsWith(text, i)) fail("expected $text")
            i += text.length
            return value
        }

        private fun peek(): Char = if (atEnd()) fail("unexpected end") else s[i]
    }
}
