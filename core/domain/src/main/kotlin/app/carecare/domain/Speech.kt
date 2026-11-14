package app.carecare.domain

/**
 * 화면 문구를 음성으로 읽기 좋은 문장으로 바꾼다. 화면에는 원래 문구를 그대로 보여 준다.
 * - 괄호 안 내용은 화면용 보충 설명(시연 표시, 거리 등)이라 읽지 않는다.
 * - 단위 약어는 소리 나는 대로 바꾼다(psi → 피에스아이)
 */
object SpeechText {
    private val paren = Regex("""\s*[(（][^()（）]*[)）]""")
    private val psi = Regex("""(?<=\d)\s*psi(?![A-Za-z])""", RegexOption.IGNORE_CASE)  // \b는 뒤 한글을 글자로 봐 경계가 생기지 않는다
    private val spaces = Regex("""\s{2,}""")
    private val spaceBeforePunct = Regex("""\s+([.,?!])""")

    fun forTts(text: String): String {
        var t = text
        // 괄호가 겹쳐 있어도 안쪽부터 모두 지운다
        while (true) { val n = paren.replace(t, ""); if (n == t) break; t = n }
        t = psi.replace(t, " 피에스아이")
        t = spaceBeforePunct.replace(t, "$1")
        return spaces.replace(t, " ").trim()
    }
}

/** 타이어 공기압 단위. 차량 신호는 kPa로 오고, 운전자에게는 계기판과 같은 psi로 보여 준다 */
object TireUnits {
    const val KPA_PER_PSI = 6.894757
    fun psi(kpa: Double): Double = kpa / KPA_PER_PSI
    fun kpa(psi: Double): Double = psi * KPA_PER_PSI
    /** 화면·음성용 정수 psi */
    fun psiText(kpa: Double): String = "${kotlin.math.round(psi(kpa)).toInt()}psi"
}
