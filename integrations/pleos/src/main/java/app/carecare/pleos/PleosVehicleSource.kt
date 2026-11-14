package app.carecare.pleos

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WheelArea
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.WheelValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TirePressureListener
import android.content.Context
import android.os.Build
import android.util.Log
import app.carecare.domain.CapabilitySnapshot
import app.carecare.domain.CareRuntimeSupervisor
import app.carecare.domain.DataSource
import app.carecare.domain.Gear
import app.carecare.domain.PermissionState
import app.carecare.domain.RawVehicleSignal
import app.carecare.domain.SignalType
import app.carecare.domain.TelltaleKind
import app.carecare.domain.TelltaleState
import app.carecare.domain.VehicleSignalSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.coroutines.resume

private const val TAG = "CareVehicle"

/** SDK 바퀴 영역 → 도메인 영역 이름 */
private val WHEELS = listOf(
    WheelValue.LEFT_FRONT to "LEFT_FRONT", WheelValue.RIGHT_FRONT to "RIGHT_FRONT",
    WheelValue.LEFT_REAR to "LEFT_REAR", WheelValue.RIGHT_REAR to "RIGHT_REAR",
)

object PleosPlatform {
    /** 에뮬레이터에서 받은 신호는 실차 신호로 표시하지 않는다 (원칙 5, FR-019) */
    fun dataSource(): DataSource =
        if (Build.HARDWARE in setOf("ranchu", "goldfish") || Build.PRODUCT.contains("emulator") || Build.FINGERPRINT.contains("generic"))
            DataSource.EMULATOR_VIRTUAL_CONSOLE else DataSource.REAL_SDK

    const val SDK_VEHICLE = "Vehicle 2.3.2"
}

/** 주행가능거리 지원 상태 키 */
const val CAP_RANGE = "vehicle.range.remaining"

/**
 * Pleos Vehicle SDK 어댑터. 경고등·주행거리·기어·연료·EV 배터리를 구독한다.
 * 경고등의 의미를 해석하지 않고 상태 변화만 전달한다. 해석은 도메인 규칙이 한다.
 */
class PleosVehicleSource(context: Context, override val source: DataSource = PleosPlatform.dataSource()) : VehicleSignalSource {
    private val app = context.applicationContext
    @Volatile private var vehicle: Vehicle? = null
    @Volatile private var lastOdometer: Double? = null

    @Synchronized private fun v(): Vehicle = vehicle ?: Vehicle(app).also { it.initialize(); vehicle = it }

    fun release() { runCatching { vehicle?.release() }; vehicle = null }

    override suspend fun latestOdometerKm(): Double? = lastOdometer

    override suspend fun capabilitySnapshot(): List<CapabilitySnapshot> = coroutineScope {
        val veh = v()
        listOf(
            async { cap(CareRuntimeSupervisor.CAP_TELLTALE) { ok, err -> veh.getSafety().checkTelltalesCapabilities(ok, err) } },
            async { cap(CareRuntimeSupervisor.CAP_ODOMETER) { ok, err -> veh.getOdometer().checkCurrentDistanceDrivenCapabilities(ok, err) } },
            async { cap("vehicle.fuel.percent") { ok, err -> veh.getFuel().checkFuelPercentCapabilities(ok, err) } },
            async { cap("vehicle.ev.soc") { ok, err -> veh.getEvBattery().checkEvBatteryStateOfChargeCapabilities(ok, err) } },
            async { cap("vehicle.tire.pressure") { ok, err -> veh.getTire().checkTirePressureCapabilities({ cfg, _ -> ok(cfg) }, err) } },
            async { rangeCap(veh) },
        ).awaitAll()
    }

    /** 주행가능거리는 지원 확인 API가 없어 한 번 읽어 본다(읽히면 지원) */
    private suspend fun rangeCap(veh: Vehicle): CapabilitySnapshot {
        fun snap(supported: Boolean?, note: String?) =
            CapabilitySnapshot(PleosPlatform.SDK_VEHICLE, "2.3.2", CAP_RANGE, supported, if (supported == true) PermissionState.GRANTED else PermissionState.UNKNOWN, Instant.now(), note)
        return withTimeoutOrNull(3000) {
            suspendCancellableCoroutine { c ->
                runCatching {
                    veh.getOdometer().getRangeRemainingDistance({ m -> if (c.isActive) c.resume(snap(true, "현재 ${m?.let { "%.0f".format(it / 1000f) } ?: "?"}km")) },
                        { e -> if (c.isActive) c.resume(snap(false, e.message)) })
                }.onFailure { e -> if (c.isActive) c.resume(snap(null, e.message)) }
            }
        } ?: snap(null, "응답 없음")
    }

    private suspend fun cap(key: String, call: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit): CapabilitySnapshot {
        fun snap(supported: Boolean?, perm: PermissionState, note: String?) =
            CapabilitySnapshot(PleosPlatform.SDK_VEHICLE, "2.3.2", key, supported, perm, Instant.now(), note)
        return withTimeoutOrNull(3000) {
            suspendCancellableCoroutine { c ->
                runCatching {
                    call({ cfg -> if (c.isActive) c.resume(snap(true, PermissionState.GRANTED, "access=${cfg.accessType}")) },
                        { e -> if (c.isActive) c.resume(snap(false, if (e is SecurityException) PermissionState.DENIED else PermissionState.UNKNOWN, e.message)) })
                }.onFailure { e -> if (c.isActive) c.resume(snap(null, if (e is SecurityException) PermissionState.DENIED else PermissionState.UNKNOWN, e.message)) }
            }
        } ?: snap(null, PermissionState.UNKNOWN, "응답 없음")
    }

    override fun observeSignals(): Flow<RawVehicleSignal> = callbackFlow {
        val veh = v()
        val safety = veh.getSafety(); val odo = veh.getOdometer(); val gear = veh.getGear(); val fuel = veh.getFuel(); val ev = veh.getEvBattery()
        val states = HashMap<TelltaleKind, TelltaleState>()

        fun emit(s: RawVehicleSignal) { trySend(s) }

        @Synchronized fun onTelltales(t: Telltales?) {
            val list = t?.telltales ?: return
            val seen = HashSet<TelltaleKind>()
            for (d in list) {
                val kind = TelltaleKind.entries.firstOrNull { it != TelltaleKind.OTHER && it.name == d.type.name } ?: continue
                val st = when (d.state.name) { "ON" -> TelltaleState.ON; "BLINK" -> TelltaleState.BLINK; else -> TelltaleState.OFF }
                seen += kind
                if (states[kind] != st) {
                    states[kind] = st
                    emit(RawVehicleSignal(SignalType.TELLTALE, source, Instant.now(), null, kind, st, telltaleColor = d.color.toString()))
                }
            }
            // 목록에서 빠진 경고등은 꺼진 것으로 본다
            for ((k, st) in states.toMap()) if (k !in seen && st != TelltaleState.OFF) {
                states[k] = TelltaleState.OFF
                emit(RawVehicleSignal(SignalType.TELLTALE, source, Instant.now(), null, k, TelltaleState.OFF))
            }
        }

        fun gearSignal(g: VehicleGear?) = RawVehicleSignal(SignalType.GEAR, source, Instant.now(), gear = when (g) {
            VehicleGear.GEAR_PARK -> Gear.PARK
            VehicleGear.GEAR_REVERSE -> Gear.REVERSE
            VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
            null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
            else -> Gear.DRIVE
        })

        fun odoSignal(km: Float) = RawVehicleSignal(SignalType.ODOMETER, source, Instant.now(), value = km.toDouble(), unit = "km")
            .also { lastOdometer = km.toDouble() }

        // 주행가능거리: SDK 값은 미터, 도메인에는 km로 보낸다
        fun rangeSignal(m: Float) = RawVehicleSignal(SignalType.RANGE_REMAINING, source, Instant.now(), value = m / 1000.0, unit = "km")
        val rl = object : RangeRemainingListener {
            override fun onRangeRemainingChanged(m: Float?) { m?.let { emit(rangeSignal(it)) } }
            override fun onFailed(e: Exception) { Log.w(TAG, "range listener: $e") }
        }

        val tl = object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(t: Telltales?) = onTelltales(t)
            override fun onFailed(e: Exception) { Log.w(TAG, "telltale listener: $e") }
        }
        val ol = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(d: Float?) { d?.let { emit(odoSignal(it)) } }
            override fun onFailed(e: Exception) { Log.w(TAG, "odometer listener: $e") }
        }
        val gl = object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) { emit(gearSignal(g)) }
            override fun onFailed(e: Exception) { Log.w(TAG, "gear listener: $e") }
        }
        val fl = object : FuelPercentListener {
            override fun onFuelPercentUpdated(p: Int?) { p?.let { emit(RawVehicleSignal(SignalType.FUEL_PERCENT, source, Instant.now(), value = it.toDouble(), unit = "%")) } }
            override fun onFailed(e: Exception) { Log.w(TAG, "fuel listener: $e") }
        }
        val el = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(p: Int?) { p?.let { emit(RawVehicleSignal(SignalType.EV_SOC, source, Instant.now(), value = it.toDouble(), unit = "%")) } }
            override fun onFailed(e: Exception) { Log.w(TAG, "ev listener: $e") }
        }

        // 바퀴별 공기압(kPa). 바퀴 이름은 도메인의 영역 이름으로 바꿔 보낸다 (SC-A, SC-B)
        val tire = veh.getTire()
        fun tireSignal(wheel: String, kpa: Float) =
            RawVehicleSignal(SignalType.TIRE_PRESSURE, source, Instant.now(), area = wheel, value = kpa.toDouble(), unit = "kPa")
        val tireListeners = WHEELS.map { (wv, name) ->
            WheelArea(wv) to object : TirePressureListener {
                override fun onTirePressureUpdated(p: Float?) { p?.let { emit(tireSignal(name, it)) } }
                override fun onFailed(e: Exception) { Log.w(TAG, "tire listener $name: $e") }
            }
        }
        tireListeners.forEachIndexed { i, (area, l) ->
            val name = WHEELS[i].second
            runCatching { tire.registerTirePressure(area, l) }.onFailure { Log.w(TAG, "registerTirePressure $name: $it") }
            runCatching { tire.getTirePressure(area, { p -> p?.let { emit(tireSignal(name, it)) } }, { Log.w(TAG, "tire get $name: $it") }) }
        }

        // 지원하지 않는 항목이 있어도 나머지는 계속 받는다 (DEGRADED)
        runCatching { safety.registerTelltales(tl) }.onFailure { Log.w(TAG, "registerTelltales: $it") }
        runCatching { safety.getTelltalesInfo({ onTelltales(it) }, { Log.w(TAG, "getTelltalesInfo: $it") }) }
        runCatching { odo.registerDistanceDriven(ol) }.onFailure { Log.w(TAG, "registerDistanceDriven: $it") }
        runCatching { odo.getCurrentDistanceDriven({ v -> v?.let { emit(odoSignal(it)) } }, { Log.w(TAG, "odometer get: $it") }) }
        runCatching { odo.registerRangeRemainingListener(rl) }.onFailure { Log.w(TAG, "registerRangeRemaining: $it") }
        runCatching { odo.getRangeRemainingDistance({ m -> m?.let { emit(rangeSignal(it)) } }, { Log.w(TAG, "range get: $it") }) }
        runCatching { gear.registerGearSelection(gl) }.onFailure { Log.w(TAG, "registerGearSelection: $it") }
        runCatching { gear.getCurrentGearSelectionState({ emit(gearSignal(it)) }, { Log.w(TAG, "gear get: $it") }) }
        runCatching { fuel.registerFuelPercent(fl) }.onFailure { Log.w(TAG, "registerFuelPercent: $it") }
        runCatching { fuel.getFuelPercent({ p -> p?.let { emit(RawVehicleSignal(SignalType.FUEL_PERCENT, source, Instant.now(), value = it.toDouble(), unit = "%")) } }, { Log.w(TAG, "fuel get: $it") }) }
        runCatching { ev.registerEvBatteryStateOfCharge(el) }.onFailure { Log.w(TAG, "registerEvSoc: $it") }
        runCatching { ev.getEvBatteryStateOfCharge({ p -> p?.let { emit(RawVehicleSignal(SignalType.EV_SOC, source, Instant.now(), value = it.toDouble(), unit = "%")) } }, { Log.w(TAG, "ev get: $it") }) }

        awaitClose {
            runCatching { safety.unregisterTelltales(tl) }
            runCatching { odo.unregisterDistanceDriven(ol) }
            runCatching { odo.unregisterRangeRemainingListener(rl) }
            runCatching { gear.unregisterGearSelection(gl) }
            runCatching { fuel.unregisterFuelPercent(fl) }
            runCatching { ev.unregisterEvBatteryStateOfCharge(el) }
            tireListeners.forEach { (area, l) -> runCatching { tire.unregisterTirePressure(area, l) } }
        }
    }
}
