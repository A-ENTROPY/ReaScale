package io.reascale.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 全局应用设置存储
 * 对应 §18.10
 * 使用 DataStore Preferences + JSON 序列化以支持版本演进
 */
private val Context.appSettingsStore by preferencesDataStore(name = "reascale_settings")

class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val settingsKey: Preferences.Key<String> = stringPreferencesKey("app_settings_json")

    /** 设置流（响应式） */
    val settingsFlow: Flow<AppSettings> = context.appSettingsStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs ->
            val raw = prefs[settingsKey]
            if (raw.isNullOrBlank()) {
                AppSettings()
            } else {
                runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
                    .getOrElse { AppSettings() }
            }
        }

    /** 当前快照（一次性读取） */
    suspend fun get(): AppSettings = settingsFlow.firstSnapshot()

    /**
     * 更新设置（函数式 transform）
     *
     * [FIX] 原实现先 get()（读 DataStore 首帧快照）再 edit 写入：
     * 快速连续操作时第二次 transform 基于旧快照，后写覆盖先写 → 设置丢失。
     * 现在把解码 + transform + 编码放进 DataStore.edit 的 transform 内，
     * DataStore 保证 edit 串行执行，多次快速更新不会互相覆盖。
     */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appSettingsStore.edit { prefs ->
            val raw = prefs[settingsKey]
            val current = if (raw.isNullOrBlank()) {
                AppSettings()
            } else {
                runCatching { json.decodeFromString(AppSettings.serializer(), raw) }
                    .getOrElse { AppSettings() }
            }
            prefs[settingsKey] = json.encodeToString(AppSettings.serializer(), transform(current))
        }
    }

    /** 直接覆盖 */
    suspend fun set(settings: AppSettings) {
        val encoded = json.encodeToString(AppSettings.serializer(), settings)
        context.appSettingsStore.edit { it[settingsKey] = encoded }
    }
}

/**
 * Flow.first() 的精简版，避免重复依赖 Flow 扩展
 */
private suspend fun <T> Flow<T>.firstSnapshot(): T {
    var result: T? = null
    val collector = object : kotlinx.coroutines.flow.FlowCollector<T> {
        override suspend fun emit(value: T) {
            if (result == null) {
                result = value
                throw kotlinx.coroutines.CancellationException("first")
            }
        }
    }
    try {
        collect(collector)
    } catch (_: kotlinx.coroutines.CancellationException) {
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    @Suppress("UNCHECKED_CAST")
    return result as T
}