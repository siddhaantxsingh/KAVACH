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
        return joinSpelled(sb.toString().split(' ').filter { it.isNotEmpty() }).joinToString(" ")
    }

    /** Short forms callers spell out; used only to split a joined run ("sbikyc" -> "sbi kyc"). */
    private val SPELLED = setOf("otp", "pin", "kyc", "sbi", "rbi", "cbi", "cvv", "upi", "atm", "pan", "ed", "ncb", "trai", "hdfc", "icici",
        "pnb", "bob", "npci", "it", "id", "sim", "emi", "apk", "sms", "ats", "fir", "dgp", "ips", "ias", "uidai")

    private fun splitKnown(run: String): List<String>? {
        if (run.isEmpty()) return emptyList()
        for (n in minOf(5, run.length) downTo 2) {
            val head = run.substring(0, n)
            if (head in SPELLED) { val rest = splitKnown(run.substring(n)); if (rest != null) return listOf(head) + rest }
        }
        return null
    }

    private fun single(w: String) = w.length == 1 && w[0] in 'a'..'z'

    /** "o t p" -> "otp", "s b i" -> "sbi"; "a c v v" -> "a cvv" (rule 7 in normalize.py). */
    private fun joinSpelled(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        var i = 0
        while (i < words.size) {
            var j = i
            while (j < words.size && single(words[j])) j++
            if (j - i >= 2) {
                var run = words.subList(i, j)
                if (run[0] == "a" && run.size >= 3) { out.add("a"); run = run.subList(1, run.size) }
                val joined = run.joinToString("")
                val parts = splitKnown(joined)
                if (parts != null && parts.size > 1) out.addAll(parts) else out.add(joined)
                i = j
            } else { out.add(words[i]); i++ }
        }
        return out
    }

    fun tokens(text: String): List<String> {
        val n = normalize(text)
        return if (n.isEmpty()) emptyList() else n.split(' ')
    }
}
