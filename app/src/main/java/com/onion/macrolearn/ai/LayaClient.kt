package com.onion.macrolearn.ai

import retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.onion.macrolearn.util.RunLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/** type: "noul"(예/아니오 확률) 또는 "choice"(options 중 하나 선택) */
@Serializable
data class LayaQuestion(
    val type: String,
    val instructions: String,
    val options: List<String>? = null,
) {
    companion object {
        fun noul(instructions: String) = LayaQuestion("noul", instructions)
        fun choice(instructions: String, options: List<String>) = LayaQuestion("choice", instructions, options)
    }
}

@Serializable
data class LayaRequest(val state: String, val questions: Map<String, LayaQuestion>)

/** answers[질문ID][키] = 확률. noul 이면 키는 "noul", choice 면 옵션 문자열. */
@Serializable
data class LayaResponse(val answers: Map<String, Map<String, Double>> = emptyMap()) {
    /** 질문의 특정 키 확률 (없으면 0.0) */
    fun score(id: String, key: String = "noul"): Double = answers[id]?.get(key) ?: 0.0

    /** choice 질문에서 가장 확률이 높은 옵션과 확률 */
    fun best(id: String): Pair<String, Double>? =
        answers[id]?.maxByOrNull { it.value }?.let { it.key to it.value }
}

interface LayaApi {
    @POST("v1/systemone")
    suspend fun systemOne(@Body request: LayaRequest): LayaResponse
}

/** Hugging Face Space 에 호스팅된 Laya 서버 클라이언트. */
class LayaClient(
    private val api: LayaApi = defaultApi(LayaConfig.baseUrl()),
) {
    /** 원시 호출. 네트워크/서버 오류는 예외로 전달된다. */
    suspend fun decide(state: String, questions: Map<String, LayaQuestion>): LayaResponse =
        api.systemOne(LayaRequest(state.take(MAX_STATE_CHARS), questions))

    /** noul 질문 하나를 던지고 확률이 임계값 이상일 때만 true. 오류 시 false (안전 측). */
    suspend fun confirm(state: String, id: String, instructions: String): Boolean {
        val res = safeDecide(state, mapOf(id to LayaQuestion.noul(instructions))) ?: return false
        val p = res.score(id)
        RunLog.log("Laya [$id] noul=${"%.2f".format(p)}")
        return p >= CONFIDENCE_THRESHOLD
    }

    /** choice 질문. 최고 확률이 임계값 이상일 때만 옵션을 반환한다. */
    suspend fun choose(state: String, id: String, instructions: String, options: List<String>): String? {
        if (options.isEmpty()) return null
        val res = safeDecide(state, mapOf(id to LayaQuestion.choice(instructions, options))) ?: return null
        val best = res.best(id)
        RunLog.log("Laya [$id] 선택=${best?.first} (${best?.second?.let { "%.2f".format(it) }})")
        return best?.takeIf { it.second >= CONFIDENCE_THRESHOLD && it.first in options }?.first
    }

    /** 실패 원인을 로그로 남기고 null 반환 (매크로 진행은 막지 않는다) */
    private suspend fun safeDecide(state: String, questions: Map<String, LayaQuestion>): LayaResponse? =
        try {
            decide(state, questions)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            RunLog.log("Laya 호출 실패(${LayaConfig.baseUrl()}): ${e.javaClass.simpleName} ${e.message}")
            null
        }

    /** 연결 테스트: 성공하면 null, 실패하면 원인 문자열 */
    suspend fun ping(): String? = try {
        val r = decide("연결 테스트", mapOf("ping" to LayaQuestion.noul("이 문장은 테스트인가?")))
        if (r.answers.isEmpty()) "응답은 왔지만 answers 가 비어 있습니다" else null
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        "${e.javaClass.simpleName}: ${e.message}"
    }

    companion object {
        /** 이 확률 이상일 때만 Laya 판단을 실행에 반영한다 */
        const val CONFIDENCE_THRESHOLD = 0.8
        private const val MAX_STATE_CHARS = 4000

        private fun defaultApi(baseUrl: String): LayaApi {
            val http = OkHttpClient.Builder()
                // HF Space 는 슬립 상태에서 깨어나는 데 시간이 걸릴 수 있다
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            return Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(http)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(LayaApi::class.java)
        }
    }
}
