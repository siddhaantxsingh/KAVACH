package kavach.core

/**
 * Minimal, dependency-free JSON reader/writer for the shared assets and the
 * Llama verdict contract. Objects keep key order (LinkedHashMap) because the
 * lexicon's category order is part of the matching semantics.
 *
 * Values: Map<String, Any?>, List<Any?>, String, Double, Boolean, null.
 */
object MiniJson {
    class ParseException(msg: String) : RuntimeException(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseException("trailing characters at ${p.i}")
        return v
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw ParseException("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseException("unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw ParseException("bad literal at $i")
            i += word.length
            return v
        }

        fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(start, i).toDoubleOrNull() ?: throw ParseException("bad number at $start")
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseException("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw ParseException("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException("bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> throw ParseException("bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out.add(value()); ws()
                if (i >= s.length) throw ParseException("unterminated array")
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> throw ParseException("expected , or ] at ${i - 1}") }
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw ParseException("expected key at $i")
                val k = str(); ws()
                if (i >= s.length || s[i++] != ':') throw ParseException("expected : at ${i - 1}")
                ws(); out[k] = value(); ws()
                if (i >= s.length) throw ParseException("unterminated object")
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> throw ParseException("expected , or } at ${i - 1}") }
            }
        }
    }

    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    fun write(v: Any?): String = when (v) {
        null -> "null"
        is String -> quote(v)
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Number -> { val d = v.toDouble(); if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString() }
        is Map<*, *> -> v.entries.joinToString(", ", "{", "}") { quote(it.key.toString()) + ": " + write(it.value) }
        is List<*> -> v.joinToString(", ", "[", "]") { write(it) }
        else -> quote(v.toString())
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?> = this as Map<String, Any?>

@Suppress("UNCHECKED_CAST")
internal fun Any?.arr(): List<Any?> = this as List<Any?>

internal fun Any?.strList(): List<String> = arr().map { it as String }

/** Public cast helper for app modules (the internal ones above are module-private). */
@Suppress("UNCHECKED_CAST")
fun Any?.obj2(): Map<String, Any?> = this as Map<String, Any?>
