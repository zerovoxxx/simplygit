package com.example.simplygit.ui.policy

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.simplygit.domain.model.SyncPolicyModel
import com.example.simplygit.domain.repository.SyncPolicyRepository
import com.example.simplygit.domain.usecase.UpdateSyncPolicyUseCase
import com.example.simplygit.notification.NotificationPermissionHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface SyncPolicyIntent {
    data class ChangeInterval(val minutes: Int) : SyncPolicyIntent
    data class ChangeRequireUnmetered(val v: Boolean) : SyncPolicyIntent
    data class ChangeRequireCharging(val v: Boolean) : SyncPolicyIntent
    data class ChangeTemplate(val v: String) : SyncPolicyIntent
    data object Save : SyncPolicyIntent
    data object RefreshNotificationPermission : SyncPolicyIntent
}

@HiltViewModel
class SyncPolicyViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val policyRepo: SyncPolicyRepository,
    private val updatePolicy: UpdateSyncPolicyUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(SyncPolicyUiState())
    val state: StateFlow<SyncPolicyUiState> = _state.asStateFlow()

    init {
        policyRepo.observe().onEach { policy ->
            _state.value = _state.value.copy(
                intervalMinutes = policy.intervalMinutes,
                requireUnmetered = policy.requireUnmetered,
                requireCharging = policy.requireCharging,
                commitMessageTemplate = policy.commitMessageTemplate,
                notificationGranted = NotificationPermissionHelper.isGranted(appContext),
            )
        }.launchIn(viewModelScope)
    }

    fun onIntent(intent: SyncPolicyIntent) {
        when (intent) {
            is SyncPolicyIntent.ChangeInterval ->
                _state.value = _state.value.copy(intervalMinutes = intent.minutes)
            is SyncPolicyIntent.ChangeRequireUnmetered ->
                _state.value = _state.value.copy(requireUnmetered = intent.v)
            is SyncPolicyIntent.ChangeRequireCharging ->
                _state.value = _state.value.copy(requireCharging = intent.v)
            is SyncPolicyIntent.ChangeTemplate ->
                _state.value = _state.value.copy(commitMessageTemplate = intent.v)
            SyncPolicyIntent.Save -> save()
            SyncPolicyIntent.RefreshNotificationPermission ->
                _state.value = _state.value.copy(
                    notificationGranted = NotificationPermissionHelper.isGranted(appContext),
                )
        }
    }

    private fun save() {
        val snapshot = _state.value
        if (snapshot.intervalMinutes !in SyncPolicyModel.VALID_INTERVALS) return
        viewModelScope.launch {
            _state.value = snapshot.copy(saving = true)
            runCatching { updatePolicy(snapshot.toModel()) }
            _state.value = _state.value.copy(saving = false, savedTick = _state.value.savedTick + 1)
        }
    }
}
