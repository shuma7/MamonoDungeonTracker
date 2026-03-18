package com.mamotra.tracker.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.mamotra.tracker.data.AppDatabase
import com.mamotra.tracker.databinding.ActivityStatsBinding
import kotlinx.coroutines.launch

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private lateinit var adapter: BattleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        adapter = BattleAdapter(emptyList())
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        loadStats()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val db = AppDatabase.getInstance(this@StatsActivity)
            val records = db.battleDao().getAllRecords()
            val total = records.size
            val wins = records.count { it.result == "WIN" }
            val losses = total - wins
            val winRate = if (total > 0) wins * 100 / total else 0

            binding.tvWinRate.text = "${winRate}%"
            binding.tvTotal.text = "総対戦数: ${total}戦"
            binding.tvWins.text = "WIN: ${wins}"
            binding.tvLosses.text = "LOSE: ${losses}"

            adapter.updateData(records)
        }
    }
}
