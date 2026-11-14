package app.carecare.domain

import java.time.Duration
import java.time.Instant

/**
 * 신호 종류. Vehicle SDK 2.3.2 AAR에서 확인한 API에 대응한다 (docs/sdk-capability-matrix.md).
 * - TELLTALE: Safety.registerTelltales -> TelltaleData(type, state, color, priority, warningCount)
 * - TIRE_PRESSURE: Tire.registerTirePressure(WheelArea) -> Float
 * - ODOMETER: Odometer.registerDistanceDriven -> Float
 * - GEAR: Gear.registerGearSelection -> VehicleGear (정차 판단: 지역 정책상 P단은 주행 규제 해제 조건)
 * - FUEL_PERCENT: Fuel.registerFuelPercent -> Int, EV_SOC: EvBattery.registerEvBatteryStateOfCharge -> Int
 * - RANGE_REMAINING: Odometer.registerRangeRemainingListener -> Float(미터). 도메인에는 km로 넣는다
 */
enum class SignalType { TELLTALE, TIRE_PRESSURE, ODOMETER, FUEL_PERCENT, EV_SOC, ENGINE_OIL_LEVEL, GEAR, RANGE_REMAINING }

/** 기어 상태. PARK이면 안전한 정차로 본다. 알 수 없으면 정차로 보지 않는다. */
enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** SDK TelltaleType 중 MVP가 다루는 것. 이름은 SDK 상수와 같게 둔다. */
enum class TelltaleKind { TPMS, MASTER_WARNING, ENGINE, ENGINE_OIL_PRESSURE, ENGINE_TEMPERATURE, LOW_FUEL, LOW_EV_BATTERY, ABS, AIRBAG, OTHER }

enum class TelltaleState { OFF, ON, BLINK }

data class RawVehicleSignal(
    val type: SignalType,
    val source: DataSource,
    val receivedAt: Instant,
    val sourceAt: Instant? = null,
    val telltale: TelltaleKind? = null,
    val telltaleState: TelltaleState? = null,
    val telltaleColor: String? = null,        // SDK 색 코드. 의미 해석은 OEM 규칙이 한다
    val area: String? = null,                  // WheelArea 등
    val value: Double? = null,
    val unit: String? = null,
    val supported: Boolean? = true,
    val gear: Gear? = null,
)

data class NormalizedSignal(
    val raw: RawVehicleSignal,
    val freshness: Freshness,
    /** 같은 사건 판단용 지문. 값의 미세 변화는 포함하지 않는다. */
    val fingerprint: String,
    val active: Boolean,
)

class EventNormalizer(private val maxAge: Duration = Duration.ofMinutes(2)) {
    fun normalize(raw: RawVehicleSignal, now: Instant): NormalizedSignal {
        val ts = raw.sourceAt ?: raw.receivedAt
        val freshness = when {
            raw.supported != true -> Freshness.UNKNOWN
            Duration.between(ts, now) > maxAge -> Freshness.STALE
            else -> Freshness.VALID
        }
        val active = when (raw.type) {
            SignalType.TELLTALE -> raw.telltaleState == TelltaleState.ON || raw.telltaleState == TelltaleState.BLINK
            else -> raw.value != null
        }
        val fp = listOf(raw.type, raw.telltale ?: "", raw.area ?: "").joinToString(":")
        return NormalizedSignal(raw, freshness, fp, active)
    }
}
