package app.carecare.app.demo

/**
 * PC 키보드 입력을 한글로 조합한다. 에뮬레이터는 PC 키보드를 영문 키로만 받으므로 입력칸에서 두벌식 키를 한글로 바꾼다.
 * 이미 조합된 글자는 자모로 풀어 새 키와 다시 조합한다. 그래서 한 글자씩 쳐도 이어서 조합된다.
 */
object HangulInput {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val KEYS = mapOf(
        'r' to 'ㄱ', 'R' to 'ㄲ', 's' to 'ㄴ', 'e' to 'ㄷ', 'E' to 'ㄸ', 'f' to 'ㄹ', 'a' to 'ㅁ', 'q' to 'ㅂ', 'Q' to 'ㅃ', 't' to 'ㅅ', 'T' to 'ㅆ',
        'd' to 'ㅇ', 'w' to 'ㅈ', 'W' to 'ㅉ', 'c' to 'ㅊ', 'z' to 'ㅋ', 'x' to 'ㅌ', 'v' to 'ㅍ', 'g' to 'ㅎ',
        'k' to 'ㅏ', 'o' to 'ㅐ', 'i' to 'ㅑ', 'O' to 'ㅒ', 'j' to 'ㅓ', 'p' to 'ㅔ', 'u' to 'ㅕ', 'P' to 'ㅖ', 'h' to 'ㅗ', 'y' to 'ㅛ', 'n' to 'ㅜ', 'b' to 'ㅠ', 'm' to 'ㅡ', 'l' to 'ㅣ',
    )
    private val VOWEL_PAIRS = mapOf("ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ', "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ', "ㅡㅣ" to 'ㅢ')
    private val FINAL_PAIRS = mapOf("ㄱㅅ" to 'ㄳ', "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ', "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ', "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ', "ㅂㅅ" to 'ㅄ')
    private val SPLIT_VOWEL = VOWEL_PAIRS.entries.associate { (k, v) -> v to k }
    private val SPLIT_FINAL = FINAL_PAIRS.entries.associate { (k, v) -> v to k }

    private fun key(c: Char): Char? = KEYS[c] ?: KEYS[c.lowercaseChar()]

    fun needsCompose(text: String): Boolean = text.any { key(it) != null }

    fun compose(text: String): String {
        val out = StringBuilder()
        var cho: Char? = null; var jung: Char? = null; var jong: Char? = null
        fun flush() {
            val c = cho; val j = jung
            when {
                c != null && j != null -> out.append(('가'.code + (CHO.indexOf(c) * 21 + JUNG.indexOf(j)) * 28 + (jong?.let { JONG.indexOf(it) } ?: 0)).toChar())
                c != null -> out.append(c)
                j != null -> out.append(j)
            }
            cho = null; jung = null; jong = null
        }
        for (ch in jamo(text)) when {
            ch in CHO || ch in JONG.drop(1) -> when {
                jung == null || cho == null -> { flush(); cho = ch }
                jong == null -> if (ch in JONG) jong = ch else { flush(); cho = ch }
                else -> { val pair = FINAL_PAIRS["${jong}$ch"]; if (pair != null) jong = pair else { flush(); cho = ch } }
            }
            ch in JUNG -> when {
                jong != null -> {
                    val split = SPLIT_FINAL[jong!!]
                    val next = split?.get(1) ?: jong!!
                    jong = split?.get(0)
                    flush(); cho = next; jung = ch
                }
                jung != null -> { val pair = VOWEL_PAIRS["${jung}$ch"]; if (pair != null) jung = pair else { flush(); jung = ch } }
                else -> jung = ch
            }
            else -> { flush(); out.append(ch) }
        }
        flush()
        return out.toString()
    }

    /** 영문 키는 자모로, 완성 글자는 자모로 풀어 한 줄로 만든다 */
    private fun jamo(text: String): List<Char> = buildList {
        for (c in text) {
            val k = key(c)
            when {
                k != null -> add(k)
                c in '가'..'힣' -> {
                    val i = c.code - '가'.code
                    add(CHO[i / 588])
                    JUNG[(i % 588) / 28].let { v -> SPLIT_VOWEL[v]?.forEach { add(it) } ?: add(v) }
                    (i % 28).takeIf { it > 0 }?.let { f -> JONG[f].let { v -> SPLIT_FINAL[v]?.forEach { add(it) } ?: add(v) } }
                }
                else -> add(c)
            }
        }
    }
}
