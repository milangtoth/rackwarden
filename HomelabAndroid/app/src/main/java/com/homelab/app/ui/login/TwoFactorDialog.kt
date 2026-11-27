package com.homelab.app.ui.login

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.homelab.app.R
import com.homelab.app.domain.model.TwoFactorChallenge
import com.homelab.app.util.ErrorHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

/** Bridges the code prompt to the login that is waiting for it. */
class TwoFactorPrompt(
    val challenge: TwoFactorChallenge,
    val resend: (suspend (TwoFactorChallenge) -> TwoFactorChallenge)?,
    val verify: suspend (TwoFactorChallenge, String) -> String
) {
    val result = CompletableDeferred<String>()
}

@Composable
fun TwoFactorDialog(prompt: TwoFactorPrompt) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var challenge by remember(prompt) { mutableStateOf(prompt.challenge) }
    var code by remember(prompt) { mutableStateOf("") }
    var working by remember(prompt) { mutableStateOf(false) }
    var error by remember(prompt) { mutableStateOf<String?>(null) }
    val isEmail = challenge.kind is TwoFactorChallenge.Kind.Email
    val trimmed = code.trim()

    fun run(work: suspend () -> Unit) {
        if (working) return
        working = true
        error = null
        scope.launch {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                code = ""
                error = ErrorHandler.getMessage(context, e)
            }
            working = false
        }
    }

    fun submit() {
        if (trimmed.length >= 6) run { prompt.result.complete(prompt.verify(challenge, trimmed)) }
    }

    AlertDialog(
        onDismissRequest = { prompt.result.cancel() },
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.two_factor_title)) },
        text = {
            Column {
                Text(stringResource(if (isEmail) R.string.two_factor_email_message else R.string.two_factor_app_message))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(stringResource(R.string.two_factor_code)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isEmail) KeyboardType.NumberPassword else KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                prompt.resend?.let { resend ->
                    TextButton(enabled = !working, onClick = { run { challenge = resend(challenge); code = "" } }) {
                        Text(stringResource(R.string.two_factor_resend))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !working && trimmed.length >= 6, onClick = { submit() }) {
                if (working) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.two_factor_verify))
            }
        },
        dismissButton = {
            TextButton(onClick = { prompt.result.cancel() }) { Text(stringResource(R.string.cancel)) }
        }
    )
}
