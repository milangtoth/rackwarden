package com.homelab.app.ui.forwardauth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.homelab.app.R
import com.homelab.app.data.remote.access.ForwardAuth
import com.homelab.app.data.remote.access.ProxyOutcome
import com.homelab.app.domain.model.AccessMethod
import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.domain.model.OriginAccess
import com.homelab.app.util.ErrorHandler
import com.homelab.app.util.ServiceType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ForwardAuthSection(
    form: ForwardAuthForm,
    serviceType: ServiceType,
    /** Keeps the browser session of this sign-in. */
    instanceId: String,
    url: String?,
    fallbackUrl: String?,
    allowSelfSigned: Boolean,
    onAllowSelfSignedChange: (Boolean) -> Unit,
    onTestHeaders: suspend (String) -> ProxyOutcome,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf<ForwardAuthTarget?>(null) }
    var checking by remember { mutableStateOf<String?>(null) }
    var confirmsTurnOff by remember { mutableStateOf(false) }
    var confirmsSignOut by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val origin = AccessOrigin.from(url)
    val fallbackOrigin = AccessOrigin.from(fallbackUrl)?.takeIf { it != origin }
    val needsConsent = form.needsConsent(listOfNotNull(origin, fallbackOrigin), allowSelfSigned)

    /** Null means the address answered without any proxy credentials. */
    fun finish(checked: AccessOrigin, access: OriginAccess?) {
        error = null
        when {
            access != null -> form.connect(access)
            checked == origin -> {
                form.turnOff()
                form.notice = R.string.forward_auth_not_needed
            }
            else -> error = context.getString(R.string.forward_auth_fallback_not_needed)
        }
    }

    fun connect(address: String, checked: AccessOrigin) {
        error = null
        if (form.method == AccessMethod.BROWSER) {
            target = ForwardAuthTarget.create(instanceId, address, form.allowsInsecure, allowSelfSigned)
            return
        }
        form.firstInvalidHeader(serviceType)?.let {
            error = context.getString(R.string.forward_auth_header_invalid, it.name)
            return
        }
        val access = OriginAccess(checked, headers = form.headers)
        checking = address
        scope.launch {
            try {
                when (onTestHeaders(address)) {
                    ProxyOutcome.PASSED -> finish(checked, access)
                    ProxyOutcome.NOT_NEEDED -> finish(checked, null)
                    ProxyOutcome.BLOCKED -> error = context.getString(R.string.forward_auth_headers_blocked)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ErrorHandler.getMessage(context, e)
            }
            checking = null
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LabeledSwitch(
                stringResource(R.string.forward_auth_title), stringResource(R.string.forward_auth_desc), form.isOn
            ) { isOn ->
                when {
                    isOn -> {
                        form.notice = null
                        // Proxy credentials need verified TLS unless the user opts out below.
                        if (!form.isConnected(origin)) onAllowSelfSignedChange(false)
                        form.isOn = true
                    }
                    form.hasCredentials -> confirmsTurnOff = true
                    else -> form.turnOff()
                }
            }
            form.notice?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }

            if (form.isOn) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val methods = listOf(
                        AccessMethod.BROWSER to R.string.forward_auth_method_browser,
                        AccessMethod.HEADERS to R.string.forward_auth_method_headers
                    )
                    methods.forEachIndexed { index, (method, label) ->
                        SegmentedButton(
                            selected = form.method == method,
                            onClick = { form.method = method; error = null },
                            shape = SegmentedButtonDefaults.itemShape(index, methods.size)
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }

                if (form.method == AccessMethod.HEADERS) HeaderEditor(form)

                if (needsConsent) {
                    LabeledSwitch(null, stringResource(R.string.forward_auth_insecure), form.allowsInsecure) {
                        form.allowsInsecure = it
                    }
                }

                val enabled = checking == null && (!needsConsent || form.allowsInsecure) &&
                    (form.method == AccessMethod.BROWSER || form.headers.isNotEmpty())
                if (url != null && origin != null) {
                    Connection(form, origin, enabled, checking == url) { connect(url, origin) }
                }
                if (fallbackUrl != null && fallbackOrigin != null && form.isConnected(origin)) {
                    HorizontalDivider()
                    Text(
                        stringResource(R.string.forward_auth_fallback, fallbackOrigin.host),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Connection(form, fallbackOrigin, enabled, checking == fallbackUrl) { connect(fallbackUrl, fallbackOrigin) }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (form.method == AccessMethod.BROWSER && form.isConnected(origin)) {
                    TextButton(onClick = { confirmsSignOut = true }) {
                        Text(stringResource(R.string.forward_auth_sign_out), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (ForwardAuth.canSkipNativeLogin(serviceType)) {
                    HorizontalDivider()
                    LabeledSwitch(
                        stringResource(R.string.forward_auth_skip_login),
                        stringResource(R.string.forward_auth_skip_login_desc),
                        form.skipsNativeLogin
                    ) { form.skipsNativeLogin = it }
                }
            }
        }
    }

    target?.let { current ->
        ForwardAuthDialog(
            target = current,
            onFinish = { target = null; finish(current.origin, it) },
            onDismiss = { target = null }
        )
    }
    if (confirmsTurnOff) {
        Confirmation(
            R.string.forward_auth_turn_off_title, R.string.forward_auth_turn_off_message, R.string.forward_auth_turn_off,
            onConfirm = { form.turnOff() }, onClose = { confirmsTurnOff = false }
        )
    }
    if (confirmsSignOut) {
        Confirmation(
            R.string.forward_auth_sign_out, R.string.forward_auth_sign_out_message, R.string.forward_auth_sign_out,
            onConfirm = onSignOut, onClose = { confirmsSignOut = false }
        )
    }
}

@Composable
private fun LabeledSwitch(title: String?, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Connection(form: ForwardAuthForm, origin: AccessOrigin, enabled: Boolean, checking: Boolean, onConnect: () -> Unit) {
    val connected = form.isConnected(origin)
    if (connected) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Default.VerifiedUser, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)
            )
            Text(stringResource(R.string.forward_auth_connected), style = MaterialTheme.typography.bodyMedium)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onConnect, enabled = enabled) {
            Text(
                stringResource(
                    when {
                        form.method == AccessMethod.HEADERS -> R.string.forward_auth_test_headers
                        connected -> R.string.forward_auth_sign_in_again
                        else -> R.string.forward_auth_sign_in
                    }
                )
            )
        }
        if (checking) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun HeaderEditor(form: ForwardAuthForm) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        form.rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = row.name,
                    onValueChange = { form.rows[index] = row.copy(name = it) },
                    label = { Text(stringResource(R.string.forward_auth_header_name), maxLines = 1) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { form.rows[index] = row.copy(value = it) },
                    label = { Text(stringResource(R.string.forward_auth_header_value), maxLines = 1) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { form.rows.removeAt(index) }) {
                    Icon(
                        Icons.Default.RemoveCircle, contentDescription = stringResource(R.string.delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        TextButton(onClick = { form.rows.add(ForwardAuthForm.Row()) }) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.forward_auth_add_header), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
internal fun Confirmation(title: Int, message: Int, action: Int, onConfirm: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(message)) },
        confirmButton = {
            TextButton(onClick = { onClose(); onConfirm() }) {
                Text(stringResource(action), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } }
    )
}
