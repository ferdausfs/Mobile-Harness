package com.jarves.mh.data

import com.jarves.mh.model.Project
import com.jarves.mh.model.ProjectKind
import com.jarves.mh.model.projectSlug
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure (de)serialization for the persisted project list (v1.0.31).
 *
 * Extracted from [AppPreferences.loadProjects] so it is JVM-testable and so
 * the failure model is explicit:
 *  - unreadable JSON → empty list, never a throw;
 *  - ONE malformed entry (missing id/name, wrong types) is SKIPPED — before
 *    this change a single bad element discarded the whole list (silent data
 *    loss on the projects screen after a corrupt write or an old-data
 *    migration), which is exactly the class of persisted-state failure the
 *    2026-10-19 startup-crash report demanded be made impossible;
 *  - unknown kinds fall back to PROJECT; missing timestamps/slugs are
 *    backfilled and flagged so the caller can re-persist the normalized list.
 */
object ProjectStoreJson {

    data class Parsed(
        val projects: List<Project>,
        /** True when at least one entry was backfilled/normalized — caller should re-save. */
        val needsSave: Boolean,
    )

    /**
     * Parses the array produced by [AppPreferences.saveProjects].
     * [workspaceLastModified] supplies the legacy timestamp backfill
     * (filesDir/workspaces/<id> mtime; -1 when absent) — pure callers pass { -1 }.
     */
    fun parse(
        raw: String?,
        workspaceLastModified: (String) -> Long = { -1L },
    ): Parsed {
        if (raw.isNullOrBlank()) return Parsed(emptyList(), false)
        val array = runCatching { JSONArray(raw) }.getOrNull()
            ?: return Parsed(emptyList(), false)
        var needsSave = false
        val usedSlugs = mutableSetOf<String>()
        val projects = ArrayList<Project>(array.length())
        for (i in 0 until array.length()) {
            // Per-entry tolerance: one bad row is skipped, the rest survive.
            val entry = runCatching {
                parseEntry(array.optJSONObject(i) ?: return@runCatching null, usedSlugs, workspaceLastModified)
            }.getOrNull()
            if (entry == null) {
                needsSave = true
                continue
            }
            projects.add(entry.project)
            if (entry.flagged) needsSave = true
        }
        return Parsed(projects, needsSave)
    }

    /** Normalized entry plus whether it had to be repaired (→ caller re-saves). */
    private data class Entry(val project: Project, val flagged: Boolean)

    private fun parseEntry(
        obj: JSONObject,
        usedSlugs: MutableSet<String>,
        workspaceLastModified: (String) -> Long,
    ): Entry {
        val id = obj.getString("id")
        val name = obj.getString("name")
        val storedKind = obj.optString("kind", ProjectKind.PROJECT.name)
        var flagged = false
        if (!obj.has("kind") || storedKind == "QUICK_CHAT") flagged = true
        val requestedSlug = obj.optString("slug").ifBlank { projectSlug(name) }
        var slug = requestedSlug
        if (!usedSlugs.add(slug)) {
            slug = "$requestedSlug-${id.take(6)}"
            var suffix = 2
            while (!usedSlugs.add(slug)) slug = "$requestedSlug-${suffix++}"
        }
        if (slug != obj.optString("slug")) flagged = true
        var millis = obj.optLong("updatedAtMillis", 0L)
        if (millis <= 0L) {
            val workspaceMillis = workspaceLastModified(id)
            millis = if (workspaceMillis > 0L) workspaceMillis else System.currentTimeMillis() - 3600_000L
            flagged = true
        }
        val kind = when (storedKind) {
            "QUICK_CHAT" -> ProjectKind.QUICK_PROJECT
            else -> runCatching { ProjectKind.valueOf(storedKind) }.getOrDefault(ProjectKind.PROJECT)
        }
        val project = Project(
            id = id,
            name = name,
            description = obj.optString("description", ""),
            language = obj.optString("language", ""),
            slug = slug,
            rootPath = obj.optString("rootPath", "").takeIf { root ->
                root.isBlank() || (!root.startsWith('/') && !root.contains(".."))
            } ?: "",
            updatedAtMillis = millis,
            kind = kind,
        )
        return Entry(project, flagged)
    }

    /** Serialized form for [AppPreferences.saveProjects]. */
    fun toJson(projects: List<Project>): String {
        val arr = JSONArray()
        projects.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("description", p.description)
                    put("language", p.language)
                    put("slug", p.slug)
                    put("rootPath", p.rootPath)
                    put("updatedAtMillis", p.updatedAtMillis)
                    put("kind", p.kind.name)
                },
            )
        }
        return arr.toString()
    }
}
