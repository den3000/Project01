package ru.den.writes.code.agenticHub.features.memory

import ru.den.writes.code.agenticHub.features.agent.memory.ProfileData
import ru.den.writes.code.agenticHub.features.agent.memory.ProfileSection
import ru.den.writes.code.agenticHub.features.agent.memory.RuleEntry
import ru.den.writes.code.agenticHub.features.agent.memory.TaskNotes

/**
 * Persistence port for the long-term (profile + rules) and working
 * (per-task notes) memory layers. Neutral surface — all types cross the
 * portable boundary ([ProfileData] / [RuleEntry] / [TaskNotes] / plain
 * strings), so the conversation runtime ([MemoryProvider], `CommandRunner`,
 * `TurnEngine`) depends only on this interface. The file-backed
 * implementation ([FileMemoryStore]) supplies the storage layout.
 */
public interface MemoryStore {
    // --- Unnamed profile (profile.md) ------------------------------
    fun loadProfile(): String?
    fun saveProfile(text: String)
    fun loadProfileData(): ProfileData?
    fun saveProfileData(data: ProfileData)
    fun addProfileItem(section: ProfileSection, text: String): ProfileData
    fun clearProfileSection(section: ProfileSection): ProfileData
    fun clearProfile()

    // --- Named profiles (profiles/<name>.md) -----------------------
    fun listProfileNames(): List<String>
    fun loadNamedProfile(name: String): ProfileData?
    fun saveNamedProfile(name: String, data: ProfileData)
    fun addNamedProfileItem(name: String, section: ProfileSection, text: String): ProfileData
    fun clearNamedProfileSection(name: String, section: ProfileSection): ProfileData
    fun clearNamedProfile(name: String): Boolean
    fun clearAllProfiles(): Int
    fun touchNamedProfile(name: String)

    // --- Rules (rules/NNN-*.md) ------------------------------------
    fun listRules(): List<RuleEntry>
    fun addRule(text: String): RuleEntry
    fun removeRule(id: String): Boolean
    fun clearRules(): Int

    // --- Tasks (tasks/<id>.md) -------------------------------------
    fun listTaskIds(): List<String>
    fun loadTask(taskId: String): TaskNotes?
    fun saveTask(notes: TaskNotes)
    fun appendTaskNote(taskId: String, note: String)
    fun deleteTask(taskId: String): Boolean
    fun clearTasks(): Int
}
