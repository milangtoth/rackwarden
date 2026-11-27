package com.homelab.app.ui.scrutiny

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.homelab.app.R
import com.homelab.app.data.repository.ScrutinyAttribute
import com.homelab.app.data.repository.ScrutinyDrive
import com.homelab.app.data.repository.ScrutinyDriveDetails
import com.homelab.app.data.repository.ScrutinyHealth
import com.homelab.app.data.repository.ScrutinyLevel
import com.homelab.app.data.repository.ScrutinyOverview
import com.homelab.app.data.repository.ScrutinyParser
import com.homelab.app.data.repository.ScrutinyTemperature
import com.homelab.app.ui.components.DashboardScaffold
import com.homelab.app.ui.components.InfoRow
import com.homelab.app.ui.components.StatCard
import com.homelab.app.ui.forwardauth.Confirmation
import com.homelab.app.ui.theme.StatusGreen
import com.homelab.app.ui.theme.StatusOrange
import com.homelab.app.ui.theme.StatusRed
import com.homelab.app.ui.theme.primaryColor
import com.homelab.app.util.ServiceType
import com.homelab.app.util.UiState
import java.text.DateFormat
import java.util.Date

@Composable
fun ScrutinyDashboardScreen(
    viewModel: ScrutinyViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit,
    onNavigateToInstance: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val instances by viewModel.instances.collectAsStateWithLifecycle()
    val isWorking by viewModel.isWorking.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var selected by remember { mutableStateOf<ScrutinyDrive?>(null) }
    var showsArchived by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.messages.collect { snackbar.showSnackbar(it) } }

    DashboardScaffold(
        title = ServiceType.SCRUTINY.displayName,
        state = state,
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        instances = instances,
        instanceId = viewModel.instanceId,
        onNavigateBack = onNavigateBack,
        onNavigateToInstance = onNavigateToInstance,
        snackbar = snackbar,
        actions = {
            IconButton(enabled = !isWorking, onClick = { viewModel.sendTestNotification() }) {
                Icon(Icons.Default.NotificationsActive, contentDescription = stringResource(R.string.scrutiny_test_notification))
            }
        }
    ) { overview ->
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    stringResource(R.string.scrutiny_healthy_drives),
                    "${overview.healthyCount}/${overview.active.size}", StatusGreen, Modifier.weight(1f)
                )
                StatCard(
                    stringResource(R.string.scrutiny_failed_drives), "${overview.failedCount}",
                    if (overview.failedCount == 0) MaterialTheme.colorScheme.onSurfaceVariant else StatusRed, Modifier.weight(1f)
                )
                overview.hottest?.let {
                    StatCard(
                        stringResource(R.string.scrutiny_hottest), overview.settings.format(it),
                        ServiceType.SCRUTINY.primaryColor, Modifier.weight(1f)
                    )
                }
            }
        }
        if (overview.active.isEmpty()) {
            item { Text(stringResource(R.string.scrutiny_no_drives), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        drives(overview.active, overview) { selected = it }
        if (overview.archived.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().clickable { showsArchived = !showsArchived }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${stringResource(R.string.scrutiny_archived)} (${overview.archived.size})",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)
                    )
                    Icon(if (showsArchived) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                }
            }
            if (showsArchived) drives(overview.archived, overview) { selected = it }
        }
    }

    selected?.let { drive ->
        (state as? UiState.Success)?.data?.let { overview ->
            DriveSheet(viewModel, drive, overview, isWorking) { selected = null }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.drives(
    drives: List<ScrutinyDrive>, overview: ScrutinyOverview, onOpen: (ScrutinyDrive) -> Unit
) {
    val hosts = drives.groupBy { it.device.hostId.orEmpty() }
    hosts.toSortedMap().forEach { (host, list) ->
        if (hosts.size > 1 || host.isNotEmpty()) {
            item {
                Text(
                    host.ifEmpty { stringResource(R.string.scrutiny_drives) },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
                )
            }
        }
        items(list.size) { index -> DriveRow(list[index], overview) { onOpen(list[index]) } }
    }
}

@Composable
private fun DriveRow(drive: ScrutinyDrive, overview: ScrutinyOverview, onClick: () -> Unit) {
    val health = overview.health(drive)
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(health.icon, contentDescription = stringResource(health.label), tint = health.color)
            Column(Modifier.weight(1f)) {
                Text(drive.device.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        drive.device.modelName?.takeIf { it.isNotBlank() },
                        drive.device.capacity?.takeIf { it > 0 }?.let { ScrutinyParser.capacity(it) },
                        drive.powerOnHours?.let { poweredOn(it) }
                    ).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            drive.temperature?.let { Text(overview.settings.format(it), fontWeight = FontWeight.SemiBold) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveSheet(
    viewModel: ScrutinyViewModel, drive: ScrutinyDrive, overview: ScrutinyOverview, isWorking: Boolean, onClose: () -> Unit
) {
    var details by remember(drive.id) { mutableStateOf<ScrutinyDriveDetails?>(null) }
    var error by remember(drive.id) { mutableStateOf<String?>(null) }
    var temperatures by remember(drive.id) { mutableStateOf<List<ScrutinyTemperature>>(emptyList()) }
    var duration by remember(drive.id) { mutableStateOf("week") }
    var showsPassed by remember(drive.id) { mutableStateOf(false) }
    var confirms by remember { mutableStateOf(false) }
    val health = overview.health(drive)
    val threshold = overview.settings.statusThreshold

    LaunchedEffect(drive.id) {
        viewModel.details(drive.id).onSuccess { details = it }.onFailure { error = viewModel.message(it) }
    }
    LaunchedEffect(drive.id, duration) { temperatures = viewModel.temperatures(drive.id, duration) }

    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(drive.device.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(health.icon, contentDescription = null, tint = health.color)
                Text(stringResource(health.label), color = health.color, fontWeight = FontWeight.SemiBold)
            }
            InfoRow(stringResource(R.string.scrutiny_model), drive.device.modelName)
            InfoRow(stringResource(R.string.scrutiny_serial), drive.device.serialNumber)
            InfoRow(stringResource(R.string.scrutiny_firmware), drive.device.firmware)
            InfoRow(
                stringResource(R.string.scrutiny_capacity),
                drive.device.capacity?.takeIf { it > 0 }?.let { ScrutinyParser.capacity(it) }
            )
            InfoRow(stringResource(R.string.scrutiny_temperature), drive.temperature?.let { overview.settings.format(it) })
            InfoRow(stringResource(R.string.scrutiny_powered_on), drive.powerOnHours?.let { poweredOn(it) })
            InfoRow(stringResource(R.string.scrutiny_power_cycles), details?.powerCycles?.toString())
            InfoRow(
                stringResource(R.string.scrutiny_last_report),
                drive.lastSeen?.let {
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date.from(it))
                }
            )

            HorizontalDivider()
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val periods = listOf(
                    "week" to R.string.scrutiny_week, "month" to R.string.scrutiny_month, "year" to R.string.scrutiny_year
                )
                periods.forEachIndexed { index, (key, label) ->
                    SegmentedButton(
                        selected = duration == key, onClick = { duration = key },
                        shape = SegmentedButtonDefaults.itemShape(index, periods.size)
                    ) { Text(stringResource(label)) }
                }
            }
            if (temperatures.size > 1) {
                TemperatureChart(temperatures) { overview.settings.format(it) }
            } else {
                Text(stringResource(R.string.no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            HorizontalDivider()
            Text(stringResource(R.string.scrutiny_attributes), style = MaterialTheme.typography.titleMedium)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val loaded = details
            if (loaded == null) {
                if (error == null) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            } else {
                val (passed, problems) = loaded.attributes.partition { it.level(threshold) == ScrutinyLevel.PASSED }
                when {
                    loaded.attributes.isEmpty() ->
                        Text(stringResource(R.string.no_data), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    problems.isEmpty() -> Text(stringResource(R.string.scrutiny_all_passed), color = StatusGreen)
                }
                problems.sortedBy { it.level(threshold) }.forEach { AttributeRow(it, it.level(threshold)) }
                if (passed.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showsPassed = !showsPassed }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${stringResource(R.string.scrutiny_passed)} (${passed.size})", modifier = Modifier.weight(1f))
                        Icon(if (showsPassed) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
                    }
                    if (showsPassed) passed.forEach { AttributeRow(it, ScrutinyLevel.PASSED) }
                }
            }

            if (drive.canArchive) {
                OutlinedButton(
                    enabled = !isWorking,
                    onClick = {
                        if (drive.isArchived) viewModel.setArchived(drive.id, false, onClose) else confirms = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(if (drive.isArchived) R.string.scrutiny_unarchive else R.string.scrutiny_archive))
                }
            }
        }
    }
    if (confirms) {
        Confirmation(
            R.string.scrutiny_archive, R.string.scrutiny_archive_confirm, R.string.scrutiny_archive,
            onConfirm = { viewModel.setArchived(drive.id, true, onClose) }, onClose = { confirms = false }
        )
    }
}

@Composable
private fun AttributeRow(attribute: ScrutinyAttribute, level: ScrutinyLevel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(attribute.name, style = MaterialTheme.typography.bodyMedium)
            if (attribute.isCritical) {
                Text(
                    stringResource(R.string.scrutiny_critical), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (level != ScrutinyLevel.PASSED) attribute.reason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            attribute.value,
            color = when (level) {
                ScrutinyLevel.FAILED -> StatusRed
                ScrutinyLevel.WARNING -> StatusOrange
                ScrutinyLevel.PASSED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun TemperatureChart(points: List<ScrutinyTemperature>, format: (Int) -> String) {
    val color = ServiceType.SCRUTINY.primaryColor
    val low = points.minOf { it.celsius }
    val high = points.maxOf { it.celsius }
    Column {
        Text(format(high), style = MaterialTheme.typography.labelSmall)
        Box(Modifier.fillMaxWidth().height(140.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val start = points.first().date.epochSecond
                val span = (points.last().date.epochSecond - start).coerceAtLeast(1)
                val range = (high - low).coerceAtLeast(1)
                val path = Path()
                points.forEachIndexed { index, point ->
                    val at = Offset(
                        size.width * (point.date.epochSecond - start) / span,
                        size.height * (1 - (point.celsius - low).toFloat() / range)
                    )
                    if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Text(format(low), style = MaterialTheme.typography.labelSmall)
    }
}

private fun poweredOn(hours: Int): String = if (hours < 24) "$hours h" else "${hours / 24} d"

private val ScrutinyHealth.icon
    get() = when (this) {
        ScrutinyHealth.PASSED -> Icons.Default.CheckCircle
        ScrutinyHealth.FAILED -> Icons.Default.Error
        ScrutinyHealth.UNKNOWN -> Icons.Default.Help
    }

private val ScrutinyHealth.color: Color
    @Composable get() = when (this) {
        ScrutinyHealth.PASSED -> StatusGreen
        ScrutinyHealth.FAILED -> StatusRed
        ScrutinyHealth.UNKNOWN -> Color.Gray
    }

private val ScrutinyHealth.label: Int
    get() = when (this) {
        ScrutinyHealth.PASSED -> R.string.scrutiny_passed
        ScrutinyHealth.FAILED -> R.string.scrutiny_failed
        ScrutinyHealth.UNKNOWN -> R.string.scrutiny_unknown
    }
