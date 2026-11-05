package com.aistra.hail.ui.home

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
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
    private val slots = mutableListOf<AppInfo?>()
    val currentList: List<AppInfo> get() = slots.filterNotNull()
    val slotCount: Int get() = slots.size
    var dragEnabled: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }
    var onStartDragListener: ((ViewHolder) -> Unit)? = null
    private var loadIconJob: Job? = null
    lateinit var onItemClickListener: OnItemClickListener
    lateinit var onItemLongClickListener: OnItemLongClickListener

    init { submitSlots(selectedList) }

    override fun getItemCount(): Int = slots.size

    fun submitSlots(list: List<AppInfo?>) {
        slots.clear()
        slots.addAll(list)
        notifyDataSetChanged()
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in slots.indices || toPosition !in slots.indices) return
        val moving = slots[fromPosition] ?: return
        val target = slots[toPosition]
        slots[toPosition] = moving
        slots[fromPosition] = target
        notifyItemChanged(fromPosition)
        notifyItemChanged(toPosition)
    }

    fun getGridPositions(): Map<String, Int> = buildMap {
        slots.forEachIndexed { index, info -> if (info != null) put(info.packageName, index) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder = ViewHolder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_home, parent, false)
    )

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = slots[position]
        holder.itemView.run {
            if (info == null) {
                alpha = if (dragEnabled) 0.22f else 0f
                isClickable = false
                setOnClickListener(null)
                setOnLongClickListener(null)
                findViewById<ImageView>(R.id.app_icon).visibility = View.INVISIBLE
                findViewById<TextView>(R.id.app_name).visibility = View.INVISIBLE
                return
            }
            alpha = 1f
            isClickable = true
            setOnClickListener { if (!dragEnabled) onItemClickListener.onItemClick(info) }
            setOnLongClickListener {
                if (dragEnabled) {
                    onStartDragListener?.invoke(holder)
                    true
                } else onItemLongClickListener.onItemLongClick(info)
            }
            findViewById<ImageView>(R.id.app_icon).run {
                isVisible = true
                info.applicationInfo?.let {
                    loadIconJob = AppIconCache.loadIconBitmapAsync(
                        context, it, myUserId, this,
                        HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN
                    )
                } ?: run {
                    setImageDrawable(context.packageManager.defaultActivityIcon)
                    colorFilter = null
                }
            }
            findViewById<TextView>(R.id.app_name).run {
                isVisible = true
                text = buildString {
                    if (!HailData.grayscaleIcon && info.state == AppInfo.State.FROZEN) append("\u2744\uFE0F")
                    if (info.whitelisted) append("\uD83D\uDD12")
                    append(info.name)
                }
                isEnabled = !HailData.grayscaleIcon || info.state != AppInfo.State.FROZEN
                when {
                    info in selectedList -> setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary))
                    info.state == AppInfo.State.NOT_FOUND -> setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError))
                    else -> setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, HailData.homeFontSize)
            }
        }
    }

    fun onDestroy() {
        if (loadIconJob?.isActive == true) loadIconJob?.cancel()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
    interface OnItemClickListener { fun onItemClick(info: AppInfo) }
    interface OnItemLongClickListener { fun onItemLongClick(info: AppInfo): Boolean }
}

private fun AppInfo.getFlag(selectedList: List<AppInfo>) =
    (1 shl state.ordinal) or (this in selectedList).shl(3) or whitelisted.shl(4)

private fun Boolean.shl(bitCount: Int) = if (this) 1 shl bitCount else 0
