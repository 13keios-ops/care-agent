package app.carecare.app.demo

import app.carecare.domain.DataSource
import app.carecare.domain.GeoPoint
import app.carecare.domain.LocationFix
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import java.time.Instant

/**
 * 시연 위치. 값이 있으면 실제 위치 대신 이 위치를 10초마다 '시뮬레이션' 출처로 넣는다(방문 체류 판단에 반복 위치가 필요).
 * 패널은 에뮬레이터 GPS(`emu geo fix`)를 바꿀 수 없어서 앱 안에서 위치를 정한다. 좌표는 기록하지 않는다.
 */
object DemoLocation {
    private val _point = MutableStateFlow<GeoPoint?>(null)
    val point: StateFlow<GeoPoint?> = _point.asStateFlow()

    fun set(p: GeoPoint?) { _point.value = p }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun ticks(): Flow<LocationFix> = _point.flatMapLatest { p ->
        if (p == null) emptyFlow() else flow {
            while (true) { emit(LocationFix(p, 5f, Instant.now(), DataSource.SIMULATED_REPLAY)); delay(10_000) }
        }
    }

    /** 실제 위치(있으면)와 시연 위치를 합친다. 시연 위치가 있는 동안 실제 위치는 버린다 */
    fun overlay(real: Flow<LocationFix>?): Flow<LocationFix> =
        if (real == null) ticks() else merge(real.filter { _point.value == null }, ticks())
}
