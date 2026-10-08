package com.onion.macrolearn.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.onion.macrolearn.service.MacroRecorderService

/** 접근성/오버레이 권한 상태 확인 및 설정 화면 이동 */
object Permissions {
    fun isAccessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(ctx, MacroRecorderService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun canDrawOverlays(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun openAccessibilitySettings(ctx: Context) =
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun openOverlaySettings(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
