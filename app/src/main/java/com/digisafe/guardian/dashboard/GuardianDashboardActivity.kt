package com.digisafe.guardian.dashboard

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.digisafe.guardian.approvals.ApprovalManager
import com.digisafe.guardian.databinding.ActivityGuardianDashboardBinding
import com.digisafe.guardian.setup.GuardianSetupActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * GuardianDashboardActivity: The primary monitoring interface for Guardians.
 * Displays a real-time timeline of alerts, transactions, and evidence.
 * Now manages Approval cycles for high-risk events.
 */
class GuardianDashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuardianDashboardBinding
    private lateinit var viewModel: GuardianDashboardViewModel
    private lateinit var adapter: GuardianDashboardAdapter
    private lateinit var encryptedPrefs: EncryptedSharedPreferences
    
    // Production-grade approval management
    private val approvalManager = ApprovalManager()
    private val monitoredEvents = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuardianDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        initSecurity()
        setupRecyclerView()
        setupViewModel()
        setupListeners()
        observeState()
    }

    private fun initSecurity() {
        try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            encryptedPrefs = EncryptedSharedPreferences.create(
                this,
                "secure_guardian_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ) as EncryptedSharedPreferences
        } catch (e: Exception) {
            Log.e("DashboardDebug", "Security initialization failed", e)
        }
    }

    private fun setupRecyclerView() {
        adapter = GuardianDashboardAdapter()
        binding.rvTimeline.apply {
            layoutManager = LinearLayoutManager(this@GuardianDashboardActivity)
            adapter = this@GuardianDashboardActivity.adapter
            setHasFixedSize(true)
        }
    }

    private fun setupViewModel() {
        // Retrieve userId from secure storage for repository
        val userId = encryptedPrefs.getString("internal_user_id", "demo_user_id") ?: "demo_user_id"
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

        binding.btnResetRegistration.setOnClickListener {
            Log.d("DashboardDebug", "Resetting registration...")
            encryptedPrefs.edit().clear().apply()
            
            val intent = Intent(this, GuardianSetupActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }
    }

    private fun observeState() {
        val userId = encryptedPrefs.getString("internal_user_id", "demo_user_id") ?: "demo_user_id"
        
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
                        
                        Log.d("DASHBOARD_DEBUG", "Events received: ${state.events.size}")

                        // INTEGRATION: Monitor newly discovered approvals
                        state.events.forEach { event ->
                            
                            // Debug logging for every event
                            if (event is DashboardEvent.Approval) {
                                Log.d("DASHBOARD_DEBUG", "Event: ${event.id} TYPE: APPROVAL STATE: ${event.state}")
                            }

                            if (event is DashboardEvent.Approval && 
                                event.state == "AWAITING_GUARDIAN" && 
                                !monitoredEvents.contains(event.id)) {
                                
                                Log.d("DASHBOARD_DEBUG", "Starting monitoring for ${event.id}")
                                monitoredEvents.add(event.id)
                                
                                approvalManager.startApprovalCycle(userId, event.id) { terminalState ->
                                    Log.d("DASHBOARD_DEBUG", "Terminal callback for ${event.id}: $terminalState")

                                    runOnUiThread {
                                        Toast.makeText(
                                            this@GuardianDashboardActivity,
                                            "Event ${event.id}: $terminalState",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                    is DashboardUiState.Empty -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvEmptyState.visibility = View.VISIBLE
                        adapter.submitList(emptyList())
                    }
                    is DashboardUiState.Error -> {
                        binding.progressBar.visibility = View.GONE
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Prevent memory leaks and detach all active approval listeners
        approvalManager.cleanup()
    }
}
