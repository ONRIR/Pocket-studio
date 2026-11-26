package com.vscodroid

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog

/** Launcher icons of this fork: activity-alias name and the label shown in the picker. */
internal val ICON_CHOICES = listOf(
    "IconBlack" to "Black (default)",
    "IconWhite" to "White",
    "IconBlue" to "Blue glow",
    "IconGreen" to "Green-blue",
    "IconPurple" to "Purple",
    "IconCharcoal" to "Charcoal",
    "IconGlossy" to "Glossy (transparent)",
)

/**
 * Switches the launcher icon by enabling one activity-alias and disabling the rest.
 * DONT_KILL_APP keeps this app running. The launcher decides when it redraws: most do it
 * within a few seconds, some briefly reload the home screen.
 */
internal fun MainActivity.applyLauncherIcon(alias: String) {
    val pm = packageManager
    val target = ComponentName(packageName, "com.vscodroid.$alias")
    pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    for ((name, _) in ICON_CHOICES) {
        if (name == alias) continue
        pm.setComponentEnabledSetting(
            ComponentName(packageName, "com.vscodroid.$name"),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}

private fun MainActivity.currentIconIndex(): Int {
    val pm = packageManager
    for ((i, c) in ICON_CHOICES.withIndex()) {
        val state = pm.getComponentEnabledSetting(ComponentName(packageName, "com.vscodroid.${c.first}"))
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i
    }
    return 0
}

internal fun MainActivity.showIconPickerDialog() {
    if (isFinishing || isDestroyed) return
    var chosen = currentIconIndex()
    showWithReachableButtons(
        AlertDialog.Builder(this)
            .setTitle("App icon")
            .setSingleChoiceItems(ICON_CHOICES.map { it.second }.toTypedArray(), chosen) { _, which -> chosen = which }
            .setPositiveButton("Apply") { _, _ ->
                try {
                    applyLauncherIcon(ICON_CHOICES[chosen].first)
                } catch (_: Exception) {
                }
            }
            .setNegativeButton("Cancel", null)
    )
}
