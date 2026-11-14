package app.carecare.app.gleo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.carecare.app.AgentHolder
import app.carecare.domain.CareStatus
import org.json.JSONObject

/**
 * Gleo AI 호출 연동(Pleos Gleo Interface). 앱은 assets/schema.json으로 함수 2개를 등록한다.
 *  - CHECK_CAR_CARE_STATUS(component=service): Gleo가 브로드캐스트로 부르면 앱이 한국어 요약을 직접 말하고, Gleo에는 영어 한 문장을 돌려준다
 *  - RECORD_MAINTENANCE(component=activity): Gleo가 앱 화면을 열면 받은 품목으로 정비 기록 확인을 시작한다(저장은 운전자 승인 뒤)
 * 에뮬레이터 실측: Gleo(ai.umos.ambientai 4.2.0.15)가 설치 때 schema.json을 읽고 APP_ACTION_RESPONSE를 받는다.
 * 음성으로 실제 함수가 불리는지는 마이크 시험이 남아 있다.
 */
object GleoBridge {
    const val CHECK_STATUS = "CHECK_CAR_CARE_STATUS"
    const val RECORD_MAINTENANCE = "RECORD_MAINTENANCE"
    private const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    /** 에뮬레이터의 Gleo AI 패키지. 차량 상태 요약을 다른 앱이 엿듣지 않도록 응답은 이 패키지로만 보낸다 */
    private const val GLEO_PACKAGE = "ai.umos.ambientai"
    private const val TAG = "CareGleo"

    /** Gleo 응답. message는 영어 한 문장, mode=silent면 Gleo는 말하지 않고 앱이 직접 말한다 */
    fun respond(ctx: Context, functionName: String, status: String, message: String, mode: String = "silent") {
        val json = JSONObject().put("functionName", functionName).put("status", status).put("message", message).put("mode", mode).toString()
        runCatching {
            ctx.sendBroadcast(Intent(RESPONSE).setPackage(GLEO_PACKAGE).putExtra("packageName", ctx.packageName).putExtra("response", json))
        }.onFailure { Log.w(TAG, "respond: $it") }
        Log.i(TAG, "gleo response $functionName $status")
    }

    /** Gleo 매개변수: ACTION_PARAM(JSON 문자열) 또는 같은 이름의 extra. 문서 예시와 설명이 달라 둘 다 읽는다 */
    fun param(intent: Intent, key: String): String? {
        intent.getStringExtra(key)?.let { return it }
        val raw = intent.getStringExtra("ACTION_PARAM") ?: return null
        return runCatching { JSONObject(raw).optString(key).ifEmpty { null } }.getOrNull()
    }
}

/** CHECK_CAR_CARE_STATUS: 차량 관리 상태 요약을 말한다. 읽기만 하고 아무것도 실행하지 않는다 */
class GleoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GleoBridge.CHECK_STATUS) return
        val agent = AgentHolder.agent.value
        if (agent == null) {
            GleoBridge.respond(context, GleoBridge.CHECK_STATUS, "fail", "The car care agent is not running right now.", mode = "voice")
            return
        }
        val st = agent.state.value
        val s = CareStatus.summary(st)
        agent.announce(s.ko, "Gleo AI 요청: 차량 관리 상태 요약")
        // 안내 중이면 앱은 말하지 않으므로(announce) Gleo가 직접 답하게 한다
        GleoBridge.respond(context, GleoBridge.CHECK_STATUS, "success", s.en, mode = if (st.conversation != null) "voice" else "silent")
    }
}
