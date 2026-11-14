package app.carecare.domain

/** 승인 관문을 거치지 않고는 내비 목적지를 바꿀 수 없다 (13.4). */
class ActionExecutor(
    private val gate: ApprovalGate,
    private val navigation: NavigationGateway,
) {
    suspend fun setDestination(action: ProposedAction, dest: NavDestination): ExecutionResult {
        require(action.type == ActionType.SET_NAV_DESTINATION)
        if (dest.toPayload() != action.payload) return ExecutionResult.Blocked("승인된 목적지와 실행 목적지가 다름")
        return when (val auth = gate.authorize(action)) {
            is Authorization.Denied -> ExecutionResult.Blocked(auth.reason)
            is Authorization.Granted -> navigation.setDestination(dest)
        }
    }

    /** 경유지 경로. 승인 대상(목적지+경유지)과 실행 대상이 같아야 한다 */
    suspend fun setRoute(action: ProposedAction, dest: NavDestination, via: NavDestination): ExecutionResult {
        require(action.type == ActionType.SET_NAV_DESTINATION)
        if (routePayload(dest, via) != action.payload) return ExecutionResult.Blocked("승인된 경로와 실행 경로가 다름")
        return when (val auth = gate.authorize(action)) {
            is Authorization.Denied -> ExecutionResult.Blocked(auth.reason)
            is Authorization.Granted -> navigation.setRoute(dest, via)
        }
    }

    companion object {
        fun proposeNavigation(actionId: String, dest: NavDestination, incidentId: String?) =
            ProposedAction(actionId, ActionType.SET_NAV_DESTINATION, dest.toPayload(), incidentId = incidentId)

        fun proposeRoute(actionId: String, dest: NavDestination, via: NavDestination, incidentId: String?) =
            ProposedAction(actionId, ActionType.SET_NAV_DESTINATION, routePayload(dest, via), incidentId = incidentId)

        private fun routePayload(dest: NavDestination, via: NavDestination) = dest.toPayload() + via.toPayload().mapKeys { "via.${it.key}" }
    }
}
