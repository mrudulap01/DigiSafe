package com.digisafe.guardian.dashboard

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.digisafe.guardian.databinding.ActivityGuardianDashboardBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * GuardianDashboardActivity: The primary monitoring interface for Guardians.
 * Displays a real-time timeline of alerts, transactions, and evidence.
 */
class GuardianDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianDashboardBinding
    private lateinit var viewModel: GuardianDashboardViewModel
    private lateinit var adapter: GuardianDashboardAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupViewModel()
        setupListeners()
        observeState()
    }

    private fun setupRecyclerView() {
        adapter = GuardianDashboardAdapter()
        binding.rvTimeline.apply {
            layoutManager = LinearLayoutManager(this@GuardianDashboardActivity)
            adapter = this@GuardianDashboardActivity.adapter
            setHasFixedSize(true) // Performance optimization for fixed-width items
        }
    }

    private fun setupViewModel() {
        // PRODUCTION NOTE: In a real app, use a ViewModelFactory to inject the repository
        // and retrieving the current userId from secure storage.
        val userId = "demo_user_id" 
        val repository = GuardianRepository(userId)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return GuardianDashboardViewModel(repository) as T
            }
        }
        viewModel = ViewModelProvider(this, factory)[GuardianDashboardViewModel::class.java]
    }

    private fun setupListeners() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.refresh()
            binding.swipeRefresh.isRefreshing = false
        }
    }

    /**
     * OBSERVE STATE: Reactive UI updates based on StateFlow.
     * Automatically handles Loading, Success, Empty, and Error states.
     */
    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is DashboardUiState.Loading -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvEmptyState.visibility = View.GONE
                    }
                    is DashboardUiState.Success -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvEmptyState.visibility = View.GONE
                        adapter.submitList(state.events)
                    }
                    is DashboardUiState.Empty -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvEmptyState.visibility = View.VISIBLE
                        adapter.submitList(emptyList())
                    }
                    is DashboardUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                        // In production, show a Snackbar or Error View
                    }
                }
            }
        }
    }
}
