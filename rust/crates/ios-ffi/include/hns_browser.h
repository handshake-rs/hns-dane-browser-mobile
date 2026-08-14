#ifndef HNS_BROWSER_H
#define HNS_BROWSER_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define HNS_BROWSER_ABI_VERSION 1u
#define HNS_BROWSER_WALLET_READ_BUNDLE_VERSION 2u
#define HNS_BROWSER_WALLET_NAME_IMPORT_BUNDLE_VERSION 1u

typedef uint32_t HnsBrowserResult;
#define HNS_BROWSER_RESULT_OK 0u
#define HNS_BROWSER_RESULT_INVALID_ARGUMENT 1u
#define HNS_BROWSER_RESULT_INVALID_UTF8 2u
#define HNS_BROWSER_RESULT_NOT_FOUND 3u
#define HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED 4u
#define HNS_BROWSER_RESULT_RUNTIME_ERROR 5u
#define HNS_BROWSER_RESULT_PROXY_ERROR 6u
#define HNS_BROWSER_RESULT_BUFFER_ERROR 7u
#define HNS_BROWSER_RESULT_PANIC 8u
#define HNS_BROWSER_RESULT_NOT_READY 9u

typedef uint32_t HnsBrowserNetwork;
#define HNS_BROWSER_NETWORK_MAINNET 0u
#define HNS_BROWSER_NETWORK_TESTNET 1u
#define HNS_BROWSER_NETWORK_REGTEST 2u

typedef uint32_t HnsBrowserResolutionMode;
#define HNS_BROWSER_RESOLUTION_COMPATIBILITY 0u
#define HNS_BROWSER_RESOLUTION_STRICT 1u

typedef uint32_t HnsBrowserNameClass;
#define HNS_BROWSER_NAME_HNS 0u
#define HNS_BROWSER_NAME_ICANN 1u
#define HNS_BROWSER_NAME_SEARCH 2u

typedef uint32_t HnsBrowserTlsPolicy;
#define HNS_BROWSER_TLS_POLICY_UNKNOWN 0u
#define HNS_BROWSER_TLS_POLICY_DANE 1u
#define HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK 2u

typedef uint32_t HnsBrowserResolverPolicy;
#define HNS_BROWSER_RESOLVER_POLICY_UNKNOWN 0u
#define HNS_BROWSER_RESOLVER_POLICY_HNS_DOH_COMPATIBILITY 1u

typedef uint32_t HnsBrowserSecurityPath;
#define HNS_BROWSER_SECURITY_PATH_UNKNOWN 0u
#define HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DOH 1u
#define HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DNS53 2u
#define HNS_BROWSER_SECURITY_PATH_DANE_THIRD_PARTY_DOH 3u
#define HNS_BROWSER_SECURITY_PATH_STATELESS_DANE 4u
#define HNS_BROWSER_SECURITY_PATH_DANE_ICANN_DOH 5u
#define HNS_BROWSER_SECURITY_PATH_HNS_AUTHORITATIVE_DOH 6u
#define HNS_BROWSER_SECURITY_PATH_HNS_AUTHORITATIVE_DNS53 7u
#define HNS_BROWSER_SECURITY_PATH_HNS_THIRD_PARTY_DOH 8u
#define HNS_BROWSER_SECURITY_PATH_DANE_P2P_DNS_RELAY 9u
#define HNS_BROWSER_SECURITY_PATH_HNS_P2P_DNS_RELAY 10u

typedef uint64_t HnsBrowserRuntimeHandle;
typedef uint64_t HnsBrowserProxyHandle;
typedef uint64_t HnsBrowserWalletHandle;

/* A borrowed byte slice. A null pointer is valid only when len is zero. */
typedef struct HnsBrowserSlice {
    const uint8_t *ptr;
    uint64_t len;
} HnsBrowserSlice;

/*
 * A Rust-owned byte buffer. Treat all fields as opaque after receipt.
 * Release each non-empty buffer exactly once with hns_browser_buffer_free.
 */
typedef struct HnsBrowserBuffer {
    uint8_t *ptr;
    uint64_t len;
    uint64_t allocation_id;
} HnsBrowserBuffer;

/* Versioned runtime creation options. Initialize with the default function. */
typedef struct HnsBrowserRuntimeOptions {
    uint32_t struct_size;
    HnsBrowserNetwork network;
    HnsBrowserSlice data_dir;
    uint64_t sync_timeout_millis;
    uint64_t resource_cache_limit_bytes;
    HnsBrowserResolutionMode resolution_mode;
    uint8_t seed_peers;
    uint8_t stateless_dane_certificates;
    /* Non-standard DNS relay experiment; disabled by default. */
    uint8_t experimental_p2p_dns_relay;
    /* Historical compatibility flag; ignored and forced off. */
    uint8_t legacy_hns_doh_compatibility;
    /* Explicit recursive HNS DoH recovery URL; blank means off. */
    HnsBrowserSlice hns_doh_resolver;
    uint64_t reserved1[2];
} HnsBrowserRuntimeOptions;

/* Versioned live policy. Initialize with the default function. */
typedef struct HnsBrowserPolicy {
    uint32_t struct_size;
    HnsBrowserResolutionMode resolution_mode;
    /* Explicit recursive HNS DoH recovery URL; blank means off. */
    HnsBrowserSlice hns_doh_resolver;
    uint8_t stateless_dane_certificates;
    /* Non-standard DNS relay experiment; disabled by default. */
    uint8_t experimental_p2p_dns_relay;
    /* Historical compatibility flag; ignored and forced off. */
    uint8_t legacy_hns_doh_compatibility;
    uint8_t reserved0[5];
    uint64_t reserved1;
} HnsBrowserPolicy;

/*
 * Credentials are sensitive and intended only for the in-memory WebKit proxy
 * authentication challenge. Do not persist or log them. Release all four
 * buffers with hns_browser_buffer_free.
 */
typedef struct HnsBrowserProxyEndpoint {
    uint32_t struct_size;
    uint16_t port;
    uint16_t reserved0;
    uint64_t generation;
    HnsBrowserBuffer session_id;
    HnsBrowserBuffer realm;
    HnsBrowserBuffer username;
    HnsBrowserBuffer password;
} HnsBrowserProxyEndpoint;

/*
 * One consumed, typed main-frame status. The resolution trace is sensitive.
 * Release host and resolution_trace_json with hns_browser_buffer_free.
 */
typedef struct HnsBrowserProxyStatus {
    uint32_t struct_size;
    HnsBrowserTlsPolicy tls_policy;
    HnsBrowserResolverPolicy resolver_policy;
    HnsBrowserSecurityPath security_path;
    uint64_t generation;
    uint32_t http_status;
    uint32_t reserved0;
    HnsBrowserBuffer host;
    HnsBrowserBuffer resolution_trace_json;
} HnsBrowserProxyStatus;

uint32_t hns_browser_abi_version(void);

HnsBrowserResult hns_browser_core_version(HnsBrowserBuffer *out_version);
HnsBrowserResult hns_browser_diagnostics_json(HnsBrowserBuffer *out_json);

/* Copies the current thread's bounded error text into a Rust-owned buffer. */
HnsBrowserResult hns_browser_last_error(HnsBrowserBuffer *out_error);

/* Rejects stale, double, mismatched, and foreign frees without dereferencing. */
HnsBrowserResult hns_browser_buffer_free(HnsBrowserBuffer buffer);

HnsBrowserResult hns_browser_runtime_options_default(
    HnsBrowserRuntimeOptions *out_options);
HnsBrowserResult hns_browser_policy_default(HnsBrowserPolicy *out_policy);

HnsBrowserResult hns_browser_runtime_create(
    const HnsBrowserRuntimeOptions *options,
    HnsBrowserRuntimeHandle *out_runtime);
HnsBrowserResult hns_browser_runtime_destroy(
    HnsBrowserRuntimeHandle runtime);
HnsBrowserResult hns_browser_runtime_set_policy(
    HnsBrowserRuntimeHandle runtime,
    const HnsBrowserPolicy *policy,
    uint64_t *out_revision);

HnsBrowserResult hns_browser_runtime_sync_once(
    HnsBrowserRuntimeHandle runtime,
    HnsBrowserBuffer *out_status_json);
HnsBrowserResult hns_browser_runtime_sync_status(
    HnsBrowserRuntimeHandle runtime,
    HnsBrowserBuffer *out_status_json);
HnsBrowserResult hns_browser_runtime_add_static_relay_peer(
    HnsBrowserRuntimeHandle runtime,
    HnsBrowserSlice endpoint,
    HnsBrowserBuffer *out_status_json);
HnsBrowserResult hns_browser_runtime_clear_resolver_cache(
    HnsBrowserRuntimeHandle runtime,
    HnsBrowserBuffer *out_status_json);
HnsBrowserResult hns_browser_runtime_install_header_snapshot(
    HnsBrowserRuntimeHandle runtime,
    HnsBrowserSlice snapshot_path,
    HnsBrowserBuffer *out_status_json);
HnsBrowserResult hns_browser_runtime_reset_headers_from_peers(
    HnsBrowserRuntimeHandle runtime,
    HnsBrowserBuffer *out_status_json);
HnsBrowserResult hns_browser_runtime_proof_details(
    HnsBrowserRuntimeHandle runtime,
    HnsBrowserSlice host_or_url,
    HnsBrowserBuffer *out_details_json);

HnsBrowserResult hns_browser_classify_name(
    HnsBrowserSlice input,
    HnsBrowserNameClass *out_class);
/*
 * Canonicalizes one extracted host (not a URL): lowercase IDNA DNS form with
 * no terminal dot, or canonical strict IPv4/IPv6 text. Rejects authorities,
 * ports, legacy numeric IPv4 forms, and malformed input.
 */
HnsBrowserResult hns_browser_canonical_host(
    HnsBrowserSlice input,
    HnsBrowserBuffer *out_host);
HnsBrowserResult hns_browser_hns_root(
    HnsBrowserSlice input,
    HnsBrowserBuffer *out_root);

/*
 * Native wallet controls are intentionally limited to one non-value HNS
 * account. No provider, value-movement, settlement, or marketplace authority
 * is exposed through this ABI.
 */
HnsBrowserResult hns_browser_wallet_create(
    HnsBrowserSlice database_path,
    HnsBrowserSlice database_key,
    HnsBrowserNetwork network,
    uint64_t birthday_height,
    HnsBrowserWalletHandle *out_wallet);
HnsBrowserResult hns_browser_wallet_restore(
    HnsBrowserSlice database_path,
    HnsBrowserSlice database_key,
    HnsBrowserNetwork network,
    uint64_t birthday_height,
    HnsBrowserSlice recovery_phrase,
    HnsBrowserWalletHandle *out_wallet);
HnsBrowserResult hns_browser_wallet_open(
    HnsBrowserSlice database_path,
    HnsBrowserSlice database_key,
    HnsBrowserWalletHandle *out_wallet);
/* Returned JSON is a non-sensitive Rust-owned buffer. */
HnsBrowserResult hns_browser_wallet_status(
    HnsBrowserWalletHandle wallet,
    HnsBrowserBuffer *out_status_json);
HnsBrowserResult hns_browser_wallet_accounts(
    HnsBrowserWalletHandle wallet,
    HnsBrowserBuffer *out_accounts_json);
/*
 * Trusted-native synchronized HNS read composition. The endpoint is fixed to
 * 127.0.0.1 at loopback_port; there is no remote URL/host/proxy input. The
 * caller owns and must wipe the borrowed Authorization bytes. Composition is
 * rejected for a newly created recovery-confirmation controller; reopen the
 * durable wallet after the platform key has been committed first.
 */
HnsBrowserResult hns_browser_wallet_configure_hns_reads(
    HnsBrowserWalletHandle wallet,
    uint16_t loopback_port,
    HnsBrowserSlice authorization);
HnsBrowserResult hns_browser_wallet_has_hns_reads(
    HnsBrowserWalletHandle wallet,
    uint8_t *out_enabled);
/*
 * Returns a private Rust-owned HNWR-v2 bundle containing one exact serialized
 * MobileHnsReadSnapshot with distinct ordinary-payment and name-transfer
 * receive targets. Free it promptly; never log it or expose it to WebKit.
 */
HnsBrowserResult hns_browser_wallet_synchronize_hns_reads(
    HnsBrowserWalletHandle wallet,
    HnsBrowserBuffer *out_snapshot_bundle);
/*
 * Imports exact UTF-8 name text only through the synchronized HNS-read
 * controller, without trimming, lowercasing, IDNA, Unicode normalization, or
 * trailing-dot edits. The input must contain 1..63 UTF-8 bytes. On success,
 * returns a private HNWI-v1 bundle: "HNWI", version 1, zero flags, two zero
 * reserved bytes, a big-endian u32 JSON length, and one exact minimized
 * MobileHnsNameSummary payload of at most 4096 bytes. Every non-success is a C
 * result with empty output. Free successful output promptly; never log it or
 * expose it to WebKit.
 */
HnsBrowserResult hns_browser_wallet_import_hns_name_exact_text(
    HnsBrowserWalletHandle wallet,
    HnsBrowserSlice exact_name,
    HnsBrowserBuffer *out_summary_bundle);
HnsBrowserResult hns_browser_wallet_unlock(
    HnsBrowserWalletHandle wallet,
    HnsBrowserSlice database_key);
HnsBrowserResult hns_browser_wallet_lock(
    HnsBrowserWalletHandle wallet);
/*
 * Takes a newly created wallet's phrase exactly once. The buffer is sensitive:
 * copy only into a dedicated recovery display and free it immediately so Rust
 * wipes its allocation.
 */
HnsBrowserResult hns_browser_wallet_take_recovery_phrase(
    HnsBrowserWalletHandle wallet,
    HnsBrowserBuffer *out_recovery_phrase);
HnsBrowserResult hns_browser_wallet_destroy(
    HnsBrowserWalletHandle wallet);

/*
 * Starts an authenticated whole-WebKit loopback proxy generation. A null
 * scope slice ({NULL, 0}) is ICANN mode and denies every HNS request. A
 * non-null, non-empty slice admits only that exact HNS root and subdomains.
 * A non-null zero-length scope is rejected as ambiguous.
 */
HnsBrowserResult hns_browser_proxy_start(
    HnsBrowserRuntimeHandle runtime,
    HnsBrowserSlice hns_scope_root,
    HnsBrowserProxyHandle *out_proxy);
HnsBrowserResult hns_browser_proxy_endpoint(
    HnsBrowserProxyHandle proxy,
    HnsBrowserProxyEndpoint *out_endpoint);
HnsBrowserResult hns_browser_proxy_matches_instance(
    HnsBrowserProxyHandle proxy,
    HnsBrowserSlice session_id,
    uint64_t generation,
    uint8_t *out_matches);
HnsBrowserResult hns_browser_proxy_matches_authentication_challenge(
    HnsBrowserProxyHandle proxy,
    HnsBrowserSlice host,
    uint16_t port,
    HnsBrowserSlice realm,
    uint8_t *out_matches);
HnsBrowserResult hns_browser_proxy_matches_local_certificate(
    HnsBrowserProxyHandle proxy,
    HnsBrowserSlice host,
    HnsBrowserSlice certificate_der,
    uint8_t *out_matches);

/*
 * Atomically consumes only the latest status matching the canonical host and
 * this live proxy generation. Statuses for other hosts remain isolated.
 */
HnsBrowserResult hns_browser_proxy_take_main_frame_status(
    HnsBrowserProxyHandle proxy,
    HnsBrowserSlice canonical_main_frame_host,
    HnsBrowserProxyStatus *out_status);

/* Immediate, non-blocking credential/certificate/socket revocation. */
HnsBrowserResult hns_browser_proxy_request_stop(
    HnsBrowserProxyHandle proxy);

/* Removes the handle, revokes immediately, then blocks for worker teardown. */
HnsBrowserResult hns_browser_proxy_destroy(
    HnsBrowserProxyHandle proxy);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* HNS_BROWSER_H */
