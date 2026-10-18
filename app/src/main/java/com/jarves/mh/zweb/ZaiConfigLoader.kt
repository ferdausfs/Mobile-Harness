package com.jarves.mh.zweb

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads the Z.ai Web selector config with this precedence:
 *
 *  1. a remotely refreshed copy cached in `filesDir/zweb/selectors.json`, when its
 *     [ZaiWebConfig.version] is strictly higher than the bundled one;
 *  2. the config bundled in assets (`assets/zweb/selectors.json`).
 *
 * Remote refresh is best-effort: any network or parse failure keeps the previous
 * config and only logs. The cached copy never downgrades to an older version, and
 * a cached file that fails validation is deleted so the bundled copy takes over.
 * This keeps chat.z.ai UI drift fixable by a JSON-only commit, without an app release.
 */
class ZaiConfigLoader(private val context: Context) {

    @Volatile
    private var cached: ZaiWebConfig? = null

    @Volatile
    private var refreshInFlight: Boolean = false

    private val cacheFile: File
        get() = File(File(context.filesDir, CACHE_DIR_NAME), CACHE_FILE_NAME)

    /** Current config (cached remote copy when newer, else bundled). Never null unless assets are broken. */
    fun load(): ZaiWebConfig? {
        cached?.let { return it }
        val bundled = bundledConfig()
        val remoteCached = remoteCachedConfig(bundled?.version ?: 0)
        val chosen = remoteCached ?: bundled
        cached = chosen
        return chosen
    }

    fun cachedVersion(): Int = cached?.version ?: load()?.version ?: 0

    /**
     * Downloads [ZaiWebConfig.remoteUpdateUrl] in the caller's thread and installs the
     * result when its version is newer than what is currently loaded. Returns the new
     * version on success, 0 when nothing changed and -1 on failure.
     */
    fun refreshFromRemote(): Int {
        val current = load() ?: return -1
        val url = current.remoteUpdateUrl
        if (url.isBlank()) return 0
        if (!enterRefresh()) return 0
        try {
            val text = download(url) ?: return -1
            val parsed = try {
                ZaiWebConfig.fromJson(text)
            } catch (e: ZaiConfigException) {
                Log.w(TAG, "Remote selector config rejected: ${e.message}")
                return -1
            }
            if (parsed.version <= current.version) return 0
            return try {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(text)
                cached = parsed
                Log.i(TAG, "Selector config updated to v${parsed.version} from remote")
                parsed.version
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist selector config cache", e)
                -1
            }
        } finally {
            exitRefresh()
        }
    }

    /** Clears the cached config (used by tests and by disconnect-style resets). */
    fun resetCache() {
        cached = null
        cacheFile.delete()
    }

    private fun bundledConfig(): ZaiWebConfig? = try {
        context.assets.open(BUNDLED_ASSET_PATH).use { input ->
            ZaiWebConfig.fromJson(input.bufferedReader().readText())
        }
    } catch (e: Exception) {
        Log.e(TAG, "Bundled selector config missing or invalid — Z.ai Web disabled", e)
        null
    }

    private fun remoteCachedConfig(bundledVersion: Int): ZaiWebConfig? {
        if (!cacheFile.isFile) return null
        return try {
            val parsed = ZaiWebConfig.fromJson(cacheFile.readText())
            if (parsed.version > bundledVersion) {
                parsed
            } else {
                // Stale or corrupt cache: delete so the bundled copy wins.
                cacheFile.delete()
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cached selector config unreadable — falling back to bundled", e)
            runCatching { cacheFile.delete() }
            null
        }
    }

    private fun download(url: String): String? = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.instanceFollowRedirects = true
        if (connection.responseCode !in 200..299) {
            Log.w(TAG, "Selector config refresh HTTP ${connection.responseCode}")
            null
        } else {
            connection.inputStream.bufferedReader().readText()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Selector config refresh failed: ${e.message}")
        null
    }

    private fun enterRefresh(): Boolean = synchronized(this) {
        if (refreshInFlight) false else { refreshInFlight = true; true }
    }

    private fun exitRefresh(): Unit = synchronized(this) { refreshInFlight = false }

    companion object {
        private const val TAG = "ZaiConfigLoader"
        const val BUNDLED_ASSET_PATH = "zweb/selectors.json"
        const val CACHE_DIR_NAME = "zweb"
        const val CACHE_FILE_NAME = "selectors.json"
    }
}
