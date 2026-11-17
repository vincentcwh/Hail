package com.aistra.hail.ui.clone

import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aistra.hail.R
import com.aistra.hail.utils.HShizuku
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Manage packages installed in Honor/OEM clone users without touching owner user 0.
 *
 * All package state changes are explicitly scoped with --user USER_ID.
 * This screen intentionally supports only per-app disable/restore (option A).
 */
class CloneAppsActivity : AppCompatActivity() {
    private data class UserEntry(val id: Int, val label: String) {
        override fun toString() = "$label（User $id）"
    }

    private data class CloneApp(val packageName: String, val label: String, val disabled: Boolean)

    private lateinit var userSpinner: Spinner
    private lateinit var statusText: TextView
    private lateinit var appContainer: LinearLayout
    private lateinit var refreshButton: Button
    private var users: List<UserEntry> = emptyList()
    private var selectedUserId: Int = -1
    private var suppressSpinnerCallback = false
    private var loading = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) {
            refreshUsers()
        } else if (requestCode == REQUEST_SHIZUKU) {
            statusText.text = getString(R.string.clone_shizuku_denied)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.title_clone_apps)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        buildContent()
        refreshUsers()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        super.onDestroy()
    }

    private fun buildContent() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val description = TextView(this).apply {
            text = getString(R.string.clone_apps_description)
            textSize = 14f
        }
        root.addView(description, LinearLayout.LayoutParams(-1, -2))

        val selectorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        userSpinner = Spinner(this)
        selectorRow.addView(userSpinner, LinearLayout.LayoutParams(0, dp(52), 1f))
        refreshButton = Button(this).apply {
            text = getString(R.string.action_refresh)
            setOnClickListener { refreshUsers() }
        }
        selectorRow.addView(refreshButton, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(selectorRow, LinearLayout.LayoutParams(-1, -2))

        statusText = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        appContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(appContainer, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        userSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                if (suppressSpinnerCallback || position !in users.indices) return
                val userId = users[position].id
                if (selectedUserId != userId) {
                    selectedUserId = userId
                    refreshApps()
                }
            }
        }
    }

    private fun refreshUsers() {
        if (loading) return
        if (!Shizuku.pingBinder()) {
            statusText.text = getString(R.string.clone_shizuku_missing)
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            statusText.text = getString(R.string.clone_shizuku_permission)
            Shizuku.requestPermission(REQUEST_SHIZUKU)
            return
        }

        loading = true
        refreshButton.isEnabled = false
        statusText.text = getString(R.string.clone_loading_users)
        lifecycleScope.launch {
            var shouldLoadApps = false
            try {
                val output = shell("pm list users")
                val found = USER_PATTERN.findAll(output).mapNotNull {
                    val id = it.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                    // Never expose owner user 0 in this screen.
                    if (id <= 0) return@mapNotNull null
                    UserEntry(id, it.groupValues[2].trim().ifEmpty { "User" })
                }.toList()

                users = found
                if (found.isEmpty()) {
                    selectedUserId = -1
                    appContainer.removeAllViews()
                    statusText.text = getString(R.string.clone_no_users)
                    userSpinner.adapter = null
                } else {
                    suppressSpinnerCallback = true
                    userSpinner.adapter = ArrayAdapter(
                        this@CloneAppsActivity,
                        android.R.layout.simple_spinner_item,
                        found
                    ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    val selected = found.indexOfFirst { it.id == selectedUserId }.takeIf { it >= 0 } ?: 0
                    userSpinner.setSelection(selected, false)
                    selectedUserId = found[selected].id
                    suppressSpinnerCallback = false
                    shouldLoadApps = true
                }
            } catch (e: Exception) {
                statusText.text = getString(R.string.operation_failed, e.message ?: "Shizuku")
            } finally {
                loading = false
                refreshButton.isEnabled = true
            }
            if (shouldLoadApps) refreshApps()
        }
    }

    private fun refreshApps() {
        val userId = selectedUserId
        if (userId <= 0) {
            statusText.text = getString(R.string.clone_invalid_user)
            return
        }
        if (loading) return
        loading = true
        refreshButton.isEnabled = false
        statusText.text = getString(R.string.clone_loading_apps)
        lifecycleScope.launch {
            try {
                val installedOutput = shell("pm list packages --user $userId -3")
                val disabledOutput = shell("pm list packages --user $userId -d")
                val installed = parsePackages(installedOutput)
                val disabled = parsePackages(disabledOutput).toSet()
                val apps = installed.map { pkg ->
                    CloneApp(pkg, loadAppLabel(pkg), pkg in disabled)
                }.sortedBy { it.label.lowercase() }

                appContainer.removeAllViews()
                apps.forEach { app -> addAppRow(userId, app) }
                statusText.text = getString(R.string.clone_app_count, userId, apps.size)
                if (apps.isEmpty()) {
                    val empty = TextView(this@CloneAppsActivity).apply {
                        text = getString(R.string.clone_no_apps)
                        textSize = 14f
                        setPadding(dp(12), dp(12), dp(12), dp(12))
                    }
                    appContainer.addView(empty)
                }
            } catch (e: Exception) {
                statusText.text = getString(R.string.operation_failed, e.message ?: "Shizuku")
            } finally {
                loading = false
                refreshButton.isEnabled = true
            }
        }
    }

    private fun addAppRow(userId: Int, app: CloneApp) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
        }
        val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name = TextView(this).apply {
            text = app.label
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        val pkg = TextView(this).apply {
            text = app.packageName
            textSize = 11f
            alpha = 0.7f
        }
        labels.addView(name)
        labels.addView(pkg)
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))

        val action = Button(this).apply {
            text = getString(if (app.disabled) R.string.action_unfreeze else R.string.action_freeze)
            setOnClickListener { setPackageFrozen(userId, app.packageName, !app.disabled) }
        }
        row.addView(action, LinearLayout.LayoutParams(-2, dp(44)))
        appContainer.addView(row, LinearLayout.LayoutParams(-1, -2))
        appContainer.addView(View(this).apply { setBackgroundColor(0x22000000) },
            LinearLayout.LayoutParams(-1, dp(1)))
    }

    private fun setPackageFrozen(userId: Int, packageName: String, freeze: Boolean) {
        // Strict guardrails: never permit owner user 0, malformed package names, or Hail itself.
        if (userId <= 0 || userId != selectedUserId || !PACKAGE_PATTERN.matches(packageName)
            || packageName == packageNameForThisApp()
        ) {
            Toast.makeText(this, R.string.clone_operation_blocked, Toast.LENGTH_LONG).show()
            return
        }
        if (loading) return
        loading = true
        refreshButton.isEnabled = false
        lifecycleScope.launch {
            var succeeded = false
            try {
                val command = if (freeze) {
                    "am force-stop --user $userId $packageName && pm disable-user --user $userId $packageName"
                } else {
                    "pm default-state --user $userId $packageName"
                }
                shell(command)
                Toast.makeText(
                    this@CloneAppsActivity,
                    getString(if (freeze) R.string.clone_frozen else R.string.clone_unfrozen, packageName),
                    Toast.LENGTH_SHORT
                ).show()
                succeeded = true
            } catch (e: Exception) {
                Toast.makeText(
                    this@CloneAppsActivity,
                    getString(R.string.operation_failed, e.message ?: "Shizuku"),
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                loading = false
                refreshButton.isEnabled = true
            }
            if (succeeded) refreshApps()
        }
    }

    private suspend fun shell(command: String): String = withContext(Dispatchers.IO) {
        val (code, output) = HShizuku.execute(command)
        if (code != 0) throw IllegalStateException(output?.takeIf { it.isNotBlank() } ?: "exit=$code")
        output.orEmpty()
    }

    private fun parsePackages(output: String): List<String> =
        output.lineSequence().map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { PACKAGE_PATTERN.matches(it) }
            .distinct()
            .toList()

    private fun loadAppLabel(packageName: String): String = runCatching {
        packageManager.getApplicationInfo(packageName, PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .loadLabel(packageManager).toString()
    }.getOrDefault(packageName)

    private fun packageNameForThisApp() = applicationContext.packageName

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_SHIZUKU = 7281
        private val USER_PATTERN = Regex("""UserInfo\{(\d+):([^:}]+)""")
        private val PACKAGE_PATTERN = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+""")
    }
}
