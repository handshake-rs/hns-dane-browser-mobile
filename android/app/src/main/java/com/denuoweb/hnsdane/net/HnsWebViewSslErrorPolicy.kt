package com.denuoweb.hnsdane.net

import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import androidx.annotation.RequiresApi
import com.denuoweb.hnsdane.core.BrowserNamespacePolicy
import com.denuoweb.hnsdane.core.HnsHostPolicy
import java.net.URI
import java.util.Locale

fun interface HnsLocalCertificateDerVerifier {
    fun matchesLocalCertificate(host: String, certificateDer: ByteArray): Boolean
}

object HnsWebViewSslErrorPolicy {
    fun canProceed(
        error: SslError,
        certificateVerifier: HnsLocalCertificateDerVerifier,
        namespacePolicy: BrowserNamespacePolicy,
    ): Boolean {
        val certificateDer = encodedCertificate(error.certificate)
        return canProceed(error.url, certificateDer, certificateVerifier, namespacePolicy)
    }

    private fun encodedCertificate(certificate: SslCertificate?): ByteArray? = runCatching {
        certificate ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            encodedCertificateApi29(certificate)
        } else {
            // Android 9 exposes the same DER certificate through the stable
            // save-state bundle used to parcel SslCertificate instances.
            SslCertificate.saveState(certificate)?.getByteArray("x509-certificate")
        }
    }.getOrNull()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun encodedCertificateApi29(certificate: SslCertificate): ByteArray? =
        certificate.x509Certificate?.encoded

    internal fun canProceed(
        url: String?,
        certificateDer: ByteArray?,
        certificateVerifier: HnsLocalCertificateDerVerifier,
        namespacePolicy: BrowserNamespacePolicy,
    ): Boolean {
        val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        val host = eligiblePinnedLocalCertificateHost(uri, namespacePolicy) ?: return false
        val presentedCertificateDer = certificateDer?.takeIf(ByteArray::isNotEmpty) ?: return false
        return runCatching {
            certificateVerifier.matchesLocalCertificate(host, presentedCertificateDer)
        }.getOrDefault(false)
    }

    internal fun isEligiblePinnedLocalCertificateUrl(
        url: String,
        namespacePolicy: BrowserNamespacePolicy,
    ): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return eligiblePinnedLocalCertificateHost(uri, namespacePolicy) != null
    }

    private fun eligiblePinnedLocalCertificateHost(
        uri: URI,
        namespacePolicy: BrowserNamespacePolicy,
    ): String? {
        if (uri.scheme?.lowercase(Locale.US) !in setOf("https", "wss")) {
            return null
        }
        val host = uri.httpAuthorityHost() ?: return null
        return host.takeIf {
            HnsHostPolicy.requiresNativeGatewayResolution(it, namespacePolicy)
        }
    }
}

internal fun supportsDirectSslCertificateAccess(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.Q
