package com.hwcamtrack.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hwcamtrack.R
import com.hwcamtrack.data.CamEndpoint
import com.hwcamtrack.databinding.ItemEndpointBinding

/** 发现结果列表。点一行就是"选中这个端点并连接"。 */
class EndpointAdapter(
    private val onClick: (CamEndpoint) -> Unit
) : RecyclerView.Adapter<EndpointAdapter.Holder>() {

    private val items = ArrayList<CamEndpoint>()

    /** 当前选中的端点 key，用来高亮。 */
    var selectedKey: String? = null

    fun submit(list: List<CamEndpoint>) {
        items.clear()
        items.addAll(list)
        items.sortByDescending { it.confidence }
        notifyDataSetChanged()
    }

    fun add(endpoint: CamEndpoint) {
        if (items.any { it.key == endpoint.key }) return
        items.add(endpoint)
        items.sortByDescending { it.confidence }
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemEndpointBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val endpoint = items[position]
        holder.bind(endpoint, endpoint.key == selectedKey, onClick)
    }

    class Holder(private val binding: ItemEndpointBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(endpoint: CamEndpoint, selected: Boolean, onClick: (CamEndpoint) -> Unit) {
            binding.tvUri.text = endpoint.displayUri

            val details = buildList {
                add(endpoint.protocol.label)
                add(endpoint.videoSummary)
                if (endpoint.vendor.isNotEmpty()) add(endpoint.vendor)
            }
            binding.tvDetail.text = details.joinToString(" · ")

            binding.tvScore.text = endpoint.confidence.toString()

            binding.root.setBackgroundResource(
                if (selected) R.color.surface_high else android.R.color.transparent
            )

            binding.root.setOnClickListener { onClick(endpoint) }
        }
    }
}
