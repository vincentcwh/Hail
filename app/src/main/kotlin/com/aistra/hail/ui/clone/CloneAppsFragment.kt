package com.aistra.hail.ui.clone

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.aistra.hail.R
import com.aistra.hail.databinding.FragmentAppsBinding
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.HShizuku
import com.aistra.hail.utils.HUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** Original Hail Apps layout, populated only with packages from the OEM clone user. */
class CloneAppsFragment : MainFragment() {
    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CloneAppsAdapter
    private var targetUserId = -1
    private var loading = false
    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) loadCloneApps()
        else if (requestCode == REQUEST_SHIZUKU) HUI.showToast(R.string.clone_shizuku_denied)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        adapter = CloneAppsAdapter(::setCloneDisabled)
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(activity, resources.getInteger(R.integer.apps_span))
            adapter = this@CloneAppsFragment.adapter
            activity.appbar.setLiftOnScrollTargetView(this)
        }
        binding.refresh.setOnRefreshListener { loadCloneApps() }
        Shizuku.addRequestPermissionResultListener(permissionListener)
        loadCloneApps()
        return binding.root
    }

    override fun onDestroyView() {
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        _binding = null
        super.onDestroyView()
    }

    private fun loadCloneApps() {
        if (loading || _binding == null) return
        if (!Shizuku.pingBinder()) {
            HUI.showToast(R.string.clone_shizuku_missing)
            binding.refresh.isRefreshing = false
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(REQUEST_SHIZUKU)
            return
        }
        loading = true
        binding.refresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val usersOutput = shell("pm list users").second.orEmpty()
                    val userRegex = Regex("""UserInfo\{(\d+):([^:}]+)""")
                    val users = userRegex.findAll(usersOutput).mapNotNull { it.groupValues[1].toIntOrNull() }.filter { it > 0 }.toList()
                    val userId = users.firstOrNull { it == 128 } ?: users.firstOrNull()
                        ?: return@withContext -1 to emptyList<CloneAppItem>()
                    val installed = parsePackages(shell("pm list packages --user $userId -3").second.orEmpty())
                    val disabled = parsePackages(shell("pm list packages --user $userId -d").second.orEmpty()).toSet()
                    val pm = requireContext().packageManager
                    val items = installed.mapNotNull { pkg ->
                        if (!PACKAGE_REGEX.matches(pkg) || pkg == requireContext().packageName) return@mapNotNull null
                        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
                        CloneAppItem(info, userId, pkg in disabled)
                    }.sortedBy { it.applicationInfo.loadLabel(pm).toString().lowercase() }
                    userId to items
                }
                targetUserId = result.first
                adapter.submitList(result.second)
                if (targetUserId < 0) HUI.showToast(R.string.clone_no_users)
            } catch (e: Exception) {
                HUI.showToast(R.string.operation_failed, e.message ?: "Shizuku")
            } finally {
                loading = false
                if (_binding != null) binding.refresh.isRefreshing = false
            }
        }
    }

    private fun setCloneDisabled(item: CloneAppItem, disabled: Boolean) {
        if (item.userId <= 0 || item.userId != targetUserId || !PACKAGE_REGEX.matches(item.packageName)) {
            HUI.showToast(R.string.clone_operation_blocked)
            loadCloneApps()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val command = if (disabled) {
                "am force-stop --user ${item.userId} ${item.packageName} && pm disable-user --user ${item.userId} ${item.packageName}"
            } else {
                "pm default-state --user ${item.userId} ${item.packageName}"
            }
            val result = withContext(Dispatchers.IO) { shell(command) }
            if (result.first == 0) HUI.showToast(
                if (disabled) R.string.clone_frozen else R.string.clone_unfrozen,
                item.applicationInfo.loadLabel(requireContext().packageManager).toString()
            ) else HUI.showToast(R.string.operation_failed, result.second ?: "pm")
            loadCloneApps()
        }
    }

    private suspend fun shell(command: String): Pair<Int, String?> = HShizuku.execute(command)
    private fun parsePackages(output: String): List<String> = output.lineSequence().map { it.trim() }
        .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toList()

    companion object {
        private const val REQUEST_SHIZUKU = 8127
        private val PACKAGE_REGEX = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+""")
    }
}