package kavach.core

import java.text.Normalizer

/** Port of kavach_core/normalize.py. Must stay byte-identical (see ParityChecks). */
object Normalize {
    private const val APOSTROPHES = "'’‘`´"

    fun normalize(text: String): String {
        var t = Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
        t = t.replace("़", "").replace('ँ', 'ं')
        val sb = StringBuilder(t.length)
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            i += Character.charCount(cp)
            if (cp in 0x0966..0x096F) { sb.append(('0'.code + (cp - 0x0966)).toChar()); continue }
            val ch = String(Character.toChars(cp))
            if (APOSTROPHES.contains(ch)) continue
            when {
                cp < 128 -> sb.append(if (Character.isLetterOrDigit(cp)) ch else " ")
                cp in 0x0900..0x097F -> sb.append(if (cp == 0x0964 || cp == 0x0965) " " else ch)
                else -> sb.append(if (Character.isLetterOrDigit(cp)) ch else " ")
            }
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    fun tokens(text: String): List<String> {
        val n = normalize(text)
        return if (n.isEmpty()) emptyList() else n.split(' ')
    }
}
