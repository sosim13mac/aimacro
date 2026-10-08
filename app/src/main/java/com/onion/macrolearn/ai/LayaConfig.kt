package com.onion.macrolearn.ai

import android.content.Context
import com.onion.macrolearn.BuildConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Laya 서버 URL. 앱에서 저장한 값이 있으면 빌드 시 값(BuildConfig)보다 우선한다. */
object LayaConfig {
    private const val PREFS = "laya"
    private const val KEY_URL = "base_url"
    @Volatile private var override: String? = null

    fun init(context: Context) {
        override = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_URL, null)?.takeIf { it.isNotBlank() }
    }

    fun baseUrl(): String = normalize(override ?: BuildConfig.LAYA_BASE_URL)

    /** 아직 예시 URL 이면 서버가 연결되지 않은 상태 */
    fun isPlaceholder(): Boolean = "your-hf-space" in baseUrl()

    /** 유효한 URL 이면 저장하고 true */
    fun save(context: Context, url: String): Boolean {
        val normalized = normalize(url)
        if (normalized.toHttpUrlOrNull() == null) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, normalized).apply()
        override = normalized
        return true
    }

    // Retrofit 은 baseUrl 끝에 '/' 가 필요하다
    private fun normalize(url: String) = url.trim().trimEnd('/') + "/"
}
