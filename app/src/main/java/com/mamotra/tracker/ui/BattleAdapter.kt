package com.mamotra.tracker.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mamotra.tracker.data.BattleRecord
import com.mamotra.tracker.databinding.ItemBattleBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BattleAdapter(private var records: List<BattleRecord>) :
    RecyclerView.Adapter<BattleAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)

    class ViewHolder(val binding: ItemBattleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBattleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = records[position]
        with(holder.binding) {
            tvResult.text = record.result
            tvResult.setTextColor(
                if (record.result == "WIN")
                    root.context.getColor(android.R.color.holo_green_dark)
                else
                    root.context.getColor(android.R.color.holo_red_dark)
            )
            tvOpponent.text = "${record.opponentName}  (${record.opponentRating})"
            tvMyRating.text = "自分: ${record.myRating}"
            val trophySign = if (record.trophyChange >= 0) "+" else ""
            tvTrophyChange.text = "🏆 ${trophySign}${record.trophyChange}"
            tvDate.text = dateFormat.format(Date(record.timestamp))
        }
    }

    override fun getItemCount() = records.size

    fun updateData(newRecords: List<BattleRecord>) {
        records = newRecords
        notifyDataSetChanged()
    }
}
