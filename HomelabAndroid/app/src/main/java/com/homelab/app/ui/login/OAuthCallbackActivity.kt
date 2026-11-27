package com.homelab.app.ui.login

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.homelab.app.MainActivity
import kotlinx.coroutines.CompletableDeferred

/** Hands a sign-in provider's reply to the login that asked for it. */
object OAuthReplies {
    private var waiting: CompletableDeferred<String>? = null

    @Synchronized
    fun expect(): CompletableDeferred<String> {
        waiting?.cancel()
        return CompletableDeferred<String>().also { waiting = it }
    }

    @Synchronized
    fun deliver(reply: String) {
        waiting?.complete(reply)
        waiting = null
    }

    @Synchronized
    fun cancel() {
        waiting?.cancel()
        waiting = null
    }
}

/** Receives rackwarden://oauth, then brings the app back to the front. */
class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.dataString?.let { OAuthReplies.deliver(it) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
