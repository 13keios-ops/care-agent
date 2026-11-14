package app.carecare.app.demo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 에뮬레이터 Virtual Console 클라이언트. 앱 안에서 127.0.0.1:8088로 바로 부른다.
 * Virtual Console은 브라우저 세션 신호(3초 만료)가 있어야 값을 받으므로 PUT마다 세션 신호를 먼저 보낸다.
 * 닿지 않으면(실차, 콘솔 꺼짐) 예외를 던지지 않고 false를 돌려준다. 호출 측은 시뮬레이션 신호로 대신한다.
 */
class VirtualConsoleClient(
    @Volatile var base: String = DEFAULT_BASE,
    private val http: (method: String, url: String) -> Response = ::defaultHttp,
) {
    data class Response(val code: Int, val body: String)

    suspend fun apply(cmds: List<VcCommand>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            cmds.all { c ->
                ok(http("POST", "$base/browser/session?client=care-demo-panel")) && ok(http("PUT", base + c.path))
            }
        }.getOrDefault(false)
    }

    suspend fun reachable(): Boolean = withContext(Dispatchers.IO) { runCatching { ok(http("GET", "$base/vehicle/state")) }.getOrDefault(false) }

    /** Virtual Console의 누적 주행거리(km). 앱이 받은 값은 늦을 수 있어 줄일 수 없는 주행거리 판단에는 이 값을 쓴다. 닿지 않으면 null */
    suspend fun odometerKm(): Double? = withContext(Dispatchers.IO) {
        runCatching {
            val r = http("GET", "$base/vehicle/state")
            if (!ok(r)) null else ODOMETER.find(r.body)?.groupValues?.get(1)?.toDoubleOrNull()
        }.getOrNull()
    }

    private fun ok(r: Response) = r.code in 200..299

    companion object {
        const val DEFAULT_BASE = "http://127.0.0.1:8088"
        private val ODOMETER = Regex("\"traveled_distance_km\"\\s*:\\s*([0-9.]+)")

        fun defaultHttp(method: String, url: String): Response {
            val c = URL(url).openConnection() as HttpURLConnection
            return try {
                c.requestMethod = method; c.connectTimeout = 1500; c.readTimeout = 4000
                if (method == "POST" || method == "PUT") { c.doOutput = true; c.outputStream.use { } }
                val code = c.responseCode
                val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull() ?: ""
                Response(code, body)
            } finally {
                c.disconnect()
            }
        }
    }
}
