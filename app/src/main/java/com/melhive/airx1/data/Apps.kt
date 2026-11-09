package com.melhive.airx1.data

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** Launchable apps, for the Quick Launcher and its picker. */
object Apps {
    class App(val pkg: String, val label: String, val icon: Drawable)

    /** Common apps tried first when the user hasn't picked favorites. */
    private val DEFAULTS = listOf(
        "com.google.android.youtube", "com.whatsapp", "com.android.chrome", "com.zhiliaoapp.musically",
        "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.google.android.apps.maps",
        "com.spotify.music", "com.google.android.gm", "com.google.android.dialer", "com.android.settings",
    )

    fun launchable(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .asSequence()
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { App(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Up to [max] apps: the user's picks, or common apps that are installed, then alphabetical. */
    fun favorites(context: Context, chosen: List<String>, max: Int = 8): List<App> {
        val all = launchable(context)
        val byPkg = all.associateBy { it.pkg }
        if (chosen.isNotEmpty()) return chosen.mapNotNull { byPkg[it] }.take(max)
        val picked = DEFAULTS.mapNotNull { byPkg[it] }.toMutableList()
        for (a in all) {
            if (picked.size >= max) break
            if (a !in picked) picked += a
        }
        return picked.take(max)
    }

    fun launch(context: Context, pkg: String): Boolean {
        val i = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        return true
    }
}
