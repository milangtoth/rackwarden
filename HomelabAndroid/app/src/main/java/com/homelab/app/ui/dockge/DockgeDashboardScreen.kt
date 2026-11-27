package com.homelab.app.ui.dockge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homelab.app.R
import com.homelab.app.data.repository.DockgeAction
import com.homelab.app.data.repository.DockgeService
import com.homelab.app.data.repository.DockgeStack
import com.homelab.app.data.repository.DockgeStatus
import com.homelab.app.ui.components.DashboardScaffold
import com.homelab.app.ui.components.InfoRow
import com.homelab.app.ui.components.StatCard
import com.homelab.app.ui.theme.StatusGreen
import com.homelab.app.ui.theme.StatusOrange
import com.homelab.app.ui.theme.StatusRed
import com.homelab.app.util.ServiceType

@Composable
fun DockgeDashboardScreen(
    viewModel: DockgeViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToInstance: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<DockgeStack?>(null) }

    DashboardScaffold(
        title = ServiceType.DOCKGE.displayName,
        state = state,
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        instances = instances,
        instanceId = viewModel.instanceId,
        onNavigateBack = onNavigateBack,
        onNavigateToInstance = onNavigateToInstance,
        snackbar = snackbar
    ) { overview ->
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    stringResource(R.string.dockge_running_stacks),
                    "${overview.runningCount}/${overview.stacks.size}", StatusGreen, Modifier.weight(1f)
                )
                StatCard(
                    stringResource(R.string.dockge_status_exited), "${overview.exitedCount}",
                    if (overview.exitedCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else StatusRed, Modifier.weight(1f)
                )
            }
        }
        items(overview.offlineAgents.size) {
            Text(stringResource(R.string.dockge_agent_offline, overview.offlineAgents[it]), color = StatusOrange)
        }
        if (overview.stacks.isEmpty()) {
            item { Text(stringResource(R.string.dockge_no_stacks), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        overview.endpoints.forEach { endpoint ->
            item {
                Text(
                    endpoint.ifEmpty { stringResource(R.string.dockge_stacks) },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
                )
            }
            val stacks = overview.stacks.filter { it.endpoint == endpoint }
            items(stacks.size, key = { stacks[it].id }) { index ->
                StackRow(stacks[index]) {
                    viewModel.clearError()
                    selected = stacks[index]
                }
            }
        }
    }

    selected?.let { StackSheet(viewModel, it) { selected = null } }
}

@Composable
private fun StackRow(stack: DockgeStack, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Circle, contentDescription = null, tint = stack.status.color, modifier = Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(stack.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!stack.isManaged) {
                    Text(
                        stringResource(R.string.dockge_not_managed), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(stringResource(stack.status.label), color = stack.status.color, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StackSheet(viewModel: DockgeViewModel, stack: DockgeStack, onClose: () -> Unit) {
    val running by viewModel.running.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()
    var services by remember(stack.id) { mutableStateOf<List<DockgeService>?>(null) }
    var error by remember(stack.id) { mutableStateOf<String?>(null) }
    var confirming by remember { mutableStateOf<DockgeAction?>(null) }
    val isRunning by rememberUpdatedState(running != null)

    LaunchedEffect(stack.id) {
        viewModel.services(stack).onSuccess { services = it }.onFailure { error = viewModel.message(it) }
    }

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !isRunning }
        )
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stack.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            InfoRow(
                stack.endpoint.ifEmpty { stringResource(R.string.dockge_stacks) },
                stringResource(stack.status.label), stack.status.color
            )
            (actionError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (stack.isManaged) {
                val actions = if (stack.status == DockgeStatus.RUNNING) {
                    listOf(DockgeAction.RESTART, DockgeAction.UPDATE, DockgeAction.STOP)
                } else {
                    listOf(DockgeAction.START, DockgeAction.UPDATE, DockgeAction.STOP)
                }
                actions.forEach { action ->
                    OutlinedButton(
                        enabled = running == null,
                        onClick = {
                            if (action == DockgeAction.STOP || action == DockgeAction.UPDATE) confirming = action
                            else viewModel.perform(action, stack, onClose)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (running == action) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(action.icon, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(action.label), modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (running != null) {
                    Text(stringResource(R.string.dockge_working), style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(stringResource(R.string.dockge_not_managed), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text(stringResource(R.string.dockge_services), style = MaterialTheme.typography.titleMedium)
            val loaded = services
            when {
                loaded == null -> if (error == null) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                loaded.isEmpty() -> Text(stringResource(R.string.no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> loaded.forEach { service ->
                    InfoRow(service.name, service.state, if (service.isRunning) StatusGreen else StatusOrange)
                    if (service.ports.isNotEmpty()) {
                        Text(
                            service.ports.joinToString(", "), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    confirming?.let { action ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            text = {
                Text(
                    stringResource(
                        if (action == DockgeAction.STOP) R.string.dockge_stop_confirm else R.string.dockge_update_confirm,
                        stack.name
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = null
                    viewModel.perform(action, stack, onClose)
                }) { Text(stringResource(action.label)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private val DockgeStatus.color: Color
    @Composable get() = when (this) {
        DockgeStatus.RUNNING -> StatusGreen
        DockgeStatus.EXITED -> StatusRed
        else -> Color.Gray
    }

private val DockgeStatus.label: Int
    get() = when (this) {
        DockgeStatus.RUNNING -> R.string.dockge_status_running
        DockgeStatus.EXITED -> R.string.dockge_status_exited
        DockgeStatus.DRAFT, DockgeStatus.CREATED -> R.string.dockge_status_inactive
        DockgeStatus.UNKNOWN -> R.string.dockge_status_unknown
    }

private val DockgeAction.icon
    get() = when (this) {
        DockgeAction.START -> Icons.Default.PlayArrow
        DockgeAction.STOP -> Icons.Default.Stop
        DockgeAction.RESTART -> Icons.Default.RestartAlt
        DockgeAction.UPDATE -> Icons.Default.SystemUpdateAlt
    }

private val DockgeAction.label: Int
    get() = when (this) {
        DockgeAction.START -> R.string.dockge_start
        DockgeAction.STOP -> R.string.dockge_stop
        DockgeAction.RESTART -> R.string.dockge_restart
        DockgeAction.UPDATE -> R.string.dockge_update
    }
