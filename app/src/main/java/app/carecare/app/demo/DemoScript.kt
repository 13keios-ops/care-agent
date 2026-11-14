package app.carecare.app.demo

import app.carecare.domain.CommutePattern
import app.carecare.domain.DataSource
import app.carecare.domain.DemoPlaceCatalog
import app.carecare.domain.GeoPoint
import app.carecare.domain.ItemCode
import app.carecare.domain.Powertrain
import app.carecare.domain.TireUnits
import app.carecare.domain.TripContext
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * 시연 장(章)과 단계. 장 구성: 1 타이어, 2-A 주유, 2-B 충전, 2-C 찾아가는 충전, 3 방문 기록, 4 교환 시기.
 * 각 장의 첫 단계는 [base](문제 없는 상태)를 먼저 맞춘다. 주행 60km/h, 정차 0km/h.
 */
object DemoScript {
    private val driving = listOf(VcCommand.Gear("D"), VcCommand.Speed(60))
    private val parked = listOf(VcCommand.Gear("P"), VcCommand.Speed(0))
    /** 에뮬레이터 차량 배터리 용량 */
    const val BATTERY_KWH = 77.4
    /** 기본 상태의 최소 주행거리(재부팅 뒤 0km여도 교환 시점을 계산할 수 있게) */
    const val BASE_ODOMETER_KM = 32_500.0
    private fun battery(pct: Int) = listOf(VcCommand.Soc(pct), VcCommand.BatteryKwh(BATTERY_KWH * pct / 100))
    private val shopB = DemoPlaceCatalog.byId("DEMO_SHOP_B")!!.let { GeoPoint(it.latitude, it.longitude) }
    /** 공기압 시연 값(psi → kPa). 권장 36psi(VehicleProfile 기본값) 기준: 31.7psi(88%) 이하는 관리 필요, 27psi(75%) 이하는 경고 수준. 시연 29·30psi는 경고 수준 바로 위 */
    private fun psi(v: Double) = TireUnits.kpa(v)

    /** 평소 귀가 패턴(시연 데이터): 집 45km, 평일 17:00~20:00 */
    val commute = CommutePattern("집", 45.0, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
        LocalTime.of(17, 0), LocalTime.of(20, 0), DemoPlaceCatalog.DEMO_HOME, DataSource.SIMULATED_REPLAY)
    val highwayTrip = TripContext("부산", 300.0, highway = true, source = DataSource.SIMULATED_REPLAY, basis = "고속도로 · 내비 목적지")

    /**
     * 문제 없는 상태: 시연 데이터(사건·기록) 초기화, 정차, 프렁크 닫힘, 문 잠김, 경고등 끔, 네 바퀴 권장 공기압(36psi), 배터리 80%, 연료 60%, 주행가능거리 380km, 출발점.
     * 시연마다 데이터를 지워야 같은 시연을 다시 눌러도 열린 사건(같은 경고 30분 묶음)에 막히지 않는다
     */
    val base = DemoStep(
        "0.base", "기본 상태(문제 없음)",
        vc = parked + listOf(VcCommand.Frunk(closed = true), VcCommand.DoorsLocked(true), VcCommand.Telltale("SeatBelt", false),
            VcCommand.Telltale("TPMS", false), VcCommand.RangeM(380_000)) + battery(80),
        sim = listOf(SimAction.ResetData, SimAction.Tire("ALL", psi(36.0)), SimAction.Fuel(60), SimAction.Trip(null), SimAction.Commute(null, null),
            SimAction.Location(DemoPlaceCatalog.DEMO_START), SimAction.OdometerAtLeast(BASE_ODOMETER_KM)),
        screen = StepScreen.NAV,
    )

    val chapters: List<DemoChapter> = listOf(
        DemoChapter("0", "준비", "기본 상태로 맞추고 체험 기록을 지웁니다", listOf(
            DemoStep("0.reset", "체험 데이터 초기화", vc = base.vc, sim = base.sim, screen = StepScreen.NAV),
        )),
        // 예시 하나만. 두 바퀴 공기압이 경고 수준(27psi)에 가까워짐 → 공기 넣으라는 안내 → 정비소 안내
        DemoChapter("1", "타이어 공기압", "주행 중 앞바퀴 두 개 공기압이 경고 수준에 가까워짐 → 공기 보충 안내 → 정비소 안내", listOf(
            DemoStep("1.1", "주행 시작 (D · 60km/h)", vc = driving, screen = StepScreen.NAV),
            DemoStep("1.2", "앞 왼쪽 29psi · 앞 오른쪽 30psi (권장 36psi, 경고등 전)",
                sim = listOf(SimAction.Tire("LEFT_FRONT", psi(29.0)), SimAction.Tire("RIGHT_FRONT", psi(30.0))), talks = true),
        )),
        DemoChapter("2A", "주유", "플러그인 하이브리드 · 평소 주유소까지 충분한지", listOf(
            DemoStep("2A.0", "동력 방식: 플러그인 하이브리드", sim = listOf(SimAction.SetPowertrain(Powertrain.PHEV)), screen = StepScreen.NAV),
            DemoStep("2A.1", "주행 시작 (D · 60km/h)", vc = driving, screen = StepScreen.NAV),
            DemoStep("2A.2", "연료 12% · 주행가능거리 40km", vc = listOf(VcCommand.RangeM(40_000)), sim = listOf(SimAction.Fuel(12)), talks = true),
        )),
        DemoChapter("2B", "충전 (퇴근길)", "전기차 · 평일 퇴근 시간 · 집까지 갈 수 있는지", listOf(
            DemoStep("2B.0", "동력 방식: 전기차", sim = listOf(SimAction.SetPowertrain(Powertrain.EV)), screen = StepScreen.NAV),
            DemoStep("2B.1", "퇴근길 주행 시작 (평일 18:30 · 집 45km, 체험용 데이터)", vc = driving,
                sim = listOf(SimAction.Commute(commute, LocalTime.of(18, 30))), screen = StepScreen.NAV),
            // 주행가능거리를 먼저 보내야 '목적지까지 부족'이 먼저 생기고 배터리 부족 안내를 합친다
            DemoStep("2B.2", "배터리 18% · 주행가능거리 32km", vc = listOf(VcCommand.RangeM(32_000)), vcAfter = battery(18), talks = true),
        )),
        DemoChapter("2C", "찾아가는 충전", "전기차 · 고속도로 · 잔여 60km 이하", listOf(
            DemoStep("2C.0", "동력 방식: 전기차", sim = listOf(SimAction.SetPowertrain(Powertrain.EV)), screen = StepScreen.NAV),
            DemoStep("2C.1", "고속도로 주행 (목적지 부산 300km, 체험용 데이터)", vc = driving,
                sim = listOf(SimAction.Commute(null, null), SimAction.Trip(highwayTrip)), screen = StepScreen.NAV),
            DemoStep("2C.2", "배터리 8% · 주행가능거리 25km", vc = listOf(VcCommand.RangeM(25_000)), vcAfter = battery(8), talks = true),
        )),
        // 체류 시간을 실제로 기다리지 않고, 2분 머문 위치 기록을 체험용 데이터로 바로 넣는다
        DemoChapter("3", "정비소 방문 기록", "플러그인 하이브리드 · 정비소에 2분 머묾(체험용 데이터) → 출발 → 다음 정차", listOf(
            DemoStep("3.1", "데모 정비소 B에 2분 머묾 (체험용 데이터, 정차)",
                sim = listOf(SimAction.SetPowertrain(Powertrain.PHEV), SimAction.VisitDemoOn, SimAction.Location(shopB, agoSeconds = 120)), screen = StepScreen.NAV),
            DemoStep("3.2", "출발 (D · 60km/h)", vc = driving, sim = listOf(SimAction.Location(DemoPlaceCatalog.DEMO_START)), screen = StepScreen.NAV),
            DemoStep("3.3", "다음 정차 (P · 0km/h)", vc = parked, screen = StepScreen.NAV, talks = true),
        )),
        DemoChapter("4", "소모품 교환 시기", "플러그인 하이브리드 · 지난 교환 기록 → 교환 700km 전 미리 알림 → 정비소 예약", listOf(
            DemoStep("4.1", "지난 교환 기록 (없을 때만 체험용 데이터로 추가)",
                sim = listOf(SimAction.SetPowertrain(Powertrain.PHEV), SimAction.SeedRecord(listOf(ItemCode.ENGINE_OIL, ItemCode.OIL_FILTER))), screen = StepScreen.NAV),
            DemoStep("4.2", "주행거리 +9,300km (교환 700km 전)", sim = listOf(SimAction.OdometerAdd(9_300.0)), talks = true),
        )),
    )

    fun step(id: String): DemoStep? = if (id == base.id) base else chapters.flatMap { it.steps }.firstOrNull { it.id == id }
    fun chapterOf(id: String): DemoChapter? = chapters.firstOrNull { c -> c.steps.any { it.id == id } }
}
