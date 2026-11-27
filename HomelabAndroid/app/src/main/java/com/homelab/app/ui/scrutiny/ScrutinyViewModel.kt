package com.homelab.app.ui.scrutiny

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.homelab.app.R
import com.homelab.app.data.repository.ScrutinyDriveDetails
import com.homelab.app.data.repository.ScrutinyOverview
import com.homelab.app.data.repository.ScrutinyRepository
import com.homelab.app.data.repository.ScrutinyTemperature
import com.homelab.app.data.repository.ServicesRepository
import com.homelab.app.domain.model.ServiceInstance
import com.homelab.app.util.ErrorHandler
import com.homelab.app.util.ServiceType
import com.homelab.app.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScrutinyViewModel @Inject constructor(
    private val repository: ScrutinyRepository,
    private val servicesRepository: ServicesRepository,
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    val instanceId: String = checkNotNull(savedStateHandle["instanceId"])

    private val _state = MutableStateFlow<UiState<ScrutinyOverview>>(UiState.Loading)
    val state: StateFlow<UiState<ScrutinyOverview>> = _state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing
    private val _isWorking = MutableStateFlow(false)
    val isWorking: StateFlow<Boolean> = _isWorking
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
    private var job: Job? = null

    val instances: StateFlow<List<ServiceInstance>> = servicesRepository.instancesByType
        .map { it[ServiceType.SCRUTINY].orEmpty() }
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

    suspend fun details(driveId: String): Result<ScrutinyDriveDetails> = try {
        Result.success(repository.getDetails(instanceId, driveId))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun temperatures(driveId: String, duration: String): List<ScrutinyTemperature> = try {
        repository.getTemperatures(instanceId, driveId, duration)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    fun message(error: Throwable): String = ErrorHandler.getMessage(context, error)

    fun setArchived(driveId: String, archived: Boolean, onDone: () -> Unit) = work {
        repository.setArchived(instanceId, driveId, archived)
        onDone()
        load()
    }

    fun sendTestNotification() = work {
        repository.sendTestNotification(instanceId)
        _messages.emit(context.getString(R.string.scrutiny_test_sent))
    }

    private fun work(action: suspend () -> Unit) {
        if (_isWorking.value) return
        viewModelScope.launch {
            _isWorking.value = true
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.emit(ErrorHandler.getMessage(context, e))
            } finally {
                _isWorking.value = false
            }
        }
    }
}
