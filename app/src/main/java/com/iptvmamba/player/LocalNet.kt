package com.iptvmamba.player

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Android 16/17 blocks incoming connections from the local network unless the app holds
 * ACCESS_LOCAL_NETWORK (enforced for apps targeting API 37). Without it the QR server starts but
 * the phone can never connect. The permission is requested once at startup and again from the
 * phone card when the user explicitly wants to use it.
 */
object LocalNet {
    const val PERM = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val PREFS = "ipvio_localnet"

    fun needed(): Boolean = Build.VERSION.SDK_INT >= 36

    fun granted(ctx: Context): Boolean =
        !needed() || ContextCompat.checkSelfPermission(ctx, PERM) == PackageManager.PERMISSION_GRANTED

    fun asked(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("asked", false)

    fun markAsked(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()
    }
}
