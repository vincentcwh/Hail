package com.aistra.hail.ui.clone

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.aistra.hail.R
import com.aistra.hail.app.CloneUserManager
import com.aistra.hail.databinding.FragmentAppsBinding
import com.aistra.hail.ui.main.MainFragment
import com.aistra.hail.utils.HShizuku
import com.aistra.hail.utils.HUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** The original Hail app-selection page, backed only by the Honor clone user. */
class CloneAppsFragment : MainFragment() {
    private var _binding: FragmentAppsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: CloneAppsAdapter
    private var loading = false

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == REQUEST_SHIZUKU && grantResult == PackageManager.PERMISSION_GRANTED) loadCloneApps()
        else if (requestCode == REQUEST_SHIZUKU) HUI.showToast(R.string.clone_shizuku_denied)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppsBinding.inflate(inflater, container, false)
        adapter = CloneAppsAdapter()
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
                    val usersOutput = HShizuku.execute("pm list users").second.orEmpty()
                    val userRegex = Regex("""UserInfo\{(\d+):([^:}]+)""")
                    val userId = userRegex.findAll(usersOutput).mapNotNull { it.groupValues[1].toIntOrNull() }
                        .firstOrNull { it == 128 }
                        ?: userRegex.findAll(usersOutput).mapNotNull { it.groupValues[1].toIntOrNull() }
                            .firstOrNull { it > 0 }
                        ?: return@withContext -1 to emptyList<CloneAppItem>()
                    CloneUserManager.setUserId(userId)
                    val output = HShizuku.execute("pm list packages --user $userId -3")
                    if (output.first != 0) throw IllegalStateException(output.second ?: "Unable to list clone apps")
                    CloneUserManager.refreshDisabledPackages()
                    val pm = requireContext().packageManager
                    val items = output.second.orEmpty().lineSequence().map { it.trim() }
                        .filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }
                        .filter { PACKAGE_REGEX.matches(it) && it != requireContext().packageName }
                        .distinct().mapNotNull { pkg ->
                            runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()?.let(::CloneAppItem)
                        }.sortedBy { it.applicationInfo.loadLabel(pm).toString().lowercase() }.toList()
                    userId to items
                }
                adapter.submitList(result.second)
                if (result.first < 0) HUI.showToast(R.string.clone_no_users)
            } catch (e: Exception) {
                HUI.showToast(R.string.operation_failed, e.message ?: "Shizuku")
            } finally {
                loading = false
                if (_binding != null) binding.refresh.isRefreshing = false
            }
        }
    }

    companion object {
        private const val REQUEST_SHIZUKU = 8127
        private val PACKAGE_REGEX = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+""")
    }
}