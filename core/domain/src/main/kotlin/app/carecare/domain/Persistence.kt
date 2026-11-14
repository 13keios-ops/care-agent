package app.carecare.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 로컬 영속 데이터 전체 (13.5: 로컬 단일 단말).
 * 저장하지 않는 것: GPS 좌표 원본, 음성 원본, VIN (21.1).
 */
@Serializable
data class CareSnapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val incidents: List<Incident> = emptyList(),
    val records: List<MaintenanceRecord> = emptyList(),
    val intervals: List<ServiceInterval> = emptyList(),
    val visits: List<VisitCandidate> = emptyList(),
    val suppressedPois: Set<String> = emptySet(),
    val visitFeatureSuppressed: Boolean = false,
    val visitOptIn: Boolean = false,
    val consents: List<ConsentDecision> = emptyList(),
    val handoffs: List<ServiceHandoffCard> = emptyList(),
    /** 이미 물어본 교환 시기 알림 (recordId:itemCode). 같은 알림을 반복하지 않는다 */
    val promptedReminders: Set<String> = emptySet(),
    val voiceEnabled: Boolean = true,
    val demoShortDwell: Boolean = false,
    val lastOdometerKm: Double? = null,
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

interface CareStore {
    fun load(): CareSnapshot?
    fun save(snapshot: CareSnapshot)
    fun clear()
}

/** JSON 파일 저장소. 임시 파일에 쓴 뒤 바꿔 끼워 쓰다 만 파일이 남지 않게 한다 (NFR-11). */
class JsonFileCareStore(private val file: File) : CareStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): CareSnapshot? {
        if (!file.exists()) return null
        return runCatching { json.decodeFromString(CareSnapshot.serializer(), file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    override fun save(snapshot: CareSnapshot) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(CareSnapshot.serializer(), snapshot), Charsets.UTF_8)
        if (file.exists() && !file.delete()) error("기존 저장 파일을 바꾸지 못했습니다: $file")
        if (!tmp.renameTo(file)) error("저장 파일 이름을 바꾸지 못했습니다: $tmp")
    }

    override fun clear() { file.delete() }
}

class InMemoryCareStore : CareStore {
    var snapshot: CareSnapshot? = null
    var saveCount = 0
        private set
    override fun load() = snapshot
    override fun save(snapshot: CareSnapshot) { this.snapshot = snapshot; saveCount++ }
    override fun clear() { snapshot = null }
}
