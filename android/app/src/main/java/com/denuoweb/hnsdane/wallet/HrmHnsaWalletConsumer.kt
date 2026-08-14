package com.denuoweb.hnsdane.wallet

/** Exact version-1 HRM resource profile used by the HNSA draft. */
internal const val HRM_HNSA_NAMED_SERVICE_PROFILE = "hns.named-service/v1"

/**
 * Trusted application selection for one named service.
 *
 * This is only the canonical identity requested by the application. It is not
 * evidence that an HRM resource, delegation, endpoint, or application profile
 * has been validated.
 */
internal class HrmHnsaNamedServiceSelection private constructor(
    val networkId: String,
    val networkMagic: Long,
    val nameHash: String,
    val serviceName: String,
    val applicationProfileId: Int,
) {
    override fun equals(other: Any?): Boolean =
        other is HrmHnsaNamedServiceSelection &&
            networkId == other.networkId &&
            networkMagic == other.networkMagic &&
            nameHash == other.nameHash &&
            serviceName == other.serviceName &&
            applicationProfileId == other.applicationProfileId

    override fun hashCode(): Int {
        var result = networkId.hashCode()
        result = 31 * result + networkMagic.hashCode()
        result = 31 * result + nameHash.hashCode()
        result = 31 * result + serviceName.hashCode()
        result = 31 * result + applicationProfileId
        return result
    }

    override fun toString(): String =
        "HrmHnsaNamedServiceSelection(<redacted>)"

    companion object {
        fun create(
            networkId: String,
            networkMagic: Long,
            nameHash: String,
            serviceName: String,
            applicationProfileId: Int,
        ): HrmHnsaNamedServiceSelection? {
            if (
                HNS_NETWORK_MAGICS[networkId] != networkMagic ||
                !nameHash.isLowerHex(bytes = 32) ||
                !serviceName.isCanonicalHnsaServiceName() ||
                applicationProfileId !in 1..USHORT_MAX
            ) return null
            return HrmHnsaNamedServiceSelection(
                networkId,
                networkMagic,
                nameHash,
                serviceName,
                applicationProfileId,
            )
        }
    }
}

/**
 * Exact durable HRM/HNSA observation asserted by a trusted native broker.
 *
 * Kotlin never constructs this claim from CBOR, DNS, URLs, provider messages,
 * or legacy authority records. The broker guard must compare every field with
 * its sole current subject aggregate while holding its fenced lease through
 * the callback.
 */
internal data class HrmHnsaCurrentAuthorityClaim(
    val selection: HrmHnsaNamedServiceSelection,
    val hrmSequence: ULong,
    val hrmEnvelopeHash: String,
    val authorityRevision: ULong,
    val trustedOperationTime: ULong,
    val operationLeaseGeneration: ULong,
    val serviceResourceId: String,
    val serviceDelegationId: String,
    val serviceGeneration: ULong,
) {
    override fun toString(): String = "HrmHnsaCurrentAuthorityClaim(<redacted>)"
}

/**
 * Broker-owned exact-current guard. Implementations must invoke [operation]
 * synchronously at most once and only while the sole subject aggregate or its
 * namespace-wide fenced operation lease remains current and held.
 */
internal fun interface HrmHnsaCurrentAuthorityGuard {
    fun useIfCurrent(
        claim: HrmHnsaCurrentAuthorityClaim,
        operation: () -> Boolean,
    ): Boolean
}

/**
 * Opaque, broker-verified current `hns.named-service/v1` authority.
 *
 * [adoptBrokerVerified] performs defensive shape checks only. It deliberately
 * does not parse or validate HRM/HNSA cryptography, durable rollback state, or
 * application-profile semantics. A future production source may call it only
 * after a qualified native broker has completed and durably acknowledged all
 * of those steps.
 */
internal class BrokerVerifiedHrmHnsaNamedService private constructor(
    val claim: HrmHnsaCurrentAuthorityClaim,
    val walletAuthority: WalletReadBootstrapAuthority,
    val hrmIssuedAt: ULong,
    val hrmExpiresAt: ULong,
    val serviceNotBefore: ULong,
    val serviceExpiresAt: ULong,
    val delegationNotBefore: ULong,
    val delegationExpiresAt: ULong,
    val serviceControllerKey: String,
    val profileFlags: Int,
    val profileConstraintsHash: String,
    val maxEndpointLifetimeSeconds: Long,
    val allowedEndpointCapabilities: Long,
    val endpointConstraintsHash: String,
    private val currentAuthorityGuard: HrmHnsaCurrentAuthorityGuard,
) {
    private val useLock = Any()
    private var useAvailable = true

    /** Prevents a malformed guard from invoking a dependent operation twice. */
    fun useIfCurrent(operation: (BrokerVerifiedHrmHnsaNamedService) -> Boolean): Boolean {
        val mayAttempt = synchronized(useLock) {
            useAvailable.also { useAvailable = false }
        }
        if (!mayAttempt) return false
        val callbackLock = Any()
        var callbackActive = true
        var callbackInvoked = false
        var callbackSucceeded = false
        val guardSucceeded = runCatching {
            currentAuthorityGuard.useIfCurrent(claim) {
                synchronized(callbackLock) {
                    if (!callbackActive || callbackInvoked) {
                        false
                    } else {
                        callbackInvoked = true
                        operation(this).also { callbackSucceeded = it }
                    }
                }
            }
        }.getOrDefault(false)
        return synchronized(callbackLock) {
            callbackActive = false
            guardSucceeded && callbackInvoked && callbackSucceeded
        }
    }

    fun discard() {
        synchronized(useLock) { useAvailable = false }
    }

    override fun toString(): String =
        "BrokerVerifiedHrmHnsaNamedService(<redacted>)"

    companion object {
        @Suppress("LongParameterList")
        fun adoptBrokerVerified(
            selection: HrmHnsaNamedServiceSelection,
            walletAuthority: WalletReadBootstrapAuthority,
            hrmSequence: ULong,
            hrmEnvelopeHash: String,
            authorityRevision: ULong,
            trustedOperationTime: ULong,
            operationLeaseGeneration: ULong,
            serviceResourceId: String,
            serviceDelegationId: String,
            serviceGeneration: ULong,
            hrmIssuedAt: ULong,
            hrmExpiresAt: ULong,
            serviceNotBefore: ULong,
            serviceExpiresAt: ULong,
            delegationNotBefore: ULong,
            delegationExpiresAt: ULong,
            serviceControllerKey: String,
            profileFlags: Int,
            profileConstraintsHash: String,
            maxEndpointLifetimeSeconds: Long,
            allowedEndpointCapabilities: Long,
            endpointConstraintsHash: String,
            currentAuthorityGuard: HrmHnsaCurrentAuthorityGuard,
        ): BrokerVerifiedHrmHnsaNamedService? {
            if (
                selection.networkId != walletAuthority.networkId ||
                !hrmEnvelopeHash.isLowerHex(bytes = 32) ||
                authorityRevision == 0UL ||
                operationLeaseGeneration == 0UL ||
                !serviceResourceId.isLowerHex(bytes = 32) ||
                !serviceDelegationId.isLowerHex(bytes = 32) ||
                serviceGeneration == 0UL ||
                hrmIssuedAt >= hrmExpiresAt ||
                serviceNotBefore < hrmIssuedAt ||
                serviceExpiresAt > hrmExpiresAt ||
                serviceNotBefore >= serviceExpiresAt ||
                delegationNotBefore < serviceNotBefore ||
                delegationExpiresAt > serviceExpiresAt ||
                delegationNotBefore >= delegationExpiresAt ||
                trustedOperationTime < delegationNotBefore ||
                trustedOperationTime >= delegationExpiresAt ||
                !serviceControllerKey.isCompressedSecp256k1Key() ||
                profileFlags !in 0..USHORT_MAX ||
                !profileConstraintsHash.isLowerHex(bytes = 32) ||
                maxEndpointLifetimeSeconds !in MIN_ENDPOINT_LIFETIME..MAX_ENDPOINT_LIFETIME ||
                allowedEndpointCapabilities !in 0L..UINT_MAX ||
                !endpointConstraintsHash.isLowerHex(bytes = 32)
            ) return null

            val claim = HrmHnsaCurrentAuthorityClaim(
                selection = selection,
                hrmSequence = hrmSequence,
                hrmEnvelopeHash = hrmEnvelopeHash,
                authorityRevision = authorityRevision,
                trustedOperationTime = trustedOperationTime,
                operationLeaseGeneration = operationLeaseGeneration,
                serviceResourceId = serviceResourceId,
                serviceDelegationId = serviceDelegationId,
                serviceGeneration = serviceGeneration,
            )
            return BrokerVerifiedHrmHnsaNamedService(
                claim,
                walletAuthority,
                hrmIssuedAt,
                hrmExpiresAt,
                serviceNotBefore,
                serviceExpiresAt,
                delegationNotBefore,
                delegationExpiresAt,
                serviceControllerKey,
                profileFlags,
                profileConstraintsHash,
                maxEndpointLifetimeSeconds,
                allowedEndpointCapabilities,
                endpointConstraintsHash,
                currentAuthorityGuard,
            )
        }
    }
}

/** Single-use transfer of one broker-issued authority into the wallet. */
internal class HrmHnsaWalletConsumerLease private constructor(
    private var retainedService: BrokerVerifiedHrmHnsaNamedService?,
) : AutoCloseable {
    private val lock = Any()

    fun consumeFor(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority,
        operation: (BrokerVerifiedHrmHnsaNamedService) -> Boolean,
    ): Boolean {
        val service = synchronized(lock) {
            retainedService?.also { retainedService = null }
        } ?: return false
        if (
            service.claim.selection != selection ||
            service.walletAuthority != walletAuthority
        ) {
            service.discard()
            return false
        }
        return service.useIfCurrent(operation)
    }

    override fun close() {
        val service = synchronized(lock) {
            retainedService?.also { retainedService = null }
        }
        service?.discard()
    }

    override fun toString(): String = "HrmHnsaWalletConsumerLease(<redacted>)"

    companion object {
        fun takeOwnership(
            service: BrokerVerifiedHrmHnsaNamedService,
        ): HrmHnsaWalletConsumerLease = HrmHnsaWalletConsumerLease(service)
    }
}

/**
 * Moves one authority for the exact trusted selection and live wallet.
 * Sources must not derive either identity from a URL, endpoint record,
 * provider message, intent, preference, pasteboard, or renderer input.
 */
internal fun interface HrmHnsaWalletConsumerSource {
    fun take(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority,
    ): HrmHnsaWalletConsumerLease?
}

/** Shipping builds have no HRM broker or recognized wallet application profile. */
internal object UnavailableHrmHnsaWalletConsumerSource : HrmHnsaWalletConsumerSource {
    override fun take(
        selection: HrmHnsaNamedServiceSelection,
        walletAuthority: WalletReadBootstrapAuthority,
    ): HrmHnsaWalletConsumerLease? = null
}

/** Current wallet/application facts re-read around acquisition and under guard. */
internal data class HrmHnsaWalletConsumerState(
    val selection: HrmHnsaNamedServiceSelection?,
    val walletAuthority: WalletReadBootstrapAuthority?,
    val foreground: Boolean,
    val protectedStorageAvailable: Boolean,
    val reopenedDurableWallet: Boolean,
    val confirmedPersistentWallet: Boolean,
    val hasUnconfirmedRecovery: Boolean,
    val operationInFlight: Boolean,
    val retirementBlocked: Boolean,
)

internal fun hrmHnsaWalletConsumerMayUse(
    expectedSelection: HrmHnsaNamedServiceSelection,
    expectedWalletAuthority: WalletReadBootstrapAuthority,
    current: HrmHnsaWalletConsumerState,
): Boolean =
    current.selection == expectedSelection &&
        current.walletAuthority == expectedWalletAuthority &&
        expectedSelection.networkId == expectedWalletAuthority.networkId &&
        expectedWalletAuthority.hasCurrentStorageLease() &&
        current.foreground &&
        current.protectedStorageAvailable &&
        current.reopenedDurableWallet &&
        current.confirmedPersistentWallet &&
        !current.hasUnconfirmedRecovery &&
        !current.operationInFlight &&
        !current.retirementBlocked

/**
 * Fails closed before source acquisition, after its potentially re-entrant
 * callback, and once more while the broker holds exact current HRM authority.
 */
internal fun attemptHrmHnsaWalletConsumerUse(
    expectedSelection: HrmHnsaNamedServiceSelection,
    expectedWalletAuthority: WalletReadBootstrapAuthority,
    source: HrmHnsaWalletConsumerSource,
    currentState: () -> HrmHnsaWalletConsumerState,
    operation: (BrokerVerifiedHrmHnsaNamedService) -> Boolean,
): Boolean {
    val initial = runCatching(currentState).getOrNull() ?: return false
    if (
        !hrmHnsaWalletConsumerMayUse(
            expectedSelection,
            expectedWalletAuthority,
            initial,
        )
    ) return false
    val lease = runCatching {
        source.take(expectedSelection, expectedWalletAuthority)
    }.getOrNull() ?: return false
    lease.use {
        val afterSource = runCatching(currentState).getOrNull() ?: return false
        val currentWalletAuthority = afterSource.walletAuthority
        if (
            currentWalletAuthority == null ||
            !hrmHnsaWalletConsumerMayUse(
                expectedSelection,
                expectedWalletAuthority,
                afterSource,
            )
        ) return false
        return runCatching {
            lease.consumeFor(expectedSelection, currentWalletAuthority) { service ->
                hrmHnsaWalletConsumerMayUse(
                    expectedSelection,
                    expectedWalletAuthority,
                    currentState(),
                ) && operation(service)
            }
        }.getOrDefault(false)
    }
}

private fun String.isLowerHex(bytes: Int): Boolean =
    length == bytes * 2 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String.isCanonicalHnsaServiceName(): Boolean =
    length in 1..63 &&
        first() != '-' &&
        last() != '-' &&
        all { it in 'a'..'z' || it in '0'..'9' || it == '-' }

private fun String.isCompressedSecp256k1Key(): Boolean =
    isLowerHex(bytes = 33) && (startsWith("02") || startsWith("03"))

private val HNS_NETWORK_MAGICS = mapOf(
    "mainnet" to 0x5b6e_f2d3L,
    "testnet" to 0xb152_0dd2L,
    "regtest" to 0xae38_95cfL,
)

private const val USHORT_MAX = 65_535
private const val UINT_MAX = 4_294_967_295L
private const val MIN_ENDPOINT_LIFETIME = 300L
private const val MAX_ENDPOINT_LIFETIME = 604_800L
