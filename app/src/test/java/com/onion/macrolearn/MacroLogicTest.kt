package com.onion.macrolearn

import com.onion.macrolearn.ai.LayaApi
import com.onion.macrolearn.ai.LayaClient
import com.onion.macrolearn.ai.LayaGeneralizer
import com.onion.macrolearn.ai.LayaRequest
import com.onion.macrolearn.ai.LayaResponse
import com.onion.macrolearn.data.Converters
import com.onion.macrolearn.data.MacroStep
import com.onion.macrolearn.worker.MacroScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MacroLogicTest {

    /** 고정 확률을 돌려주는 가짜 Laya 서버 */
    private class FakeApi(private val noul: Double) : LayaApi {
        override suspend fun systemOne(request: LayaRequest) =
            LayaResponse(request.questions.mapValues { mapOf("noul" to noul) })
    }

    private fun generalizer(noul: Double) = LayaGeneralizer(LayaClient(FakeApi(noul)))

    @Test
    fun `동적 체크한 숫자 스텝은 dynamic today 로 변환된다`() = runTest {
        val steps = listOf(MacroStep(findBy = "text", value = "8", text = "8", viewId = "a:id/day"))
        val result = generalizer(0.0).generalize(steps, setOf(0))
        assertEquals(MacroStep.FIND_DYNAMIC_TODAY, result[0].findBy)
        assertTrue(result[0].isDynamic)
    }

    @Test
    fun `날짜가 아니면 Laya 확률이 낮을 때 정적으로 유지된다`() = runTest {
        val steps = listOf(MacroStep(findBy = "text", value = "출석", text = "출석"))
        assertFalse(generalizer(0.3).generalize(steps, setOf(0))[0].isDynamic)
        assertTrue(generalizer(0.95).generalize(steps, setOf(0))[0].isDynamic)
    }

    @Test
    fun `숫자 포함 텍스트는 viewId 를 우선한다`() = runTest {
        val steps = listOf(MacroStep(findBy = "text", value = "포인트 120", text = "포인트 120", viewId = "a:id/pt"))
        val r = generalizer(0.0).generalize(steps, emptySet())[0]
        assertEquals(MacroStep.FIND_ID, r.findBy)
        assertEquals("a:id/pt", r.value)
    }

    @Test
    fun `스텝 JSON 변환은 왕복 가능하다`() {
        val steps = listOf(MacroStep(findBy = "text", value = "전체메뉴", text = "전체메뉴", fallback = "메뉴"))
        val c = Converters()
        assertEquals(steps, c.toSteps(c.fromSteps(steps)))
    }

    @Test
    fun `예약 지연은 다음 09시까지 계산된다`() {
        val before = LocalDateTime.of(2026, 1, 1, 8, 0)
        val after = LocalDateTime.of(2026, 1, 1, 10, 0)
        assertEquals(60 * 60 * 1000L, MacroScheduler.delayUntilNext(9, before))
        assertEquals(23 * 60 * 60 * 1000L, MacroScheduler.delayUntilNext(9, after))
    }
}
