package com.homelab.app.ui.dockge

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homelab.app.data.repository.DockgeAction
import com.homelab.app.data.repository.DockgeOverview
import com.homelab.app.data.repository.DockgeRepository
import com.homelab.app.data.repository.DockgeService
import com.homelab.app.data.repository.DockgeStack
import com.homelab.app.data.repository.ServicesRepository
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.util.ErrorHandler
import com.homelab.app.util.ServiceType
import com.homelab.app.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DockgeViewModel @Inject constructor(
    private val repository: DockgeRepository,
    private val servicesRepository: ServicesRepository,
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val instanceId: String = checkNotNull(savedStateHandle["instanceId"])

    private val _state = MutableStateFlow<UiState<DockgeOverview>>(UiState.Loading)
    val state: StateFlow<UiState<DockgeOverview>> = _state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    /** The action that is still running, and how the last one failed. */
    private val _running = MutableStateFlow<DockgeAction?>(null)
    val running: StateFlow<DockgeAction?> = _running
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError
    private var job: Job? = null

    val instances: StateFlow<List<ServiceInstance>> = servicesRepository.instancesByType
        .map { it[ServiceType.DOCKGE].orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = viewModelScope.launch { load() }
    }

    private suspend fun load() {
        _isRefreshing.value = true
        try {
            _state.value = UiState.Success(repository.getOverview(instanceId))
            servicesRepository.markInstanceReachable(instanceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UiState.Error(ErrorHandler.getMessage(context, e)) { refresh() }
        }
        _isRefreshing.value = false
    }

    suspend fun services(stack: DockgeStack): Result<List<DockgeService>> = try {
        Result.success(repository.getServices(instanceId, stack))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    fun message(error: Throwable): String = ErrorHandler.getMessage(context, error)

    fun clearError() {
        _actionError.value = null
    }

    fun perform(action: DockgeAction, stack: DockgeStack, onDone: () -> Unit) {
        if (_running.value != null) return
        viewModelScope.launch {
            _running.value = action
            _actionError.value = null
            try {
                repository.perform(instanceId, action, stack)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionError.value = ErrorHandler.getMessage(context, e)
            } finally {
                _running.value = null
            }
            load()
        }
    }
}
