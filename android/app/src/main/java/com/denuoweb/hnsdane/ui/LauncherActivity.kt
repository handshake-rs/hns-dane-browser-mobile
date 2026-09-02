package com.denuoweb.hnsdane.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Exported entry point for the system launcher, browser URLs, and Handshake payment URIs.
 *
 * Keeping the browser activity non-exported prevents other apps from supplying
 * internal navigation extras that would otherwise be loaded with this app's
 * WebView cookies and storage. External `http` and `https` data is forwarded
 * only as an untrusted address for the browser's strict classifier; an
 * untrusted `handshake:` string is forwarded for strict wallet parsing.
 */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paymentUri = intent
            .takeIf { it.action == Intent.ACTION_VIEW }
            ?.dataString
            ?.takeIf { intent.data?.scheme.equals("handshake", ignoreCase = true) }
        if (paymentUri != null) {
            startActivity(
                Intent(this, WalletActivity::class.java)
                    .putExtra(EXTRA_HANDSHAKE_PAYMENT_URI, paymentUri)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            finish()
            return
        }
        val browserUrl = intent
            .takeIf { it.action == Intent.ACTION_VIEW }
            ?.dataString
            ?.takeIf { intent.data?.scheme?.lowercase() in WEB_SCHEMES }
        val browserIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (browserUrl != null) {
            browserIntent
                .putExtra(MainActivity.EXTRA_LOAD_URL, browserUrl)
                .putExtra(MainActivity.EXTRA_RETURN_TO_BACKGROUND_AFTER_BROWSER, true)
        }
        startActivity(browserIntent)
        finish()
    }

    private companion object {
        val WEB_SCHEMES = setOf("http", "https")
    }
}
