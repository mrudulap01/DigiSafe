package com.digisafe.guardian.dashboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.digisafe.guardian.databinding.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * GuardianDashboardAdapter: High-performance multi-view RecyclerView adapter.
 * Uses DiffUtil for smooth real-time timeline updates.
 */
class GuardianDashboardAdapter : ListAdapter<DashboardEvent, RecyclerView.ViewHolder>(EventDiffCallback()) {

    override fun getItemViewType(position: Int): Int = getItem(position).type.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (EventType.values()[viewType]) {
            EventType.ALERT -> AlertViewHolder(ItemDashboardAlertBinding.inflate(inflater, parent, false))
            EventType.TRANSACTION -> TransactionViewHolder(ItemDashboardTransactionBinding.inflate(inflater, parent, false))
            EventType.APPROVAL -> ApprovalViewHolder(ItemDashboardApprovalBinding.inflate(inflater, parent, false))
            EventType.EVIDENCE -> EvidenceViewHolder(ItemDashboardEvidenceBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val event = getItem(position)
        when (holder) {
            is AlertViewHolder -> holder.bind(event as DashboardEvent.Alert)
            is TransactionViewHolder -> holder.bind(event as DashboardEvent.Transaction)
            is ApprovalViewHolder -> holder.bind(event as DashboardEvent.Approval)
            is EvidenceViewHolder -> holder.bind(event as DashboardEvent.Evidence)
        }
    }

    // ViewHolders
    class AlertViewHolder(private val binding: ItemDashboardAlertBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DashboardEvent.Alert) {
            binding.tvRiskLevel.text = item.riskLevel
            binding.tvRiskLevel.setTextColor(if (item.riskLevel == "HIGH") Color.RED else Color.YELLOW)
            binding.tvTime.text = formatTime(item.timestamp)
            binding.tvCaller.text = "Caller: ${item.callerNumber}"
        }
    }

    class TransactionViewHolder(private val binding: ItemDashboardTransactionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DashboardEvent.Transaction) {
            binding.tvAmount.text = "₹${item.amount}"
            binding.tvMerchant.text = item.merchant
            binding.tvStatus.text = item.status
            binding.tvTime.text = formatTime(item.timestamp)
        }
    }

    // Identical structures for Approval and Evidence ViewHolders...
    class ApprovalViewHolder(private val binding: ItemDashboardApprovalBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DashboardEvent.Approval) {
            binding.tvApprovalState.text = "State: ${item.state}"
            binding.tvTime.text = formatTime(item.timestamp)
        }
    }
    class EvidenceViewHolder(private val binding: ItemDashboardEvidenceBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DashboardEvent.Evidence) {
            binding.tvEvidenceId.text = "Vault ID: ${item.evidenceId}"
            binding.tvTime.text = formatTime(item.timestamp)
        }
    }

    class EventDiffCallback : DiffUtil.ItemCallback<DashboardEvent>() {
        override fun areItemsTheSame(oldItem: DashboardEvent, newItem: DashboardEvent) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: DashboardEvent, newItem: DashboardEvent) = oldItem == newItem
    }

    companion object {
        private fun formatTime(timestamp: Long): String {
            return SimpleDateFormat("HH:mm:ss, MMM dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
