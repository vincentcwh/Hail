package com.aistra.hail.ui.clone

import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.aistra.hail.databinding.ItemAppsBinding

data class CloneAppItem(val applicationInfo: ApplicationInfo, val userId: Int, val disabled: Boolean) {
    val packageName: String get() = applicationInfo.packageName
}
class CloneAppsAdapter(private val onToggle: (CloneAppItem, Boolean) -> Unit) :
    ListAdapter<CloneAppItem, CloneAppsAdapter.Holder>(object : DiffUtil.ItemCallback<CloneAppItem>() {
        override fun areItemsTheSame(a: CloneAppItem, b: CloneAppItem) = a.userId == b.userId && a.packageName == b.packageName
        override fun areContentsTheSame(a: CloneAppItem, b: CloneAppItem) = a.disabled == b.disabled
    }) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAppsBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
    inner class Holder(private val binding: ItemAppsBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {
        private var bindingItem = false
        private var item: CloneAppItem? = null
        init {
            binding.appStar.setOnCheckedChangeListener { _, checked ->
                val current = item
                if (!bindingItem && current != null && checked != current.disabled) {
                    binding.appStar.isEnabled = false
                    onToggle(current, checked)
                }
            }
        }
        fun bind(value: CloneAppItem) {
            item = value
            bindingItem = true
            binding.appIcon.setImageDrawable(value.applicationInfo.loadIcon(binding.root.context.packageManager))
            binding.appName.text = value.applicationInfo.loadLabel(binding.root.context.packageManager)
            binding.appDesc.text = value.packageName
            binding.appStar.isChecked = value.disabled
            binding.appStar.isEnabled = true
            bindingItem = false
        }
    }
}