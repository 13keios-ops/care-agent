package app.carecare.domain

import java.time.Instant

/** 모든 데이터는 실제 SDK 신호인지 시뮬레이션인지 구분한다 (명세 13.4, 17.3). */
enum class DataSource { REAL_SDK, EMULATOR_VIRTUAL_CONSOLE, SIMULATED_REPLAY, MANUAL_INPUT;
    val isSimulation: Boolean get() = this == SIMULATED_REPLAY
    val label: String get() = when (this) {
        REAL_SDK -> "실제 SDK"
        EMULATOR_VIRTUAL_CONSOLE -> "에뮬레이터 가상 신호"
        SIMULATED_REPLAY -> "시뮬레이션"
        MANUAL_INPUT -> "사용자 입력"
    }
}

enum class Freshness { VALID, STALE, UNKNOWN }

enum class Powertrain { ICE, HEV, PHEV, EV, FCEV, UNKNOWN }

data class VehicleProfile(
    val id: String,
    val powertrain: Powertrain,
    val modelCode: String? = null,
    val modelYear: Int? = null,
    val odometerUnit: DistanceUnit = DistanceUnit.KM,
    /**
     * 차량 권장 타이어 공기압(psi, 운전석 문 안쪽 표시값). 공기압 판단의 기준이다.
     * 기본값 36psi는 현대자동차 승용·SUV에 흔한 값으로 둔 제품 기본값이며, 실차는 차종 정보나 사용자 설정으로 바꾼다
     */
    val recommendedTirePsi: Double = 36.0,
)

enum class DistanceUnit { KM, MILE }

/** 시간 주입용. 테스트에서 고정 시계를 쓴다. */
fun interface Clock { fun now(): Instant }

/** 지원 여부. 값이 없을 때 0 같은 정상값으로 대체하지 않는다 (13.1). */
data class CapabilitySnapshot(
    val sdkName: String,
    val sdkVersion: String,
    val capabilityKey: String,
    val supported: Boolean?,          // null = 확인 못함
    val permissionState: PermissionState,
    val checkedAt: Instant,
    val note: String? = null,
)

enum class PermissionState { GRANTED, DENIED, NOT_DECLARED, API_NOT_APPROVED, UNKNOWN }
