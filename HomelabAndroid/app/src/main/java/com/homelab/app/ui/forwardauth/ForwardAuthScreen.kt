package com.homelab.app.ui.forwardauth

import android.annotation.SuppressLint
import android.net.http.SslError
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.homelab.app.R
import com.homelab.app.data.remote.access.ForwardAuth
import com.homelab.app.data.remote.access.ForwardAuthProber
import com.homelab.app.data.remote.access.ProxyOutcome
import com.homelab.app.domain.model.AccessOrigin
import com.homelab.app.domain.model.OriginAccess
import com.homelab.app.util.ErrorHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Address to sign in to, and the instance that keeps the browser session. */
data class ForwardAuthTarget(
    val instanceId: String,
    val url: String,
    val origin: AccessOrigin,
    val allowsInsecureTransport: Boolean,
    val allowSelfSigned: Boolean
) {
    companion object {
        fun create(instanceId: String, url: String, allowsInsecure: Boolean, allowSelfSigned: Boolean): ForwardAuthTarget? =
            AccessOrigin.from(url)?.let { ForwardAuthTarget(instanceId, url, it, allowsInsecure, allowSelfSigned) }
    }
}

@HiltViewModel
class ForwardAuthViewModel @Inject constructor(
    val webData: ForwardAuthWebData,
    private val prober: ForwardAuthProber
) : ViewModel() {
    suspend fun probe(target: ForwardAuthTarget, access: OriginAccess): ProxyOutcome =
        prober.probe(target.url, access, target.allowsInsecureTransport, target.allowSelfSigned)
}

/** Lets the user sign in to the proxy and hands back the resulting session, or null when none is needed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForwardAuthDialog(
    target: ForwardAuthTarget,
    onFinish: (OriginAccess?) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ForwardAuthViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(target.url) }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var cookies by remember { mutableStateOf<CookieManager?>(null) }
    var done by remember { mutableStateOf(false) }

    fun check(url: String) {
        if (checking || done || !ForwardAuth.isServicePage(url, target.origin)) return
        checking = true
        error = null
        scope.launch {
            val access = OriginAccess(
                target.origin, cookies = ForwardAuthWebData.parse(cookies?.getCookie(target.url))
            )
            try {
                when (viewModel.probe(target, access)) {
                    ProxyOutcome.PASSED -> { done = true; onFinish(access) }
                    ProxyOutcome.NOT_NEEDED -> { done = true; onFinish(null) }
                    ProxyOutcome.BLOCKED -> error = context.getString(R.string.forward_auth_blocked)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = ErrorHandler.getMessage(context, e)
            }
            checking = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else onDismiss() }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            AccessOrigin.from(page)?.host.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.StartEllipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        if (checking) {
                            CircularProgressIndicator(Modifier.padding(end = 16.dp).size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                if (page.startsWith("https://")) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 16.dp).size(18.dp)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                webView?.let { view ->
                    AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    LaunchedEffect(target) {
        try {
            val view = WebView(context)
            cookies = viewModel.webData.attach(view, target.instanceId)
            configure(view, target, onNavigate = { page = it }, onPage = { check(it) }, onError = { error = it })
            cookies?.setAcceptThirdPartyCookies(view, true)
            view.loadUrl(target.url)
            webView = view
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = ErrorHandler.getMessage(context, e)
        }
    }
    DisposableEffect(target) {
        onDispose {
            cookies?.flush()
            webView?.run {
                (parent as? ViewGroup)?.removeView(this)
                stopLoading()
                destroy()
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configure(
    view: WebView,
    target: ForwardAuthTarget,
    onNavigate: (String) -> Unit,
    onPage: (String) -> Unit,
    onError: (String) -> Unit
) {
    view.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        setSupportMultipleWindows(true)
    }
    view.webViewClient = object : WebViewClient() {
        /** Pages only; apps must not be opened from a sign-in page. */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.url.scheme?.lowercase() !in setOf("http", "https")

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = onNavigate(url)

        override fun onPageFinished(view: WebView, url: String) {
            onNavigate(url)
            onPage(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) onError(error.description.toString())
        }

        /** Accepts an unverified certificate only for the service itself, and only with consent. */
        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            if (target.allowSelfSigned && target.allowsInsecureTransport && AccessOrigin.from(error.url) == target.origin) {
                handler.proceed()
            } else {
                handler.cancel()
            }
        }
    }
    view.webChromeClient = object : WebChromeClient() {
        /** Sign-in pages that open a new window continue in this one. */
        override fun onCreateWindow(
            view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
        ): Boolean {
            val popup = WebView(view.context)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(child: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.scheme?.lowercase() in setOf("http", "https")) view.loadUrl(request.url.toString())
                    child.post { child.destroy() }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }
}
