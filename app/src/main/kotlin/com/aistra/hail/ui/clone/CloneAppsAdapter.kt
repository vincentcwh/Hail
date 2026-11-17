package com.aistra.hail.ui.clone

import android.content.pm.ApplicationInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import com.aistra.hail.app.CloneUserManager
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ItemAppsBinding

data class CloneAppItem(val applicationInfo: ApplicationInfo) {
    val packageName: String get() = applicationInfo.packageName
}

class CloneAppsAdapter : ListAdapter<CloneAppItem, CloneAppsAdapter.Holder>(
    object : DiffUtil.ItemCallback<CloneAppItem>() {
        override fun areItemsTheSame(old: CloneAppItem, new: CloneAppItem) = old.packageName == new.packageName
        override fun areContentsTheSame(old: CloneAppItem, new: CloneAppItem) =
            HailData.isChecked(old.packageName) == HailData.isChecked(new.packageName)
    }
) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(ItemAppsBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemAppsBinding) :
        androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {
        private var bindingItem = false
        private lateinit var item: CloneAppItem

        init {
            binding.appStar.setOnCheckedChangeListener { _, checked ->
                if (bindingItem || !::item.isInitialized) return@setOnCheckedChangeListener
                if (checked && !HailData.isChecked(item.packageName)) {
                    HailData.addCheckedApp(item.packageName)
                } else if (!checked && HailData.isChecked(item.packageName)) {
                    HailData.removeCheckedApp(item.packageName)
                }
            }
        }

        fun bind(value: CloneAppItem) {
            item = value
            bindingItem = true
            binding.appIcon.setImageDrawable(value.applicationInfo.loadIcon(binding.root.context.packageManager))
            binding.appName.text = value.applicationInfo.loadLabel(binding.root.context.packageManager)
            binding.appDesc.text = value.packageName
            binding.appStar.isChecked = HailData.isChecked(value.packageName)
            binding.appStar.isEnabled = true
            val frozen = CloneUserManager.isAppFrozen(value.packageName)
            binding.appName.isEnabled = !HailData.grayscaleIcon || !frozen
            binding.appDesc.isEnabled = !HailData.grayscaleIcon || !frozen
            bindingItem = false
        }
    }
}