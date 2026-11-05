package com.aistra.hail.ui.home

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.aistra.hail.R
import com.aistra.hail.app.AppInfo
import com.aistra.hail.app.HailData
import com.aistra.hail.utils.AppIconCache
import com.aistra.hail.utils.HPackages.myUserId
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Job

class PagerAdapter(
    private val selectedList: List<AppInfo>,
    private val flags: MutableMap<String, Int> = mutableMapOf()
) : RecyclerView.Adapter<PagerAdapter.ViewHolder>() {
    private val items = mutableListOf<AppInfo>()
    val currentList: List<AppInfo> get() = items
    var dragEnabled: Boolean = false
    var onStartDragListener: ((ViewHolder) -> Unit)? = null
    private var loadIconJob: Job? = null
    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemLongClickListener: OnItemLongClickListener

    init {
        submitList(selectedList)
    }

    override fun getItemCount(): Int = items.size

    fun submitList(list: List<AppInfo>) {
        val oldItems = items.toList()
        val newItems = list.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) =
                oldItems[oldItemPosition] == newItems[newItemPosition]
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val oldItem = oldItems[oldItemPosition]
                val newItem = newItems[newItemPosition]
                return flags[oldItem.packageName] == newItem.getFlag(selectedList)
            }
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in items.indices || toPosition !in items.indices) return
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_home, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = items[position]
        flags[info.packageName] = info.getFlag(selectedList)
        holder.itemView.run {
            setOnClickListener { onItemClickListener.onItemClick(info) }
            setOnLongClickListener { onItemLongClickListener.onItemLongClick(info) }
            findViewById<ImageView>(R.id.app_icon).run {
                info.applicationInfo?.let {
                    loadIconJob = AppIconCache.loadIconBitmapAsync(
                        context,
                        it,
                        myUserId,
                        this,
                        HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                    )
                } ?: run {
                    setImageDrawable(context.packageManager.defaultActivityIcon)
                    colorFilter = null
                }
            }
            findViewById<TextView>(R.id.app_name).run {
                text = buildString {
                    if (!HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN) append("\u2744\uFE0F")
                    if (info.whitelisted) append("\uD83D\uDD12")
                    append(info.name)
                }
                isEnabled = !HailData.grayscaleIcon || info.state != AppInfo.State.FROZEN
                when {
                    info in selectedList -> setTextColor(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
                    )
                    info.state == AppInfo.State.NOT_FOUND -> setTextColor(
                        MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
                    )
                    else -> setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HailData.homeFontSize)
            }
            findViewById<TextView>(R.id.drag_handle).run {
                isVisible = dragEnabled && info.state == AppInfo.State.FROZEN
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) onStartDragListener?.invoke(holder)
                    true
                }
            }
        }
    }

    fun onDestroy() {
        if (loadIconJob?.isActive == true) loadIconJob?.cancel()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)

    interface OnItemClickListener {
        fun onItemClick(info: AppInfo)
    }

    interface OnItemLongClickListener {
        fun onItemLongClick(info: AppInfo): Boolean
    }
}

private fun AppInfo.getFlag(selectedList: List<AppInfo>) =
    (1 shl state.ordinal) or (this in selectedList).shl(3) or whitelisted.shl(4)

private fun Boolean.shl(bitCount: Int) = if (this) 1 shl bitCount else 0
