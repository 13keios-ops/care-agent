package app.carecare.app.demo

/**
 * 앱 안에 넣은 시뮬레이션 신호의 마지막 값. 동력 방식을 바꾸면 상시 서비스가 다시 시작되어 새 에이전트가 생기므로,
 * 그때 이 값을 다시 넣어 장면이 이어지게 한다(Review Focus 2). 한 번만 하는 조작(초기화, 기록 추가 등)은 기억하지 않는다.
 */
class SimState {
    private val tires = linkedMapOf<String, Double>()
    private var fuel: SimAction.Fuel? = null
    private var range: SimAction.Range? = null
    private var trip: SimAction.Trip? = null
    private var commute: SimAction.Commute? = null
    private var location: SimAction.Location? = null

    @Synchronized fun record(a: SimAction) {
        when (a) {
            is SimAction.Tire -> if (a.wheel == "ALL") WHEELS.forEach { tires[it] = a.kpa } else tires[a.wheel] = a.kpa
            is SimAction.Fuel -> fuel = a
            is SimAction.Range -> range = a
            is SimAction.Trip -> trip = a
            is SimAction.Commute -> commute = a
            is SimAction.Location -> location = a
            else -> {}
        }
    }

    @Synchronized fun replay(): List<SimAction> =
        tires.map { (w, k) -> SimAction.Tire(w, k) } + listOfNotNull(fuel, range, commute, trip, location)

    @Synchronized fun clear() { tires.clear(); fuel = null; range = null; trip = null; commute = null; location = null }

    companion object { val WHEELS = listOf("LEFT_FRONT", "RIGHT_FRONT", "LEFT_REAR", "RIGHT_REAR") }
}
