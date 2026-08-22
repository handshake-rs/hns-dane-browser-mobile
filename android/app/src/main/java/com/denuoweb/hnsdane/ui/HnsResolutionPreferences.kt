package com.denuoweb.hnsdane.ui

import android.content.Context
import androidx.annotation.StringRes
import com.denuoweb.hnsdane.BuildConfig
import com.denuoweb.hnsdane.R
import java.net.URI
import java.util.Locale

enum class HandshakeNetwork(
    val id: String,
    @param:StringRes private val displayNameRes: Int,
    @param:StringRes private val summaryRes: Int,
) {
    Mainnet(
        id = "mainnet",
        displayNameRes = R.string.handshake_network_mainnet,
        summaryRes = R.string.handshake_network_mainnet_summary,
    ),
    Testnet(
        id = "testnet",
        displayNameRes = R.string.handshake_network_testnet,
        summaryRes = R.string.handshake_network_testnet_summary,
    ),
    Regtest(
        id = "regtest",
        displayNameRes = R.string.handshake_network_regtest,
        summaryRes = R.string.handshake_network_regtest_summary,
    );

    fun displayName(context: Context): String =
        context.getString(displayNameRes)

    fun summary(context: Context): String =
        context.getString(summaryRes)

    companion object {
        fun fromId(id: String?): HandshakeNetwork =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: Mainnet
    }
}

internal object HnsResolutionPreferences {
    const val DEFAULT_HANDSHAKE_NETWORK = "mainnet"
    const val DEFAULT_STRICT_HNS_MODE = true
    const val DEFAULT_STATELESS_DANE_CERTIFICATES = true
    const val DEFAULT_EXPERIMENTAL_P2P_DNS_RELAY = false
    const val DEFAULT_LEGACY_HNS_DOH_COMPATIBILITY = false

    private const val PREFS = "hns_resolution_preferences"
    private const val KEY_HANDSHAKE_NETWORK = "handshake_network"
    private const val KEY_STRICT_HNS_MODE = "strict_hns_mode"
    // Historical compatibility key: permanently deleted during migration.
    private const val KEY_DOH_RESOLVER_URL = "doh_resolver_url"
    private const val KEY_HNS_DOH_RECOVERY_URL = "hns_doh_recovery_url_v1"
    private const val KEY_STATELESS_DANE_CERTIFICATES = "stateless_dane_certificates"
    private const val KEY_EXPERIMENTAL_P2P_DNS_RELAY = "experimental_p2p_dns_relay"
    private const val KEY_LEGACY_HNS_DOH_COMPATIBILITY = "legacy_hns_doh_compatibility"

    fun handshakeNetwork(context: Context): HandshakeNetwork =
        HandshakeNetwork.fromId(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_HANDSHAKE_NETWORK, buildDefaultHandshakeNetwork().id),
        )

    fun handshakeNetworkId(context: Context): String =
        handshakeNetwork(context).id

    fun setHandshakeNetwork(context: Context, network: HandshakeNetwork) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HANDSHAKE_NETWORK, network.id)
            .apply()
    }

    fun strictHnsMode(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true

    fun statelessDaneCertificates(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(
                KEY_STATELESS_DANE_CERTIFICATES,
                DEFAULT_STATELESS_DANE_CERTIFICATES,
            )

    fun setStatelessDaneCertificates(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_STATELESS_DANE_CERTIFICATES, enabled)
            .apply()
    }

    fun experimentalP2pDnsRelay(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(
                KEY_EXPERIMENTAL_P2P_DNS_RELAY,
                buildDefaultExperimentalP2pDnsRelay(),
            )

    fun setExperimentalP2pDnsRelay(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EXPERIMENTAL_P2P_DNS_RELAY, enabled)
            .apply()
    }

    fun legacyHnsDohCompatibility(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false

    fun dohResolverUrl(context: Context): String {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = preferences.getString(KEY_HNS_DOH_RECOVERY_URL, "") ?: ""
        val normalized = normalizeHnsDohRecoveryUrl(stored)
        if (normalized == null) {
            preferences.edit().remove(KEY_HNS_DOH_RECOVERY_URL).apply()
            return ""
        }
        return normalized
    }

    fun setHnsDohRecoveryUrl(context: Context, input: String): String? {
        val normalized = normalizeHnsDohRecoveryUrl(input) ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .also { editor ->
                if (normalized.isEmpty()) {
                    editor.remove(KEY_HNS_DOH_RECOVERY_URL)
                } else {
                    editor.putString(KEY_HNS_DOH_RECOVERY_URL, normalized)
                }
            }
            .apply()
        return normalized
    }

    /**
     * One-way migration for releases that persisted public recursive HNS DoH or
     * HNS WebPKI compatibility controls. An explicit legacy compatibility choice
     * is never reinterpreted as consent to the P2P relay: when no independent
     * relay preference exists, that migration starts with relay fallback off.
     * Fresh installs have no legacy preference and keep relay consumption off
     * until the user explicitly opts in.
     */
    fun migrateProhibitedHnsFallbackSettings(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hadExplicitRelayPreference =
            preferences.contains(KEY_EXPERIMENTAL_P2P_DNS_RELAY)
        val hadExplicitLegacyFallbackPreference =
            (
                preferences.contains(KEY_STRICT_HNS_MODE) &&
                    !preferences.getBoolean(KEY_STRICT_HNS_MODE, true)
                ) ||
                (
                    preferences.contains(KEY_LEGACY_HNS_DOH_COMPATIBILITY) &&
                        preferences.getBoolean(KEY_LEGACY_HNS_DOH_COMPATIBILITY, false)
                    ) ||
                preferences.contains(KEY_DOH_RESOLVER_URL)

        preferences.edit()
            .putBoolean(KEY_STRICT_HNS_MODE, true)
            .putBoolean(KEY_LEGACY_HNS_DOH_COMPATIBILITY, false)
            .remove(KEY_DOH_RESOLVER_URL)
            .also { editor ->
                if (hadExplicitLegacyFallbackPreference && !hadExplicitRelayPreference) {
                    editor.putBoolean(KEY_EXPERIMENTAL_P2P_DNS_RELAY, false)
                }
            }
            .apply()
    }

    internal fun buildDefaultHandshakeNetwork(): HandshakeNetwork =
        HandshakeNetwork.fromId(BuildConfig.HNS_DEFAULT_HANDSHAKE_NETWORK)

    internal fun buildDefaultStrictHnsMode(): Boolean =
        BuildConfig.HNS_DEFAULT_STRICT_MODE

    internal fun buildDefaultExperimentalP2pDnsRelay(): Boolean =
        BuildConfig.HNS_DEFAULT_EXPERIMENTAL_P2P_DNS_RELAY

    internal fun buildDefaultLegacyHnsDohCompatibility(): Boolean =
        BuildConfig.HNS_DEFAULT_LEGACY_HNS_DOH_COMPATIBILITY

    /**
     * Validates one explicit RFC 8484 POST endpoint without opening a socket.
     * Blank disables recovery. Historical compatibility values never enter
     * this new key, so upgrades cannot silently resurrect consent.
     */
    fun normalizeHnsDohRecoveryUrl(input: String): String? {
        val value = input.trim()
        if (value.isEmpty()) return ""
        if (value.toByteArray(Charsets.UTF_8).size > MAX_HNS_DOH_RECOVERY_URL_BYTES ||
            value.any { it.isWhitespace() || it.isISOControl() } ||
            '#' in value ||
            '{' in value ||
            '}' in value
        ) {
            return null
        }
        val uri = runCatching { URI(value).parseServerAuthority() }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.rawUserInfo != null ||
            uri.rawFragment != null ||
            uri.host.isNullOrBlank() ||
            uri.rawPath.isNullOrEmpty() ||
            !uri.rawPath.startsWith("/")
        ) {
            return null
        }
        val host = uri.host.trimEnd('.').lowercase(Locale.US)
        if ('.' !in host ||
            normalizeIpv4Literal(host) != null ||
            isBrowserSpecialUseHost(host) ||
            !HOSTNAME_PATTERN.matches(host)
        ) {
            return null
        }
        val port = if (uri.port == -1) 443 else uri.port
        if (port !in 1..65535 || port in BROWSER_BLOCKED_PORTS) {
            return null
        }
        val authority = if (port == 443) host else "$host:$port"
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "https://$authority${uri.rawPath}$query"
    }

    /**
     * Normalizes one explicit Handshake peer without performing DNS or opening a socket.
     * Network-specific address and port policy is enforced again by the Rust runtime before save.
     */
    fun normalizeStaticRelayPeerEndpoint(input: String): String? {
        val endpoint = input.trim()
        if (endpoint.isEmpty() ||
            endpoint.length > MAX_STATIC_RELAY_PEER_ENDPOINT_CHARS ||
            endpoint.any { it.isWhitespace() || it.isISOControl() }
        ) {
            return null
        }

        val (host, portText, bracketedIpv6) = if (endpoint.startsWith('[')) {
            val closingBracket = endpoint.indexOf(']')
            if (closingBracket <= 1 ||
                closingBracket + 2 >= endpoint.length ||
                endpoint[closingBracket + 1] != ':'
            ) {
                return null
            }
            Triple(
                endpoint.substring(1, closingBracket),
                endpoint.substring(closingBracket + 2),
                true,
            )
        } else {
            val colon = endpoint.lastIndexOf(':')
            if (colon <= 0 || colon == endpoint.lastIndex || endpoint.indexOf(':') != colon) {
                return null
            }
            Triple(endpoint.substring(0, colon), endpoint.substring(colon + 1), false)
        }

        if (portText.isEmpty() || !portText.all(Char::isDigit)) {
            return null
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null

        if (bracketedIpv6) {
            if ('%' in host || ':' !in host || host.any { it !in IPV6_LITERAL_CHARS }) {
                return null
            }
            val parsed = runCatching {
                URI("hns://[$host]:$port").parseServerAuthority()
            }.getOrNull() ?: return null
            if (parsed.host.isNullOrBlank() || parsed.port != port) {
                return null
            }
            return "[${host.lowercase(Locale.US)}]:$port"
        }

        normalizeIpv4Literal(host)?.let { return "$it:$port" }
        return null
    }

    private const val MAX_STATIC_RELAY_PEER_ENDPOINT_CHARS = 320
    private const val MAX_HNS_DOH_RECOVERY_URL_BYTES = 2 * 1024
    private val HOSTNAME_PATTERN =
        Regex("""(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?""")
    private val BROWSER_BLOCKED_PORTS = setOf(
        0, 1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79,
        87, 95, 101, 102, 103, 104, 109, 110, 111, 113, 115, 117, 119, 123, 135, 137,
        139, 143, 161, 179, 389, 427, 465, 512, 513, 514, 515, 526, 530, 531, 532,
        540, 548, 554, 556, 563, 587, 601, 636, 989, 990, 993, 995, 1719, 1720,
        1723, 2049, 3659, 4045, 4190, 5060, 5061, 6000, 6566, 6665, 6666, 6667, 6668,
        6669, 6679, 6697, 10080,
    )
    private val BROWSER_SPECIAL_USE_SUFFIXES = setOf(
        "alt", "arpa", "example", "internal", "invalid", "local", "localhost", "onion", "test",
    )
    private val IPV6_LITERAL_CHARS = ('0'..'9').toSet() + ('a'..'f') + ('A'..'F') + setOf(':', '.')

    private fun normalizeIpv4Literal(host: String): String? {
        val octets = host.split('.')
        if (octets.size != 4 || octets.any { it.isEmpty() || !it.all(Char::isDigit) }) {
            return null
        }
        val values = octets.map { it.toIntOrNull()?.takeIf { value -> value in 0..255 } ?: return null }
        return values.joinToString(".")
    }

    private fun isBrowserSpecialUseHost(host: String): Boolean =
        host.substringAfterLast('.').lowercase(Locale.US) in BROWSER_SPECIAL_USE_SUFFIXES
}
