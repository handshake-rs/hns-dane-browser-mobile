package com.denuoweb.hnsdane.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Exported entry point for the system launcher and Handshake payment URIs.
 *
 * Keeping the browser activity non-exported prevents other apps from supplying
 * internal navigation extras that would otherwise be loaded with this app's
 * WebView cookies and storage. The only external data forwarded here is an
 * untrusted `handshake:` string for strict parsing by the wallet screen.
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
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
