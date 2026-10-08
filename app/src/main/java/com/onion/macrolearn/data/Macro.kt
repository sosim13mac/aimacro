package com.onion.macrolearn.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 저장된 매크로. steps 는 [Converters] 를 통해 JSON 문자열로 저장된다. */
@Entity(tableName = "macros")
data class Macro(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val steps: List<MacroStep>,
    val createdAt: Long = System.currentTimeMillis(),
    /** 매일 09:00 자동 실행 예약 여부 */
    val scheduled: Boolean = false,
    /** 실행 시 먼저 띄울 대상 앱 패키지 (녹화 시 자동 캡처, null 이면 현재 화면에서 시작) */
    val targetPackage: String? = null,
)

class Converters {
    @TypeConverter
    fun fromSteps(steps: List<MacroStep>): String = json.encodeToString(steps)

    @TypeConverter
    fun toSteps(raw: String): List<MacroStep> = json.decodeFromString(raw)

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
