package com.aistra.hail.app

import android.content.Intent
import androidx.preference.PreferenceManager
import com.aistra.hail.HailApp.Companion.app
import com.aistra.hail.utils.HShizuku
import rikka.shizuku.Shizuku

/**
 * User-scoped operations for Honor's app-clone profile.
 * All package state changes are explicitly scoped to a non-owner Android user.
 */
object CloneUserManager {
    private const val KEY_USER_ID = "hail_duo_clone_user_id"
    private val preferences get() = PreferenceManager.getDefaultSharedPreferences(app)
    private var cachedUserId = -1
    private var disabledPackages: Set<String> = emptySet()
    private var cacheUserId = -1

    val userId: Int
        get() = if (cachedUserId > 0) cachedUserId else preferences.getInt(KEY_USER_ID, -1)

    fun setUserId(value: Int): Boolean {
        if (value <= 0) return false
        cachedUserId = value
        preferences.edit().putInt(KEY_USER_ID, value).apply()
        cacheUserId = -1
        return true
    }

    fun resolveUserId(): Int {
        userId.takeIf { it > 0 }?.let { return it }
        if (!hasShizukuPermission()) return -1
        val output = HShizuku.execute("pm list users").second.orEmpty()
        val userRegex = Regex("""UserInfo\{(\d+):([^:}]+)""")
        val ids = userRegex.findAll(output).mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it > 0 }.toList()
        val found = ids.firstOrNull { it == 128 } ?: ids.firstOrNull() ?: return -1
        setUserId(found)
        return found
    }

    fun refreshDisabledPackages(): Boolean {
        if (!hasShizukuPermission()) return false
        val id = resolveUserId()
        if (id <= 0) return false
        val result = HShizuku.execute("pm list packages --user $id -d")
        if (result.first != 0) return false
        disabledPackages = result.second.orEmpty().lineSequence()
            .map { it.trim() }.filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }.toSet()
        cacheUserId = id
        return true
    }

    fun isAppFrozen(packageName: String): Boolean {
        if (!PACKAGE_REGEX.matches(packageName)) return false
        val id = resolveUserId()
        if (id <= 0) return false
        if (cacheUserId != id) refreshDisabledPackages()
        return packageName in disabledPackages
    }

    fun setAppFrozen(packageName: String, frozen: Boolean): Boolean {
        if (!PACKAGE_REGEX.matches(packageName)) return false
        val id = resolveUserId()
        if (id <= 0 || !hasShizukuPermission()) return false
        val command = if (frozen) {
            "am force-stop --user $id $packageName && pm disable-user --user $id $packageName"
        } else {
            "pm enable --user $id $packageName"
        }
        val result = HShizuku.execute(command)
        if (result.first != 0) return false
        cacheUserId = -1
        if (!refreshDisabledPackages()) return false
        return (packageName in disabledPackages) == frozen
    }

    fun launchApp(packageName: String): Boolean {
        if (!PACKAGE_REGEX.matches(packageName)) return false
        val id = resolveUserId()
        if (id <= 0 || !hasShizukuPermission()) return false
        val intent = app.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        val component = intent.component ?: intent.resolveActivity(app.packageManager) ?: return false
        val result = HShizuku.execute("am start --user $id -n ${component.flattenToString()}")
        return result.first == 0 && !result.second.orEmpty().contains("Error:")
    }

    private fun hasShizukuPermission(): Boolean =
        runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }
            .getOrDefault(false)

    private val PACKAGE_REGEX = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+""")
}