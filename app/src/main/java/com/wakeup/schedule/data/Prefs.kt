package com.wakeup.schedule.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "wakeup_prefs")

/** 全局偏好：当前课表、深色模式、时间轴是否显示具体时间 */
class Prefs(private val context: Context) {

    private val KEY_CURRENT_TABLE = longPreferencesKey("current_table_id")
    private val KEY_DARK_MODE = intPreferencesKey("dark_mode") // 0 跟随系统 1 浅色 2 深色
    private val KEY_SHOW_TIME = intPreferencesKey("show_section_time") // 1 显示 0 仅节数
    private val KEY_SAMPLE_LOADED = intPreferencesKey("sample_loaded")
    private val KEY_IGNORED_VERSION = intPreferencesKey("ignored_version_code")
    /** 上次成功下载更新的来源下标（0=官方，1=镜像）；默认 1：国内镜像通常快得多 */
    private val KEY_UPDATE_SOURCE = intPreferencesKey("update_source_index")

    val currentTableId: Flow<Long> = context.dataStore.data.map { it[KEY_CURRENT_TABLE] ?: -1L }
    val darkMode: Flow<Int> = context.dataStore.data.map { it[KEY_DARK_MODE] ?: 0 }
    val showSectionTime: Flow<Boolean> = context.dataStore.data.map { (it[KEY_SHOW_TIME] ?: 1) == 1 }
    val sampleLoaded: Flow<Boolean> = context.dataStore.data.map { (it[KEY_SAMPLE_LOADED] ?: 0) == 1 }

    /** 用户选择「忽略此版本」的 versionCode，0 表示没有忽略任何版本 */
    val ignoredVersionCode: Flow<Int> = context.dataStore.data.map { it[KEY_IGNORED_VERSION] ?: 0 }

    /**
     * 上次成功下载更新的来源下标，默认 0 = **CDN 加速**。
     * 实测同一时刻：CDN（jsDelivr）约 316 KB/s、加速镜像 4~8 KB/s、GitHub 直连无 VPN 时不可用。
     */
    val updateSourceIndex: Flow<Int> = context.dataStore.data.map { it[KEY_UPDATE_SOURCE] ?: 0 }

    suspend fun setIgnoredVersionCode(code: Int) {
        context.dataStore.edit { it[KEY_IGNORED_VERSION] = code }
    }

    suspend fun setUpdateSourceIndex(index: Int) {
        context.dataStore.edit { it[KEY_UPDATE_SOURCE] = index }
    }

    suspend fun setCurrentTable(id: Long) {
        context.dataStore.edit { it[KEY_CURRENT_TABLE] = id }
    }

    suspend fun setDarkMode(mode: Int) {
        context.dataStore.edit { it[KEY_DARK_MODE] = mode }
    }

    suspend fun setShowSectionTime(show: Boolean) {
        context.dataStore.edit { it[KEY_SHOW_TIME] = if (show) 1 else 0 }
    }

    suspend fun setSampleLoaded(loaded: Boolean) {
        context.dataStore.edit { it[KEY_SAMPLE_LOADED] = if (loaded) 1 else 0 }
    }
}
