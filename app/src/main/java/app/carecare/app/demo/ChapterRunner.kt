package app.carecare.app.demo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 장 하나를 끝까지 한 번 진행한다(반복하지 않는다).
 * 상태만 바꾸는 단계(주행 시작, 동력 방식 등)는 기다리지 않고 바로 다음 단계로 간다.
 * 에이전트가 말을 거는 단계([talks])는 대화가 시작되기를 [talkStartMs]까지 기다리고, 대화가 끝날 때까지 기다린다.
 */
class ChapterRunner(
    private val busy: Flow<Boolean>,
    private val talkStartMs: Long = 10_000,
    private val settleMs: Long = 1_500,
    private val maxWaitMs: Long = 240_000,
) {
    suspend fun run(stepIds: List<String>, talks: (String) -> Boolean = { true }, onStep: (index: Int) -> Unit = {}, runStep: suspend (String) -> Unit) {
        stepIds.forEachIndexed { i, id ->
            onStep(i)
            runStep(id)
            awaitQuiet(startWaitMs = if (talks(id)) talkStartMs else 0)
        }
    }

    private suspend fun awaitQuiet(startWaitMs: Long) {
        // 말을 거는 단계는 대화 시작을 기다린다. 상태만 바꾸는 단계는 이미 진행 중인 대화가 있을 때만 기다린다
        val started = if (startWaitMs > 0) withTimeoutOrNull(startWaitMs) { busy.first { it } } != null else busy.first()
        if (!started) return
        withTimeoutOrNull(maxWaitMs) {
            while (true) {
                busy.first { !it }
                // 질문 사이의 짧은 공백은 대화가 끝난 것이 아니다
                if (withTimeoutOrNull(settleMs) { busy.first { it } } == null) break
            }
        }
    }
}
