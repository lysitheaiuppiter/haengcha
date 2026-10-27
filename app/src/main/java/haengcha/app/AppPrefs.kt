package haengcha.app

import android.content.Context
import haengcha.judge.Quest

/** 운전자 프로필(기획서 12.1 목업). 프로필마다 주행 기록 파일을 따로 쓴다. */
data class Profile(val id: String, val name: String)

/**
 * 프로필 목록·현재 프로필과 콜라보 동반자 보유 목록을 앱 설정에 둔다.
 * 콜라보 상점은 시연용 목업이라 실제 결제 없이 보유 표시만 남긴다.
 * 차량에 딸린 상태(열린 대장간 퀘스트, 역참 연속 운전)는 운전자와 상관없이 하나, 개근 보호권은 운전자마다 둔다.
 */
class AppPrefs(context: Context) {

    private val sp = context.getSharedPreferences("haengcha", Context.MODE_PRIVATE)

    val profiles: List<Profile>
        get() = sp.getString(KEY_PROFILES, null)
            ?.split(';')?.mapNotNull { e -> e.split('|').takeIf { it.size == 2 }?.let { Profile(it[0], it[1]) } }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(DEFAULT)

    var activeProfile: String
        get() = sp.getString(KEY_ACTIVE, null)?.takeIf { id -> profiles.any { it.id == id } } ?: DEFAULT.id
        set(value) = sp.edit().putString(KEY_ACTIVE, value).apply()

    fun addProfile(): Profile {
        val list = profiles
        val p = Profile("p${System.currentTimeMillis()}", "운전자 ${list.size + 1}")
        sp.edit().putString(KEY_PROFILES, (list + p).joinToString(";") { "${it.id}|${it.name}" }).apply()
        return p
    }

    var owned: Set<String>
        get() = sp.getStringSet(KEY_OWNED, emptySet()).orEmpty().toSet()
        set(value) = sp.edit().putStringSet(KEY_OWNED, value).apply()

    /** 대장간 퀘스트로 받은 개근 보호권 수(쓴 것 포함). */
    fun shields(profileId: String): Int = sp.getInt(KEY_SHIELDS + profileId, 0)

    fun addShield(profileId: String) = sp.edit().putInt(KEY_SHIELDS + profileId, shields(profileId) + 1).apply()

    /** 열려 있는 대장간 퀘스트와 연 시각(ms). */
    var openQuests: Map<Quest, Long>
        get() = sp.getStringSet(KEY_QUESTS, emptySet()).orEmpty().mapNotNull { e ->
            val f = e.split(':')
            runCatching { Quest.valueOf(f[0]) to (f.getOrNull(1)?.toLong() ?: 0L) }.getOrNull()
        }.toMap()
        set(value) = sp.edit().putStringSet(KEY_QUESTS, value.map { (q, t) -> "${q.name}:$t" }.toSet()).apply()

    /** 역참 연속 운전 상태([haengcha.judge.RestTracker.encode]). */
    var restState: String?
        get() = sp.getString(KEY_REST, null)
        set(value) = sp.edit().putString(KEY_REST, value).apply()

    companion object {
        private const val KEY_SHIELDS = "shields_"
        private const val KEY_QUESTS = "open_quests"
        private const val KEY_REST = "rest_state"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_ACTIVE = "active_profile"
        private const val KEY_OWNED = "owned_collab"
        val DEFAULT = Profile("default", "운전자 1")

        /** 첫 프로필은 예전과 같은 trips.csv를 쓴다. */
        fun tripsFile(id: String) = if (id == DEFAULT.id) "trips.csv" else "trips_$id.csv"
    }
}
