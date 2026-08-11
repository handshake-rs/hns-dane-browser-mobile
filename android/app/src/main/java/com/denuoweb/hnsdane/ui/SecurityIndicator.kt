package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.core.SecurityState

/** Maps each security state to a compact toolbar icon while retaining its label for accessibility. */
internal object SecurityIndicator {
    enum class Tone { Ok, Neutral, Warning, Danger }

    internal data class Presentation(
        val iconRes: Int,
        val tone: Tone,
        val labelRes: Int,
    )

    fun forState(state: SecurityState): Presentation = when (state) {
        SecurityState.LocalContent ->
            Presentation(R.drawable.ic_security_info, Tone.Neutral, R.string.security_local_content)
        SecurityState.Syncing ->
            Presentation(R.drawable.ic_security_info, Tone.Neutral, R.string.security_syncing)
        SecurityState.Loading ->
            Presentation(R.drawable.ic_security_info, Tone.Neutral, R.string.security_loading)
        SecurityState.HnsVerified ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_hns_verified)
        SecurityState.HnsViaAuthoritativeDoh ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_hns_via_authoritative_doh)
        SecurityState.HnsViaAuthoritativeDns53 ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_hns_via_authoritative_dns53)
        SecurityState.HnsViaP2pDnsRelay ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_hns_via_p2p_dns_relay)
        SecurityState.HnsViaUserConfiguredRecoveryDoh ->
            Presentation(
                R.drawable.ic_security_lock,
                Tone.Ok,
                R.string.security_hns_via_user_configured_recovery_doh,
            )
        SecurityState.DaneVerified ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_dane_verified)
        SecurityState.DaneViaAuthoritativeDoh ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_dane_via_authoritative_doh)
        SecurityState.DaneViaAuthoritativeDns53 ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_dane_via_authoritative_dns53)
        SecurityState.DaneViaP2pDnsRelay ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_dane_via_p2p_dns_relay)
        SecurityState.DaneViaUserConfiguredRecoveryDoh ->
            Presentation(
                R.drawable.ic_security_lock,
                Tone.Ok,
                R.string.security_dane_via_user_configured_recovery_doh,
            )
        SecurityState.StatelessDane ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_stateless_dane)
        SecurityState.DaneViaIcannDoh ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_dane_via_icann_doh)
        SecurityState.WebPkiOnly ->
            Presentation(R.drawable.ic_security_lock, Tone.Ok, R.string.security_webpki)
        SecurityState.ValidationFailed ->
            Presentation(R.drawable.ic_security_error, Tone.Danger, R.string.security_failed)
        SecurityState.ProofUnavailable ->
            Presentation(R.drawable.ic_security_warning, Tone.Warning, R.string.security_proof_unavailable)
    }

    internal fun toneColor(colors: ThemeColors, tone: Tone): Int = when (tone) {
        Tone.Ok -> colors.securityText
        Tone.Neutral -> colors.secondaryText
        Tone.Warning -> colors.securityWarning
        Tone.Danger -> colors.destructive
    }
}
