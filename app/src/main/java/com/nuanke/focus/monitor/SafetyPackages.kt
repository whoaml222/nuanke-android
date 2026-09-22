package com.nuanke.focus.monitor

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager

/** Always leave system settings, Home, and the default phone reachable. */
fun safetyPackages(context: Context): Set<String> = buildSet {
    add(context.packageName)
    add("android")
    add("com.android.settings")
    add("com.android.systemui")
    add("com.android.phone")
    add("com.android.emergency")
    runCatching {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName?.let(::add)
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(::add)
    }
}
