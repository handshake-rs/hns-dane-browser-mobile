//! Stable, panic-contained C ABI for the iOS browser shell.
//!
//! The ABI exposes only numeric registry handles and allocator-paired owned
//! buffers. No Rust object address crosses the boundary.

#![cfg_attr(
    not(test),
    deny(clippy::expect_used, clippy::panic, clippy::unwrap_used)
)]
#![deny(unsafe_op_in_unsafe_fn)]

use hns_header_consensus::{HEADER_SIZE, Header, Network};
use hns_light_sync::SyncState;
use hns_mobile_platform_runtime::{
    BrowserNameClass, BrowserProxy, BrowserProxyResolverPolicy, BrowserProxySecurityPath,
    BrowserProxyStatus, BrowserProxyStatusObserver, BrowserProxyTlsPolicy, BrowserRuntime,
    DEFAULT_RESOURCE_CACHE_LIMIT_BYTES, MAX_HNS_DOH_RECOVERY_URL_BYTES, NetworkKind,
    ResolutionMode, RuntimeConfiguration, RuntimePolicy, SyncOptions, browser_hns_root_label,
    canonical_browser_host, classify_browser_name, core_version, diagnostics_json,
    normalize_hns_doh_recovery_url,
};
use hns_wallet_ffi::ServiceErrorCode;
use hns_wallet_mobile::{
    EmbeddedHnsBackend, HnsBootstrapPolicy, HnsClock, HnsDirectDenuoListener,
    HnsDirectDenuoMessage, HnsDirectDenuoPeer, HnsDirectPeerConfig, HnsDirectPeerCoordinator,
    HnsLightFloor, HnsNetwork, HnsNodeRpcBackend, HnsNodeRpcConfig, HnsReadSystemClock,
    MAX_MOBILE_RECOVERY_PHRASE_BYTES, MOBILE_DATABASE_KEY_BYTES, MobileBitcoinDirectConfig,
    MobileBitcoinValueController, MobileDatabaseKey, MobileDenuoSessionController,
    MobileHnsNameSummary, MobileHnsReadController, MobileHnsReadSnapshot, MobileHnsValueController,
    MobileHnsValueIntent, MobilePlatform, MobileRecoveryPhrase, MobileShakedexQuery,
    MobileWalletController, MobileWalletError,
};
use hns_wallet_types::BaseUnits;
use serde_json::{Value, json};
use std::cell::RefCell;
use std::collections::{HashMap, HashSet, VecDeque};
use std::fs::File;
use std::io::{BufReader, Read};
use std::net::{Ipv4Addr, SocketAddr};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Component, Path, PathBuf};
use std::ptr;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, OnceLock, TryLockError};
use std::time::Duration;

pub const HNS_BROWSER_ABI_VERSION: u32 = 1;

pub type HnsBrowserResult = u32;
pub const HNS_BROWSER_RESULT_OK: HnsBrowserResult = 0;
pub const HNS_BROWSER_RESULT_INVALID_ARGUMENT: HnsBrowserResult = 1;
pub const HNS_BROWSER_RESULT_INVALID_UTF8: HnsBrowserResult = 2;
pub const HNS_BROWSER_RESULT_NOT_FOUND: HnsBrowserResult = 3;
pub const HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED: HnsBrowserResult = 4;
pub const HNS_BROWSER_RESULT_RUNTIME_ERROR: HnsBrowserResult = 5;
pub const HNS_BROWSER_RESULT_PROXY_ERROR: HnsBrowserResult = 6;
pub const HNS_BROWSER_RESULT_BUFFER_ERROR: HnsBrowserResult = 7;
pub const HNS_BROWSER_RESULT_PANIC: HnsBrowserResult = 8;
pub const HNS_BROWSER_RESULT_NOT_READY: HnsBrowserResult = 9;

pub type HnsBrowserRuntimeHandle = u64;
pub type HnsBrowserProxyHandle = u64;
pub type HnsBrowserWalletHandle = u64;

const HNS_BROWSER_NETWORK_MAINNET: u32 = 0;
const HNS_BROWSER_NETWORK_TESTNET: u32 = 1;
const HNS_BROWSER_NETWORK_REGTEST: u32 = 2;
const HNS_BROWSER_RESOLUTION_COMPATIBILITY: u32 = 0;
const HNS_BROWSER_RESOLUTION_STRICT: u32 = 1;
const HNS_BROWSER_NAME_HNS: u32 = 0;
const HNS_BROWSER_NAME_ICANN: u32 = 1;
const HNS_BROWSER_NAME_SEARCH: u32 = 2;
const HNS_BROWSER_TLS_POLICY_UNKNOWN: u32 = 0;
const HNS_BROWSER_TLS_POLICY_DANE: u32 = 1;
const HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK: u32 = 2;
const HNS_BROWSER_RESOLVER_POLICY_UNKNOWN: u32 = 0;
const HNS_BROWSER_RESOLVER_POLICY_HNS_DOH_COMPATIBILITY: u32 = 1;
const HNS_BROWSER_SECURITY_PATH_UNKNOWN: u32 = 0;
const HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DOH: u32 = 1;
const HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DNS53: u32 = 2;
const HNS_BROWSER_SECURITY_PATH_DANE_THIRD_PARTY_DOH: u32 = 3;
const HNS_BROWSER_SECURITY_PATH_STATELESS_DANE: u32 = 4;
const HNS_BROWSER_SECURITY_PATH_DANE_ICANN_DOH: u32 = 5;
const HNS_BROWSER_SECURITY_PATH_HNS_AUTHORITATIVE_DOH: u32 = 6;
const HNS_BROWSER_SECURITY_PATH_HNS_AUTHORITATIVE_DNS53: u32 = 7;
const HNS_BROWSER_SECURITY_PATH_HNS_THIRD_PARTY_DOH: u32 = 8;
const HNS_BROWSER_SECURITY_PATH_DANE_P2P_DNS_RELAY: u32 = 9;
const HNS_BROWSER_SECURITY_PATH_HNS_P2P_DNS_RELAY: u32 = 10;

const DEFAULT_SYNC_TIMEOUT_MILLIS: u64 = 3_000;
const MAX_SYNC_TIMEOUT_MILLIS: u64 = 10 * 60 * 1_000;
const MAX_RESOURCE_CACHE_LIMIT_BYTES: u64 = 1024 * 1024 * 1024;
const MAX_RUNTIME_HANDLES: usize = 16;
const MAX_PROXY_HANDLES: usize = 64;
const MAX_WALLET_HANDLES: usize = 8;
const MAX_MAIN_FRAME_STATUSES: usize = 64;
const MAX_ALLOCATIONS: usize = 256;
const MAX_ALLOCATED_BYTES: usize = 8 * 1024 * 1024;
const MAX_OUTPUT_BUFFER_BYTES: usize = 1024 * 1024;
const MAX_ERROR_BYTES: usize = 4 * 1024;
const MAX_PATH_BYTES: usize = 4 * 1024;
const MAX_NAME_INPUT_BYTES: usize = 4 * 1024;
const MAX_HOST_BYTES: usize = 253;
const MAX_AUTH_FIELD_BYTES: usize = 4 * 1024;
const MAX_CERTIFICATE_DER_BYTES: usize = 1024 * 1024;
const WALLET_READ_BUNDLE_MAGIC: &[u8; 4] = b"HNWR";
const WALLET_READ_BUNDLE_VERSION: u8 = 2;
const WALLET_READ_BUNDLE_HNS_READ_ONLY: u8 = 1;
const WALLET_READ_BUNDLE_HEADER_BYTES: usize = 12;
const WALLET_NAME_IMPORT_BUNDLE_MAGIC: &[u8; 4] = b"HNWI";
const WALLET_NAME_IMPORT_BUNDLE_VERSION: u8 = 1;
const WALLET_NAME_IMPORT_BUNDLE_FLAGS: u8 = 0;
const WALLET_NAME_IMPORT_BUNDLE_HEADER_BYTES: usize = 12;
const MAX_WALLET_NAME_INPUT_BYTES: usize = 63;
const MAX_WALLET_NAME_IMPORT_JSON_BYTES: usize = 4 * 1024;
const WALLET_HNS_RECEIVE_BUNDLE_MAGIC: &[u8; 4] = b"HNRT";
const WALLET_HNS_RECEIVE_BUNDLE_VERSION: u8 = 1;
const WALLET_BITCOIN_BUNDLE_MAGIC: &[u8; 4] = b"HNBW";
const WALLET_BITCOIN_BUNDLE_VERSION: u8 = 1;
const MAX_WALLET_BITCOIN_JSON_BYTES: usize = 16 * 1024;
const MAX_WALLET_BITCOIN_ADDRESS_BYTES: usize = 128;
const MAX_WALLET_BITCOIN_SATS_BYTES: usize = 20;
const WALLET_VALUE_APPROVAL_BUNDLE_MAGIC: &[u8; 4] = b"HNVP";
const WALLET_VALUE_APPROVAL_BUNDLE_VERSION: u8 = 1;
const WALLET_VALUE_RESULT_BUNDLE_MAGIC: &[u8; 4] = b"HNVX";
const WALLET_VALUE_RESULT_BUNDLE_VERSION: u8 = 1;
const WALLET_SHAKEDEX_QUERY_BUNDLE_MAGIC: &[u8; 4] = b"HNVQ";
const WALLET_SHAKEDEX_QUERY_BUNDLE_VERSION: u8 = 1;
const WALLET_JSON_BUNDLE_HEADER_BYTES: usize = 12;
const MAX_WALLET_HNS_RECEIVE_JSON_BYTES: usize = 4 * 1024;
const MAX_WALLET_VALUE_APPROVAL_JSON_BYTES: usize = 16 * 1024;
const MAX_WALLET_VALUE_RESULT_JSON_BYTES: usize = 256 * 1024;
const MAX_WALLET_VALUE_RECIPIENT_BYTES: usize = 512;
const MAX_WALLET_BASE_UNITS_BYTES: usize = 39;
/// Closed native value-intent JSON is accepted only from UIKit. This cap is
/// deliberately independent of the larger approval/result envelopes.
const MAX_WALLET_VALUE_INTENT_JSON_BYTES: usize = 8 * 1024;
const MAX_WALLET_SHAKEDEX_QUERY_JSON_BYTES: usize = 4 * 1024;
const MAX_WALLET_SHAKEDEX_RESULT_JSON_BYTES: usize = 256 * 1024;
const MAX_WALLET_DENUO_ENDPOINT_BYTES: usize = 128;
const WALLET_DIRECT_DENUO_STATUS_BUNDLE_MAGIC: &[u8; 4] = b"HNDS";
const WALLET_DIRECT_DENUO_CONNECT_BUNDLE_MAGIC: &[u8; 4] = b"HNDC";
const WALLET_DIRECT_DENUO_BUNDLE_VERSION: u8 = 1;
const WALLET_DIRECT_DENUO_BUNDLE_HEADER_BYTES: usize = 12;
const WALLET_DIRECT_DENUO_STATUS_UNLOCKED: u8 = 1;
const WALLET_DIRECT_DENUO_STATUS_LISTENING: u8 = 1 << 1;
const WALLET_DIRECT_DENUO_STATUS_PAIRED: u8 = 1 << 2;
const WALLET_DIRECT_DENUO_CONNECT_CONNECTED: u8 = 1;
const WALLET_DIRECT_DENUO_CONNECT_REPLACED: u8 = 2;
const WALLET_DIRECT_DENUO_CONNECT_UNAVAILABLE: u8 = 3;
const WALLET_DIRECT_DENUO_CONNECT_LOCKED: u8 = 4;
const WALLET_DIRECT_DENUO_CONNECT_FAILED: u8 = 5;
const WALLET_DIRECT_DENUO_CONNECT_EXCHANGE_FAILED: u8 = 6;
const WALLET_ACTION_TOKEN_BYTES: usize = 64;
const HNS_LIGHT_FLOOR_BYTES: usize = 36;
const MAINNET_GENESIS_BOOTSTRAP_MAGIC: &[u8; 11] = b"HNSHDRSNAP1";
const MAINNET_GENESIS_BOOTSTRAP_HEIGHT: u32 = 300_000;
const MAINNET_GENESIS_BOOTSTRAP_BYTES: u64 = 70_800_287;
const MAINNET_GENESIS_BOOTSTRAP_HASH: [u8; 32] = [
    0, 0, 0, 0, 0, 0, 0, 12, 52, 107, 32, 60, 77, 216, 102, 166, 136, 26, 130, 156, 157, 202, 16,
    190, 31, 89, 123, 179, 142, 19, 43, 169,
];
const DIRECT_HNS_MAX_HEADER_ROUNDS_PER_SYNC: usize = 32;
const DIRECT_HNS_MAX_SCAN_CHUNKS_PER_SYNC: usize = 32;
// Match the direct coordinator's single atomic filtered-block request window.
// Cancellation is checked between these calls, so a Stop request can prevent
// every not-yet-started batch instead of allowing the old 2,000-block call to
// begin another internally committed batch.
const DIRECT_HNS_SCAN_BLOCKS_PER_CHUNK: u32 = 64;
const WALLET_HNS_SYNC_CONNECTING: u8 = 1;
const WALLET_HNS_SYNC_HEADERS: u8 = 2;
const WALLET_HNS_SYNC_SCANNING: u8 = 3;
const WALLET_HNS_SYNC_FINALIZING: u8 = 4;
const IOS_DIRECT_DENUO_LISTEN_PORT: u16 = 12_038;
const IOS_DIRECT_DENUO_SOCKET_TIMEOUT: Duration = Duration::from_secs(2);
const WALLET_RPC_CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
const WALLET_RPC_READ_TIMEOUT: Duration = Duration::from_secs(20);
const WALLET_RPC_WRITE_TIMEOUT: Duration = Duration::from_secs(20);

#[repr(C)]
#[derive(Clone, Copy)]
pub struct HnsBrowserSlice {
    pub ptr: *const u8,
    pub len: u64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct HnsBrowserWalletHnsSyncProgress {
    pub struct_size: u32,
    pub stage: u8,
    pub has_scanned_height: u8,
    pub reserved0: u16,
    pub verified_header_height: u64,
    pub birthday_height: u64,
    pub scanned_height: u64,
    pub target_height: u64,
}

impl HnsBrowserWalletHnsSyncProgress {
    const fn empty() -> Self {
        Self {
            struct_size: size_u32::<Self>(),
            stage: 0,
            has_scanned_height: 0,
            reserved0: 0,
            verified_header_height: 0,
            birthday_height: 0,
            scanned_height: 0,
            target_height: 0,
        }
    }
}

impl HnsBrowserSlice {
    const fn empty() -> Self {
        Self {
            ptr: ptr::null(),
            len: 0,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct HnsBrowserBuffer {
    pub ptr: *mut u8,
    pub len: u64,
    pub allocation_id: u64,
}

impl HnsBrowserBuffer {
    const fn empty() -> Self {
        Self {
            ptr: ptr::null_mut(),
            len: 0,
            allocation_id: 0,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct HnsBrowserRuntimeOptions {
    pub struct_size: u32,
    pub network: u32,
    pub data_dir: HnsBrowserSlice,
    pub sync_timeout_millis: u64,
    pub resource_cache_limit_bytes: u64,
    pub resolution_mode: u32,
    pub seed_peers: u8,
    pub stateless_dane_certificates: u8,
    pub experimental_p2p_dns_relay: u8,
    pub legacy_hns_doh_compatibility: u8,
    pub hns_doh_resolver: HnsBrowserSlice,
    pub reserved1: [u64; 2],
}

impl HnsBrowserRuntimeOptions {
    fn defaults() -> Self {
        Self {
            struct_size: size_u32::<Self>(),
            network: HNS_BROWSER_NETWORK_MAINNET,
            data_dir: HnsBrowserSlice::empty(),
            sync_timeout_millis: DEFAULT_SYNC_TIMEOUT_MILLIS,
            resource_cache_limit_bytes: DEFAULT_RESOURCE_CACHE_LIMIT_BYTES as u64,
            resolution_mode: HNS_BROWSER_RESOLUTION_STRICT,
            seed_peers: 1,
            stateless_dane_certificates: 0,
            experimental_p2p_dns_relay: 0,
            legacy_hns_doh_compatibility: 0,
            hns_doh_resolver: HnsBrowserSlice::empty(),
            reserved1: [0; 2],
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct HnsBrowserPolicy {
    pub struct_size: u32,
    pub resolution_mode: u32,
    pub hns_doh_resolver: HnsBrowserSlice,
    pub stateless_dane_certificates: u8,
    pub experimental_p2p_dns_relay: u8,
    pub legacy_hns_doh_compatibility: u8,
    pub reserved0: [u8; 5],
    pub reserved1: u64,
}

impl HnsBrowserPolicy {
    fn defaults() -> Self {
        Self {
            struct_size: size_u32::<Self>(),
            resolution_mode: HNS_BROWSER_RESOLUTION_STRICT,
            hns_doh_resolver: HnsBrowserSlice::empty(),
            stateless_dane_certificates: 0,
            experimental_p2p_dns_relay: 0,
            legacy_hns_doh_compatibility: 0,
            reserved0: [0; 5],
            reserved1: 0,
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct HnsBrowserProxyEndpoint {
    pub struct_size: u32,
    pub port: u16,
    pub reserved0: u16,
    pub generation: u64,
    pub session_id: HnsBrowserBuffer,
    pub realm: HnsBrowserBuffer,
    pub username: HnsBrowserBuffer,
    pub password: HnsBrowserBuffer,
}

impl HnsBrowserProxyEndpoint {
    fn empty() -> Self {
        Self {
            struct_size: size_u32::<Self>(),
            port: 0,
            reserved0: 0,
            generation: 0,
            session_id: HnsBrowserBuffer::empty(),
            realm: HnsBrowserBuffer::empty(),
            username: HnsBrowserBuffer::empty(),
            password: HnsBrowserBuffer::empty(),
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
pub struct HnsBrowserProxyStatus {
    pub struct_size: u32,
    pub tls_policy: u32,
    pub resolver_policy: u32,
    pub security_path: u32,
    pub generation: u64,
    pub http_status: u32,
    pub reserved0: u32,
    pub host: HnsBrowserBuffer,
    pub resolution_trace_json: HnsBrowserBuffer,
}

impl HnsBrowserProxyStatus {
    fn empty() -> Self {
        Self {
            struct_size: size_u32::<Self>(),
            tls_policy: HNS_BROWSER_TLS_POLICY_UNKNOWN,
            resolver_policy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
            security_path: HNS_BROWSER_SECURITY_PATH_UNKNOWN,
            generation: 0,
            http_status: 0,
            reserved0: 0,
            host: HnsBrowserBuffer::empty(),
            resolution_trace_json: HnsBrowserBuffer::empty(),
        }
    }
}

const fn size_u32<T>() -> u32 {
    std::mem::size_of::<T>() as u32
}

struct FfiFailure {
    code: HnsBrowserResult,
    message: &'static str,
}

impl FfiFailure {
    const fn new(code: HnsBrowserResult, message: &'static str) -> Self {
        Self { code, message }
    }

    const fn invalid(message: &'static str) -> Self {
        Self::new(HNS_BROWSER_RESULT_INVALID_ARGUMENT, message)
    }

    const fn internal() -> Self {
        Self::new(
            HNS_BROWSER_RESULT_RUNTIME_ERROR,
            "internal runtime state is unavailable",
        )
    }
}

thread_local! {
    static LAST_ERROR: RefCell<String> = const { RefCell::new(String::new()) };
}

fn bounded_utf8(value: &str, max_bytes: usize) -> String {
    if value.len() <= max_bytes {
        return value.to_owned();
    }
    let mut end = max_bytes;
    while end != 0 && !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_owned()
}

fn set_last_error(message: &str) {
    let message = bounded_utf8(message, MAX_ERROR_BYTES);
    LAST_ERROR.with(|slot| {
        *slot.borrow_mut() = message;
    });
}

fn clear_last_error() {
    LAST_ERROR.with(|slot| slot.borrow_mut().clear());
}

fn last_error_snapshot() -> String {
    LAST_ERROR.with(|slot| slot.borrow().clone())
}

fn contained_set_last_error(message: &str) {
    let _ = catch_unwind(AssertUnwindSafe(|| set_last_error(message)));
}

fn ffi_call(operation: impl FnOnce() -> Result<(), FfiFailure>) -> HnsBrowserResult {
    match catch_unwind(AssertUnwindSafe(|| {
        clear_last_error();
        operation()
    })) {
        Ok(Ok(())) => HNS_BROWSER_RESULT_OK,
        Ok(Err(failure)) => {
            contained_set_last_error(failure.message);
            failure.code
        }
        Err(_) => {
            contained_set_last_error("panic contained at the C ABI boundary");
            HNS_BROWSER_RESULT_PANIC
        }
    }
}

fn ffi_call_preserving_error(
    operation: impl FnOnce() -> Result<(), FfiFailure>,
) -> HnsBrowserResult {
    match catch_unwind(AssertUnwindSafe(operation)) {
        Ok(Ok(())) => HNS_BROWSER_RESULT_OK,
        Ok(Err(failure)) => {
            contained_set_last_error(failure.message);
            failure.code
        }
        Err(_) => {
            contained_set_last_error("panic contained at the C ABI boundary");
            HNS_BROWSER_RESULT_PANIC
        }
    }
}

fn checked_len(len: u64, max: usize) -> Result<usize, FfiFailure> {
    let len = usize::try_from(len).map_err(|_| FfiFailure::invalid("input length is invalid"))?;
    if len > max {
        return Err(FfiFailure::invalid("input exceeds its ABI size limit"));
    }
    Ok(len)
}

unsafe fn input_bytes(slice: HnsBrowserSlice, max: usize) -> Result<Vec<u8>, FfiFailure> {
    let len = checked_len(slice.len, max)?;
    if len == 0 {
        return Ok(Vec::new());
    }
    if slice.ptr.is_null() {
        return Err(FfiFailure::invalid("non-empty input has a null pointer"));
    }
    // SAFETY: The C ABI contract requires a non-null input pointer to remain
    // readable for `len` bytes for the duration of the call. Length is bounded
    // before constructing the slice.
    Ok(unsafe { std::slice::from_raw_parts(slice.ptr, len) }.to_vec())
}

unsafe fn input_str(slice: HnsBrowserSlice, max: usize) -> Result<String, FfiFailure> {
    // SAFETY: Propagates the caller's readable-slice contract.
    String::from_utf8(unsafe { input_bytes(slice, max) }?)
        .map_err(|_| FfiFailure::new(HNS_BROWSER_RESULT_INVALID_UTF8, "input is not valid UTF-8"))
}

unsafe fn required_input_str(slice: HnsBrowserSlice, max: usize) -> Result<String, FfiFailure> {
    // SAFETY: Propagates the caller's readable-slice contract.
    let value = unsafe { input_str(slice, max) }?;
    if value.is_empty() {
        return Err(FfiFailure::invalid("required text input is empty"));
    }
    Ok(value)
}

unsafe fn optional_scope(slice: HnsBrowserSlice) -> Result<Option<String>, FfiFailure> {
    if slice.ptr.is_null() {
        if slice.len == 0 {
            return Ok(None);
        }
        return Err(FfiFailure::invalid(
            "scope length is nonzero with a null pointer",
        ));
    }
    if slice.len == 0 {
        return Err(FfiFailure::invalid(
            "an empty non-null scope is ambiguous; use a null slice for no HNS scope",
        ));
    }
    // SAFETY: The non-null, bounded slice is covered by the caller contract.
    unsafe { required_input_str(slice, MAX_HOST_BYTES) }.map(Some)
}

fn require_output<T>(output: *mut T) -> Result<(), FfiFailure> {
    if output.is_null() {
        Err(FfiFailure::invalid("output pointer is null"))
    } else {
        Ok(())
    }
}

unsafe fn write_output<T>(output: *mut T, value: T) {
    // SAFETY: The caller supplied a non-null, writable output pointer under
    // the C ABI contract; every call validates null before reaching here.
    unsafe { output.write(value) };
}

struct Allocation {
    bytes: Box<[u8]>,
    sensitive: bool,
}

impl Drop for Allocation {
    fn drop(&mut self) {
        if self.sensitive {
            self.bytes.fill(0);
        }
    }
}

#[derive(Default)]
struct AllocationRegistry {
    entries: HashMap<u64, Allocation>,
    total_bytes: usize,
}

static ALLOCATIONS: OnceLock<Mutex<AllocationRegistry>> = OnceLock::new();
static NEXT_ALLOCATION_ID: AtomicU64 = AtomicU64::new(1);

struct OutputValue<'a> {
    bytes: &'a [u8],
    sensitive: bool,
}

fn next_monotonic_id(counter: &AtomicU64) -> Result<u64, FfiFailure> {
    counter
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |current| {
            current.checked_add(1)
        })
        .map_err(|_| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
                "numeric handle space is exhausted",
            )
        })
}

fn allocation_registry() -> &'static Mutex<AllocationRegistry> {
    ALLOCATIONS.get_or_init(|| Mutex::new(AllocationRegistry::default()))
}

fn allocate_outputs(values: &[OutputValue<'_>]) -> Result<Vec<HnsBrowserBuffer>, FfiFailure> {
    let nonempty = values
        .iter()
        .filter(|value| !value.bytes.is_empty())
        .count();
    let additional_bytes = values.iter().try_fold(0usize, |total, value| {
        if value.bytes.len() > MAX_OUTPUT_BUFFER_BYTES {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
                "output exceeds the ABI buffer size limit",
            ));
        }
        total.checked_add(value.bytes.len()).ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
                "output allocation size is exhausted",
            )
        })
    })?;
    let mut registry = allocation_registry()
        .lock()
        .map_err(|_| FfiFailure::internal())?;
    if registry.entries.len().saturating_add(nonempty) > MAX_ALLOCATIONS
        || registry.total_bytes.saturating_add(additional_bytes) > MAX_ALLOCATED_BYTES
    {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
            "owned output buffer registry is full",
        ));
    }

    let mut ids = Vec::with_capacity(nonempty);
    for _ in 0..nonempty {
        ids.push(next_monotonic_id(&NEXT_ALLOCATION_ID)?);
    }
    let mut id_iter = ids.into_iter();
    let mut outputs = Vec::with_capacity(values.len());
    for value in values {
        if value.bytes.is_empty() {
            outputs.push(HnsBrowserBuffer::empty());
            continue;
        }
        let allocation_id = id_iter.next().ok_or_else(FfiFailure::internal)?;
        let mut allocation = Allocation {
            bytes: value.bytes.to_vec().into_boxed_slice(),
            sensitive: value.sensitive,
        };
        let output = HnsBrowserBuffer {
            ptr: allocation.bytes.as_mut_ptr(),
            len: allocation.bytes.len() as u64,
            allocation_id,
        };
        registry.entries.insert(allocation_id, allocation);
        outputs.push(output);
    }
    registry.total_bytes = registry
        .total_bytes
        .checked_add(additional_bytes)
        .ok_or_else(FfiFailure::internal)?;
    Ok(outputs)
}

fn allocate_output(bytes: &[u8], sensitive: bool) -> Result<HnsBrowserBuffer, FfiFailure> {
    let mut outputs = allocate_outputs(&[OutputValue { bytes, sensitive }])?;
    outputs.pop().ok_or_else(FfiFailure::internal)
}

fn free_output(buffer: HnsBrowserBuffer) -> Result<(), FfiFailure> {
    if buffer.allocation_id == 0 {
        if buffer.ptr.is_null() && buffer.len == 0 {
            return Ok(());
        }
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_BUFFER_ERROR,
            "owned buffer token is invalid",
        ));
    }
    let mut registry = allocation_registry()
        .lock()
        .map_err(|_| FfiFailure::internal())?;
    let matches = registry
        .entries
        .get(&buffer.allocation_id)
        .is_some_and(|allocation| {
            allocation.bytes.as_ptr().cast_mut() == buffer.ptr
                && allocation.bytes.len() as u64 == buffer.len
        });
    if !matches {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_BUFFER_ERROR,
            "owned buffer is stale, mismatched, or foreign",
        ));
    }
    let allocation = registry
        .entries
        .remove(&buffer.allocation_id)
        .ok_or_else(FfiFailure::internal)?;
    registry.total_bytes = registry.total_bytes.saturating_sub(allocation.bytes.len());
    drop(allocation);
    Ok(())
}

fn release_allocated_outputs(outputs: &[HnsBrowserBuffer]) {
    for output in outputs {
        let _ = free_output(*output);
    }
}

struct RuntimeEntry {
    runtime: BrowserRuntime,
}

#[derive(Clone)]
struct QueuedMainFrameStatus {
    generation: u64,
    host: String,
    http_status: u16,
    tls_policy: u32,
    resolver_policy: u32,
    security_path: u32,
    resolution_trace_json: String,
}

struct MainFrameStatusMailbox {
    statuses: Mutex<VecDeque<QueuedMainFrameStatus>>,
    accepting: AtomicBool,
}

impl Default for MainFrameStatusMailbox {
    fn default() -> Self {
        Self {
            statuses: Mutex::new(VecDeque::new()),
            accepting: AtomicBool::new(true),
        }
    }
}

impl BrowserProxyStatusObserver for MainFrameStatusMailbox {
    fn observe_status(&self, status: &BrowserProxyStatus) {
        if !status.is_likely_main_frame() || !self.accepting.load(Ordering::Acquire) {
            return;
        }
        let queued = QueuedMainFrameStatus {
            generation: status.generation(),
            host: status.host().to_owned(),
            http_status: status.status_code(),
            tls_policy: tls_policy_code(status.tls_policy()),
            resolver_policy: resolver_policy_code(status.resolver_policy()),
            security_path: security_path_code(status.security_path()),
            resolution_trace_json: status
                .resolution_trace_json()
                .unwrap_or_default()
                .to_owned(),
        };
        let Ok(mut statuses) = self.statuses.lock() else {
            return;
        };
        if !self.accepting.load(Ordering::Acquire) {
            return;
        }
        if let Some(index) = statuses.iter().position(|existing| {
            existing.generation == queued.generation && existing.host == queued.host
        }) {
            statuses.remove(index);
        }
        if statuses.len() == MAX_MAIN_FRAME_STATUSES {
            statuses.pop_front();
        }
        statuses.push_back(queued);
    }
}

struct ProxyEntry {
    runtime_handle: HnsBrowserRuntimeHandle,
    #[cfg(test)]
    policy_revision: u64,
    proxy: BrowserProxy,
    mailbox: Arc<MainFrameStatusMailbox>,
    active: AtomicBool,
}

struct SensitiveBytes(Vec<u8>);

impl Drop for SensitiveBytes {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}

struct WalletEntry {
    controller: NativeWalletController,
    pending_recovery_phrase: Option<SensitiveBytes>,
    hns_reads_installable: bool,
    bitcoin_data_dir: PathBuf,
    active: bool,
}

enum NativeWalletController {
    Lifecycle(MobileWalletController),
    HnsReads(MobileHnsReadController<HnsNodeRpcBackend>),
    /// The iOS wallet's self-contained HNS path. The coordinator owns the
    /// independently verified peer/header/block state, while the value
    /// controller owns wallet evidence, fee observation, and broadcast.
    DirectHnsValue {
        coordinator: HnsDirectPeerCoordinator,
        controller: Box<MobileHnsValueController<EmbeddedHnsBackend>>,
        denuo_sessions: MobileDenuoSessionController,
        denuo_listener: Option<HnsDirectDenuoListener>,
        denuo_peer: Option<HnsDirectDenuoPeer>,
    },
    Failed,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum IosDirectDenuoConnectOutcome {
    Connected,
    Replaced,
    Unavailable,
    Locked,
    ConnectionFailed,
    ExchangeFailed,
}

#[derive(Clone, Debug, Eq, PartialEq)]
struct IosDirectDenuoConnectResult {
    outcome: IosDirectDenuoConnectOutcome,
    peer_endpoint: Option<SocketAddr>,
}

impl NativeWalletController {
    fn with_mut<T>(
        &mut self,
        lifecycle: impl FnOnce(&mut MobileWalletController) -> Result<T, MobileWalletError>,
        hns_reads: impl FnOnce(
            &mut MobileHnsReadController<HnsNodeRpcBackend>,
        ) -> Result<T, MobileWalletError>,
        direct_hns_value: impl FnOnce(
            &mut MobileHnsValueController<EmbeddedHnsBackend>,
        ) -> Result<T, MobileWalletError>,
    ) -> Result<T, MobileWalletError> {
        match self {
            Self::Lifecycle(controller) => lifecycle(controller),
            Self::HnsReads(controller) => hns_reads(controller),
            Self::DirectHnsValue { controller, .. } => direct_hns_value(controller),
            Self::Failed => Err(MobileWalletError::ControllerFailed),
        }
    }

    fn enable_hns_reads(&mut self, backend: HnsNodeRpcBackend) -> Result<(), MobileWalletError> {
        if !matches!(self, Self::Lifecycle(_)) {
            return Err(MobileWalletError::ControllerFailed);
        }
        let current = std::mem::replace(self, Self::Failed);
        match current {
            Self::Lifecycle(controller) => {
                *self = Self::HnsReads(controller.into_hns_reads(backend)?);
                Ok(())
            }
            Self::HnsReads(controller) => {
                *self = Self::HnsReads(controller);
                Err(MobileWalletError::ControllerFailed)
            }
            Self::DirectHnsValue {
                coordinator,
                controller,
                denuo_sessions,
                denuo_listener,
                denuo_peer,
            } => {
                *self = Self::DirectHnsValue {
                    coordinator,
                    controller,
                    denuo_sessions,
                    denuo_listener,
                    denuo_peer,
                };
                Err(MobileWalletError::ControllerFailed)
            }
            Self::Failed => Err(MobileWalletError::ControllerFailed),
        }
    }

    /// Converts a reopened lifecycle controller into the wallet-owned direct
    /// HNS implementation. No RPC URL, peer locator, relay, or website data
    /// crosses this boundary: the published wallet derives its own watch set
    /// and discovers/verifies ordinary Handshake peers itself.
    fn enable_direct_hns_value(
        &mut self,
        database_key: &MobileDatabaseKey,
        rollback_floor: HnsLightFloor,
        bootstrap_snapshot_path: Option<&Path>,
        bitcoin_data_dir: PathBuf,
    ) -> Result<MobileBitcoinValueController, MobileWalletError> {
        let Self::Lifecycle(lifecycle) = self else {
            return Err(MobileWalletError::ControllerFailed);
        };
        let requires_genesis_bootstrap = lifecycle.account_config().network == HnsNetwork::Mainnet
            && lifecycle.account_config().birthday_height
                == u64::from(MAINNET_GENESIS_BOOTSTRAP_HEIGHT);
        if requires_genesis_bootstrap && bootstrap_snapshot_path.is_none() {
            return Err(MobileWalletError::ControllerFailed);
        }
        let bootstrap_headers = if requires_genesis_bootstrap {
            let path = bootstrap_snapshot_path.ok_or(MobileWalletError::ControllerFailed)?;
            Some(
                load_mainnet_genesis_bootstrap(path)
                    .map_err(|_| MobileWalletError::ControllerFailed)?,
            )
        } else {
            None
        };
        // Opening the coordinator can fail for transient filesystem, peer
        // bootstrap, or rollback-floor reasons.  Keep the lifecycle
        // controller intact until that step succeeds so a caller can report
        // the failure and safely retry rather than being left with a poisoned
        // in-memory handle that forces an unnecessary wallet reopen.
        let coordinator = {
            let Self::Lifecycle(lifecycle) = self else {
                return Err(MobileWalletError::ControllerFailed);
            };
            let peer_config = direct_hns_peer_config(lifecycle.account_config().network);
            match bootstrap_headers {
                Some(headers) => lifecycle
                    .open_direct_hns_peer_coordinator_with_floor_and_genesis_bootstrap(
                        database_key,
                        peer_config,
                        rollback_floor,
                        MAINNET_GENESIS_BOOTSTRAP_HEIGHT,
                        MAINNET_GENESIS_BOOTSTRAP_HASH,
                        headers,
                    )?,
                None => lifecycle.open_direct_hns_peer_coordinator_with_floor(
                    database_key,
                    peer_config,
                    rollback_floor,
                )?,
            }
        };
        let lifecycle = match std::mem::replace(self, Self::Failed) {
            Self::Lifecycle(controller) => controller,
            _ => return Err(MobileWalletError::ControllerFailed),
        };
        let backend = coordinator.backend().clone();
        let controller =
            lifecycle.into_hns_value_with_wallet_owned_direct_shakedex(database_key, backend)?;
        let bitcoin_config = MobileBitcoinDirectConfig::for_hns_wallet(
            controller.account_config().network,
            bitcoin_data_dir,
        );
        let bitcoin = controller.direct_bitcoin_value_controller(bitcoin_config)?;
        let denuo_sessions = controller.direct_denuo_session_controller()?;
        *self = Self::DirectHnsValue {
            coordinator,
            controller: Box::new(controller),
            denuo_sessions,
            denuo_listener: None,
            denuo_peer: None,
        };
        Ok(bitcoin)
    }

    const fn has_hns_reads(&self) -> bool {
        matches!(self, Self::HnsReads(_) | Self::DirectHnsValue { .. })
    }

    const fn has_hns_value(&self) -> bool {
        matches!(self, Self::DirectHnsValue { .. })
    }

    fn start_direct_denuo_listener(&mut self) -> bool {
        let Self::DirectHnsValue {
            controller,
            denuo_listener,
            ..
        } = self
        else {
            return true;
        };
        if denuo_listener.is_some() {
            return true;
        }
        if controller.status().map_or(true, |status| status.locked) {
            return false;
        }
        let mut config = HnsDirectPeerConfig::for_network(controller.account_config().network);
        config.connect_timeout = IOS_DIRECT_DENUO_SOCKET_TIMEOUT;
        match HnsDirectDenuoListener::bind(
            config,
            SocketAddr::from((Ipv4Addr::UNSPECIFIED, IOS_DIRECT_DENUO_LISTEN_PORT)),
        ) {
            Ok(listener) => {
                *denuo_listener = Some(listener);
                true
            }
            Err(_) => false,
        }
    }

    fn clear_direct_denuo_transport(&mut self) {
        if let Self::DirectHnsValue {
            denuo_listener,
            denuo_peer,
            ..
        } = self
        {
            denuo_peer.take();
            denuo_listener.take();
        }
    }

    fn direct_denuo_status(&mut self) -> Option<Vec<u8>> {
        let Self::DirectHnsValue {
            controller,
            denuo_listener,
            denuo_peer,
            ..
        } = self
        else {
            return None;
        };
        let unlocked = !controller.status().ok()?.locked;
        let listener_port = denuo_listener
            .as_ref()
            .and_then(|listener| listener.local_addr().ok())
            .map(|address| address.port());
        let peer_endpoint = denuo_peer.as_ref().map(HnsDirectDenuoPeer::address);
        wallet_direct_denuo_status_bundle(unlocked, listener_port, peer_endpoint)
    }

    fn prepare_btc_for_hns_offer(
        &mut self,
        confirmed_sats: u64,
        btc_amount_sats: u64,
        hns_amount_dollarydoos: u64,
        bitcoin_fee_reserve_sats: u64,
        listing_lifetime_seconds: u64,
    ) -> Result<hns_wallet_mobile::MobileBtcForHnsOfferApproval, MobileWalletError> {
        let Self::DirectHnsValue { denuo_sessions, .. } = self else {
            return Err(MobileWalletError::ControllerFailed);
        };
        denuo_sessions.prepare_btc_for_hns_offer(
            confirmed_sats,
            btc_amount_sats,
            hns_amount_dollarydoos,
            bitcoin_fee_reserve_sats,
            listing_lifetime_seconds,
            HnsReadSystemClock.now_unix()?,
        )
    }

    fn approve_btc_for_hns_offer(
        &mut self,
        action_token: &str,
    ) -> Result<hns_wallet_mobile::MobileBtcForHnsOfferSummary, MobileWalletError> {
        let Self::DirectHnsValue {
            denuo_sessions,
            denuo_peer,
            ..
        } = self
        else {
            return Err(MobileWalletError::ControllerFailed);
        };
        let now_unix = HnsReadSystemClock.now_unix()?;
        let summary = denuo_sessions.approve_btc_for_hns_offer(action_token, now_unix)?;
        if let Some(peer) = denuo_peer.as_mut() {
            denuo_sessions.announce_direct_offer_inventory(peer, now_unix)?;
        }
        Ok(summary)
    }

    fn reject_btc_for_hns_offer(&mut self, action_token: &str) -> Result<(), MobileWalletError> {
        let Self::DirectHnsValue { denuo_sessions, .. } = self else {
            return Err(MobileWalletError::ControllerFailed);
        };
        denuo_sessions.reject_btc_for_hns_offer(action_token)
    }

    fn local_btc_for_hns_offers(
        &self,
    ) -> Result<Vec<hns_wallet_mobile::MobileBtcForHnsOfferSummary>, MobileWalletError> {
        let Self::DirectHnsValue { denuo_sessions, .. } = self else {
            return Err(MobileWalletError::ControllerFailed);
        };
        denuo_sessions.local_btc_for_hns_offers(HnsReadSystemClock.now_unix()?)
    }

    fn cancel_btc_for_hns_offer(&mut self, offer_id: &str) -> Result<(), MobileWalletError> {
        let Self::DirectHnsValue {
            denuo_sessions,
            denuo_peer,
            ..
        } = self
        else {
            return Err(MobileWalletError::ControllerFailed);
        };
        let now_unix = HnsReadSystemClock.now_unix()?;
        denuo_sessions.cancel_local_btc_for_hns_offer(offer_id, now_unix)?;
        if let Some(peer) = denuo_peer.as_mut() {
            denuo_sessions.announce_direct_offer_cancellation(peer, offer_id)?;
        }
        Ok(())
    }

    fn disconnect_direct_denuo_peer(&mut self) -> bool {
        let Self::DirectHnsValue { denuo_peer, .. } = self else {
            return false;
        };
        denuo_peer.take().is_some()
    }

    fn service_direct_denuo_once(&mut self) -> bool {
        let Self::DirectHnsValue {
            coordinator,
            controller,
            denuo_sessions,
            denuo_listener,
            denuo_peer,
        } = self
        else {
            return false;
        };
        let Ok(now_unix) = HnsReadSystemClock.now_unix() else {
            return false;
        };
        if let Some(peer) = denuo_peer.as_mut() {
            let accepted = match peer.receive_denuo_message(now_unix) {
                Ok(HnsDirectDenuoMessage::NameMarket {
                    request_id,
                    message,
                }) => controller
                    .service_wallet_owned_direct_shakedex_message(peer, request_id, message)
                    .is_ok(),
                Ok(HnsDirectDenuoMessage::CrossChain { envelope }) => denuo_sessions
                    .service_direct_envelope(peer, envelope.as_slice(), now_unix)
                    .is_ok(),
                Err(_) => false,
            };
            if accepted {
                return true;
            }
            denuo_peer.take();
            return false;
        }
        let Some(listener) = denuo_listener.as_ref() else {
            return false;
        };
        let Ok(floor) = coordinator.rollback_floor() else {
            return false;
        };
        let mut peer = match listener.accept_next(floor.height, now_unix) {
            Ok(Some(peer)) => peer,
            Ok(None) | Err(_) => return false,
        };
        if controller
            .begin_wallet_owned_direct_shakedex(&mut peer)
            .and_then(|_| controller.announce_wallet_owned_direct_shakedex(&mut peer))
            .and_then(|_| denuo_sessions.announce_direct_offer_inventory(&mut peer, now_unix))
            .is_err()
        {
            return false;
        }
        *denuo_peer = Some(peer);
        true
    }

    fn connect_direct_denuo_peer(&mut self, address: SocketAddr) -> IosDirectDenuoConnectResult {
        let Self::DirectHnsValue {
            coordinator,
            controller,
            denuo_sessions,
            denuo_peer,
            ..
        } = self
        else {
            return IosDirectDenuoConnectResult {
                outcome: IosDirectDenuoConnectOutcome::Unavailable,
                peer_endpoint: None,
            };
        };
        if controller.status().map_or(true, |status| status.locked) {
            return IosDirectDenuoConnectResult {
                outcome: IosDirectDenuoConnectOutcome::Locked,
                peer_endpoint: None,
            };
        }
        let (Ok(now_unix), Ok(floor)) =
            (HnsReadSystemClock.now_unix(), coordinator.rollback_floor())
        else {
            return IosDirectDenuoConnectResult {
                outcome: IosDirectDenuoConnectOutcome::ConnectionFailed,
                peer_endpoint: None,
            };
        };
        let mut config = HnsDirectPeerConfig::for_network(controller.account_config().network);
        config.connect_timeout = IOS_DIRECT_DENUO_SOCKET_TIMEOUT;
        config.allow_private_addresses = true;
        config.static_peers.push(address);
        let mut peer = match HnsDirectDenuoPeer::connect(&config, address, floor.height, now_unix) {
            Ok(peer) => peer,
            Err(_) => {
                return IosDirectDenuoConnectResult {
                    outcome: IosDirectDenuoConnectOutcome::ConnectionFailed,
                    peer_endpoint: None,
                };
            }
        };
        if controller
            .begin_wallet_owned_direct_shakedex(&mut peer)
            .and_then(|_| controller.announce_wallet_owned_direct_shakedex(&mut peer))
            .and_then(|_| denuo_sessions.announce_direct_offer_inventory(&mut peer, now_unix))
            .is_err()
        {
            return IosDirectDenuoConnectResult {
                outcome: IosDirectDenuoConnectOutcome::ExchangeFailed,
                peer_endpoint: None,
            };
        }
        let outcome = if denuo_peer.is_some() {
            IosDirectDenuoConnectOutcome::Replaced
        } else {
            IosDirectDenuoConnectOutcome::Connected
        };
        let endpoint = peer.address();
        *denuo_peer = Some(peer);
        IosDirectDenuoConnectResult {
            outcome,
            peer_endpoint: Some(endpoint),
        }
    }

    fn lock_fail_closed(&mut self) {
        match self {
            Self::Lifecycle(controller) => {
                let _ = controller.lock();
            }
            Self::HnsReads(controller) => {
                let _ = controller.lock();
            }
            Self::DirectHnsValue {
                controller,
                denuo_listener,
                denuo_peer,
                ..
            } => {
                denuo_peer.take();
                denuo_listener.take();
                let _ = controller.lock();
            }
            Self::Failed => {}
        }
    }

    const fn is_lifecycle(&self) -> bool {
        matches!(self, Self::Lifecycle(_))
    }
}

impl ProxyEntry {
    fn ensure_active(&self) -> Result<(), FfiFailure> {
        if !self.active.load(Ordering::Acquire)
            || self.proxy.is_stop_requested()
            || self.proxy.is_stopped()
        {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_FOUND,
                "proxy generation is inactive",
            ));
        }
        Ok(())
    }

    fn request_stop(&self) {
        self.active.store(false, Ordering::Release);
        self.mailbox.accepting.store(false, Ordering::Release);
        // Revoke the shared endpoint, credentials, pins, sockets, and backend
        // work without waiting for worker joins or in-flight FFI reads.
        self.proxy.request_stop();
        match self.mailbox.statuses.try_lock() {
            Ok(mut statuses) => statuses.clear(),
            Err(TryLockError::Poisoned(poisoned)) => poisoned.into_inner().clear(),
            Err(TryLockError::WouldBlock) => {}
        }
    }

    fn blocking_stop(&self) {
        self.request_stop();
        self.proxy.stop();
    }
}

struct ProxyStartReservation {
    runtime_handle: HnsBrowserRuntimeHandle,
}

struct WalletStartReservation {
    handle: HnsBrowserWalletHandle,
    active: bool,
}

impl Drop for WalletStartReservation {
    fn drop(&mut self) {
        if !self.active {
            return;
        }
        let mut registry = match handle_registry().lock() {
            Ok(registry) => registry,
            Err(poisoned) => poisoned.into_inner(),
        };
        registry.starting_wallets = registry.starting_wallets.saturating_sub(1);
    }
}

impl Drop for ProxyStartReservation {
    fn drop(&mut self) {
        let mut registry = match handle_registry().lock() {
            Ok(registry) => registry,
            Err(poisoned) => poisoned.into_inner(),
        };
        registry
            .starting_proxy_runtimes
            .remove(&self.runtime_handle);
    }
}

#[derive(Default)]
struct HandleRegistry {
    runtimes: HashMap<HnsBrowserRuntimeHandle, Arc<RuntimeEntry>>,
    proxies: HashMap<HnsBrowserProxyHandle, Arc<ProxyEntry>>,
    wallets: HashMap<HnsBrowserWalletHandle, Arc<Mutex<WalletEntry>>>,
    wallet_hns_sync_controls: HashMap<HnsBrowserWalletHandle, Arc<WalletHnsSyncControl>>,
    wallet_bitcoin_controls: HashMap<HnsBrowserWalletHandle, Arc<WalletBitcoinControl>>,
    starting_proxy_runtimes: HashSet<HnsBrowserRuntimeHandle>,
    starting_wallets: usize,
}

#[derive(Default)]
struct WalletBitcoinControl {
    controller: Mutex<Option<MobileBitcoinValueController>>,
    shutdown: Mutex<Option<hns_wallet_mobile::MobileBitcoinShutdownHandle>>,
    progress: Mutex<Option<hns_wallet_mobile::MobileBitcoinSyncProgressHandle>>,
    activity: Mutex<WalletBitcoinSyncActivityState>,
}

#[derive(Default)]
struct WalletBitcoinSyncActivityState {
    active: bool,
    cancellation_requested: bool,
}

impl WalletBitcoinSyncActivityState {
    fn begin(&mut self) -> bool {
        if self.active {
            return false;
        }
        self.active = true;
        self.cancellation_requested = false;
        true
    }

    fn request_cancellation(&mut self) -> bool {
        if !self.active {
            return false;
        }
        self.cancellation_requested = true;
        true
    }

    fn finish(&mut self) {
        self.active = false;
        self.cancellation_requested = false;
    }
}

struct WalletBitcoinSyncActivity {
    control: Arc<WalletBitcoinControl>,
}

impl Drop for WalletBitcoinSyncActivity {
    fn drop(&mut self) {
        if let Ok(mut activity) = self.control.activity.lock() {
            activity.finish();
        }
    }
}

impl WalletBitcoinControl {
    fn request_shutdown(&self) {
        if let Ok(mut current) = self.shutdown.lock()
            && let Some(handle) = current.take()
        {
            let _ = handle.request_shutdown();
        }
    }

    fn replace_runtime_handles(&self, controller: &MobileBitcoinValueController) {
        if let Ok(mut current) = self.shutdown.lock() {
            *current = controller.shutdown_handle();
        }
        if let Ok(mut current) = self.progress.lock() {
            *current = controller.sync_progress_handle();
        }
    }

    fn clear_runtime_handles(&self) {
        if let Ok(mut current) = self.shutdown.lock() {
            *current = None;
        }
        if let Ok(mut current) = self.progress.lock() {
            *current = None;
        }
    }
}

#[derive(Default)]
struct WalletHnsSyncControl {
    progress: Mutex<Option<HnsBrowserWalletHnsSyncProgress>>,
    activity: Mutex<WalletHnsSyncActivityState>,
}

#[derive(Default)]
struct WalletHnsSyncActivityState {
    active: bool,
    cancellation_requested: bool,
}

struct WalletHnsSyncActivity {
    control: Arc<WalletHnsSyncControl>,
}

impl Drop for WalletHnsSyncActivity {
    fn drop(&mut self) {
        if let Ok(mut activity) = self.control.activity.lock() {
            activity.cancellation_requested = false;
            activity.active = false;
        }
    }
}

static HANDLES: OnceLock<Mutex<HandleRegistry>> = OnceLock::new();
// Runtime, proxy, and wallet handles share one monotonic namespace so
// accidental cross-type use cannot alias a simultaneously live object.
static NEXT_OBJECT_HANDLE: AtomicU64 = AtomicU64::new(1);

fn handle_registry() -> &'static Mutex<HandleRegistry> {
    HANDLES.get_or_init(|| Mutex::new(HandleRegistry::default()))
}

fn runtime_entry(handle: HnsBrowserRuntimeHandle) -> Result<Arc<RuntimeEntry>, FfiFailure> {
    if handle == 0 {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_NOT_FOUND,
            "runtime handle is invalid or stale",
        ));
    }
    handle_registry()
        .lock()
        .map_err(|_| FfiFailure::internal())?
        .runtimes
        .get(&handle)
        .cloned()
        .ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_FOUND,
                "runtime handle is invalid or stale",
            )
        })
}

fn proxy_entry(handle: HnsBrowserProxyHandle) -> Result<Arc<ProxyEntry>, FfiFailure> {
    if handle == 0 {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_NOT_FOUND,
            "proxy handle is invalid or stale",
        ));
    }
    handle_registry()
        .lock()
        .map_err(|_| FfiFailure::internal())?
        .proxies
        .get(&handle)
        .cloned()
        .ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_FOUND,
                "proxy handle is invalid or stale",
            )
        })
}

fn wallet_entry(handle: HnsBrowserWalletHandle) -> Result<Arc<Mutex<WalletEntry>>, FfiFailure> {
    if handle == 0 {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_NOT_FOUND,
            "wallet handle is invalid or stale",
        ));
    }
    handle_registry()
        .lock()
        .map_err(|_| FfiFailure::internal())?
        .wallets
        .get(&handle)
        .cloned()
        .ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_FOUND,
                "wallet handle is invalid or stale",
            )
        })
}

fn wallet_hns_sync_control_entry(
    handle: HnsBrowserWalletHandle,
) -> Result<Arc<WalletHnsSyncControl>, FfiFailure> {
    if handle == 0 {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_NOT_FOUND,
            "wallet handle is invalid or stale",
        ));
    }
    handle_registry()
        .lock()
        .map_err(|_| FfiFailure::internal())?
        .wallet_hns_sync_controls
        .get(&handle)
        .cloned()
        .ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_FOUND,
                "wallet handle is invalid or stale",
            )
        })
}

fn wallet_bitcoin_control_entry(
    handle: HnsBrowserWalletHandle,
) -> Result<Arc<WalletBitcoinControl>, FfiFailure> {
    if handle == 0 {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_NOT_FOUND,
            "wallet handle is invalid or stale",
        ));
    }
    handle_registry()
        .lock()
        .map_err(|_| FfiFailure::internal())?
        .wallet_bitcoin_controls
        .get(&handle)
        .cloned()
        .ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_FOUND,
                "wallet handle is invalid or stale",
            )
        })
}

fn reserve_wallet_start() -> Result<WalletStartReservation, FfiFailure> {
    let mut registry = handle_registry()
        .lock()
        .map_err(|_| FfiFailure::internal())?;
    if registry
        .wallets
        .len()
        .saturating_add(registry.starting_wallets)
        >= MAX_WALLET_HANDLES
    {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
            "wallet handle registry is full",
        ));
    }
    let handle = next_monotonic_id(&NEXT_OBJECT_HANDLE)?;
    registry.starting_wallets += 1;
    Ok(WalletStartReservation {
        handle,
        active: true,
    })
}

fn insert_wallet(
    entry: WalletEntry,
    mut reservation: WalletStartReservation,
) -> Result<HnsBrowserWalletHandle, FfiFailure> {
    let mut registry = handle_registry()
        .lock()
        .map_err(|_| FfiFailure::internal())?;
    if registry.starting_wallets == 0 || registry.wallets.contains_key(&reservation.handle) {
        return Err(FfiFailure::internal());
    }
    registry.starting_wallets -= 1;
    let handle = reservation.handle;
    reservation.active = false;
    registry.wallets.insert(handle, Arc::new(Mutex::new(entry)));
    registry
        .wallet_hns_sync_controls
        .insert(handle, Arc::new(WalletHnsSyncControl::default()));
    registry
        .wallet_bitcoin_controls
        .insert(handle, Arc::new(WalletBitcoinControl::default()));
    Ok(handle)
}

fn ensure_wallet_active(entry: &WalletEntry) -> Result<(), FfiFailure> {
    if !entry.active {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_NOT_FOUND,
            "wallet handle is invalid or stale",
        ));
    }
    Ok(())
}

/// Kyoto's compact-filter state is an app-private sibling of the encrypted
/// wallet database. The exact derivation also prevents bounded simultaneous
/// handles from aliasing one another's Bitcoin journal.
fn ios_wallet_bitcoin_data_dir(database_path: &Path) -> PathBuf {
    let mut data_dir = database_path.to_path_buf();
    data_dir.set_extension("bitcoin-kyoto");
    data_dir
}

fn wallet_network(value: u32) -> Result<HnsNetwork, FfiFailure> {
    match value {
        HNS_BROWSER_NETWORK_MAINNET => Ok(HnsNetwork::Mainnet),
        HNS_BROWSER_NETWORK_TESTNET => Ok(HnsNetwork::Testnet),
        HNS_BROWSER_NETWORK_REGTEST => Ok(HnsNetwork::Regtest),
        _ => Err(FfiFailure::invalid("wallet network value is unsupported")),
    }
}

fn direct_hns_peer_config(network: HnsNetwork) -> HnsDirectPeerConfig {
    let mut config = HnsDirectPeerConfig::for_network(network);
    if matches!(network, HnsNetwork::Mainnet | HnsNetwork::Testnet) {
        // Mainnet/testnet discovery is allowed to replace a bounded pool of
        // candidates. The direct wallet still requires independently agreed
        // headers before it treats any peer as chain authority.
        config.target_peers = 12;
        config.connect_timeout = Duration::from_secs(30);
    }
    config
}

unsafe fn wallet_direct_hns_floor(slice: HnsBrowserSlice) -> Result<HnsLightFloor, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let mut bytes = unsafe { input_bytes(slice, HNS_LIGHT_FLOOR_BYTES) }?;
    if bytes.len() != HNS_LIGHT_FLOOR_BYTES {
        bytes.fill(0);
        return Err(FfiFailure::invalid(
            "wallet direct HNS rollback floor is invalid",
        ));
    }
    let height = u32::from_be_bytes(bytes[..4].try_into().map_err(|_| FfiFailure::internal())?);
    let mut chainwork = [0_u8; 32];
    chainwork.copy_from_slice(&bytes[4..]);
    bytes.fill(0);
    Ok(HnsLightFloor { height, chainwork })
}

fn wallet_direct_hns_floor_bytes(floor: HnsLightFloor) -> [u8; HNS_LIGHT_FLOOR_BYTES] {
    let mut output = [0_u8; HNS_LIGHT_FLOOR_BYTES];
    output[..4].copy_from_slice(&floor.height.to_be_bytes());
    output[4..].copy_from_slice(&floor.chainwork);
    output
}

unsafe fn wallet_optional_snapshot_path(
    slice: HnsBrowserSlice,
) -> Result<Option<PathBuf>, FfiFailure> {
    if slice.ptr.is_null() && slice.len == 0 {
        return Ok(None);
    }
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let path = unsafe { required_input_str(slice, MAX_PATH_BYTES) }?;
    let path = PathBuf::from(path);
    if !path.is_absolute()
        || path
            .components()
            .any(|component| matches!(component, Component::ParentDir))
    {
        return Err(FfiFailure::invalid(
            "wallet header bootstrap path is invalid",
        ));
    }
    Ok(Some(path))
}

fn read_exact_array<const N: usize>(reader: &mut impl Read) -> Result<[u8; N], FfiFailure> {
    let mut bytes = [0_u8; N];
    reader.read_exact(&mut bytes).map_err(|_| {
        FfiFailure::new(
            HNS_BROWSER_RESULT_INVALID_ARGUMENT,
            "wallet header bootstrap is truncated",
        )
    })?;
    Ok(bytes)
}

/// Parse the same constant-pinned direct-wallet snapshot used by Android.
/// The outer app resource is only an accelerator: every header is still
/// independently replayed and checked by the wallet coordinator before it is
/// committed as local authority.
fn load_mainnet_genesis_bootstrap(path: &Path) -> Result<Vec<Header>, FfiFailure> {
    let metadata = std::fs::metadata(path).map_err(|_| {
        FfiFailure::new(
            HNS_BROWSER_RESULT_INVALID_ARGUMENT,
            "wallet header bootstrap is unreadable",
        )
    })?;
    if !metadata.is_file() || metadata.len() != MAINNET_GENESIS_BOOTSTRAP_BYTES {
        return Err(FfiFailure::invalid(
            "wallet header bootstrap has an unexpected length",
        ));
    }
    let file = File::open(path).map_err(|_| {
        FfiFailure::new(
            HNS_BROWSER_RESULT_INVALID_ARGUMENT,
            "wallet header bootstrap cannot be opened",
        )
    })?;
    let mut reader = BufReader::new(file);
    if read_exact_array::<11>(&mut reader)? != *MAINNET_GENESIS_BOOTSTRAP_MAGIC {
        return Err(FfiFailure::invalid(
            "wallet header bootstrap has an invalid magic",
        ));
    }
    let target_height = u32::from_be_bytes(read_exact_array::<4>(&mut reader)?);
    let header_count = u32::from_be_bytes(read_exact_array::<4>(&mut reader)?);
    let target_hash = read_exact_array::<32>(&mut reader)?;
    if target_height != MAINNET_GENESIS_BOOTSTRAP_HEIGHT
        || header_count != target_height.saturating_add(1)
        || target_hash != MAINNET_GENESIS_BOOTSTRAP_HASH
    {
        return Err(FfiFailure::invalid(
            "wallet header bootstrap metadata does not match this app",
        ));
    }
    let genesis = Header::decode(&read_exact_array::<HEADER_SIZE>(&mut reader)?).map_err(|_| {
        FfiFailure::invalid("wallet header bootstrap has an invalid genesis header")
    })?;
    if genesis.block_hash() != Network::Mainnet.parameters().genesis_hash {
        return Err(FfiFailure::invalid(
            "wallet header bootstrap has a non-mainnet genesis header",
        ));
    }
    let mut headers = Vec::with_capacity(target_height as usize);
    for _ in 0..target_height {
        let header =
            Header::decode(&read_exact_array::<HEADER_SIZE>(&mut reader)?).map_err(|_| {
                FfiFailure::invalid("wallet header bootstrap contains an invalid header")
            })?;
        headers.push(header);
    }
    let mut trailing = [0_u8; 1];
    if reader
        .read(&mut trailing)
        .map_err(|_| FfiFailure::invalid("wallet header bootstrap could not be finalized"))?
        != 0
    {
        return Err(FfiFailure::invalid(
            "wallet header bootstrap has trailing data",
        ));
    }
    Ok(headers)
}

unsafe fn wallet_database_key(slice: HnsBrowserSlice) -> Result<MobileDatabaseKey, FfiFailure> {
    let len = checked_len(slice.len, MOBILE_DATABASE_KEY_BYTES)?;
    if len != MOBILE_DATABASE_KEY_BYTES || slice.ptr.is_null() {
        return Err(FfiFailure::invalid(
            "wallet database key must be exactly 32 bytes",
        ));
    }
    // SAFETY: The C ABI contract requires the non-null key pointer to remain
    // readable for exactly the validated length for the duration of the call.
    let key = unsafe { std::slice::from_raw_parts(slice.ptr, len) };
    MobileDatabaseKey::from_slice(key)
        .map_err(|_| FfiFailure::invalid("wallet database key is invalid"))
}

unsafe fn wallet_recovery_phrase(
    slice: HnsBrowserSlice,
) -> Result<MobileRecoveryPhrase, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let bytes = unsafe { input_bytes(slice, MAX_MOBILE_RECOVERY_PHRASE_BYTES) }?;
    if bytes.is_empty() {
        return Err(FfiFailure::invalid("wallet recovery phrase is empty"));
    }
    let phrase = match String::from_utf8(bytes) {
        Ok(phrase) => phrase,
        Err(error) => {
            let mut bytes = error.into_bytes();
            bytes.fill(0);
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_INVALID_UTF8,
                "wallet recovery phrase is not valid UTF-8",
            ));
        }
    };
    MobileRecoveryPhrase::new(phrase)
        .map_err(|_| FfiFailure::invalid("wallet recovery phrase is invalid"))
}

unsafe fn wallet_hns_read_backend(
    port: u16,
    authorization: HnsBrowserSlice,
) -> Result<HnsNodeRpcBackend, FfiFailure> {
    if port == 0 {
        return Err(FfiFailure::invalid(
            "wallet read loopback port must be nonzero",
        ));
    }
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let mut bytes = unsafe { input_bytes(authorization, MAX_AUTH_FIELD_BYTES) }?;
    let valid = !bytes.is_empty()
        && bytes.first() != Some(&b' ')
        && bytes.last() != Some(&b' ')
        && bytes.iter().all(|byte| (0x20..=0x7e).contains(byte));
    if !valid {
        bytes.fill(0);
        return Err(FfiFailure::invalid(
            "wallet read authorization must be bounded visible ASCII",
        ));
    }
    // SAFETY: Every byte was validated as visible ASCII above, which is valid UTF-8.
    let authorization = unsafe { String::from_utf8_unchecked(bytes) };
    let config = HnsNodeRpcConfig::new(SocketAddr::from(([127, 0, 0, 1], port)), authorization)
        .and_then(|config| {
            config.with_timeouts(
                WALLET_RPC_CONNECT_TIMEOUT,
                WALLET_RPC_READ_TIMEOUT,
                WALLET_RPC_WRITE_TIMEOUT,
            )
        })
        .map_err(|_| FfiFailure::invalid("wallet read loopback configuration is invalid"))?;
    HnsNodeRpcBackend::new(config)
        .map_err(|_| FfiFailure::invalid("wallet read loopback configuration is invalid"))
}

unsafe fn wallet_exact_hns_name(name: HnsBrowserSlice) -> Result<SensitiveBytes, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let mut bytes = unsafe { input_bytes(name, MAX_WALLET_NAME_INPUT_BYTES) }?;
    if bytes.is_empty() {
        return Err(FfiFailure::invalid("wallet HNS name is empty"));
    }
    if std::str::from_utf8(&bytes).is_err() {
        bytes.fill(0);
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_INVALID_UTF8,
            "wallet HNS name is not valid UTF-8",
        ));
    }
    Ok(SensitiveBytes(bytes))
}

fn wallet_read_bundle(snapshot: &MobileHnsReadSnapshot) -> Result<SensitiveBytes, FfiFailure> {
    let mut payload = serde_json::to_vec(snapshot)
        .map_err(|_| wallet_runtime_failure("unable to encode synchronized HNS wallet reads"))?;
    let total = WALLET_READ_BUNDLE_HEADER_BYTES
        .checked_add(payload.len())
        .ok_or_else(FfiFailure::internal)?;
    if payload.is_empty() || total > MAX_OUTPUT_BUFFER_BYTES || payload.len() > u32::MAX as usize {
        payload.fill(0);
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
            "synchronized HNS wallet reads exceed the ABI output bound",
        ));
    }
    let mut bundle = Vec::with_capacity(total);
    bundle.extend_from_slice(WALLET_READ_BUNDLE_MAGIC);
    bundle.push(WALLET_READ_BUNDLE_VERSION);
    bundle.push(WALLET_READ_BUNDLE_HNS_READ_ONLY);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    bundle.append(&mut payload);
    Ok(SensitiveBytes(bundle))
}

fn wallet_name_import_bundle(summary: &MobileHnsNameSummary) -> Result<SensitiveBytes, FfiFailure> {
    let mut payload = serde_json::to_vec(summary)
        .map_err(|_| wallet_runtime_failure("unable to encode HNS name import summary"))?;
    let total = WALLET_NAME_IMPORT_BUNDLE_HEADER_BYTES
        .checked_add(payload.len())
        .ok_or_else(FfiFailure::internal)?;
    if payload.is_empty()
        || payload.len() > MAX_WALLET_NAME_IMPORT_JSON_BYTES
        || total > MAX_OUTPUT_BUFFER_BYTES
        || payload.len() > u32::MAX as usize
        || payload.first() != Some(&b'{')
        || payload.last() != Some(&b'}')
    {
        payload.fill(0);
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
            "HNS name import result exceeds the ABI output bound",
        ));
    }
    let mut bundle = Vec::with_capacity(total);
    bundle.extend_from_slice(WALLET_NAME_IMPORT_BUNDLE_MAGIC);
    bundle.push(WALLET_NAME_IMPORT_BUNDLE_VERSION);
    bundle.push(WALLET_NAME_IMPORT_BUNDLE_FLAGS);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    bundle.extend_from_slice(&payload);
    payload.fill(0);
    Ok(SensitiveBytes(bundle))
}

fn wallet_name_import_failure(error: &MobileWalletError) -> FfiFailure {
    if matches!(
        error,
        MobileWalletError::ServiceFailure {
            code: ServiceErrorCode::InvalidRequest,
            ..
        }
    ) {
        FfiFailure::invalid("wallet HNS name is not exact canonical text")
    } else {
        wallet_runtime_failure("trusted-native HNS name import failed")
    }
}

fn wallet_runtime_failure(message: &'static str) -> FfiFailure {
    FfiFailure::new(HNS_BROWSER_RESULT_RUNTIME_ERROR, message)
}

fn direct_hns_not_ready(message: &'static str) -> FfiFailure {
    FfiFailure::new(HNS_BROWSER_RESULT_NOT_READY, message)
}

fn direct_hns_public_progress(
    stage: u8,
    coordinator: &HnsDirectPeerCoordinator,
) -> Result<HnsBrowserWalletHnsSyncProgress, FfiFailure> {
    if !matches!(
        stage,
        WALLET_HNS_SYNC_CONNECTING
            | WALLET_HNS_SYNC_HEADERS
            | WALLET_HNS_SYNC_SCANNING
            | WALLET_HNS_SYNC_FINALIZING
    ) {
        return Err(FfiFailure::internal());
    }
    let header = coordinator
        .backend()
        .header_sync_status()
        .map_err(|_| wallet_runtime_failure("direct HNS header status is unavailable"))?;
    let scan = coordinator
        .backend()
        .light_scan_status()
        .map_err(|_| wallet_runtime_failure("direct HNS scan status is unavailable"))?;
    let verified_header_height = header.tip.height().get();
    // A restored wallet may legitimately have a birthday above the bundled
    // or partially synchronized local header tip. Publish that header-only
    // catch-up state; scanning begins after verified headers reach birthday.
    if !wallet_hns_sync_heights_are_coherent(
        scan.birthday_height,
        scan.scanned_height,
        verified_header_height,
    ) {
        return Err(FfiFailure::internal());
    }
    Ok(HnsBrowserWalletHnsSyncProgress {
        struct_size: size_u32::<HnsBrowserWalletHnsSyncProgress>(),
        stage,
        has_scanned_height: u8::from(scan.scanned_height.is_some()),
        reserved0: 0,
        verified_header_height: u64::from(verified_header_height),
        birthday_height: u64::from(scan.birthday_height),
        scanned_height: u64::from(scan.scanned_height.unwrap_or(0)),
        target_height: u64::from(verified_header_height),
    })
}

fn wallet_hns_sync_heights_are_coherent(
    birthday_height: u32,
    scanned_height: Option<u32>,
    verified_header_height: u32,
) -> bool {
    scanned_height
        .is_none_or(|height| height >= birthday_height && height <= verified_header_height)
}

fn publish_direct_hns_public_progress(
    control: &WalletHnsSyncControl,
    stage: u8,
    coordinator: &HnsDirectPeerCoordinator,
) {
    let Ok(progress) = direct_hns_public_progress(stage, coordinator) else {
        return;
    };
    if let Ok(mut current) = control.progress.lock() {
        *current = Some(progress);
    }
}

fn ensure_wallet_hns_sync_not_cancelled(control: &WalletHnsSyncControl) -> Result<(), FfiFailure> {
    if control
        .activity
        .lock()
        .map_err(|_| FfiFailure::internal())?
        .cancellation_requested
    {
        Err(direct_hns_not_ready(
            "direct HNS synchronization was stopped by the wallet screen",
        ))
    } else {
        Ok(())
    }
}

/// Progress the direct controller through bounded header agreement and wallet
/// scans before asking the value controller for a balance/history snapshot.
/// A partial catch-up is explicitly `NOT_READY`; it is never presented as an
/// empty balance or a send-ready wallet.
fn synchronize_wallet_owned_direct_hns(
    coordinator: &mut HnsDirectPeerCoordinator,
    controller: &mut MobileHnsValueController<EmbeddedHnsBackend>,
    sync_control: &WalletHnsSyncControl,
) -> Result<MobileHnsReadSnapshot, FfiFailure> {
    for _ in 0..DIRECT_HNS_MAX_HEADER_ROUNDS_PER_SYNC {
        ensure_wallet_hns_sync_not_cancelled(sync_control)?;
        publish_direct_hns_public_progress(sync_control, WALLET_HNS_SYNC_CONNECTING, coordinator);
        let now_unix = HnsReadSystemClock
            .now_unix()
            .map_err(|_| wallet_runtime_failure("direct HNS clock is unavailable"))?;
        coordinator
            .connect_available(now_unix)
            .map_err(|_| direct_hns_not_ready("direct HNS peers are unavailable"))?;
        ensure_wallet_hns_sync_not_cancelled(sync_control)?;
        publish_direct_hns_public_progress(sync_control, WALLET_HNS_SYNC_HEADERS, coordinator);
        match coordinator
            .synchronize_headers_once(now_unix)
            .map_err(|_| direct_hns_not_ready("direct HNS header agreement is unavailable"))?
        {
            hns_wallet_mobile::HnsHeaderRoundProgress::Committed(round) => {
                ensure_wallet_hns_sync_not_cancelled(sync_control)?;
                if round.accepted.is_empty() {
                    break;
                }
            }
            hns_wallet_mobile::HnsHeaderRoundProgress::AwaitingResponses { .. } => {
                return Err(direct_hns_not_ready(
                    "direct HNS header agreement is still in progress",
                ));
            }
        }
    }
    let header = coordinator
        .backend()
        .header_sync_status()
        .map_err(|_| wallet_runtime_failure("direct HNS header status is unavailable"))?;
    if header.state != SyncState::HeaderCurrent {
        return Err(direct_hns_not_ready(
            "direct HNS headers are still catching up",
        ));
    }
    let now_unix = HnsReadSystemClock
        .now_unix()
        .map_err(|_| wallet_runtime_failure("direct HNS clock is unavailable"))?;
    for _ in 0..DIRECT_HNS_MAX_SCAN_CHUNKS_PER_SYNC {
        ensure_wallet_hns_sync_not_cancelled(sync_control)?;
        publish_direct_hns_public_progress(sync_control, WALLET_HNS_SYNC_SCANNING, coordinator);
        let progress_coordinator = coordinator.clone();
        let progress = coordinator
            .scan_wallet_blocks_with_progress(DIRECT_HNS_SCAN_BLOCKS_PER_CHUNK, now_unix, |_| {
                publish_direct_hns_public_progress(
                    sync_control,
                    WALLET_HNS_SYNC_SCANNING,
                    &progress_coordinator,
                );
            })
            .map_err(|_| direct_hns_not_ready("direct HNS wallet scan is unavailable"))?;
        ensure_wallet_hns_sync_not_cancelled(sync_control)?;
        publish_direct_hns_public_progress(sync_control, WALLET_HNS_SYNC_SCANNING, coordinator);
        if progress.blocks_applied == 0 {
            break;
        }
    }
    let header = coordinator
        .backend()
        .header_sync_status()
        .map_err(|_| wallet_runtime_failure("direct HNS header status is unavailable"))?;
    if header.state != SyncState::HeaderCurrent {
        return Err(direct_hns_not_ready(
            "direct HNS headers are still catching up",
        ));
    }
    ensure_wallet_hns_sync_not_cancelled(sync_control)?;
    publish_direct_hns_public_progress(sync_control, WALLET_HNS_SYNC_FINALIZING, coordinator);
    coordinator
        .refresh_mempool(now_unix)
        .map_err(|_| direct_hns_not_ready("direct HNS mempool refresh is unavailable"))?;
    ensure_wallet_hns_sync_not_cancelled(sync_control)?;
    let mut snapshot = controller
        .synchronize()
        .map_err(|_| direct_hns_not_ready("direct HNS wallet scan is still catching up"))?;
    ensure_wallet_hns_sync_not_cancelled(sync_control)?;
    if controller
        .rebroadcast_dropped_hns_sends()
        .map_err(|_| direct_hns_not_ready("dropped HNS send resubmission is unavailable"))?
        > 0
    {
        // Socket completion is not peer mempool admission. Wait one bounded
        // propagation interval and refresh authenticated peer evidence before
        // returning the recovery snapshot to Swift.
        std::thread::sleep(Duration::from_secs(1));
        let post_rebroadcast_now = HnsReadSystemClock
            .now_unix()
            .map_err(|_| wallet_runtime_failure("direct HNS clock is unavailable"))?;
        coordinator
            .refresh_mempool(post_rebroadcast_now)
            .map_err(|_| {
                direct_hns_not_ready("post-rebroadcast HNS mempool refresh is unavailable")
            })?;
        snapshot = controller
            .synchronize()
            .map_err(|_| direct_hns_not_ready("resubmitted HNS send refresh is unavailable"))?;
    }
    Ok(snapshot)
}

unsafe fn wallet_visible_ascii(
    slice: HnsBrowserSlice,
    maximum: usize,
) -> Result<String, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let mut bytes = unsafe { input_bytes(slice, maximum) }?;
    if bytes.is_empty() || bytes.iter().any(|byte| !(0x21..=0x7e).contains(byte)) {
        bytes.fill(0);
        return Err(FfiFailure::invalid(
            "wallet value input is not bounded visible ASCII",
        ));
    }
    String::from_utf8(bytes).map_err(|error| {
        let mut bytes = error.into_bytes();
        bytes.fill(0);
        FfiFailure::invalid("wallet value input is not valid UTF-8")
    })
}

unsafe fn wallet_nonzero_base_units(slice: HnsBrowserSlice) -> Result<BaseUnits, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let mut bytes = unsafe { input_bytes(slice, MAX_WALLET_BASE_UNITS_BYTES) }?;
    let valid = !bytes.is_empty()
        && bytes.iter().all(u8::is_ascii_digit)
        && (bytes.len() == 1 || bytes.first() != Some(&b'0'));
    if !valid {
        bytes.fill(0);
        return Err(FfiFailure::invalid(
            "wallet amount is not canonical base units",
        ));
    }
    let value = std::str::from_utf8(bytes.as_slice())
        .ok()
        .and_then(|text| text.parse::<u128>().ok())
        .filter(|value| *value != 0)
        .map(BaseUnits::new)
        .ok_or_else(|| FfiFailure::invalid("wallet amount is not canonical base units"));
    bytes.fill(0);
    value
}

unsafe fn wallet_action_token(slice: HnsBrowserSlice) -> Result<String, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let mut bytes = unsafe { input_bytes(slice, WALLET_ACTION_TOKEN_BYTES) }?;
    let valid = bytes.len() == WALLET_ACTION_TOKEN_BYTES
        && bytes
            .iter()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(byte))
        && bytes.iter().any(|byte| *byte != b'0');
    if !valid {
        bytes.fill(0);
        return Err(FfiFailure::invalid("wallet action token is invalid"));
    }
    String::from_utf8(bytes).map_err(|error| {
        let mut bytes = error.into_bytes();
        bytes.fill(0);
        FfiFailure::invalid("wallet action token is invalid")
    })
}

unsafe fn wallet_bitcoin_address(slice: HnsBrowserSlice) -> Result<String, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let value = unsafe { wallet_visible_ascii(slice, MAX_WALLET_BITCOIN_ADDRESS_BYTES) }?;
    if value.bytes().all(|byte| byte.is_ascii_alphanumeric()) {
        Ok(value)
    } else {
        Err(FfiFailure::invalid(
            "Bitcoin address is not canonical visible ASCII",
        ))
    }
}

unsafe fn wallet_nonzero_sats(slice: HnsBrowserSlice) -> Result<u64, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let mut bytes = unsafe { input_bytes(slice, MAX_WALLET_BITCOIN_SATS_BYTES) }?;
    let valid = !bytes.is_empty()
        && bytes.iter().all(u8::is_ascii_digit)
        && (bytes.len() == 1 || bytes.first() != Some(&b'0'));
    let value = valid
        .then(|| {
            std::str::from_utf8(bytes.as_slice())
                .ok()?
                .parse::<u64>()
                .ok()
        })
        .flatten()
        .filter(|value| *value != 0)
        .ok_or_else(|| FfiFailure::invalid("Bitcoin amount is not canonical nonzero satoshis"));
    bytes.fill(0);
    value
}

/// Decodes only the published closed HNS wallet intent enum. The iOS C ABI
/// has no provider or WebKit caller, but retaining a strict byte/shape bound
/// here prevents UIKit form data from becoming an unbounded native parser
/// input and keeps this path congruent with Android's JNI boundary.
unsafe fn wallet_value_intent(slice: HnsBrowserSlice) -> Result<MobileHnsValueIntent, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let mut bytes = unsafe { input_bytes(slice, MAX_WALLET_VALUE_INTENT_JSON_BYTES) }?;
    let valid_frame =
        bytes.len() >= 2 && bytes.first() == Some(&b'{') && bytes.last() == Some(&b'}');
    let intent = if valid_frame {
        serde_json::from_slice::<MobileHnsValueIntent>(bytes.as_slice()).ok()
    } else {
        None
    };
    bytes.fill(0);
    intent.ok_or_else(|| FfiFailure::invalid("wallet value intent is invalid"))
}

/// Decodes a closed native Shakedex query. Like value intents, this is a
/// UIKit-only input and has no provider or renderer counterpart.
unsafe fn wallet_shakedex_query(slice: HnsBrowserSlice) -> Result<MobileShakedexQuery, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let mut bytes = unsafe { input_bytes(slice, MAX_WALLET_SHAKEDEX_QUERY_JSON_BYTES) }?;
    let valid_frame =
        bytes.len() >= 2 && bytes.first() == Some(&b'{') && bytes.last() == Some(&b'}');
    let query = if valid_frame {
        serde_json::from_slice::<MobileShakedexQuery>(bytes.as_slice()).ok()
    } else {
        None
    };
    bytes.fill(0);
    query.ok_or_else(|| FfiFailure::invalid("wallet Shakedex query is invalid"))
}

unsafe fn wallet_denuo_endpoint(slice: HnsBrowserSlice) -> Result<SocketAddr, FfiFailure> {
    // SAFETY: This helper carries the exported caller's readable-slice contract.
    let endpoint = unsafe { required_input_str(slice, MAX_WALLET_DENUO_ENDPOINT_BYTES) }?;
    if endpoint.bytes().any(|byte| !(0x21..=0x7e).contains(&byte)) {
        return Err(FfiFailure::invalid("direct Denuo endpoint is invalid"));
    }
    endpoint
        .parse::<SocketAddr>()
        .ok()
        .filter(|address| address.port() != 0)
        .ok_or_else(|| FfiFailure::invalid("direct Denuo endpoint must be an IP literal and port"))
}

fn wallet_direct_denuo_status_bundle(
    unlocked: bool,
    listener_port: Option<u16>,
    peer_endpoint: Option<SocketAddr>,
) -> Option<Vec<u8>> {
    if !unlocked && (listener_port.is_some() || peer_endpoint.is_some()) {
        return None;
    }
    let endpoint = peer_endpoint
        .map(|value| value.to_string())
        .unwrap_or_default();
    if endpoint.len() > MAX_WALLET_DENUO_ENDPOINT_BYTES
        || !endpoint.bytes().all(|byte| (0x21..=0x7e).contains(&byte))
    {
        return None;
    }
    let endpoint_length = u16::try_from(endpoint.len()).ok()?;
    let mut flags = if unlocked {
        WALLET_DIRECT_DENUO_STATUS_UNLOCKED
    } else {
        0
    };
    if listener_port.is_some() {
        flags |= WALLET_DIRECT_DENUO_STATUS_LISTENING;
    }
    if !endpoint.is_empty() {
        flags |= WALLET_DIRECT_DENUO_STATUS_PAIRED;
    }
    let mut bundle = Vec::with_capacity(WALLET_DIRECT_DENUO_BUNDLE_HEADER_BYTES + endpoint.len());
    bundle.extend_from_slice(WALLET_DIRECT_DENUO_STATUS_BUNDLE_MAGIC);
    bundle.push(WALLET_DIRECT_DENUO_BUNDLE_VERSION);
    bundle.push(flags);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(&listener_port.unwrap_or(0).to_be_bytes());
    bundle.extend_from_slice(&endpoint_length.to_be_bytes());
    bundle.extend_from_slice(endpoint.as_bytes());
    (bundle.len() == WALLET_DIRECT_DENUO_BUNDLE_HEADER_BYTES + endpoint.len()).then_some(bundle)
}

fn wallet_direct_denuo_connect_bundle(result: IosDirectDenuoConnectResult) -> Option<Vec<u8>> {
    let code = match result.outcome {
        IosDirectDenuoConnectOutcome::Connected => WALLET_DIRECT_DENUO_CONNECT_CONNECTED,
        IosDirectDenuoConnectOutcome::Replaced => WALLET_DIRECT_DENUO_CONNECT_REPLACED,
        IosDirectDenuoConnectOutcome::Unavailable => WALLET_DIRECT_DENUO_CONNECT_UNAVAILABLE,
        IosDirectDenuoConnectOutcome::Locked => WALLET_DIRECT_DENUO_CONNECT_LOCKED,
        IosDirectDenuoConnectOutcome::ConnectionFailed => WALLET_DIRECT_DENUO_CONNECT_FAILED,
        IosDirectDenuoConnectOutcome::ExchangeFailed => WALLET_DIRECT_DENUO_CONNECT_EXCHANGE_FAILED,
    };
    let endpoint = result
        .peer_endpoint
        .map(|value| value.to_string())
        .unwrap_or_default();
    let success = matches!(
        result.outcome,
        IosDirectDenuoConnectOutcome::Connected | IosDirectDenuoConnectOutcome::Replaced
    );
    if success == endpoint.is_empty()
        || endpoint.len() > MAX_WALLET_DENUO_ENDPOINT_BYTES
        || !endpoint.bytes().all(|byte| (0x21..=0x7e).contains(&byte))
    {
        return None;
    }
    let endpoint_length = u16::try_from(endpoint.len()).ok()?;
    let mut bundle = Vec::with_capacity(WALLET_DIRECT_DENUO_BUNDLE_HEADER_BYTES + endpoint.len());
    bundle.extend_from_slice(WALLET_DIRECT_DENUO_CONNECT_BUNDLE_MAGIC);
    bundle.push(WALLET_DIRECT_DENUO_BUNDLE_VERSION);
    bundle.push(code);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(&endpoint_length.to_be_bytes());
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(endpoint.as_bytes());
    (bundle.len() == WALLET_DIRECT_DENUO_BUNDLE_HEADER_BYTES + endpoint.len()).then_some(bundle)
}

fn wallet_json_bundle(
    json: &[u8],
    magic: &[u8; 4],
    version: u8,
    maximum_json_bytes: usize,
) -> Result<SensitiveBytes, FfiFailure> {
    if json.is_empty()
        || json.len() > maximum_json_bytes
        || json.first() != Some(&b'{')
        || json.last() != Some(&b'}')
    {
        return Err(FfiFailure::new(
            HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
            "wallet native result exceeds the ABI output bound",
        ));
    }
    let length = u32::try_from(json.len()).map_err(|_| FfiFailure::internal())?;
    let mut bundle = Vec::with_capacity(WALLET_JSON_BUNDLE_HEADER_BYTES + json.len());
    bundle.extend_from_slice(magic);
    bundle.push(version);
    bundle.push(0);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(&length.to_be_bytes());
    bundle.extend_from_slice(json);
    Ok(SensitiveBytes(bundle))
}

fn wallet_bitcoin_bundle(value: &impl serde::Serialize) -> Result<SensitiveBytes, FfiFailure> {
    let mut json = serde_json::to_vec(value)
        .map_err(|_| wallet_runtime_failure("unable to encode direct Bitcoin result"))?;
    let result = wallet_json_bundle(
        json.as_slice(),
        WALLET_BITCOIN_BUNDLE_MAGIC,
        WALLET_BITCOIN_BUNDLE_VERSION,
        MAX_WALLET_BITCOIN_JSON_BYTES,
    );
    json.fill(0);
    result
}

fn native_hns_send_receipt(result: Value) -> Option<Value> {
    let object = result.as_object()?;
    let expected = [
        "module",
        "workflowId",
        "requestNonce",
        "txid",
        "acceptedAtUnix",
    ];
    if object.len() != expected.len() || expected.iter().any(|key| !object.contains_key(*key)) {
        return None;
    }
    if object.get("module")?.as_str()? != "handshake"
        || !object.get("workflowId")?.is_null()
        || object.get("requestNonce")?.as_u64()? == 0
    {
        return None;
    }
    let txid = object.get("txid")?.as_str()?;
    if txid.len() != 64
        || txid
            .bytes()
            .any(|byte| !byte.is_ascii_digit() && !(b'a'..=b'f').contains(&byte))
    {
        return None;
    }
    let accepted_at_unix = object.get("acceptedAtUnix")?.as_u64()?;
    Some(json!({
        "module": "handshake",
        "txid": txid,
        "acceptedAtUnix": accepted_at_unix,
    }))
}

fn json_string(output: &mut String, value: &str) {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    output.push('"');
    for character in value.chars() {
        match character {
            '"' => output.push_str("\\\""),
            '\\' => output.push_str("\\\\"),
            '\u{08}' => output.push_str("\\b"),
            '\u{0c}' => output.push_str("\\f"),
            '\n' => output.push_str("\\n"),
            '\r' => output.push_str("\\r"),
            '\t' => output.push_str("\\t"),
            character if character <= '\u{1f}' => {
                let value = u32::from(character) as usize;
                output.push_str("\\u00");
                output.push(HEX[value >> 4] as char);
                output.push(HEX[value & 0x0f] as char);
            }
            character => output.push(character),
        }
    }
    output.push('"');
}

fn module_wire_name(module: impl std::fmt::Debug) -> String {
    format!("{module:?}").to_ascii_lowercase()
}

fn network_kind(value: u32) -> Result<NetworkKind, FfiFailure> {
    match value {
        HNS_BROWSER_NETWORK_MAINNET => Ok(NetworkKind::Mainnet),
        HNS_BROWSER_NETWORK_TESTNET => Ok(NetworkKind::Testnet),
        HNS_BROWSER_NETWORK_REGTEST => Ok(NetworkKind::Regtest),
        _ => Err(FfiFailure::invalid("network value is unsupported")),
    }
}

fn resolution_mode(value: u32) -> Result<ResolutionMode, FfiFailure> {
    match value {
        HNS_BROWSER_RESOLUTION_COMPATIBILITY | HNS_BROWSER_RESOLUTION_STRICT => {
            Ok(ResolutionMode::Strict)
        }
        _ => Err(FfiFailure::invalid("resolution mode value is unsupported")),
    }
}

fn ffi_bool(value: u8) -> Result<bool, FfiFailure> {
    match value {
        0 => Ok(false),
        1 => Ok(true),
        _ => Err(FfiFailure::invalid("boolean ABI field must be zero or one")),
    }
}

unsafe fn policy_from_fields(
    mode: u32,
    endpoint: HnsBrowserSlice,
    stateless_dane_certificates: u8,
    experimental_p2p_dns_relay: u8,
    legacy_hns_doh_compatibility: u8,
) -> Result<RuntimePolicy, FfiFailure> {
    // SAFETY: The caller guarantees a readable, bounded policy slice.
    let endpoint = unsafe { input_str(endpoint, MAX_HNS_DOH_RECOVERY_URL_BYTES) }?;
    let endpoint = normalize_hns_doh_recovery_url(&endpoint)
        .map_err(|_| FfiFailure::invalid("HNS recovery DoH URL is invalid"))?;
    Ok(RuntimePolicy {
        resolution_mode: resolution_mode(mode)?,
        hns_doh_resolver: endpoint,
        experimental_p2p_dns_relay: ffi_bool(experimental_p2p_dns_relay)?,
        legacy_hns_doh_compatibility: {
            ffi_bool(legacy_hns_doh_compatibility)?;
            false
        },
        stateless_dane_certificates: ffi_bool(stateless_dane_certificates)?,
    })
}

fn validate_options(options: HnsBrowserRuntimeOptions) -> Result<(), FfiFailure> {
    if options.struct_size != size_u32::<HnsBrowserRuntimeOptions>() {
        return Err(FfiFailure::invalid(
            "runtime options struct size does not match ABI version",
        ));
    }
    if options.reserved1 != [0; 2] {
        return Err(FfiFailure::invalid(
            "reserved runtime option fields must be zero",
        ));
    }
    ffi_bool(options.experimental_p2p_dns_relay)?;
    ffi_bool(options.legacy_hns_doh_compatibility)?;
    if options.sync_timeout_millis == 0 || options.sync_timeout_millis > MAX_SYNC_TIMEOUT_MILLIS {
        return Err(FfiFailure::invalid(
            "sync timeout is outside the supported range",
        ));
    }
    if options.resource_cache_limit_bytes == 0
        || options.resource_cache_limit_bytes > MAX_RESOURCE_CACHE_LIMIT_BYTES
    {
        return Err(FfiFailure::invalid(
            "resource cache limit is outside the supported range",
        ));
    }
    Ok(())
}

fn validate_policy(policy: HnsBrowserPolicy) -> Result<(), FfiFailure> {
    if policy.struct_size != size_u32::<HnsBrowserPolicy>() {
        return Err(FfiFailure::invalid(
            "policy struct size does not match ABI version",
        ));
    }
    if policy.reserved0 != [0; 5] || policy.reserved1 != 0 {
        return Err(FfiFailure::invalid("reserved policy fields must be zero"));
    }
    ffi_bool(policy.experimental_p2p_dns_relay)?;
    ffi_bool(policy.legacy_hns_doh_compatibility)?;
    Ok(())
}

fn tls_policy_code(policy: Option<BrowserProxyTlsPolicy>) -> u32 {
    match policy {
        None => HNS_BROWSER_TLS_POLICY_UNKNOWN,
        Some(BrowserProxyTlsPolicy::Dane) => HNS_BROWSER_TLS_POLICY_DANE,
        Some(BrowserProxyTlsPolicy::WebPkiFallback) => HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
        Some(_) => HNS_BROWSER_TLS_POLICY_UNKNOWN,
    }
}

fn resolver_policy_code(policy: Option<BrowserProxyResolverPolicy>) -> u32 {
    match policy {
        None => HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
        Some(BrowserProxyResolverPolicy::HnsDohCompatibility) => {
            HNS_BROWSER_RESOLVER_POLICY_HNS_DOH_COMPATIBILITY
        }
        Some(_) => HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
    }
}

fn security_path_code(path: Option<BrowserProxySecurityPath>) -> u32 {
    match path {
        None => HNS_BROWSER_SECURITY_PATH_UNKNOWN,
        Some(BrowserProxySecurityPath::DaneAuthoritativeDoh) => {
            HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DOH
        }
        Some(BrowserProxySecurityPath::DaneAuthoritativeDns53) => {
            HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DNS53
        }
        Some(BrowserProxySecurityPath::DaneThirdPartyDoh) => {
            HNS_BROWSER_SECURITY_PATH_DANE_THIRD_PARTY_DOH
        }
        Some(BrowserProxySecurityPath::StatelessDane) => HNS_BROWSER_SECURITY_PATH_STATELESS_DANE,
        Some(BrowserProxySecurityPath::DaneIcannDoh) => HNS_BROWSER_SECURITY_PATH_DANE_ICANN_DOH,
        Some(BrowserProxySecurityPath::HnsAuthoritativeDoh) => {
            HNS_BROWSER_SECURITY_PATH_HNS_AUTHORITATIVE_DOH
        }
        Some(BrowserProxySecurityPath::HnsAuthoritativeDns53) => {
            HNS_BROWSER_SECURITY_PATH_HNS_AUTHORITATIVE_DNS53
        }
        Some(BrowserProxySecurityPath::HnsThirdPartyDoh) => {
            HNS_BROWSER_SECURITY_PATH_HNS_THIRD_PARTY_DOH
        }
        Some(BrowserProxySecurityPath::DaneP2pDnsRelay) => {
            HNS_BROWSER_SECURITY_PATH_DANE_P2P_DNS_RELAY
        }
        Some(BrowserProxySecurityPath::HnsP2pDnsRelay) => {
            HNS_BROWSER_SECURITY_PATH_HNS_P2P_DNS_RELAY
        }
        Some(_) => HNS_BROWSER_SECURITY_PATH_UNKNOWN,
    }
}

fn name_class_code(class: BrowserNameClass) -> u32 {
    match class {
        BrowserNameClass::Hns => HNS_BROWSER_NAME_HNS,
        BrowserNameClass::Icann => HNS_BROWSER_NAME_ICANN,
        BrowserNameClass::Search => HNS_BROWSER_NAME_SEARCH,
    }
}

unsafe fn write_json_output(output: *mut HnsBrowserBuffer, json: &str) -> Result<(), FfiFailure> {
    let buffer = allocate_output(json.as_bytes(), false)?;
    // SAFETY: Output was validated by the exported caller before this helper.
    unsafe { write_output(output, buffer) };
    Ok(())
}

fn constant_time_eq(left: &[u8], right: &[u8]) -> bool {
    let max_len = left.len().max(right.len());
    let mut difference = left.len() ^ right.len();
    for index in 0..max_len {
        let left_byte = left.get(index).copied().unwrap_or(0);
        let right_byte = right.get(index).copied().unwrap_or(0);
        difference |= usize::from(left_byte ^ right_byte);
    }
    difference == 0
}

#[unsafe(no_mangle)]
pub extern "C" fn hns_browser_abi_version() -> u32 {
    match catch_unwind(|| {
        clear_last_error();
        HNS_BROWSER_ABI_VERSION
    }) {
        Ok(version) => version,
        Err(_) => {
            contained_set_last_error("panic contained at the C ABI boundary");
            0
        }
    }
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_version` must point to one writable [`HnsBrowserBuffer`].
pub unsafe extern "C" fn hns_browser_core_version(
    out_version: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_version)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_version, HnsBrowserBuffer::empty()) };
        // SAFETY: The output pointer was validated above.
        unsafe { write_json_output(out_version, core_version()) }
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_json` must point to one writable [`HnsBrowserBuffer`].
pub unsafe extern "C" fn hns_browser_diagnostics_json(
    out_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_json)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_json, HnsBrowserBuffer::empty()) };
        // SAFETY: The output pointer was validated above.
        unsafe { write_json_output(out_json, &diagnostics_json()) }
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_error` must point to one writable [`HnsBrowserBuffer`].
pub unsafe extern "C" fn hns_browser_last_error(
    out_error: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call_preserving_error(|| {
        require_output(out_error)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_error, HnsBrowserBuffer::empty()) };
        let error = last_error_snapshot();
        let buffer = allocate_output(error.as_bytes(), false)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_error, buffer) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn hns_browser_buffer_free(buffer: HnsBrowserBuffer) -> HnsBrowserResult {
    ffi_call(|| free_output(buffer))
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_options` must point to one writable [`HnsBrowserRuntimeOptions`].
pub unsafe extern "C" fn hns_browser_runtime_options_default(
    out_options: *mut HnsBrowserRuntimeOptions,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_options)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_options, HnsBrowserRuntimeOptions::defaults()) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_policy` must point to one writable [`HnsBrowserPolicy`].
pub unsafe extern "C" fn hns_browser_policy_default(
    out_policy: *mut HnsBrowserPolicy,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_policy)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_policy, HnsBrowserPolicy::defaults()) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `options` must point to one readable options value, every nested non-empty
/// slice must remain readable for its declared length, and `out_runtime` must
/// point to one writable handle.
pub unsafe extern "C" fn hns_browser_runtime_create(
    options: *const HnsBrowserRuntimeOptions,
    out_runtime: *mut HnsBrowserRuntimeHandle,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_runtime)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_runtime, 0) };
        if options.is_null() {
            return Err(FfiFailure::invalid("runtime options pointer is null"));
        }
        // SAFETY: The C contract requires `options` to point to one readable struct.
        let options = unsafe { options.read() };
        validate_options(options)?;

        {
            let registry = handle_registry()
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            if registry.runtimes.len() >= MAX_RUNTIME_HANDLES {
                return Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
                    "runtime handle registry is full",
                ));
            }
        }

        // SAFETY: The caller guarantees readable slices in the options struct.
        let data_dir = unsafe { required_input_str(options.data_dir, MAX_PATH_BYTES) }?;
        let network = network_kind(options.network)?;
        // SAFETY: The caller guarantees readable slices in the options struct.
        let initial_policy = unsafe {
            policy_from_fields(
                options.resolution_mode,
                options.hns_doh_resolver,
                options.stateless_dane_certificates,
                options.experimental_p2p_dns_relay,
                options.legacy_hns_doh_compatibility,
            )
        }?;
        let resource_cache_limit_bytes = usize::try_from(options.resource_cache_limit_bytes)
            .map_err(|_| FfiFailure::invalid("resource cache limit is unsupported"))?;
        let sync = SyncOptions {
            seed_peers: ffi_bool(options.seed_peers)?,
            timeout: Duration::from_millis(options.sync_timeout_millis),
            resource_cache_limit_bytes,
        };
        let configuration = RuntimeConfiguration::new(data_dir, network)
            .with_sync_options(sync)
            .with_initial_policy(initial_policy);
        let runtime = BrowserRuntime::open(configuration).map_err(|_| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_RUNTIME_ERROR,
                "unable to open browser runtime",
            )
        })?;
        let handle = next_monotonic_id(&NEXT_OBJECT_HANDLE)?;
        let entry = Arc::new(RuntimeEntry { runtime });
        let mut registry = handle_registry()
            .lock()
            .map_err(|_| FfiFailure::internal())?;
        if registry.runtimes.len() >= MAX_RUNTIME_HANDLES {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
                "runtime handle registry is full",
            ));
        }
        registry.runtimes.insert(handle, entry);
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_runtime, handle) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn hns_browser_runtime_destroy(
    runtime: HnsBrowserRuntimeHandle,
) -> HnsBrowserResult {
    ffi_call(|| {
        let proxies = {
            let mut registry = handle_registry()
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            if registry.runtimes.remove(&runtime).is_none() {
                return Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_FOUND,
                    "runtime handle is invalid or stale",
                ));
            }
            let owned_handles = registry
                .proxies
                .iter()
                .filter_map(|(handle, entry)| (entry.runtime_handle == runtime).then_some(*handle))
                .collect::<Vec<_>>();
            owned_handles
                .into_iter()
                .filter_map(|handle| registry.proxies.remove(&handle))
                .collect::<Vec<_>>()
        };
        for proxy in &proxies {
            proxy.request_stop();
        }
        for proxy in proxies {
            proxy.blocking_stop();
        }
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `policy` must point to one readable policy value, every nested non-empty
/// slice must remain readable for its declared length, and `out_revision`
/// must point to one writable `u64`.
pub unsafe extern "C" fn hns_browser_runtime_set_policy(
    runtime: HnsBrowserRuntimeHandle,
    policy: *const HnsBrowserPolicy,
    out_revision: *mut u64,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_revision)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_revision, 0) };
        if policy.is_null() {
            return Err(FfiFailure::invalid("policy pointer is null"));
        }
        // SAFETY: The C contract requires `policy` to point to one readable struct.
        let policy = unsafe { policy.read() };
        validate_policy(policy)?;
        // SAFETY: The caller guarantees readable slices in the policy struct.
        let policy = unsafe {
            policy_from_fields(
                policy.resolution_mode,
                policy.hns_doh_resolver,
                policy.stateless_dane_certificates,
                policy.experimental_p2p_dns_relay,
                policy.legacy_hns_doh_compatibility,
            )
        }?;
        let entry = runtime_entry(runtime)?;
        let (revision, owned) = {
            // Serialize policy publication with proxy insertion. A start that
            // began before this update must observe a revision mismatch under
            // this same lock and tear down instead of publishing its handle.
            let registry = handle_registry()
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            let runtime_is_live = registry
                .runtimes
                .get(&runtime)
                .is_some_and(|current| Arc::ptr_eq(current, &entry));
            if !runtime_is_live {
                return Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_FOUND,
                    "runtime handle is invalid or stale",
                ));
            }
            let previous_revision = entry.runtime.policy_revision();
            let revision = entry.runtime.set_policy(policy).map_err(|_| {
                FfiFailure::new(
                    HNS_BROWSER_RESULT_RUNTIME_ERROR,
                    "unable to update runtime policy",
                )
            })?;
            // Revoke published generations only after a real normalized
            // policy change. Reapplying the same ABI policy preserves the
            // canonical generation and live platform bridge.
            let owned = if revision == previous_revision {
                Vec::new()
            } else {
                registry
                    .proxies
                    .values()
                    .filter(|proxy| proxy.runtime_handle == runtime)
                    .cloned()
                    .collect::<Vec<_>>()
            };
            (revision, owned)
        };
        for proxy in owned {
            proxy.request_stop();
        }
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_revision, revision) };
        Ok(())
    })
}

unsafe fn runtime_status_json(
    runtime: HnsBrowserRuntimeHandle,
    out_status_json: *mut HnsBrowserBuffer,
    operation: impl FnOnce(&BrowserRuntime) -> Result<String, ()>,
) -> Result<(), FfiFailure> {
    require_output(out_status_json)?;
    // SAFETY: Null was rejected above and the C contract requires writable output.
    unsafe { write_output(out_status_json, HnsBrowserBuffer::empty()) };
    let entry = runtime_entry(runtime)?;
    let json = operation(&entry.runtime).map_err(|()| {
        FfiFailure::new(HNS_BROWSER_RESULT_RUNTIME_ERROR, "runtime operation failed")
    })?;
    // SAFETY: The output pointer was validated above.
    unsafe { write_json_output(out_status_json, &json) }
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_status_json` must point to one writable [`HnsBrowserBuffer`].
pub unsafe extern "C" fn hns_browser_runtime_sync_once(
    runtime: HnsBrowserRuntimeHandle,
    out_status_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This unsafe export carries the caller's writable-output contract.
        unsafe {
            runtime_status_json(runtime, out_status_json, |runtime| {
                runtime
                    .sync_once()
                    .map(|status| status.to_json())
                    .map_err(|_| ())
            })
        }
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_status_json` must point to one writable [`HnsBrowserBuffer`].
pub unsafe extern "C" fn hns_browser_runtime_sync_status(
    runtime: HnsBrowserRuntimeHandle,
    out_status_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This unsafe export carries the caller's writable-output contract.
        unsafe {
            runtime_status_json(runtime, out_status_json, |runtime| {
                runtime
                    .sync_status()
                    .map(|status| status.to_json())
                    .map_err(|_| ())
            })
        }
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// The non-empty peer endpoint slice must remain readable for its declared
/// length and `out_status_json` must point to one writable buffer.
pub unsafe extern "C" fn hns_browser_runtime_add_static_relay_peer(
    runtime: HnsBrowserRuntimeHandle,
    endpoint: HnsBrowserSlice,
    out_status_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let endpoint = unsafe { required_input_str(endpoint, MAX_NAME_INPUT_BYTES) }?;
        // SAFETY: This unsafe export carries the caller's writable-output contract.
        unsafe {
            runtime_status_json(runtime, out_status_json, |runtime| {
                runtime
                    .add_static_relay_peer(&endpoint)
                    .map(|status| status.to_json())
                    .map_err(|_| ())
            })
        }
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_status_json` must point to one writable [`HnsBrowserBuffer`].
pub unsafe extern "C" fn hns_browser_runtime_clear_resolver_cache(
    runtime: HnsBrowserRuntimeHandle,
    out_status_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This unsafe export carries the caller's writable-output contract.
        unsafe {
            runtime_status_json(runtime, out_status_json, |runtime| {
                runtime
                    .clear_resolver_cache()
                    .map(|status| status.to_json())
                    .map_err(|_| ())
            })
        }
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// The non-empty snapshot path slice must remain readable for its declared
/// length and `out_status_json` must point to one writable buffer.
pub unsafe extern "C" fn hns_browser_runtime_install_header_snapshot(
    runtime: HnsBrowserRuntimeHandle,
    snapshot_path: HnsBrowserSlice,
    out_status_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let path = unsafe { required_input_str(snapshot_path, MAX_PATH_BYTES) }?;
        // SAFETY: This unsafe export carries the caller's writable-output contract.
        unsafe {
            runtime_status_json(runtime, out_status_json, |runtime| {
                runtime
                    .install_header_snapshot(path)
                    .map(|status| status.to_json())
                    .map_err(|_| ())
            })
        }
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_status_json` must point to one writable [`HnsBrowserBuffer`].
pub unsafe extern "C" fn hns_browser_runtime_reset_headers_from_peers(
    runtime: HnsBrowserRuntimeHandle,
    out_status_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This unsafe export carries the caller's writable-output contract.
        unsafe {
            runtime_status_json(runtime, out_status_json, |runtime| {
                runtime
                    .reset_headers_from_peers()
                    .map(|status| status.to_json())
                    .map_err(|_| ())
            })
        }
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// The non-empty input slice must remain readable for its declared length and
/// `out_details_json` must point to one writable buffer.
pub unsafe extern "C" fn hns_browser_runtime_proof_details(
    runtime: HnsBrowserRuntimeHandle,
    host_or_url: HnsBrowserSlice,
    out_details_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let input = unsafe { required_input_str(host_or_url, MAX_NAME_INPUT_BYTES) }?;
        // SAFETY: This unsafe export carries the caller's writable-output contract.
        unsafe {
            runtime_status_json(runtime, out_details_json, |runtime| {
                runtime.proof_details(&input).map_err(|_| ())
            })
        }
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// Every non-empty input slice must remain readable for its declared length
/// and `out_class` must point to one writable `u32`.
pub unsafe extern "C" fn hns_browser_classify_name(
    input: HnsBrowserSlice,
    out_class: *mut u32,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_class)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_class, HNS_BROWSER_NAME_SEARCH) };
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let input = unsafe { input_str(input, MAX_NAME_INPUT_BYTES) }?;
        let class = name_class_code(classify_browser_name(&input));
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_class, class) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// The non-empty input slice must remain readable for its declared length and
/// `out_host` must point to one writable buffer.
pub unsafe extern "C" fn hns_browser_canonical_host(
    input: HnsBrowserSlice,
    out_host: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_host)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_host, HnsBrowserBuffer::empty()) };
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let input = unsafe { required_input_str(input, MAX_NAME_INPUT_BYTES) }?;
        let host = canonical_browser_host(&input).ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_FOUND,
                "input is not a canonicalizable DNS host",
            )
        })?;
        let buffer = allocate_output(host.as_bytes(), false)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_host, buffer) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// The non-empty input slice must remain readable for its declared length and
/// `out_root` must point to one writable buffer.
pub unsafe extern "C" fn hns_browser_hns_root(
    input: HnsBrowserSlice,
    out_root: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_root)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_root, HnsBrowserBuffer::empty()) };
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let input = unsafe { required_input_str(input, MAX_NAME_INPUT_BYTES) }?;
        let root = browser_hns_root_label(&input).ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_FOUND,
                "input does not identify an HNS name",
            )
        })?;
        let buffer = allocate_output(root.as_bytes(), false)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_root, buffer) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Creates one native Handshake wallet and retains its one-time
/// recovery phrase until [`hns_browser_wallet_take_recovery_phrase`] succeeds.
///
/// # Safety
/// Both input slices must remain readable for their declared lengths and
/// `out_wallet` must point to one writable handle.
pub unsafe extern "C" fn hns_browser_wallet_create(
    database_path: HnsBrowserSlice,
    database_key: HnsBrowserSlice,
    network: u32,
    birthday_height: u64,
    out_wallet: *mut HnsBrowserWalletHandle,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_wallet)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_wallet, 0) };
        // SAFETY: This unsafe export carries the caller's readable-slice contracts.
        let path = unsafe { required_input_str(database_path, MAX_PATH_BYTES) }?;
        // SAFETY: This unsafe export carries the caller's readable-slice contracts.
        let key = unsafe { wallet_database_key(database_key) }?;
        let path = PathBuf::from(path);
        let bitcoin_data_dir = ios_wallet_bitcoin_data_dir(&path);
        let policy = HnsBootstrapPolicy::new(wallet_network(network)?, birthday_height);
        let reservation = reserve_wallet_start()?;
        let creation = MobileWalletController::create(&path, &key, MobilePlatform::Ios, policy)
            .map_err(|_| wallet_runtime_failure("unable to create native wallet"))?;
        let (controller, recovery_phrase) = creation.into_parts();
        let recovery_phrase =
            SensitiveBytes(recovery_phrase.expose_for_dedicated_display().into_bytes());
        let handle = insert_wallet(
            WalletEntry {
                controller: NativeWalletController::Lifecycle(controller),
                pending_recovery_phrase: Some(recovery_phrase),
                hns_reads_installable: false,
                bitcoin_data_dir,
                active: true,
            },
            reservation,
        )?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_wallet, handle) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Restores one native Handshake wallet from an owned, bounded
/// recovery phrase copy that is wiped before returning.
///
/// # Safety
/// All input slices must remain readable for their declared lengths and
/// `out_wallet` must point to one writable handle.
pub unsafe extern "C" fn hns_browser_wallet_restore(
    database_path: HnsBrowserSlice,
    database_key: HnsBrowserSlice,
    network: u32,
    birthday_height: u64,
    recovery_phrase: HnsBrowserSlice,
    out_wallet: *mut HnsBrowserWalletHandle,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_wallet)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_wallet, 0) };
        // SAFETY: This unsafe export carries the caller's readable-slice contracts.
        let path = unsafe { required_input_str(database_path, MAX_PATH_BYTES) }?;
        // SAFETY: This unsafe export carries the caller's readable-slice contracts.
        let key = unsafe { wallet_database_key(database_key) }?;
        // SAFETY: This unsafe export carries the caller's readable-slice contracts.
        let phrase = unsafe { wallet_recovery_phrase(recovery_phrase) }?;
        let path = PathBuf::from(path);
        let bitcoin_data_dir = ios_wallet_bitcoin_data_dir(&path);
        let policy = HnsBootstrapPolicy::new(wallet_network(network)?, birthday_height);
        let reservation = reserve_wallet_start()?;
        let controller =
            MobileWalletController::restore(&path, &key, MobilePlatform::Ios, policy, phrase)
                .map_err(|_| wallet_runtime_failure("unable to restore native wallet"))?;
        let handle = insert_wallet(
            WalletEntry {
                controller: NativeWalletController::Lifecycle(controller),
                pending_recovery_phrase: None,
                hns_reads_installable: true,
                bitcoin_data_dir,
                active: true,
            },
            reservation,
        )?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_wallet, handle) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Opens one existing native wallet in its locked state.
///
/// # Safety
/// Both input slices must remain readable for their declared lengths and
/// `out_wallet` must point to one writable handle.
pub unsafe extern "C" fn hns_browser_wallet_open(
    database_path: HnsBrowserSlice,
    database_key: HnsBrowserSlice,
    out_wallet: *mut HnsBrowserWalletHandle,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_wallet)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_wallet, 0) };
        // SAFETY: This unsafe export carries the caller's readable-slice contracts.
        let path = unsafe { required_input_str(database_path, MAX_PATH_BYTES) }?;
        // SAFETY: This unsafe export carries the caller's readable-slice contracts.
        let key = unsafe { wallet_database_key(database_key) }?;
        let path = PathBuf::from(path);
        let bitcoin_data_dir = ios_wallet_bitcoin_data_dir(&path);
        let reservation = reserve_wallet_start()?;
        let controller = MobileWalletController::open(&path, &key, MobilePlatform::Ios)
            .map_err(|_| wallet_runtime_failure("unable to open native wallet"))?;
        let handle = insert_wallet(
            WalletEntry {
                controller: NativeWalletController::Lifecycle(controller),
                pending_recovery_phrase: None,
                hns_reads_installable: true,
                bitcoin_data_dir,
                active: true,
            },
            reservation,
        )?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_wallet, handle) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_status_json` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_status(
    wallet: HnsBrowserWalletHandle,
    out_status_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_status_json)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_status_json, HnsBrowserBuffer::empty()) };
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let hns_reads_enabled = entry.controller.has_hns_reads();
        let hns_value_enabled = entry.controller.has_hns_value();
        // The only iOS value composition is created with the published
        // wallet-owned direct Shakedex controller. Keep this explicit in the
        // status projection so UIKit never infers marketplace availability
        // merely from a generic value flag.
        let shakedex_enabled = matches!(
            &entry.controller,
            NativeWalletController::DirectHnsValue { .. }
        );
        let status = entry
            .controller
            .with_mut(
                |controller| controller.status(),
                |controller| controller.status(),
                |controller| controller.status(),
            )
            .map_err(|_| wallet_runtime_failure("unable to read native wallet status"))?;
        let enabled_modules_are_allowed = if !hns_reads_enabled {
            status.enabled_modules.is_empty()
        } else {
            status.enabled_modules.len() == 1
                && status
                    .enabled_modules
                    .iter()
                    .all(|module| module_wire_name(*module) == "handshake")
        };
        if !enabled_modules_are_allowed
            || (shakedex_enabled && !hns_value_enabled)
            || status.mainnet_settlement_enabled
            || status.locked != status.active_wallet.is_none()
        {
            let _ = entry.controller.with_mut(
                |controller| controller.lock(),
                |controller| controller.lock(),
                |controller| controller.lock(),
            );
            return Err(wallet_runtime_failure(
                "native wallet exposed an invalid HNS status",
            ));
        }

        let mut json = String::from("{\"locked\":");
        json.push_str(if status.locked { "true" } else { "false" });
        json.push_str(",\"activeWallet\":");
        if let Some(wallet_id) = status.active_wallet {
            json_string(&mut json, &wallet_id.to_string());
        } else {
            json.push_str("null");
        }
        json.push_str(",\"enabledModules\":[");
        for (index, module) in status.enabled_modules.iter().enumerate() {
            if index != 0 {
                json.push(',');
            }
            json_string(&mut json, &module_wire_name(module));
        }
        json.push_str("],\"hnsValueEnabled\":");
        json.push_str(if hns_value_enabled { "true" } else { "false" });
        json.push_str(",\"shakedexEnabled\":");
        json.push_str(if shakedex_enabled { "true" } else { "false" });
        json.push_str(",\"mainnetSettlementEnabled\":");
        json.push_str(if status.mainnet_settlement_enabled {
            "true"
        } else {
            "false"
        });
        json.push('}');
        let output = allocate_output(json.as_bytes(), false)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_status_json, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_accounts_json` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_accounts(
    wallet: HnsBrowserWalletHandle,
    out_accounts_json: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_accounts_json)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_accounts_json, HnsBrowserBuffer::empty()) };
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let accounts = entry
            .controller
            .with_mut(
                |controller| controller.accounts(),
                |controller| controller.accounts(),
                |controller| controller.accounts(),
            )
            .map_err(|_| wallet_runtime_failure("unable to read native wallet accounts"))?;
        if accounts.len() != 1
            || accounts[0].receive_display.is_some()
            || module_wire_name(accounts[0].module) != "handshake"
        {
            let _ = entry.controller.with_mut(
                |controller| controller.lock(),
                |controller| controller.lock(),
                |controller| controller.lock(),
            );
            return Err(wallet_runtime_failure(
                "native wallet exposed a forbidden account set",
            ));
        }
        let mut json = String::from("[");
        for (index, account) in accounts.iter().enumerate() {
            if index != 0 {
                json.push(',');
            }
            json.push_str("{\"accountId\":");
            json_string(&mut json, &account.account_id.to_string());
            json.push_str(",\"module\":");
            json_string(&mut json, &module_wire_name(account.module));
            json.push_str(",\"label\":");
            json_string(&mut json, &account.label);
            json.push_str(",\"receiveDisplay\":");
            if let Some(receive_display) = account.receive_display.as_deref() {
                json_string(&mut json, receive_display);
            } else {
                json.push_str("null");
            }
            json.push('}');
        }
        json.push(']');
        let output = allocate_output(json.as_bytes(), false)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_accounts_json, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Installs the synchronized HNS read controller around an existing lifecycle
/// controller. The endpoint is always `127.0.0.1:port`; native callers cannot
/// select a hostname, URL, proxy, redirect, or remote address. The borrowed
/// authorization bytes are sensitive and must be wiped by the caller. A newly
/// created recovery-confirmation controller is not eligible; the durable wallet
/// must first be committed by the platform and reopened.
///
/// # Safety
/// The authorization slice must remain readable for its declared length.
pub unsafe extern "C" fn hns_browser_wallet_configure_hns_reads(
    wallet: HnsBrowserWalletHandle,
    loopback_port: u16,
    authorization: HnsBrowserSlice,
) -> HnsBrowserResult {
    ffi_call(|| {
        let entry = wallet_entry(wallet)?;
        // SAFETY: This export carries the caller's readable-slice contract.
        let backend = unsafe { wallet_hns_read_backend(loopback_port, authorization) }?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        if !entry.hns_reads_installable || !entry.controller.is_lifecycle() {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "synchronized HNS wallet reads require a reopened durable controller",
            ));
        }
        entry
            .controller
            .enable_hns_reads(backend)
            .map_err(|_| wallet_runtime_failure("unable to install synchronized HNS wallet reads"))
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_enabled` must point to one writable byte. Only zero or one is written.
pub unsafe extern "C" fn hns_browser_wallet_has_hns_reads(
    wallet: HnsBrowserWalletHandle,
    out_enabled: *mut u8,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_enabled)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_enabled, 0) };
        let entry = wallet_entry(wallet)?;
        let entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_enabled, u8::from(entry.controller.has_hns_reads())) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Installs the wallet-owned direct HNS controller around one reopened durable
/// wallet. The direct controller derives its own account watch set, discovers
/// HNS peers, validates consensus headers/filtered blocks, and broadcasts
/// through those peers; callers cannot provide an RPC endpoint, peer, relay,
/// or provider payload.
///
/// `rollback_floor` is the exact 36-byte device-bound `height || chainwork`
/// floor. `bootstrap_snapshot_path` is either null/zero for non-mainnet
/// checkpoint paths or the app-private decompressed bundled snapshot.
///
/// # Safety
/// All non-empty slices must remain readable for their declared length.
pub unsafe extern "C" fn hns_browser_wallet_configure_direct_hns_value(
    wallet: HnsBrowserWalletHandle,
    database_key: HnsBrowserSlice,
    rollback_floor: HnsBrowserSlice,
    bootstrap_snapshot_path: HnsBrowserSlice,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This export carries the caller's readable-slice contracts.
        let key = unsafe { wallet_database_key(database_key) }?;
        // SAFETY: This export carries the caller's readable-slice contracts.
        let floor = unsafe { wallet_direct_hns_floor(rollback_floor) }?;
        // SAFETY: This export carries the caller's readable-slice contracts.
        let snapshot_path = unsafe { wallet_optional_snapshot_path(bootstrap_snapshot_path) }?;
        let bitcoin_control = wallet_bitcoin_control_entry(wallet)?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        if !entry.hns_reads_installable || !entry.controller.is_lifecycle() {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct HNS wallet requires a reopened durable controller",
            ));
        }
        let bitcoin_data_dir = entry.bitcoin_data_dir.clone();
        let mut bitcoin = entry
            .controller
            .enable_direct_hns_value(&key, floor, snapshot_path.as_deref(), bitcoin_data_dir)
            .map_err(|_| {
                wallet_runtime_failure("unable to install direct HNS and Bitcoin wallet")
            })?;
        let hns_unlocked = entry
            .controller
            .with_mut(
                |controller| controller.status(),
                |controller| controller.status(),
                |controller| controller.status(),
            )
            .map(|status| !status.locked)
            .unwrap_or(false);
        drop(entry);
        let mut slot = bitcoin_control
            .controller
            .lock()
            .map_err(|_| FfiFailure::internal())?;
        if slot.is_some() {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct Bitcoin wallet is already configured",
            ));
        }
        if hns_unlocked && bitcoin.activate().is_ok() {
            bitcoin_control.replace_runtime_handles(&bitcoin);
        }
        *slot = Some(bitcoin);
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_enabled` must point to one writable byte. Only zero or one is written.
pub unsafe extern "C" fn hns_browser_wallet_has_hns_value(
    wallet: HnsBrowserWalletHandle,
    out_enabled: *mut u8,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_enabled)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_enabled, 0) };
        let entry = wallet_entry(wallet)?;
        let entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_enabled, u8::from(entry.controller.has_hns_value())) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Returns the exact direct controller rollback floor. This is public chain
/// authority metadata rather than wallet-secret material, but it remains an
/// app-native result and is never exposed to WebKit.
///
/// # Safety
/// `out_floor` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_direct_hns_rollback_floor(
    wallet: HnsBrowserWalletHandle,
    out_floor: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_floor)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_floor, HnsBrowserBuffer::empty()) };
        let entry = wallet_entry(wallet)?;
        let entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let NativeWalletController::DirectHnsValue { coordinator, .. } = &entry.controller else {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct HNS wallet is not configured",
            ));
        };
        let bytes = wallet_direct_hns_floor_bytes(
            coordinator
                .rollback_floor()
                .map_err(|_| wallet_runtime_failure("direct HNS rollback floor is unavailable"))?,
        );
        let output = allocate_output(&bytes, false)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_floor, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Derives the active ordinary HNS payment target from the unlocked local
/// wallet. This is intentionally local-only: it does not query peers, claim a
/// balance, or advance synchronization state.
///
/// # Safety
/// `out_target_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_local_hns_receive_target(
    wallet: HnsBrowserWalletHandle,
    out_target_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_target_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_target_bundle, HnsBrowserBuffer::empty()) };
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let NativeWalletController::DirectHnsValue { controller, .. } = &mut entry.controller
        else {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct HNS wallet is not configured",
            ));
        };
        let target = controller
            .local_receive_target()
            .map_err(|_| wallet_runtime_failure("local HNS receive target is unavailable"))?;
        let mut json = serde_json::to_vec(&target)
            .map_err(|_| wallet_runtime_failure("unable to encode local HNS receive target"))?;
        let bundle = wallet_json_bundle(
            json.as_slice(),
            WALLET_HNS_RECEIVE_BUNDLE_MAGIC,
            WALLET_HNS_RECEIVE_BUNDLE_VERSION,
            MAX_WALLET_HNS_RECEIVE_JSON_BYTES,
        )?;
        json.fill(0);
        let output = allocate_output(&bundle.0, true)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_target_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Prepares one exact native HNS payment. The action cannot broadcast until
/// the returned single-use approval token is explicitly approved by the
/// native UI; no WebKit or page data path can invoke this C entry point.
///
/// # Safety
/// The input slices must remain readable for their declared lengths and
/// `out_approval_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_prepare_hns_send(
    wallet: HnsBrowserWalletHandle,
    recipient: HnsBrowserSlice,
    amount_base_units: HnsBrowserSlice,
    maximum_fee_base_units: HnsBrowserSlice,
    out_approval_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_approval_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_approval_bundle, HnsBrowserBuffer::empty()) };
        // SAFETY: This export carries the caller's readable-slice contracts.
        let recipient =
            unsafe { wallet_visible_ascii(recipient, MAX_WALLET_VALUE_RECIPIENT_BYTES) }?;
        // SAFETY: This export carries the caller's readable-slice contracts.
        let amount = unsafe { wallet_nonzero_base_units(amount_base_units) }?;
        // SAFETY: This export carries the caller's readable-slice contracts.
        let maximum_fee = unsafe { wallet_nonzero_base_units(maximum_fee_base_units) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let NativeWalletController::DirectHnsValue { controller, .. } = &mut entry.controller
        else {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct HNS wallet is not configured",
            ));
        };
        let approval = controller
            .prepare_value_action(MobileHnsValueIntent::Send {
                recipient,
                amount,
                maximum_fee,
            })
            .map_err(|_| {
                direct_hns_not_ready("HNS send preparation requires a current wallet scan")
            })?;
        if approval.summary.validate().is_err() {
            let _ = controller.lock();
            return Err(wallet_runtime_failure("HNS send approval is invalid"));
        }
        let mut json = serde_json::to_vec(&approval)
            .map_err(|_| wallet_runtime_failure("unable to encode HNS send approval"))?;
        let bundle = match wallet_json_bundle(
            json.as_slice(),
            WALLET_VALUE_APPROVAL_BUNDLE_MAGIC,
            WALLET_VALUE_APPROVAL_BUNDLE_VERSION,
            MAX_WALLET_VALUE_APPROVAL_JSON_BYTES,
        ) {
            Ok(bundle) => bundle,
            Err(error) => {
                json.fill(0);
                let _ = controller.lock();
                return Err(error);
            }
        };
        json.fill(0);
        let output = match allocate_output(&bundle.0, true) {
            Ok(output) => output,
            Err(error) => {
                let _ = controller.lock();
                return Err(error);
            }
        };
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_approval_bundle, output) };
        Ok(())
    })
}

/// Prepares one closed native HNS name or Shakedex value action. The JSON is
/// decoded directly into the published `MobileHnsValueIntent` enum and is
/// accepted only from the local iOS wallet UI; no provider, URL, peer, or
/// WebKit frame can invoke this entry point. The returned HNVP-v1 bundle must
/// be displayed and explicitly approved or rejected exactly once.
///
/// # Safety
/// `intent_json` must remain readable for its declared length and
/// `out_approval_bundle` must point to one writable owned-buffer value.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hns_browser_wallet_prepare_hns_value_action(
    wallet: HnsBrowserWalletHandle,
    intent_json: HnsBrowserSlice,
    out_approval_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_approval_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_approval_bundle, HnsBrowserBuffer::empty()) };
        // SAFETY: This export carries the caller's readable-slice contract.
        let intent = unsafe { wallet_value_intent(intent_json) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let NativeWalletController::DirectHnsValue { controller, .. } = &mut entry.controller
        else {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct HNS wallet is not configured",
            ));
        };
        let approval = controller
            .prepare_value_action(intent)
            .map_err(|_| direct_hns_not_ready("HNS value action requires a current wallet scan"))?;
        if approval.summary.validate().is_err() {
            let _ = controller.lock();
            return Err(wallet_runtime_failure("HNS value approval is invalid"));
        }
        let mut json = serde_json::to_vec(&approval)
            .map_err(|_| wallet_runtime_failure("unable to encode HNS value approval"))?;
        let bundle = match wallet_json_bundle(
            json.as_slice(),
            WALLET_VALUE_APPROVAL_BUNDLE_MAGIC,
            WALLET_VALUE_APPROVAL_BUNDLE_VERSION,
            MAX_WALLET_VALUE_APPROVAL_JSON_BYTES,
        ) {
            Ok(bundle) => bundle,
            Err(error) => {
                json.fill(0);
                let _ = controller.lock();
                return Err(error);
            }
        };
        json.fill(0);
        let output = match allocate_output(&bundle.0, true) {
            Ok(output) => output,
            Err(error) => {
                let _ = controller.lock();
                return Err(error);
            }
        };
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_approval_bundle, output) };
        Ok(())
    })
}

/// Runs one bounded local Shakedex query through the configured direct HNS
/// controller. Its HNVQ-v1 result remains native-only and is not a page
/// provider response. Queries do not authorize a transaction and do not
/// advance the direct header/rollback authority.
///
/// # Safety
/// `query_json` must remain readable for its declared length and
/// `out_result_bundle` must point to one writable owned-buffer value.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hns_browser_wallet_query_shakedex(
    wallet: HnsBrowserWalletHandle,
    query_json: HnsBrowserSlice,
    out_result_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_result_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_result_bundle, HnsBrowserBuffer::empty()) };
        // SAFETY: This export carries the caller's readable-slice contract.
        let query = unsafe { wallet_shakedex_query(query_json) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let NativeWalletController::DirectHnsValue { controller, .. } = &mut entry.controller
        else {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct HNS wallet is not configured",
            ));
        };
        let result = controller
            .query_shakedex(query)
            .map_err(|_| direct_hns_not_ready("HNS Shakedex query is unavailable"))?;
        if !result.is_object() {
            let _ = controller.lock();
            return Err(wallet_runtime_failure("HNS Shakedex result is invalid"));
        }
        let mut json = serde_json::to_vec(&result)
            .map_err(|_| wallet_runtime_failure("unable to encode HNS Shakedex result"))?;
        let bundle = match wallet_json_bundle(
            json.as_slice(),
            WALLET_SHAKEDEX_QUERY_BUNDLE_MAGIC,
            WALLET_SHAKEDEX_QUERY_BUNDLE_VERSION,
            MAX_WALLET_SHAKEDEX_RESULT_JSON_BYTES,
        ) {
            Ok(bundle) => bundle,
            Err(error) => {
                json.fill(0);
                let _ = controller.lock();
                return Err(error);
            }
        };
        json.fill(0);
        let output = match allocate_output(&bundle.0, true) {
            Ok(output) => output,
            Err(error) => {
                let _ = controller.lock();
                return Err(error);
            }
        };
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_result_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_status_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_direct_denuo_status(
    wallet: HnsBrowserWalletHandle,
    out_status_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_status_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_status_bundle, HnsBrowserBuffer::empty()) };
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let bundle = entry.controller.direct_denuo_status().ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct Denuo transport is unavailable",
            )
        })?;
        let output = allocate_output(&bundle, false)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_status_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn hns_browser_wallet_retry_direct_denuo_listener(
    wallet: HnsBrowserWalletHandle,
) -> HnsBrowserResult {
    ffi_call(|| {
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        entry
            .controller
            .start_direct_denuo_listener()
            .then_some(())
            .ok_or_else(|| {
                FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_READY,
                    "direct Denuo listener is unavailable",
                )
            })
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `endpoint` must remain readable and `out_connect_bundle` must be writable.
pub unsafe extern "C" fn hns_browser_wallet_connect_direct_denuo(
    wallet: HnsBrowserWalletHandle,
    endpoint: HnsBrowserSlice,
    out_connect_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_connect_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_connect_bundle, HnsBrowserBuffer::empty()) };
        // SAFETY: This export carries the caller's readable-slice contract.
        let endpoint = unsafe { wallet_denuo_endpoint(endpoint) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let result = entry.controller.connect_direct_denuo_peer(endpoint);
        let bundle = wallet_direct_denuo_connect_bundle(result).ok_or_else(FfiFailure::internal)?;
        let output = allocate_output(&bundle, false)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_connect_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_disconnected` must point to one writable byte.
pub unsafe extern "C" fn hns_browser_wallet_disconnect_direct_denuo(
    wallet: HnsBrowserWalletHandle,
    out_disconnected: *mut u8,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_disconnected)?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let disconnected = u8::from(entry.controller.disconnect_direct_denuo_peer());
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_disconnected, disconnected) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_serviced` must point to one writable byte.
pub unsafe extern "C" fn hns_browser_wallet_service_direct_denuo(
    wallet: HnsBrowserWalletHandle,
    out_serviced: *mut u8,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_serviced)?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let serviced = u8::from(entry.controller.service_direct_denuo_once());
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_serviced, serviced) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Prepares fixed BTC-for-HNS terms without signing or reserving chain inputs.
///
/// # Safety
/// `out_approval_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_prepare_btc_for_hns_offer(
    wallet: HnsBrowserWalletHandle,
    btc_amount_sats: u64,
    hns_amount_dollarydoos: u64,
    bitcoin_fee_reserve_sats: u64,
    listing_lifetime_seconds: u64,
    out_approval_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_approval_bundle)?;
        unsafe { write_output(out_approval_bundle, HnsBrowserBuffer::empty()) };
        let confirmed_sats = {
            let control = wallet_bitcoin_control_entry(wallet)?;
            let slot = control.controller.try_lock().map_err(|error| match error {
                TryLockError::WouldBlock => direct_hns_not_ready("direct Bitcoin wallet is busy"),
                TryLockError::Poisoned(_) => FfiFailure::internal(),
            })?;
            slot.as_ref()
                .filter(|controller| controller.is_active())
                .ok_or_else(|| direct_hns_not_ready("direct Bitcoin wallet is not active"))?
                .snapshot()
                .map_err(|_| wallet_runtime_failure("direct Bitcoin snapshot failed"))?
                .confirmed_sats
        };
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let approval = entry
            .controller
            .prepare_btc_for_hns_offer(
                confirmed_sats,
                btc_amount_sats,
                hns_amount_dollarydoos,
                bitcoin_fee_reserve_sats,
                listing_lifetime_seconds,
            )
            .map_err(|_| wallet_runtime_failure("BTC-for-HNS offer preparation failed"))?;
        let bundle = wallet_bitcoin_bundle(&approval)?;
        let output = allocate_output(&bundle.0, true)?;
        unsafe { write_output(out_approval_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `action_token` must remain readable and `out_summary_bundle` writable.
pub unsafe extern "C" fn hns_browser_wallet_approve_btc_for_hns_offer(
    wallet: HnsBrowserWalletHandle,
    action_token: HnsBrowserSlice,
    out_summary_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_summary_bundle)?;
        unsafe { write_output(out_summary_bundle, HnsBrowserBuffer::empty()) };
        let action_token = unsafe { wallet_action_token(action_token) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let summary = entry
            .controller
            .approve_btc_for_hns_offer(&action_token)
            .map_err(|_| wallet_runtime_failure("BTC-for-HNS offer publication failed"))?;
        let bundle = wallet_bitcoin_bundle(&summary)?;
        let output = allocate_output(&bundle.0, true)?;
        unsafe { write_output(out_summary_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `action_token` must remain readable for its declared length.
pub unsafe extern "C" fn hns_browser_wallet_reject_btc_for_hns_offer(
    wallet: HnsBrowserWalletHandle,
    action_token: HnsBrowserSlice,
) -> HnsBrowserResult {
    ffi_call(|| {
        let action_token = unsafe { wallet_action_token(action_token) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        entry
            .controller
            .reject_btc_for_hns_offer(&action_token)
            .map_err(|_| wallet_runtime_failure("BTC-for-HNS offer rejection failed"))
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_offers_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_local_btc_for_hns_offers(
    wallet: HnsBrowserWalletHandle,
    out_offers_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_offers_bundle)?;
        unsafe { write_output(out_offers_bundle, HnsBrowserBuffer::empty()) };
        let entry = wallet_entry(wallet)?;
        let entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let offers = entry
            .controller
            .local_btc_for_hns_offers()
            .map_err(|_| wallet_runtime_failure("BTC-for-HNS offer listing failed"))?;
        let bundle = wallet_bitcoin_bundle(&json!({ "offers": offers }))?;
        let output = allocate_output(&bundle.0, true)?;
        unsafe { write_output(out_offers_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `offer_id` must remain readable for its declared length.
pub unsafe extern "C" fn hns_browser_wallet_cancel_btc_for_hns_offer(
    wallet: HnsBrowserWalletHandle,
    offer_id: HnsBrowserSlice,
) -> HnsBrowserResult {
    ffi_call(|| {
        let offer_id = unsafe { wallet_action_token(offer_id) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        entry
            .controller
            .cancel_btc_for_hns_offer(&offer_id)
            .map_err(|_| wallet_runtime_failure("BTC-for-HNS offer cancellation failed"))
    })
}

#[unsafe(no_mangle)]
/// Consumes one displayed direct-HNS send approval and returns a minimized
/// receipt only after the native controller accepts the broadcast.
///
/// # Safety
/// `action_token` must remain readable for its declared length and
/// `out_receipt_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_approve_hns_send(
    wallet: HnsBrowserWalletHandle,
    action_token: HnsBrowserSlice,
    out_receipt_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_receipt_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_receipt_bundle, HnsBrowserBuffer::empty()) };
        // SAFETY: This export carries the caller's readable-slice contract.
        let action_token = unsafe { wallet_action_token(action_token) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let NativeWalletController::DirectHnsValue { controller, .. } = &mut entry.controller
        else {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct HNS wallet is not configured",
            ));
        };
        let result = controller
            .approve_value_action(action_token.as_str())
            .map_err(|_| wallet_runtime_failure("HNS send approval was rejected"))?;
        let receipt = native_hns_send_receipt(result).ok_or_else(|| {
            let _ = controller.lock();
            wallet_runtime_failure("HNS send result is invalid")
        })?;
        let mut json = serde_json::to_vec(&receipt)
            .map_err(|_| wallet_runtime_failure("unable to encode HNS send receipt"))?;
        let bundle = match wallet_json_bundle(
            json.as_slice(),
            WALLET_VALUE_RESULT_BUNDLE_MAGIC,
            WALLET_VALUE_RESULT_BUNDLE_VERSION,
            MAX_WALLET_VALUE_RESULT_JSON_BYTES,
        ) {
            Ok(bundle) => bundle,
            Err(error) => {
                json.fill(0);
                let _ = controller.lock();
                return Err(error);
            }
        };
        json.fill(0);
        let output = match allocate_output(&bundle.0, true) {
            Ok(output) => output,
            Err(error) => {
                let _ = controller.lock();
                return Err(error);
            }
        };
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_receipt_bundle, output) };
        Ok(())
    })
}

/// Approves one displayed non-send HNS value action. The exact native result
/// is returned in a private HNVX-v1 envelope only when it is a bounded JSON
/// object. UIKit treats a malformed or absent result as an ambiguous value
/// outcome and locks before allowing another action.
///
/// # Safety
/// `action_token` must remain readable for its declared length and
/// `out_result_bundle` must point to one writable owned-buffer value.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn hns_browser_wallet_approve_hns_value_action_result(
    wallet: HnsBrowserWalletHandle,
    action_token: HnsBrowserSlice,
    out_result_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_result_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_result_bundle, HnsBrowserBuffer::empty()) };
        // SAFETY: This export carries the caller's readable-slice contract.
        let action_token = unsafe { wallet_action_token(action_token) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let NativeWalletController::DirectHnsValue { controller, .. } = &mut entry.controller
        else {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct HNS wallet is not configured",
            ));
        };
        let result = controller
            .approve_value_action(action_token.as_str())
            .map_err(|_| wallet_runtime_failure("HNS value approval was rejected"))?;
        if !result.is_object() {
            let _ = controller.lock();
            return Err(wallet_runtime_failure("HNS value result is invalid"));
        }
        let mut json = serde_json::to_vec(&result)
            .map_err(|_| wallet_runtime_failure("unable to encode HNS value result"))?;
        let bundle = match wallet_json_bundle(
            json.as_slice(),
            WALLET_VALUE_RESULT_BUNDLE_MAGIC,
            WALLET_VALUE_RESULT_BUNDLE_VERSION,
            MAX_WALLET_VALUE_RESULT_JSON_BYTES,
        ) {
            Ok(bundle) => bundle,
            Err(error) => {
                json.fill(0);
                let _ = controller.lock();
                return Err(error);
            }
        };
        json.fill(0);
        let output = match allocate_output(&bundle.0, true) {
            Ok(output) => output,
            Err(error) => {
                let _ = controller.lock();
                return Err(error);
            }
        };
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_result_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Rejects and consumes one displayed direct-HNS send approval.
///
/// # Safety
/// `action_token` must remain readable for its declared length.
pub unsafe extern "C" fn hns_browser_wallet_reject_hns_send(
    wallet: HnsBrowserWalletHandle,
    action_token: HnsBrowserSlice,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This export carries the caller's readable-slice contract.
        let action_token = unsafe { wallet_action_token(action_token) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let NativeWalletController::DirectHnsValue { controller, .. } = &mut entry.controller
        else {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "direct HNS wallet is not configured",
            ));
        };
        controller
            .reject_value_action(action_token.as_str())
            .map_err(|_| wallet_runtime_failure("HNS send rejection failed"))
    })
}

#[unsafe(no_mangle)]
/// Imports one exact canonical Handshake name only through the trusted native
/// HNS read controller. No trimming, case conversion, IDNA, Unicode
/// normalization, or trailing-dot editing occurs. On success, the returned
/// private HNWI-v1 bundle contains exactly one minimized summary. Every
/// non-success is returned as a C result with an empty output descriptor.
/// Callers must free successful output promptly and never expose it to website
/// content.
///
/// # Safety
/// The exact-name slice must remain readable for its declared length and
/// `out_summary_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_import_hns_name_exact_text(
    wallet: HnsBrowserWalletHandle,
    exact_name: HnsBrowserSlice,
    out_summary_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_summary_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_summary_bundle, HnsBrowserBuffer::empty()) };
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let summary = match &mut entry.controller {
            NativeWalletController::HnsReads(controller) => {
                // Parse only after proving this is the read controller. Length
                // is rejected before any caller pointer is dereferenced.
                // SAFETY: This export carries the caller's readable-slice contract.
                let name = unsafe { wallet_exact_hns_name(exact_name) }?;
                let text = std::str::from_utf8(&name.0).map_err(|_| {
                    FfiFailure::new(
                        HNS_BROWSER_RESULT_INVALID_UTF8,
                        "wallet HNS name is not valid UTF-8",
                    )
                })?;
                match controller.import_name_exact_text(text) {
                    Ok(summary) if summary.name.as_bytes() == name.0.as_slice() => summary,
                    Ok(_) => {
                        let _ = controller.lock();
                        return Err(wallet_runtime_failure(
                            "HNS name import summary changed the exact input text",
                        ));
                    }
                    Err(error) => {
                        let failure = wallet_name_import_failure(&error);
                        if failure.code != HNS_BROWSER_RESULT_INVALID_ARGUMENT {
                            // Projection/evidence failures can occur after the
                            // service call. Enforce the lock at this ABI boundary
                            // rather than relying only on upstream behavior.
                            let _ = controller.lock();
                        }
                        return Err(failure);
                    }
                }
            }
            NativeWalletController::DirectHnsValue {
                coordinator,
                controller,
                ..
            } => {
                // Parse only after proving the direct value controller is
                // live. Exact text is preserved through proof acquisition and
                // the downstream native import; no display-side name rewrite
                // can alter the wallet evidence being tracked.
                let name = unsafe { wallet_exact_hns_name(exact_name) }?;
                let text = std::str::from_utf8(&name.0).map_err(|_| {
                    FfiFailure::new(
                        HNS_BROWSER_RESULT_INVALID_UTF8,
                        "wallet HNS name is not valid UTF-8",
                    )
                })?;
                let now_unix = HnsReadSystemClock
                    .now_unix()
                    .map_err(|_| wallet_runtime_failure("direct HNS clock is unavailable"))?;
                coordinator
                    .connect_available(now_unix)
                    .map_err(|_| direct_hns_not_ready("direct HNS peers are unavailable"))?;
                coordinator
                    .synchronize_name_proof_exact_text(text, now_unix)
                    .map_err(|_| direct_hns_not_ready("direct HNS name proof is unavailable"))?;
                match controller.import_name_exact_text(text) {
                    Ok(summary) if summary.name.as_bytes() == name.0.as_slice() => summary,
                    Ok(_) => {
                        let _ = controller.lock();
                        return Err(wallet_runtime_failure(
                            "HNS name import summary changed the exact input text",
                        ));
                    }
                    Err(error) => {
                        let failure = wallet_name_import_failure(&error);
                        if failure.code != HNS_BROWSER_RESULT_INVALID_ARGUMENT {
                            let _ = controller.lock();
                        }
                        return Err(failure);
                    }
                }
            }
            NativeWalletController::Lifecycle(_) => {
                return Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_READY,
                    "trusted-native HNS name import requires synchronized wallet reads",
                ));
            }
            NativeWalletController::Failed => {
                return Err(wallet_runtime_failure(
                    "native wallet controller has failed",
                ));
            }
        };
        let bundle = match wallet_name_import_bundle(&summary) {
            Ok(bundle) => bundle,
            Err(error) => {
                entry.controller.lock_fail_closed();
                return Err(error);
            }
        };
        let output = match allocate_output(&bundle.0, true) {
            Ok(output) => output,
            Err(error) => {
                entry.controller.lock_fail_closed();
                return Err(error);
            }
        };
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_summary_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Performs one bounded synchronized read. The returned private bundle is
/// `HNWR`, version 2, read-only-HNS flags, zero reserved bytes, a big-endian
/// JSON length, and the exact serialized `MobileHnsReadSnapshot`. Callers must
/// free it promptly and must never expose it to website content or logs.
///
/// # Safety
/// `out_snapshot_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_synchronize_hns_reads(
    wallet: HnsBrowserWalletHandle,
    out_snapshot_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_snapshot_bundle)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_snapshot_bundle, HnsBrowserBuffer::empty()) };
        let sync_control = wallet_hns_sync_control_entry(wallet)?;
        {
            let mut activity = sync_control
                .activity
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            if activity.active {
                return Err(direct_hns_not_ready(
                    "direct HNS synchronization is already active",
                ));
            }
            activity.active = true;
            activity.cancellation_requested = false;
        }
        let _sync_activity = WalletHnsSyncActivity {
            control: Arc::clone(&sync_control),
        };
        if let Ok(mut current) = sync_control.progress.lock() {
            *current = None;
        } else {
            return Err(FfiFailure::internal());
        }
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        ensure_wallet_hns_sync_not_cancelled(sync_control.as_ref())?;
        let snapshot = match &mut entry.controller {
            NativeWalletController::HnsReads(controller) => controller
                .synchronize()
                .map_err(|_| wallet_runtime_failure("synchronized HNS wallet read failed"))?,
            NativeWalletController::DirectHnsValue {
                coordinator,
                controller,
                ..
            } => {
                synchronize_wallet_owned_direct_hns(coordinator, controller, sync_control.as_ref())?
            }
            NativeWalletController::Lifecycle(_) => {
                return Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_READY,
                    "synchronized HNS wallet reads are not configured",
                ));
            }
            NativeWalletController::Failed => {
                return Err(wallet_runtime_failure(
                    "native wallet controller has failed",
                ));
            }
        };
        ensure_wallet_hns_sync_not_cancelled(sync_control.as_ref())?;
        let bundle = wallet_read_bundle(&snapshot)?;
        let output = allocate_output(&bundle.0, true)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_snapshot_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Reads public direct-HNS synchronization metadata without taking the wallet
/// controller mutex. The output contains heights and a coarse stage only.
///
/// # Safety
/// `out_progress` must point to one writable progress value.
pub unsafe extern "C" fn hns_browser_wallet_hns_sync_progress(
    wallet: HnsBrowserWalletHandle,
    out_progress: *mut HnsBrowserWalletHnsSyncProgress,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_progress)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_progress, HnsBrowserWalletHnsSyncProgress::empty()) };
        let control = wallet_hns_sync_control_entry(wallet)?;
        let progress = *control
            .progress
            .lock()
            .map_err(|_| FfiFailure::internal())?;
        let progress = progress.ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "wallet HNS synchronization progress is unavailable",
            )
        })?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_progress, progress) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Records cancellation immediately without waiting for the wallet controller
/// mutex. The active atomic peer/database call unwinds to its last durable
/// result, and no subsequent synchronization batch is started.
pub extern "C" fn hns_browser_wallet_cancel_hns_sync(
    wallet: HnsBrowserWalletHandle,
) -> HnsBrowserResult {
    ffi_call(|| {
        let control = wallet_hns_sync_control_entry(wallet)?;
        let mut activity = control
            .activity
            .lock()
            .map_err(|_| FfiFailure::internal())?;
        if !activity.active {
            return Err(FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "wallet HNS synchronization is not active",
            ));
        }
        activity.cancellation_requested = true;
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Reports whether the independent wallet-owned Kyoto controller is active.
///
/// # Safety
/// `out_enabled` must point to one writable byte.
pub unsafe extern "C" fn hns_browser_wallet_has_bitcoin_value(
    wallet: HnsBrowserWalletHandle,
    out_enabled: *mut u8,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_enabled)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_enabled, 0) };
        let control = wallet_bitcoin_control_entry(wallet)?;
        let slot = control.controller.try_lock().map_err(|error| match error {
            TryLockError::WouldBlock => direct_hns_not_ready("direct Bitcoin wallet is busy"),
            TryLockError::Poisoned(_) => FfiFailure::internal(),
        })?;
        let enabled = slot
            .as_ref()
            .is_some_and(MobileBitcoinValueController::is_active);
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_enabled, u8::from(enabled)) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Returns the last durable direct Bitcoin projection without networking.
///
/// # Safety
/// `out_snapshot_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_bitcoin_snapshot(
    wallet: HnsBrowserWalletHandle,
    out_snapshot_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_snapshot_bundle)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_snapshot_bundle, HnsBrowserBuffer::empty()) };
        let control = wallet_bitcoin_control_entry(wallet)?;
        let slot = control.controller.try_lock().map_err(|error| match error {
            TryLockError::WouldBlock => {
                direct_hns_not_ready("direct Bitcoin synchronization is active")
            }
            TryLockError::Poisoned(_) => FfiFailure::internal(),
        })?;
        let controller = slot
            .as_ref()
            .filter(|controller| controller.is_active())
            .ok_or_else(|| direct_hns_not_ready("direct Bitcoin wallet is not active"))?;
        let snapshot = controller
            .snapshot()
            .map_err(|_| wallet_runtime_failure("direct Bitcoin snapshot failed"))?;
        let bundle = wallet_bitcoin_bundle(&snapshot)?;
        let output = allocate_output(&bundle.0, true)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_snapshot_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Explicitly resets an incomplete Bitcoin recovery scan to the validated
/// predecessor of `earliest_transaction_height`.
///
/// # Safety
/// `out_snapshot_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_set_bitcoin_birthday_height(
    wallet: HnsBrowserWalletHandle,
    earliest_transaction_height: u32,
    out_snapshot_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_snapshot_bundle)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_snapshot_bundle, HnsBrowserBuffer::empty()) };
        if earliest_transaction_height == 0 {
            return Err(FfiFailure::invalid(
                "Bitcoin birthday height must be nonzero",
            ));
        }
        let control = wallet_bitcoin_control_entry(wallet)?;
        if control
            .activity
            .lock()
            .map_err(|_| FfiFailure::internal())?
            .active
        {
            return Err(direct_hns_not_ready(
                "stop Bitcoin synchronization before changing its birthday",
            ));
        }
        let mut slot = control.controller.try_lock().map_err(|error| match error {
            TryLockError::WouldBlock => direct_hns_not_ready("direct Bitcoin wallet is busy"),
            TryLockError::Poisoned(_) => FfiFailure::internal(),
        })?;
        let controller = slot
            .as_mut()
            .filter(|controller| controller.is_active())
            .ok_or_else(|| direct_hns_not_ready("direct Bitcoin wallet is not active"))?;
        let snapshot = controller
            .set_birthday_height(earliest_transaction_height)
            .map_err(|_| wallet_runtime_failure("direct Bitcoin birthday reset failed"))?;
        control.replace_runtime_handles(controller);
        let bundle = wallet_bitcoin_bundle(&snapshot)?;
        let output = allocate_output(&bundle.0, true)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_snapshot_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Reveals and persists one locally derived BIP84 receive address.
///
/// # Safety
/// `out_receive_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_next_bitcoin_receive_address(
    wallet: HnsBrowserWalletHandle,
    out_receive_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_receive_bundle)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_receive_bundle, HnsBrowserBuffer::empty()) };
        let control = wallet_bitcoin_control_entry(wallet)?;
        let mut slot = control.controller.try_lock().map_err(|error| match error {
            TryLockError::WouldBlock => {
                direct_hns_not_ready("direct Bitcoin synchronization is active")
            }
            TryLockError::Poisoned(_) => FfiFailure::internal(),
        })?;
        let controller = slot
            .as_mut()
            .filter(|controller| controller.is_active())
            .ok_or_else(|| direct_hns_not_ready("direct Bitcoin wallet is not active"))?;
        let receive_address = controller
            .next_receive_address()
            .map_err(|_| wallet_runtime_failure("direct Bitcoin receive address failed"))?;
        let snapshot = controller
            .snapshot()
            .map_err(|_| wallet_runtime_failure("direct Bitcoin receive snapshot failed"))?;
        let bundle = wallet_bitcoin_bundle(&json!({
            "receiveAddress": receive_address,
            "snapshot": snapshot,
        }))?;
        let output = allocate_output(&bundle.0, true)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_receive_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Drives one user-scheduled compact-filter synchronization cycle. Bitcoin
/// owns a distinct mutex, so this call never prevents HNS controller use.
///
/// # Safety
/// `out_sync_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_synchronize_bitcoin(
    wallet: HnsBrowserWalletHandle,
    out_sync_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_sync_bundle)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_sync_bundle, HnsBrowserBuffer::empty()) };
        let control = wallet_bitcoin_control_entry(wallet)?;
        {
            let mut activity = control
                .activity
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            if !activity.begin() {
                return Err(direct_hns_not_ready(
                    "direct Bitcoin synchronization is already active",
                ));
            }
        }
        let _activity = WalletBitcoinSyncActivity {
            control: Arc::clone(&control),
        };
        let mut slot = control.controller.try_lock().map_err(|error| match error {
            TryLockError::WouldBlock => direct_hns_not_ready("direct Bitcoin wallet is busy"),
            TryLockError::Poisoned(_) => FfiFailure::internal(),
        })?;
        let controller = slot
            .as_mut()
            .filter(|controller| controller.is_active())
            .ok_or_else(|| direct_hns_not_ready("direct Bitcoin wallet is not active"))?;
        let synchronization = controller.synchronize_once();
        let cancelled = control
            .activity
            .lock()
            .map(|activity| activity.cancellation_requested)
            .unwrap_or(true);
        if cancelled {
            let _ = controller.deactivate();
            if controller.activate().is_ok() {
                control.replace_runtime_handles(controller);
            } else {
                control.clear_runtime_handles();
            }
            return Err(direct_hns_not_ready(
                "direct Bitcoin synchronization was stopped",
            ));
        }
        let (receipt, snapshot) = synchronization
            .map_err(|_| wallet_runtime_failure("direct Bitcoin synchronization failed"))?;
        let bundle = wallet_bitcoin_bundle(&json!({
            "snapshot": snapshot,
            "sequence": receipt.sequence,
            "checkpointHeight": receipt.checkpoint.height,
            "connectedPeerCount": receipt.connected_peer_count,
            "requiredPeerCount": receipt.required_peer_count,
        }))?;
        let output = allocate_output(&bundle.0, true)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_sync_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Wakes an active Kyoto cycle immediately through its out-of-lock shutdown
/// handle. The controller is reconstructed from its durable journal before
/// the synchronization call returns.
pub extern "C" fn hns_browser_wallet_cancel_bitcoin_sync(
    wallet: HnsBrowserWalletHandle,
) -> HnsBrowserResult {
    ffi_call(|| {
        let control = wallet_bitcoin_control_entry(wallet)?;
        {
            let mut activity = control
                .activity
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            if !activity.request_cancellation() {
                return Err(direct_hns_not_ready(
                    "direct Bitcoin synchronization is not active",
                ));
            }
        }
        let shutdown = control
            .shutdown
            .lock()
            .ok()
            .and_then(|current| current.as_ref().cloned());
        if shutdown.is_some_and(|handle| handle.request_shutdown().is_ok()) {
            Ok(())
        } else {
            if let Ok(mut activity) = control.activity.lock() {
                activity.cancellation_requested = false;
            }
            Err(direct_hns_not_ready(
                "direct Bitcoin synchronization cannot be stopped",
            ))
        }
    })
}

#[unsafe(no_mangle)]
/// Returns only non-sensitive connection/chain progress without taking the
/// Bitcoin controller mutex.
///
/// # Safety
/// `out_progress_bundle` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_bitcoin_sync_progress(
    wallet: HnsBrowserWalletHandle,
    out_progress_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_progress_bundle)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_progress_bundle, HnsBrowserBuffer::empty()) };
        let control = wallet_bitcoin_control_entry(wallet)?;
        let progress = control
            .progress
            .lock()
            .map_err(|_| FfiFailure::internal())?
            .as_ref()
            .map(|handle| handle.snapshot())
            .ok_or_else(|| direct_hns_not_ready("direct Bitcoin progress is unavailable"))?;
        let bundle = wallet_bitcoin_bundle(&progress)?;
        let output = allocate_output(&bundle.0, false)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_progress_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Prepares a direct Bitcoin send without signing or network submission.
///
/// # Safety
/// All input slices must remain readable and `out_approval_bundle` writable.
pub unsafe extern "C" fn hns_browser_wallet_prepare_bitcoin_send(
    wallet: HnsBrowserWalletHandle,
    destination: HnsBrowserSlice,
    amount_sats: HnsBrowserSlice,
    maximum_fee_sats: HnsBrowserSlice,
    out_approval_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_approval_bundle)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_approval_bundle, HnsBrowserBuffer::empty()) };
        // SAFETY: This export carries all readable-slice contracts.
        let destination = unsafe { wallet_bitcoin_address(destination) }?;
        let amount_sats = unsafe { wallet_nonzero_sats(amount_sats) }?;
        let maximum_fee_sats = unsafe { wallet_nonzero_sats(maximum_fee_sats) }?;
        let control = wallet_bitcoin_control_entry(wallet)?;
        let mut slot = control.controller.try_lock().map_err(|error| match error {
            TryLockError::WouldBlock => {
                direct_hns_not_ready("direct Bitcoin synchronization is active")
            }
            TryLockError::Poisoned(_) => FfiFailure::internal(),
        })?;
        let controller = slot
            .as_mut()
            .filter(|controller| controller.is_active())
            .ok_or_else(|| direct_hns_not_ready("direct Bitcoin wallet is not active"))?;
        let approval = controller
            .prepare_send(&destination, amount_sats, maximum_fee_sats)
            .map_err(|_| wallet_runtime_failure("direct Bitcoin send preparation failed"))?;
        let bundle = wallet_bitcoin_bundle(&approval)?;
        let output = allocate_output(&bundle.0, true)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_approval_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Consumes one displayed direct Bitcoin approval exactly once.
///
/// # Safety
/// `action_token` must remain readable and `out_receipt_bundle` writable.
pub unsafe extern "C" fn hns_browser_wallet_approve_bitcoin_send(
    wallet: HnsBrowserWalletHandle,
    action_token: HnsBrowserSlice,
    out_receipt_bundle: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_receipt_bundle)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_receipt_bundle, HnsBrowserBuffer::empty()) };
        // SAFETY: This export carries the readable-slice contract.
        let token = unsafe { wallet_action_token(action_token) }?;
        let control = wallet_bitcoin_control_entry(wallet)?;
        let mut slot = control.controller.try_lock().map_err(|error| match error {
            TryLockError::WouldBlock => {
                direct_hns_not_ready("direct Bitcoin synchronization is active")
            }
            TryLockError::Poisoned(_) => FfiFailure::internal(),
        })?;
        let controller = slot
            .as_mut()
            .filter(|controller| controller.is_active())
            .ok_or_else(|| direct_hns_not_ready("direct Bitcoin wallet is not active"))?;
        let receipt = controller
            .approve_send(&token)
            .map_err(|_| wallet_runtime_failure("direct Bitcoin send approval failed"))?;
        let bundle = wallet_bitcoin_bundle(&receipt)?;
        let output = allocate_output(&bundle.0, true)?;
        // SAFETY: Null was rejected above.
        unsafe { write_output(out_receipt_bundle, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// Rejects and consumes one displayed direct Bitcoin approval.
///
/// # Safety
/// `action_token` must remain readable for its declared length.
pub unsafe extern "C" fn hns_browser_wallet_reject_bitcoin_send(
    wallet: HnsBrowserWalletHandle,
    action_token: HnsBrowserSlice,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This export carries the readable-slice contract.
        let token = unsafe { wallet_action_token(action_token) }?;
        let control = wallet_bitcoin_control_entry(wallet)?;
        let mut slot = control.controller.try_lock().map_err(|error| match error {
            TryLockError::WouldBlock => {
                direct_hns_not_ready("direct Bitcoin synchronization is active")
            }
            TryLockError::Poisoned(_) => FfiFailure::internal(),
        })?;
        let controller = slot
            .as_mut()
            .filter(|controller| controller.is_active())
            .ok_or_else(|| direct_hns_not_ready("direct Bitcoin wallet is not active"))?;
        controller
            .reject_send(&token)
            .map_err(|_| wallet_runtime_failure("direct Bitcoin send rejection failed"))
    })
}

#[unsafe(no_mangle)]
/// Unlocks the controller with one borrowed 32-byte platform-unwrapped key.
///
/// # Safety
/// The key slice must remain readable for its declared length.
pub unsafe extern "C" fn hns_browser_wallet_unlock(
    wallet: HnsBrowserWalletHandle,
    database_key: HnsBrowserSlice,
) -> HnsBrowserResult {
    ffi_call(|| {
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let key = unsafe { wallet_database_key(database_key) }?;
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        entry
            .controller
            .with_mut(
                |controller| controller.unlock(&key),
                |controller| controller.unlock(&key),
                |controller| controller.unlock(&key),
            )
            .map_err(|_| wallet_runtime_failure("native wallet unlock was rejected"))?;
        // Listener availability is operational rather than wallet authority;
        // a local bind denial must not relock or discard the HNS controller.
        let _ = entry.controller.start_direct_denuo_listener();
        drop(entry);
        let bitcoin_control = wallet_bitcoin_control_entry(wallet)?;
        let mut slot = bitcoin_control
            .controller
            .lock()
            .map_err(|_| FfiFailure::internal())?;
        if let Some(bitcoin) = slot.as_mut() {
            if bitcoin.activate().is_ok() {
                bitcoin_control.replace_runtime_handles(bitcoin);
            } else {
                let _ = bitcoin.deactivate();
                bitcoin_control.clear_runtime_handles();
            }
        }
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn hns_browser_wallet_lock(wallet: HnsBrowserWalletHandle) -> HnsBrowserResult {
    ffi_call(|| {
        let bitcoin_control = wallet_bitcoin_control_entry(wallet)?;
        bitcoin_control.request_shutdown();
        {
            let mut slot = bitcoin_control
                .controller
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            if let Some(bitcoin) = slot.as_mut() {
                bitcoin
                    .deactivate()
                    .map_err(|_| wallet_runtime_failure("unable to stop direct Bitcoin wallet"))?;
            }
        }
        bitcoin_control.clear_runtime_handles();
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        entry.controller.clear_direct_denuo_transport();
        entry
            .controller
            .with_mut(
                |controller| controller.lock(),
                |controller| controller.lock(),
                |controller| controller.lock(),
            )
            .map_err(|_| wallet_runtime_failure("unable to lock native wallet"))
    })
}

#[unsafe(no_mangle)]
/// Takes the newly created wallet's recovery phrase exactly once. The returned
/// allocation is marked sensitive and is wiped by `hns_browser_buffer_free`.
///
/// # Safety
/// `out_recovery_phrase` must point to one writable owned-buffer value.
pub unsafe extern "C" fn hns_browser_wallet_take_recovery_phrase(
    wallet: HnsBrowserWalletHandle,
    out_recovery_phrase: *mut HnsBrowserBuffer,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_recovery_phrase)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_recovery_phrase, HnsBrowserBuffer::empty()) };
        let entry = wallet_entry(wallet)?;
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        ensure_wallet_active(&entry)?;
        let phrase = entry.pending_recovery_phrase.as_ref().ok_or_else(|| {
            FfiFailure::new(
                HNS_BROWSER_RESULT_NOT_READY,
                "wallet recovery phrase is unavailable or was already consumed",
            )
        })?;
        let output = allocate_output(&phrase.0, true)?;
        drop(entry.pending_recovery_phrase.take());
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_recovery_phrase, output) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn hns_browser_wallet_destroy(wallet: HnsBrowserWalletHandle) -> HnsBrowserResult {
    ffi_call(|| {
        // Removal prevents new lookups. A caller that cloned the Arc before removal
        // either finishes first while we wait on this mutex, or observes `active =
        // false` after we acquire it; teardown therefore completes before return.
        let (entry, bitcoin_control) = {
            let mut registry = handle_registry()
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            let entry = registry.wallets.remove(&wallet).ok_or_else(|| {
                FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_FOUND,
                    "wallet handle is invalid or stale",
                )
            })?;
            registry.wallet_hns_sync_controls.remove(&wallet);
            let bitcoin_control = registry
                .wallet_bitcoin_controls
                .remove(&wallet)
                .ok_or_else(FfiFailure::internal)?;
            (entry, bitcoin_control)
        };
        bitcoin_control.request_shutdown();
        if let Ok(mut bitcoin) = bitcoin_control.controller.lock() {
            if let Some(controller) = bitcoin.as_mut() {
                let _ = controller.deactivate();
            }
            bitcoin.take();
        }
        let mut entry = entry.lock().map_err(|_| FfiFailure::internal())?;
        entry.active = false;
        drop(entry.pending_recovery_phrase.take());
        let _ = entry.controller.with_mut(
            |controller| controller.lock(),
            |controller| controller.lock(),
            |controller| controller.lock(),
        );
        entry.controller = NativeWalletController::Failed;
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// A non-null scope slice must remain readable for its declared length and
/// `out_proxy` must point to one writable handle.
pub unsafe extern "C" fn hns_browser_proxy_start(
    runtime: HnsBrowserRuntimeHandle,
    hns_scope_root: HnsBrowserSlice,
    out_proxy: *mut HnsBrowserProxyHandle,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_proxy)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_proxy, 0) };
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let scope = unsafe { optional_scope(hns_scope_root) }?;
        let runtime_entry = runtime_entry(runtime)?;
        let _start_reservation = {
            let mut registry = handle_registry()
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            if !registry
                .runtimes
                .get(&runtime)
                .is_some_and(|current| Arc::ptr_eq(current, &runtime_entry))
            {
                return Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_FOUND,
                    "runtime was destroyed before starting the proxy",
                ));
            }
            if registry
                .proxies
                .len()
                .saturating_add(registry.starting_proxy_runtimes.len())
                >= MAX_PROXY_HANDLES
            {
                return Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
                    "proxy handle registry is full",
                ));
            }
            if registry.starting_proxy_runtimes.contains(&runtime)
                || registry.proxies.values().any(|proxy| {
                    proxy.runtime_handle == runtime
                        && proxy.active.load(Ordering::Acquire)
                        && !proxy.proxy.is_stopped()
                })
            {
                return Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_PROXY_ERROR,
                    "runtime already owns an active or starting proxy generation",
                ));
            }
            registry.starting_proxy_runtimes.insert(runtime);
            ProxyStartReservation {
                runtime_handle: runtime,
            }
        };
        let mailbox = Arc::new(MainFrameStatusMailbox::default());
        let observer: Arc<dyn BrowserProxyStatusObserver> = mailbox.clone();
        let policy_revision = runtime_entry.runtime.policy_revision();
        let proxy = runtime_entry
            .runtime
            .start_whole_browser_proxy_with_observer(scope.as_deref(), observer)
            .map_err(|_| {
                FfiFailure::new(
                    HNS_BROWSER_RESULT_PROXY_ERROR,
                    "unable to start whole-browser proxy generation",
                )
            })?;
        let handle = next_monotonic_id(&NEXT_OBJECT_HANDLE)?;
        let entry = Arc::new(ProxyEntry {
            runtime_handle: runtime,
            #[cfg(test)]
            policy_revision,
            proxy,
            mailbox,
            active: AtomicBool::new(true),
        });
        let insertion = {
            let mut registry = handle_registry()
                .lock()
                .map_err(|_| FfiFailure::internal())?;
            let runtime_is_live = registry
                .runtimes
                .get(&runtime)
                .is_some_and(|current| Arc::ptr_eq(current, &runtime_entry));
            if !runtime_is_live {
                Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_FOUND,
                    "runtime was destroyed while starting the proxy",
                ))
            } else if runtime_entry.runtime.policy_revision() != policy_revision {
                Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_PROXY_ERROR,
                    "runtime policy changed while starting the proxy",
                ))
            } else if registry.proxies.len() >= MAX_PROXY_HANDLES {
                Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
                    "proxy handle registry is full",
                ))
            } else if registry.proxies.values().any(|proxy| {
                proxy.runtime_handle == runtime
                    && proxy.active.load(Ordering::Acquire)
                    && !proxy.proxy.is_stopped()
            }) {
                Err(FfiFailure::new(
                    HNS_BROWSER_RESULT_PROXY_ERROR,
                    "runtime already owns an active proxy generation",
                ))
            } else {
                registry.proxies.insert(handle, Arc::clone(&entry));
                Ok(())
            }
        };
        if let Err(failure) = insertion {
            entry.blocking_stop();
            return Err(failure);
        }
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_proxy, handle) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// `out_endpoint` must point to one writable [`HnsBrowserProxyEndpoint`].
pub unsafe extern "C" fn hns_browser_proxy_endpoint(
    proxy: HnsBrowserProxyHandle,
    out_endpoint: *mut HnsBrowserProxyEndpoint,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_endpoint)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_endpoint, HnsBrowserProxyEndpoint::empty()) };
        let entry = proxy_entry(proxy)?;
        entry.ensure_active()?;
        let proxy = &entry.proxy;
        let outputs = allocate_outputs(&[
            OutputValue {
                bytes: proxy.session_id().as_bytes(),
                sensitive: false,
            },
            OutputValue {
                bytes: proxy.authorization_realm().as_bytes(),
                sensitive: false,
            },
            OutputValue {
                bytes: proxy.authorization_username().as_bytes(),
                sensitive: true,
            },
            OutputValue {
                bytes: proxy.authorization_password().as_bytes(),
                sensitive: true,
            },
        ])?;
        if outputs.len() != 4 {
            release_allocated_outputs(&outputs);
            return Err(FfiFailure::internal());
        }
        let endpoint = HnsBrowserProxyEndpoint {
            struct_size: size_u32::<HnsBrowserProxyEndpoint>(),
            port: proxy.port(),
            reserved0: 0,
            generation: proxy.generation(),
            session_id: outputs[0],
            realm: outputs[1],
            username: outputs[2],
            password: outputs[3],
        };
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_endpoint, endpoint) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// The session slice must remain readable for its declared length and
/// `out_matches` must point to one writable byte.
pub unsafe extern "C" fn hns_browser_proxy_matches_instance(
    proxy: HnsBrowserProxyHandle,
    session_id: HnsBrowserSlice,
    generation: u64,
    out_matches: *mut u8,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_matches)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_matches, 0) };
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let session_id = unsafe { required_input_str(session_id, MAX_AUTH_FIELD_BYTES) }?;
        let entry = proxy_entry(proxy)?;
        entry.ensure_active()?;
        let matches = entry.proxy.generation() == generation
            && constant_time_eq(entry.proxy.session_id().as_bytes(), session_id.as_bytes());
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_matches, u8::from(matches)) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// The host and realm slices must remain readable for their declared lengths
/// and `out_matches` must point to one writable byte.
pub unsafe extern "C" fn hns_browser_proxy_matches_authentication_challenge(
    proxy: HnsBrowserProxyHandle,
    host: HnsBrowserSlice,
    port: u16,
    realm: HnsBrowserSlice,
    out_matches: *mut u8,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_matches)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_matches, 0) };
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let host = unsafe { required_input_str(host, MAX_HOST_BYTES) }?;
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let realm = unsafe { required_input_str(realm, MAX_AUTH_FIELD_BYTES) }?;
        let entry = proxy_entry(proxy)?;
        entry.ensure_active()?;
        let matches = entry
            .proxy
            .matches_authentication_challenge(&host, port, &realm);
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_matches, u8::from(matches)) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// The host and DER slices must remain readable for their declared lengths
/// and `out_matches` must point to one writable byte.
pub unsafe extern "C" fn hns_browser_proxy_matches_local_certificate(
    proxy: HnsBrowserProxyHandle,
    host: HnsBrowserSlice,
    certificate_der: HnsBrowserSlice,
    out_matches: *mut u8,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_matches)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_matches, 0) };
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let host = unsafe { required_input_str(host, MAX_HOST_BYTES) }?;
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let certificate_der = unsafe { input_bytes(certificate_der, MAX_CERTIFICATE_DER_BYTES) }?;
        if certificate_der.is_empty() {
            return Err(FfiFailure::invalid("certificate DER is empty"));
        }
        let entry = proxy_entry(proxy)?;
        entry.ensure_active()?;
        let matches = entry
            .proxy
            .matches_local_certificate(&host, &certificate_der);
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_matches, u8::from(matches)) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
/// # Safety
/// The host slice must remain readable for its declared length and
/// `out_status` must point to one writable [`HnsBrowserProxyStatus`].
pub unsafe extern "C" fn hns_browser_proxy_take_main_frame_status(
    proxy: HnsBrowserProxyHandle,
    canonical_main_frame_host: HnsBrowserSlice,
    out_status: *mut HnsBrowserProxyStatus,
) -> HnsBrowserResult {
    ffi_call(|| {
        require_output(out_status)?;
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_status, HnsBrowserProxyStatus::empty()) };
        // SAFETY: This unsafe export carries the caller's readable-slice contract.
        let host = unsafe { required_input_str(canonical_main_frame_host, MAX_HOST_BYTES) }?;
        let entry = proxy_entry(proxy)?;
        entry.ensure_active()?;
        let generation = entry.proxy.generation();
        let mut mailbox = entry
            .mailbox
            .statuses
            .lock()
            .map_err(|_| FfiFailure::internal())?;
        let index = mailbox
            .iter()
            .rposition(|status| status.generation == generation && status.host == host)
            .ok_or_else(|| {
                FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_READY,
                    "no matching main-frame status is available",
                )
            })?;
        let queued = mailbox
            .get(index)
            .cloned()
            .ok_or_else(FfiFailure::internal)?;
        let outputs = allocate_outputs(&[
            OutputValue {
                bytes: queued.host.as_bytes(),
                sensitive: false,
            },
            OutputValue {
                bytes: queued.resolution_trace_json.as_bytes(),
                sensitive: true,
            },
        ])?;
        if outputs.len() != 2 {
            release_allocated_outputs(&outputs);
            return Err(FfiFailure::internal());
        }
        if mailbox.remove(index).is_none() {
            release_allocated_outputs(&outputs);
            return Err(FfiFailure::internal());
        }
        // Never make an older status for the same committed identity visible
        // after consuming its latest value.
        mailbox.retain(|status| !(status.generation == generation && status.host == host));
        let status = HnsBrowserProxyStatus {
            struct_size: size_u32::<HnsBrowserProxyStatus>(),
            tls_policy: queued.tls_policy,
            resolver_policy: queued.resolver_policy,
            security_path: queued.security_path,
            generation: queued.generation,
            http_status: u32::from(queued.http_status),
            reserved0: 0,
            host: outputs[0],
            resolution_trace_json: outputs[1],
        };
        // SAFETY: Null was rejected above and the C contract requires writable output.
        unsafe { write_output(out_status, status) };
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn hns_browser_proxy_request_stop(proxy: HnsBrowserProxyHandle) -> HnsBrowserResult {
    ffi_call(|| {
        let entry = proxy_entry(proxy)?;
        entry.request_stop();
        Ok(())
    })
}

#[unsafe(no_mangle)]
pub extern "C" fn hns_browser_proxy_destroy(proxy: HnsBrowserProxyHandle) -> HnsBrowserResult {
    ffi_call(|| {
        let entry = handle_registry()
            .lock()
            .map_err(|_| FfiFailure::internal())?
            .proxies
            .remove(&proxy)
            .ok_or_else(|| {
                FfiFailure::new(
                    HNS_BROWSER_RESULT_NOT_FOUND,
                    "proxy handle is invalid or stale",
                )
            })?;
        entry.blocking_stop();
        Ok(())
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::mem::{align_of, offset_of, size_of};
    #[cfg(unix)]
    use std::os::unix::fs::PermissionsExt;
    use std::sync::{Barrier, MutexGuard, OnceLock};
    use std::thread;

    static TEST_LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    static NEXT_TEST_DIR: AtomicU64 = AtomicU64::new(1);

    fn test_guard() -> MutexGuard<'static, ()> {
        match TEST_LOCK.get_or_init(|| Mutex::new(())).lock() {
            Ok(guard) => guard,
            Err(poisoned) => poisoned.into_inner(),
        }
    }

    fn ffi_slice(bytes: &[u8]) -> HnsBrowserSlice {
        HnsBrowserSlice {
            ptr: bytes.as_ptr(),
            len: bytes.len() as u64,
        }
    }

    fn null_slice() -> HnsBrowserSlice {
        HnsBrowserSlice::empty()
    }

    fn owned_bytes(buffer: HnsBrowserBuffer) -> Vec<u8> {
        if buffer.len == 0 {
            return Vec::new();
        }
        assert!(!buffer.ptr.is_null());
        // SAFETY: Test reads a live buffer returned by this crate before free.
        unsafe { std::slice::from_raw_parts(buffer.ptr, buffer.len as usize) }.to_vec()
    }

    fn owned_string(buffer: HnsBrowserBuffer) -> String {
        String::from_utf8(owned_bytes(buffer)).expect("ABI output must be UTF-8")
    }

    fn unique_data_dir(label: &str) -> String {
        let id = NEXT_TEST_DIR.fetch_add(1, Ordering::Relaxed);
        std::env::temp_dir()
            .join(format!(
                "hns-browser-ios-ffi-{label}-{}-{id}",
                std::process::id()
            ))
            .to_string_lossy()
            .into_owned()
    }

    fn create_runtime(data_dir: &str) -> HnsBrowserRuntimeHandle {
        let mut options = HnsBrowserRuntimeOptions::defaults();
        options.network = HNS_BROWSER_NETWORK_REGTEST;
        options.data_dir = ffi_slice(data_dir.as_bytes());
        let mut runtime = 0;
        // SAFETY: All pointers and borrowed slices are valid for this call.
        let result = unsafe { hns_browser_runtime_create(&options, &mut runtime) };
        assert_eq!(result, HNS_BROWSER_RESULT_OK);
        assert_ne!(runtime, 0);
        runtime
    }

    fn start_icann_proxy(runtime: HnsBrowserRuntimeHandle) -> HnsBrowserProxyHandle {
        let mut proxy = 0;
        // SAFETY: Output is writable and the null slice is the documented ICANN mode.
        let result = unsafe { hns_browser_proxy_start(runtime, null_slice(), &mut proxy) };
        assert_eq!(result, HNS_BROWSER_RESULT_OK);
        assert_ne!(proxy, 0);
        proxy
    }

    fn cleanup_dir(data_dir: &str) {
        let _ = fs::remove_dir_all(data_dir);
    }

    #[test]
    fn abi_layout_and_header_symbols_are_stable() {
        let _guard = test_guard();
        assert_eq!(size_of::<HnsBrowserSlice>(), 16);
        assert_eq!(align_of::<HnsBrowserSlice>(), 8);
        assert_eq!(offset_of!(HnsBrowserSlice, ptr), 0);
        assert_eq!(offset_of!(HnsBrowserSlice, len), 8);
        assert_eq!(size_of::<HnsBrowserBuffer>(), 24);
        assert_eq!(offset_of!(HnsBrowserBuffer, allocation_id), 16);
        assert_eq!(size_of::<HnsBrowserWalletHandle>(), 8);
        assert_eq!(size_of::<HnsBrowserWalletHnsSyncProgress>(), 40);
        assert_eq!(offset_of!(HnsBrowserWalletHnsSyncProgress, stage), 4);
        assert_eq!(
            offset_of!(HnsBrowserWalletHnsSyncProgress, verified_header_height),
            8
        );
        assert_eq!(
            offset_of!(HnsBrowserWalletHnsSyncProgress, target_height),
            32
        );
        assert_eq!(size_of::<HnsBrowserRuntimeOptions>(), 80);
        assert_eq!(offset_of!(HnsBrowserRuntimeOptions, data_dir), 8);
        assert_eq!(
            offset_of!(HnsBrowserRuntimeOptions, experimental_p2p_dns_relay),
            46
        );
        assert_eq!(
            offset_of!(HnsBrowserRuntimeOptions, legacy_hns_doh_compatibility),
            47
        );
        assert_eq!(offset_of!(HnsBrowserRuntimeOptions, hns_doh_resolver), 48);
        assert_eq!(size_of::<HnsBrowserPolicy>(), 40);
        assert_eq!(offset_of!(HnsBrowserPolicy, experimental_p2p_dns_relay), 25);
        assert_eq!(
            offset_of!(HnsBrowserPolicy, legacy_hns_doh_compatibility),
            26
        );
        assert_eq!(size_of::<HnsBrowserProxyEndpoint>(), 112);
        assert_eq!(offset_of!(HnsBrowserProxyEndpoint, generation), 8);
        assert_eq!(offset_of!(HnsBrowserProxyEndpoint, session_id), 16);
        assert_eq!(size_of::<HnsBrowserProxyStatus>(), 80);
        assert_eq!(offset_of!(HnsBrowserProxyStatus, generation), 16);
        assert_eq!(offset_of!(HnsBrowserProxyStatus, host), 32);

        let header = include_str!("../include/hns_browser.h");
        let source = include_str!("lib.rs");
        let symbols = [
            "hns_browser_abi_version",
            "hns_browser_core_version",
            "hns_browser_diagnostics_json",
            "hns_browser_last_error",
            "hns_browser_buffer_free",
            "hns_browser_runtime_options_default",
            "hns_browser_policy_default",
            "hns_browser_runtime_create",
            "hns_browser_runtime_destroy",
            "hns_browser_runtime_set_policy",
            "hns_browser_runtime_sync_once",
            "hns_browser_runtime_sync_status",
            "hns_browser_runtime_add_static_relay_peer",
            "hns_browser_runtime_clear_resolver_cache",
            "hns_browser_runtime_install_header_snapshot",
            "hns_browser_runtime_reset_headers_from_peers",
            "hns_browser_runtime_proof_details",
            "hns_browser_classify_name",
            "hns_browser_canonical_host",
            "hns_browser_hns_root",
            "hns_browser_wallet_create",
            "hns_browser_wallet_restore",
            "hns_browser_wallet_open",
            "hns_browser_wallet_status",
            "hns_browser_wallet_accounts",
            "hns_browser_wallet_configure_hns_reads",
            "hns_browser_wallet_has_hns_reads",
            "hns_browser_wallet_configure_direct_hns_value",
            "hns_browser_wallet_has_hns_value",
            "hns_browser_wallet_direct_hns_rollback_floor",
            "hns_browser_wallet_local_hns_receive_target",
            "hns_browser_wallet_synchronize_hns_reads",
            "hns_browser_wallet_hns_sync_progress",
            "hns_browser_wallet_cancel_hns_sync",
            "hns_browser_wallet_has_bitcoin_value",
            "hns_browser_wallet_bitcoin_snapshot",
            "hns_browser_wallet_set_bitcoin_birthday_height",
            "hns_browser_wallet_next_bitcoin_receive_address",
            "hns_browser_wallet_synchronize_bitcoin",
            "hns_browser_wallet_cancel_bitcoin_sync",
            "hns_browser_wallet_bitcoin_sync_progress",
            "hns_browser_wallet_prepare_bitcoin_send",
            "hns_browser_wallet_approve_bitcoin_send",
            "hns_browser_wallet_reject_bitcoin_send",
            "hns_browser_wallet_import_hns_name_exact_text",
            "hns_browser_wallet_prepare_hns_send",
            "hns_browser_wallet_approve_hns_send",
            "hns_browser_wallet_reject_hns_send",
            "hns_browser_wallet_prepare_hns_value_action",
            "hns_browser_wallet_approve_hns_value_action_result",
            "hns_browser_wallet_query_shakedex",
            "hns_browser_wallet_direct_denuo_status",
            "hns_browser_wallet_retry_direct_denuo_listener",
            "hns_browser_wallet_connect_direct_denuo",
            "hns_browser_wallet_disconnect_direct_denuo",
            "hns_browser_wallet_service_direct_denuo",
            "hns_browser_wallet_unlock",
            "hns_browser_wallet_lock",
            "hns_browser_wallet_take_recovery_phrase",
            "hns_browser_wallet_destroy",
            "hns_browser_proxy_start",
            "hns_browser_proxy_endpoint",
            "hns_browser_proxy_matches_instance",
            "hns_browser_proxy_matches_authentication_challenge",
            "hns_browser_proxy_matches_local_certificate",
            "hns_browser_proxy_take_main_frame_status",
            "hns_browser_proxy_request_stop",
            "hns_browser_proxy_destroy",
        ];
        for symbol in symbols {
            assert!(header.contains(&format!("{symbol}(")), "header: {symbol}");
            assert!(
                source.contains(&format!("fn {symbol}(")),
                "source: {symbol}"
            );
        }
        assert!(header.contains("#ifndef HNS_BROWSER_H"));
        assert!(header.contains("extern \"C\""));
        assert!(!header.contains("hns_browser_proxy_matches_authentication("));
    }

    #[test]
    fn restored_wallet_birthday_above_local_headers_is_resumable() {
        assert!(wallet_hns_sync_heights_are_coherent(70_000, None, 64_000));
        assert!(wallet_hns_sync_heights_are_coherent(
            1_000,
            Some(42_000),
            64_000
        ));
        assert!(!wallet_hns_sync_heights_are_coherent(
            70_000,
            Some(70_000),
            64_000
        ));
        assert!(!wallet_hns_sync_heights_are_coherent(
            1_000,
            Some(999),
            64_000
        ));
    }

    #[test]
    fn wallet_name_import_input_and_success_bundle_are_exact_and_bounded() {
        let _guard = test_guard();
        for exact in [
            b"Alpha".as_slice(),
            b"alpha.".as_slice(),
            "caf\u{e9}".as_bytes(),
        ] {
            // SAFETY: Each fixture remains readable for this call.
            let copied = unsafe { wallet_exact_hns_name(ffi_slice(exact)) }
                .ok()
                .expect("bounded exact UTF-8 input");
            assert_eq!(copied.0, exact);
        }
        // SAFETY: Empty input does not dereference its null pointer.
        assert!(matches!(
            unsafe { wallet_exact_hns_name(null_slice()) },
            Err(FfiFailure {
                code: HNS_BROWSER_RESULT_INVALID_ARGUMENT,
                ..
            })
        ));
        let invalid_utf8 = [0xff];
        // SAFETY: The fixture remains readable for this call.
        assert!(matches!(
            unsafe { wallet_exact_hns_name(ffi_slice(&invalid_utf8)) },
            Err(FfiFailure {
                code: HNS_BROWSER_RESULT_INVALID_UTF8,
                ..
            })
        ));
        let oversized = HnsBrowserSlice {
            ptr: ptr::without_provenance::<u8>(1),
            len: (MAX_WALLET_NAME_INPUT_BYTES + 1) as u64,
        };
        // SAFETY: The length is rejected before its sentinel pointer is read.
        assert!(matches!(
            unsafe { wallet_exact_hns_name(oversized) },
            Err(FfiFailure {
                code: HNS_BROWSER_RESULT_INVALID_ARGUMENT,
                ..
            })
        ));

        let summary = MobileHnsNameSummary {
            name: "alpha".to_owned(),
            name_hash: "271878f8a927b4566ac951fc815b18dfad8d0302d61d11d80cbe15b7a3a056af"
                .to_owned(),
            proof_height: 7,
            resource_status: hns_wallet_mobile::MobileHnsNameResourceStatus::CanonicalDecoded,
            ownership_status: hns_wallet_mobile::MobileHnsNameOwnershipStatus::WalletOwned,
            registered: Some(true),
            expired: Some(false),
        };
        let bundle = wallet_name_import_bundle(&summary)
            .ok()
            .expect("success bundle");
        assert_eq!(&bundle.0[..4], WALLET_NAME_IMPORT_BUNDLE_MAGIC);
        assert_eq!(bundle.0[4], WALLET_NAME_IMPORT_BUNDLE_VERSION);
        assert_eq!(bundle.0[5], WALLET_NAME_IMPORT_BUNDLE_FLAGS);
        assert_eq!(&bundle.0[6..8], &[0, 0]);
        let payload_length = u32::from_be_bytes(
            bundle.0[8..12]
                .try_into()
                .expect("name import payload length"),
        ) as usize;
        assert_eq!(
            bundle.0.len(),
            WALLET_NAME_IMPORT_BUNDLE_HEADER_BYTES + payload_length
        );
        assert_eq!(
            serde_json::from_slice::<MobileHnsNameSummary>(
                &bundle.0[WALLET_NAME_IMPORT_BUNDLE_HEADER_BYTES..],
            )
            .expect("minimized summary"),
            summary
        );
        let payload = std::str::from_utf8(&bundle.0[WALLET_NAME_IMPORT_BUNDLE_HEADER_BYTES..])
            .expect("summary JSON");
        for forbidden in [
            "proofState",
            "currentState",
            "rawResource",
            "ownerOutpoint",
            "derivation",
            "provider",
            "value",
        ] {
            assert!(!payload.contains(forbidden));
        }

        assert_eq!(
            wallet_name_import_failure(&MobileWalletError::ServiceFailure {
                code: ServiceErrorCode::InvalidRequest,
                message: "invalid".to_owned(),
            })
            .code,
            HNS_BROWSER_RESULT_INVALID_ARGUMENT
        );
        assert_eq!(
            wallet_name_import_failure(&MobileWalletError::ControllerFailed).code,
            HNS_BROWSER_RESULT_RUNTIME_ERROR
        );

        let mut oversized_summary = summary;
        oversized_summary.name = "a".repeat(MAX_WALLET_NAME_IMPORT_JSON_BYTES);
        assert!(matches!(
            wallet_name_import_bundle(&oversized_summary),
            Err(FfiFailure {
                code: HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
                ..
            })
        ));
    }

    #[test]
    fn wallet_start_reservations_are_atomic_bounded_and_released() {
        let _guard = test_guard();
        let mut reservations = (0..MAX_WALLET_HANDLES)
            .map(|_| match reserve_wallet_start() {
                Ok(reservation) => reservation,
                Err(_) => panic!("wallet reservation failed"),
            })
            .collect::<Vec<_>>();
        let handles = reservations
            .iter()
            .map(|reservation| reservation.handle)
            .collect::<HashSet<_>>();
        assert_eq!(handles.len(), MAX_WALLET_HANDLES);
        assert!(matches!(
            reserve_wallet_start(),
            Err(FfiFailure {
                code: HNS_BROWSER_RESULT_RESOURCE_EXHAUSTED,
                ..
            })
        ));

        drop(reservations.pop());
        reservations.push(match reserve_wallet_start() {
            Ok(reservation) => reservation,
            Err(_) => panic!("released wallet reservation failed"),
        });
        drop(reservations);

        let registry = handle_registry().lock().expect("handle registry");
        assert_eq!(registry.starting_wallets, 0);
        assert!(registry.wallets.is_empty());
    }

    #[test]
    fn owned_buffers_reject_foreign_mismatched_and_double_free() {
        let _guard = test_guard();
        let mut version = HnsBrowserBuffer::empty();
        // SAFETY: Output points to one writable buffer descriptor.
        assert_eq!(
            unsafe { hns_browser_core_version(&mut version) },
            HNS_BROWSER_RESULT_OK
        );
        assert!(owned_string(version).starts_with("hns-dane-browser-rust-core/"));

        let mismatched = HnsBrowserBuffer {
            ptr: ptr::without_provenance_mut::<u8>(1),
            ..version
        };
        assert_eq!(
            hns_browser_buffer_free(mismatched),
            HNS_BROWSER_RESULT_BUFFER_ERROR
        );
        assert_eq!(hns_browser_buffer_free(version), HNS_BROWSER_RESULT_OK);
        assert_eq!(
            hns_browser_buffer_free(version),
            HNS_BROWSER_RESULT_BUFFER_ERROR
        );
        assert_eq!(
            hns_browser_buffer_free(HnsBrowserBuffer::empty()),
            HNS_BROWSER_RESULT_OK
        );
        let foreign = HnsBrowserBuffer {
            ptr: ptr::without_provenance_mut::<u8>(7),
            len: 12,
            allocation_id: u64::MAX,
        };
        assert_eq!(
            hns_browser_buffer_free(foreign),
            HNS_BROWSER_RESULT_BUFFER_ERROR
        );
    }

    #[test]
    fn input_and_error_boundaries_are_bounded_and_utf8_checked() {
        let _guard = test_guard();
        let mut class = u32::MAX;
        let null_nonempty = HnsBrowserSlice {
            ptr: ptr::null(),
            len: 1,
        };
        // SAFETY: This deliberately exercises the documented null rejection path.
        assert_eq!(
            unsafe { hns_browser_classify_name(null_nonempty, &mut class) },
            HNS_BROWSER_RESULT_INVALID_ARGUMENT
        );
        let mut error = HnsBrowserBuffer::empty();
        // SAFETY: Output points to one writable buffer descriptor.
        assert_eq!(
            unsafe { hns_browser_last_error(&mut error) },
            HNS_BROWSER_RESULT_OK
        );
        let error_text = owned_string(error);
        assert!(!error_text.is_empty());
        assert!(error_text.len() <= MAX_ERROR_BYTES);
        assert_eq!(hns_browser_buffer_free(error), HNS_BROWSER_RESULT_OK);

        let oversized = HnsBrowserSlice {
            ptr: ptr::without_provenance::<u8>(1),
            len: (MAX_NAME_INPUT_BYTES as u64) + 1,
        };
        // SAFETY: Length is rejected before the intentionally invalid pointer is read.
        assert_eq!(
            unsafe { hns_browser_classify_name(oversized, &mut class) },
            HNS_BROWSER_RESULT_INVALID_ARGUMENT
        );
        let invalid_utf8 = [0xff, 0xfe];
        // SAFETY: The borrowed byte slice is readable for this call.
        assert_eq!(
            unsafe { hns_browser_classify_name(ffi_slice(&invalid_utf8), &mut class) },
            HNS_BROWSER_RESULT_INVALID_UTF8
        );

        set_last_error(&"é".repeat(MAX_ERROR_BYTES));
        let bounded = last_error_snapshot();
        assert!(bounded.len() <= MAX_ERROR_BYTES);
        assert!(std::str::from_utf8(bounded.as_bytes()).is_ok());
        assert_eq!(
            ffi_call(|| -> Result<(), FfiFailure> { panic!("contained test panic") }),
            HNS_BROWSER_RESULT_PANIC
        );
    }

    #[test]
    fn native_hns_value_inputs_are_closed_bounded_and_canonical() {
        let _guard = test_guard();
        let transfer = br#"{"action":"transferName","name":"alpha","recipient":"rs1qfixture","maximumFee":"1000"}"#;
        // SAFETY: The exact JSON bytes remain readable for this call.
        assert!(matches!(
            unsafe { wallet_value_intent(ffi_slice(transfer)) },
            Ok(MobileHnsValueIntent::TransferName { .. })
        ));
        for invalid in [
            br#"{}"#.as_slice(),
            br#"{"action":"send","recipient":"rs1qfixture"}"#.as_slice(),
            br#" {"action":"transferName"}"#.as_slice(),
        ] {
            // SAFETY: Each invalid byte slice remains readable for the call.
            assert!(unsafe { wallet_value_intent(ffi_slice(invalid)) }.is_err());
        }
        let oversized_intent = HnsBrowserSlice {
            ptr: ptr::without_provenance::<u8>(1),
            len: (MAX_WALLET_VALUE_INTENT_JSON_BYTES + 1) as u64,
        };
        // SAFETY: The length is rejected before the intentionally invalid pointer is read.
        assert!(unsafe { wallet_value_intent(oversized_intent) }.is_err());

        let list = br#"{"query":"listOffers","cursor":null,"limit":32}"#;
        // SAFETY: The exact query bytes remain readable for this call.
        assert!(matches!(
            unsafe { wallet_shakedex_query(ffi_slice(list)) },
            Ok(MobileShakedexQuery::ListOffers { .. })
        ));
        // SAFETY: An unknown query is a readable, bounded input and must fail closed.
        assert!(unsafe { wallet_shakedex_query(ffi_slice(br#"{"query":"unknown"}"#)) }.is_err());

        let token = [b'a'; WALLET_ACTION_TOKEN_BYTES];
        // SAFETY: The canonical token bytes remain readable for this call.
        assert!(unsafe { wallet_action_token(ffi_slice(&token)) }.is_ok());
        let zero = [b'0'; WALLET_ACTION_TOKEN_BYTES];
        // SAFETY: The all-zero token bytes remain readable for this call.
        assert!(unsafe { wallet_action_token(ffi_slice(&zero)) }.is_err());
        let uppercase = [b'A'; WALLET_ACTION_TOKEN_BYTES];
        // SAFETY: Uppercase token bytes remain readable and must be rejected.
        assert!(unsafe { wallet_action_token(ffi_slice(&uppercase)) }.is_err());
    }

    #[test]
    fn direct_denuo_inputs_and_bundles_are_closed() {
        let _guard = test_guard();
        // SAFETY: Each endpoint slice remains readable for the call.
        assert!(matches!(
            unsafe { wallet_denuo_endpoint(ffi_slice(b"198.51.100.7:12038")) },
            Ok(endpoint) if endpoint == "198.51.100.7:12038".parse().expect("socket endpoint")
        ));
        // SAFETY: The IPv6 endpoint remains readable for the call.
        assert!(unsafe { wallet_denuo_endpoint(ffi_slice(b"[2001:db8::7]:12038")) }.is_ok());
        for invalid in [
            b"wallet.example:12038".as_slice(),
            b"198.51.100.7:0".as_slice(),
            b" 198.51.100.7:12038".as_slice(),
        ] {
            // SAFETY: Each invalid endpoint remains readable for the call.
            assert!(unsafe { wallet_denuo_endpoint(ffi_slice(invalid)) }.is_err());
        }

        let endpoint = "198.51.100.7:12038".parse().expect("socket endpoint");
        let status = wallet_direct_denuo_status_bundle(true, Some(12_038), Some(endpoint))
            .expect("status bundle");
        assert_eq!(&status[..4], WALLET_DIRECT_DENUO_STATUS_BUNDLE_MAGIC);
        assert_eq!(status[5], 0b111);
        assert_eq!(&status[12..], b"198.51.100.7:12038");
        let connected = wallet_direct_denuo_connect_bundle(IosDirectDenuoConnectResult {
            outcome: IosDirectDenuoConnectOutcome::Connected,
            peer_endpoint: Some(endpoint),
        })
        .expect("connect bundle");
        assert_eq!(&connected[..4], WALLET_DIRECT_DENUO_CONNECT_BUNDLE_MAGIC);
        assert_eq!(connected[5], WALLET_DIRECT_DENUO_CONNECT_CONNECTED);
        assert!(wallet_direct_denuo_status_bundle(false, Some(12_038), None).is_none());
        assert!(
            wallet_direct_denuo_connect_bundle(IosDirectDenuoConnectResult {
                outcome: IosDirectDenuoConnectOutcome::ConnectionFailed,
                peer_endpoint: Some(endpoint),
            })
            .is_none()
        );
    }

    #[test]
    fn shared_name_classification_and_hns_root_are_exposed() {
        let _guard = test_guard();
        let mut class = u32::MAX;
        // SAFETY: Inputs and output are valid for each call.
        assert_eq!(
            unsafe { hns_browser_classify_name(ffi_slice(b"welcome."), &mut class) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(class, HNS_BROWSER_NAME_HNS);
        let mut canonical = HnsBrowserBuffer::empty();
        // SAFETY: Input and output are valid for this call.
        assert_eq!(
            unsafe { hns_browser_canonical_host(ffi_slice(b"WWW.WELCOME."), &mut canonical) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(owned_string(canonical), "www.welcome");
        assert_eq!(hns_browser_buffer_free(canonical), HNS_BROWSER_RESULT_OK);
        // SAFETY: Input and output are valid for this call.
        assert_eq!(
            unsafe { hns_browser_canonical_host(ffi_slice(b"127.0.0.1"), &mut canonical) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(owned_string(canonical), "127.0.0.1");
        assert_eq!(hns_browser_buffer_free(canonical), HNS_BROWSER_RESULT_OK);
        // SAFETY: Input and output are valid for this call.
        assert_eq!(
            unsafe { hns_browser_canonical_host(ffi_slice(b"[2001:0db8::1]"), &mut canonical) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(owned_string(canonical), "2001:db8::1");
        assert_eq!(hns_browser_buffer_free(canonical), HNS_BROWSER_RESULT_OK);
        // SAFETY: Input and output are valid for this call.
        assert_eq!(
            unsafe { hns_browser_canonical_host(ffi_slice(b"127.1"), &mut canonical) },
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        let mut root = HnsBrowserBuffer::empty();
        // SAFETY: Input and output are valid for this call.
        assert_eq!(
            unsafe { hns_browser_hns_root(ffi_slice(b"sub.welcome."), &mut root) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(owned_string(root), "welcome");
        assert_eq!(hns_browser_buffer_free(root), HNS_BROWSER_RESULT_OK);

        // SAFETY: Inputs and output are valid for each call.
        assert_eq!(
            unsafe { hns_browser_classify_name(ffi_slice(b"example.com"), &mut class) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(class, HNS_BROWSER_NAME_ICANN);
        // SAFETY: Input and output are valid for this call.
        assert_eq!(
            unsafe { hns_browser_hns_root(ffi_slice(b"example.com"), &mut root) },
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        // SAFETY: Inputs and output are valid for each call.
        assert_eq!(
            unsafe { hns_browser_classify_name(ffi_slice(b"two words"), &mut class) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(class, HNS_BROWSER_NAME_SEARCH);
    }

    #[test]
    fn runtime_handles_are_monotonic_typed_and_stale_safe() {
        let _guard = test_guard();
        let first_dir = unique_data_dir("runtime-first");
        let first = create_runtime(&first_dir);
        assert_eq!(hns_browser_runtime_destroy(first), HNS_BROWSER_RESULT_OK);
        assert_eq!(
            hns_browser_runtime_destroy(first),
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        let second_dir = unique_data_dir("runtime-second");
        let second = create_runtime(&second_dir);
        assert!(second > first);
        assert_eq!(
            hns_browser_proxy_destroy(second),
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        assert_eq!(hns_browser_runtime_destroy(second), HNS_BROWSER_RESULT_OK);
        cleanup_dir(&first_dir);
        cleanup_dir(&second_dir);
    }

    #[cfg(unix)]
    #[test]
    fn wallet_hns_reads_require_explicit_loopback_composition() {
        let _guard = test_guard();
        let id = NEXT_TEST_DIR.fetch_add(1, Ordering::Relaxed);
        let data_dir = std::path::Path::new("/tmp")
            .join(format!(
                "hns-browser-ios-ffi-wallet-hns-reads-{}-{id}",
                std::process::id()
            ))
            .to_string_lossy()
            .into_owned();
        fs::create_dir_all(&data_dir).expect("wallet test directory");
        fs::set_permissions(&data_dir, fs::Permissions::from_mode(0o700))
            .expect("private wallet test directory");
        let database_path = std::path::Path::new(&data_dir).join("wallet.sqlite3");
        let database_path = database_path.to_string_lossy().into_owned();
        let database_key = [0x51_u8; MOBILE_DATABASE_KEY_BYTES];
        let mut wallet = 0;
        // SAFETY: All borrowed slices and the output handle remain valid for this call.
        assert_eq!(
            unsafe {
                hns_browser_wallet_create(
                    ffi_slice(database_path.as_bytes()),
                    ffi_slice(&database_key),
                    HNS_BROWSER_NETWORK_REGTEST,
                    0,
                    &mut wallet,
                )
            },
            HNS_BROWSER_RESULT_OK
        );
        assert_ne!(wallet, 0);

        let mut recovery = HnsBrowserBuffer::empty();
        // SAFETY: Output points to one writable buffer descriptor.
        assert_eq!(
            unsafe { hns_browser_wallet_take_recovery_phrase(wallet, &mut recovery) },
            HNS_BROWSER_RESULT_OK
        );
        assert!(!recovery.ptr.is_null());
        assert!(recovery.len > 0);
        assert_ne!(recovery.allocation_id, 0);
        assert_eq!(hns_browser_buffer_free(recovery), HNS_BROWSER_RESULT_OK);

        let mut enabled = u8::MAX;
        // SAFETY: Output points to one writable byte.
        assert_eq!(
            unsafe { hns_browser_wallet_has_hns_reads(wallet, &mut enabled) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(enabled, 0);

        let mut progress = HnsBrowserWalletHnsSyncProgress::empty();
        // SAFETY: Output points to one writable fixed-width progress value.
        assert_eq!(
            unsafe { hns_browser_wallet_hns_sync_progress(wallet, &mut progress) },
            HNS_BROWSER_RESULT_NOT_READY
        );
        assert_eq!(progress, HnsBrowserWalletHnsSyncProgress::empty());

        let published = HnsBrowserWalletHnsSyncProgress {
            struct_size: size_u32::<HnsBrowserWalletHnsSyncProgress>(),
            stage: WALLET_HNS_SYNC_SCANNING,
            has_scanned_height: 1,
            reserved0: 0,
            verified_header_height: 100,
            birthday_height: 0,
            scanned_height: 25,
            target_height: 100,
        };
        let sync_control = wallet_hns_sync_control_entry(wallet)
            .unwrap_or_else(|_| panic!("wallet synchronization control"));
        *sync_control.progress.lock().expect("wallet progress lock") = Some(published);
        let wallet_record = wallet_entry(wallet).unwrap_or_else(|_| panic!("wallet record"));
        let _controller_guard = wallet_record.lock().expect("wallet controller lock");
        sync_control.activity.lock().expect("sync activity").active = true;
        // SAFETY: Output is writable. Holding the controller mutex proves the
        // public mailbox surface does not wait on private wallet state.
        assert_eq!(
            unsafe { hns_browser_wallet_hns_sync_progress(wallet, &mut progress) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(progress, published);
        // Cancellation uses the same public control object and must likewise
        // remain available while synchronization owns the controller mutex.
        assert_eq!(
            hns_browser_wallet_cancel_hns_sync(wallet),
            HNS_BROWSER_RESULT_OK
        );
        assert!(
            sync_control
                .activity
                .lock()
                .expect("sync activity")
                .cancellation_requested
        );
        sync_control.activity.lock().expect("sync activity").active = false;
        drop(_controller_guard);
        *sync_control.progress.lock().expect("wallet progress lock") = None;
        sync_control
            .activity
            .lock()
            .expect("sync activity")
            .cancellation_requested = false;

        let mut snapshot = HnsBrowserBuffer::empty();
        // SAFETY: Output points to one writable buffer descriptor.
        assert_eq!(
            unsafe { hns_browser_wallet_synchronize_hns_reads(wallet, &mut snapshot) },
            HNS_BROWSER_RESULT_NOT_READY
        );
        assert!(snapshot.ptr.is_null());
        assert_eq!(snapshot.len, 0);
        assert_eq!(snapshot.allocation_id, 0);

        let mut imported = HnsBrowserBuffer::empty();
        // SAFETY: Exact UTF-8 input is readable and output is writable. A
        // lifecycle controller must reject this surface without consuming it.
        assert_eq!(
            unsafe {
                hns_browser_wallet_import_hns_name_exact_text(
                    wallet,
                    ffi_slice(b"alpha"),
                    &mut imported,
                )
            },
            HNS_BROWSER_RESULT_NOT_READY
        );
        assert!(imported.ptr.is_null());
        assert_eq!(imported.len, 0);
        assert_eq!(imported.allocation_id, 0);

        // SAFETY: Authorization is readable; port zero must be rejected before composition.
        assert_eq!(
            unsafe {
                hns_browser_wallet_configure_hns_reads(
                    wallet,
                    0,
                    ffi_slice(b"Bearer fixture-read-only"),
                )
            },
            HNS_BROWSER_RESULT_INVALID_ARGUMENT
        );
        // A newly created controller remains in recovery-confirmation state
        // even after the one-time display is consumed. Only reopening the
        // durable database may make read composition installable.
        assert_eq!(
            unsafe {
                hns_browser_wallet_configure_hns_reads(
                    wallet,
                    1,
                    ffi_slice(b"Bearer fixture-read-only"),
                )
            },
            HNS_BROWSER_RESULT_NOT_READY
        );
        assert_eq!(hns_browser_wallet_destroy(wallet), HNS_BROWSER_RESULT_OK);
        // SAFETY: Output is writable; the stale handle must not retain a mailbox.
        assert_eq!(
            unsafe { hns_browser_wallet_hns_sync_progress(wallet, &mut progress) },
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        assert_eq!(
            hns_browser_wallet_cancel_hns_sync(wallet),
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        wallet = 0;
        // SAFETY: The database path/key are readable and output is writable.
        assert_eq!(
            unsafe {
                hns_browser_wallet_open(
                    ffi_slice(database_path.as_bytes()),
                    ffi_slice(&database_key),
                    &mut wallet,
                )
            },
            HNS_BROWSER_RESULT_OK
        );
        assert_ne!(wallet, 0);
        // SAFETY: The bounded visible-ASCII authorization is readable for this call.
        assert_eq!(
            unsafe {
                hns_browser_wallet_configure_hns_reads(
                    wallet,
                    1,
                    ffi_slice(b"Bearer fixture-read-only"),
                )
            },
            HNS_BROWSER_RESULT_OK
        );
        // SAFETY: Output points to one writable byte.
        assert_eq!(
            unsafe { hns_browser_wallet_has_hns_reads(wallet, &mut enabled) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(enabled, 1);

        // Once reads are installed, malformed input is rejected at the exact
        // boundary without allocating an HNWI result or reaching node I/O.
        assert_eq!(
            unsafe {
                hns_browser_wallet_import_hns_name_exact_text(wallet, null_slice(), &mut imported)
            },
            HNS_BROWSER_RESULT_INVALID_ARGUMENT
        );
        let invalid_utf8 = [0xff];
        assert_eq!(
            unsafe {
                hns_browser_wallet_import_hns_name_exact_text(
                    wallet,
                    ffi_slice(&invalid_utf8),
                    &mut imported,
                )
            },
            HNS_BROWSER_RESULT_INVALID_UTF8
        );
        let oversized_name = HnsBrowserSlice {
            ptr: ptr::without_provenance::<u8>(1),
            len: (MAX_WALLET_NAME_INPUT_BYTES + 1) as u64,
        };
        assert_eq!(
            unsafe {
                hns_browser_wallet_import_hns_name_exact_text(wallet, oversized_name, &mut imported)
            },
            HNS_BROWSER_RESULT_INVALID_ARGUMENT
        );
        assert!(imported.ptr.is_null());
        assert_eq!(imported.len, 0);
        assert_eq!(imported.allocation_id, 0);

        // Canonical validation remains upstream and non-poisoning. These exact
        // malformed spellings must not be transformed before that rejection.
        assert_eq!(
            unsafe { hns_browser_wallet_unlock(wallet, ffi_slice(&database_key)) },
            HNS_BROWSER_RESULT_OK
        );
        for malformed_exact in [
            b" alpha".as_slice(),
            b"Alpha".as_slice(),
            b"alpha.".as_slice(),
            "\u{e9}".as_bytes(),
        ] {
            assert_eq!(
                unsafe {
                    hns_browser_wallet_import_hns_name_exact_text(
                        wallet,
                        ffi_slice(malformed_exact),
                        &mut imported,
                    )
                },
                HNS_BROWSER_RESULT_INVALID_ARGUMENT
            );
            assert!(imported.ptr.is_null());
        }
        let mut unlocked_status = HnsBrowserBuffer::empty();
        assert_eq!(
            unsafe { hns_browser_wallet_status(wallet, &mut unlocked_status) },
            HNS_BROWSER_RESULT_OK
        );
        assert!(owned_string(unlocked_status).contains("\"locked\":false"));
        assert_eq!(
            hns_browser_buffer_free(unlocked_status),
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(hns_browser_wallet_lock(wallet), HNS_BROWSER_RESULT_OK);

        let mut status = HnsBrowserBuffer::empty();
        // SAFETY: Output points to one writable buffer descriptor.
        assert_eq!(
            unsafe { hns_browser_wallet_status(wallet, &mut status) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(
            owned_string(status),
            "{\"locked\":true,\"activeWallet\":null,\"enabledModules\":[\"handshake\"],\"hnsValueEnabled\":false,\"shakedexEnabled\":false,\"mainnetSettlementEnabled\":false}"
        );
        assert_eq!(hns_browser_buffer_free(status), HNS_BROWSER_RESULT_OK);
        assert_eq!(hns_browser_wallet_destroy(wallet), HNS_BROWSER_RESULT_OK);
        cleanup_dir(&data_dir);
    }

    #[test]
    fn proxy_challenge_lifecycle_and_owned_endpoint_fail_closed() {
        let _guard = test_guard();
        let data_dir = unique_data_dir("proxy-lifecycle");
        let runtime = create_runtime(&data_dir);

        let nonnull_empty = HnsBrowserSlice {
            ptr: b"".as_ptr(),
            len: 0,
        };
        let mut rejected_proxy = 99;
        // SAFETY: Output is writable; the ambiguous slice is rejected before reading.
        assert_eq!(
            unsafe { hns_browser_proxy_start(runtime, nonnull_empty, &mut rejected_proxy) },
            HNS_BROWSER_RESULT_INVALID_ARGUMENT
        );
        assert_eq!(rejected_proxy, 0);

        let proxy = start_icann_proxy(runtime);
        assert_ne!(proxy, runtime);
        let mut endpoint = HnsBrowserProxyEndpoint::empty();
        // SAFETY: Output points to one writable endpoint descriptor.
        assert_eq!(
            unsafe { hns_browser_proxy_endpoint(proxy, &mut endpoint) },
            HNS_BROWSER_RESULT_OK
        );
        assert_ne!(endpoint.port, 0);
        let session = owned_bytes(endpoint.session_id);
        let realm = owned_bytes(endpoint.realm);
        assert!(!owned_bytes(endpoint.username).is_empty());
        assert!(!owned_bytes(endpoint.password).is_empty());

        let mut matches = 0;
        // SAFETY: All borrowed slices and output are valid for this call.
        assert_eq!(
            unsafe {
                hns_browser_proxy_matches_authentication_challenge(
                    proxy,
                    ffi_slice(b"127.0.0.1"),
                    endpoint.port,
                    ffi_slice(&realm),
                    &mut matches,
                )
            },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(matches, 1);
        // SAFETY: All borrowed slices and output are valid for this call.
        assert_eq!(
            unsafe {
                hns_browser_proxy_matches_authentication_challenge(
                    proxy,
                    ffi_slice(b"localhost"),
                    endpoint.port,
                    ffi_slice(&realm),
                    &mut matches,
                )
            },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(matches, 0);
        // SAFETY: All borrowed slices and output are valid for this call.
        assert_eq!(
            unsafe {
                hns_browser_proxy_matches_instance(
                    proxy,
                    ffi_slice(&session),
                    endpoint.generation,
                    &mut matches,
                )
            },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(matches, 1);

        for buffer in [
            endpoint.session_id,
            endpoint.realm,
            endpoint.username,
            endpoint.password,
        ] {
            assert_eq!(hns_browser_buffer_free(buffer), HNS_BROWSER_RESULT_OK);
        }
        assert_eq!(hns_browser_proxy_request_stop(proxy), HNS_BROWSER_RESULT_OK);
        // SAFETY: Output points to one writable endpoint descriptor.
        assert_eq!(
            unsafe { hns_browser_proxy_endpoint(proxy, &mut endpoint) },
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        // SAFETY: All borrowed slices and output are valid for this call.
        assert_eq!(
            unsafe {
                hns_browser_proxy_matches_authentication_challenge(
                    proxy,
                    ffi_slice(b"127.0.0.1"),
                    1,
                    ffi_slice(&realm),
                    &mut matches,
                )
            },
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        // SAFETY: All borrowed slices and output are valid for this call.
        assert_eq!(
            unsafe {
                hns_browser_proxy_matches_local_certificate(
                    proxy,
                    ffi_slice(b"welcome"),
                    ffi_slice(&[1, 2, 3]),
                    &mut matches,
                )
            },
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        assert_eq!(hns_browser_proxy_destroy(proxy), HNS_BROWSER_RESULT_OK);
        assert_eq!(
            hns_browser_proxy_destroy(proxy),
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        assert_eq!(hns_browser_runtime_destroy(runtime), HNS_BROWSER_RESULT_OK);
        cleanup_dir(&data_dir);
    }

    #[test]
    fn one_active_proxy_per_runtime_and_runtime_destroy_owns_teardown() {
        let _guard = test_guard();
        let data_dir = unique_data_dir("proxy-owner");
        let runtime = create_runtime(&data_dir);
        let barrier = Arc::new(Barrier::new(3));
        let starts = (0..2)
            .map(|_| {
                let barrier = Arc::clone(&barrier);
                thread::spawn(move || {
                    barrier.wait();
                    let mut proxy = 0;
                    // SAFETY: Output is thread-local and null scope is valid ICANN mode.
                    let result =
                        unsafe { hns_browser_proxy_start(runtime, null_slice(), &mut proxy) };
                    (result, proxy)
                })
            })
            .collect::<Vec<_>>();
        barrier.wait();
        let outcomes = starts
            .into_iter()
            .map(|start| start.join().expect("proxy start thread"))
            .collect::<Vec<_>>();
        let successful = outcomes
            .iter()
            .filter(|(result, _)| *result == HNS_BROWSER_RESULT_OK)
            .collect::<Vec<_>>();
        assert_eq!(successful.len(), 1);
        assert!(
            outcomes.iter().any(|(result, proxy)| {
                *result == HNS_BROWSER_RESULT_PROXY_ERROR && *proxy == 0
            })
        );
        let proxy = successful[0].1;
        assert_eq!(hns_browser_runtime_destroy(runtime), HNS_BROWSER_RESULT_OK);
        assert_eq!(
            hns_browser_proxy_destroy(proxy),
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        cleanup_dir(&data_dir);
    }

    #[test]
    fn policy_change_revokes_published_proxy_before_returning() {
        let _guard = test_guard();
        let data_dir = unique_data_dir("policy-revoke");
        let runtime = create_runtime(&data_dir);
        let proxy = start_icann_proxy(runtime);
        let mut policy = HnsBrowserPolicy::defaults();
        policy.experimental_p2p_dns_relay = 1;
        let mut revision = 0;
        // SAFETY: Policy and output pointers are valid for this call.
        assert_eq!(
            unsafe { hns_browser_runtime_set_policy(runtime, &policy, &mut revision) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(revision, 1);
        let mut endpoint = HnsBrowserProxyEndpoint::empty();
        // SAFETY: Output points to one writable endpoint descriptor.
        assert_eq!(
            unsafe { hns_browser_proxy_endpoint(proxy, &mut endpoint) },
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        assert_eq!(hns_browser_proxy_destroy(proxy), HNS_BROWSER_RESULT_OK);
        assert_eq!(hns_browser_runtime_destroy(runtime), HNS_BROWSER_RESULT_OK);
        cleanup_dir(&data_dir);
    }

    #[test]
    fn unchanged_policy_preserves_revision_and_published_proxy() {
        let _guard = test_guard();
        let data_dir = unique_data_dir("policy-noop");
        let runtime = create_runtime(&data_dir);
        let proxy = start_icann_proxy(runtime);
        let policy = HnsBrowserPolicy::defaults();
        let mut revision = u64::MAX;
        // SAFETY: Policy and output pointers are valid for this call.
        assert_eq!(
            unsafe { hns_browser_runtime_set_policy(runtime, &policy, &mut revision) },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(revision, 0);
        let mut endpoint = HnsBrowserProxyEndpoint::empty();
        // SAFETY: Output points to one writable endpoint descriptor.
        assert_eq!(
            unsafe { hns_browser_proxy_endpoint(proxy, &mut endpoint) },
            HNS_BROWSER_RESULT_OK
        );
        for buffer in [
            endpoint.session_id,
            endpoint.realm,
            endpoint.username,
            endpoint.password,
        ] {
            assert_eq!(hns_browser_buffer_free(buffer), HNS_BROWSER_RESULT_OK);
        }
        assert_eq!(hns_browser_proxy_destroy(proxy), HNS_BROWSER_RESULT_OK);
        assert_eq!(hns_browser_runtime_destroy(runtime), HNS_BROWSER_RESULT_OK);
        cleanup_dir(&data_dir);
    }

    #[test]
    fn concurrent_policy_and_proxy_publication_never_leave_old_revision_active() {
        let _guard = test_guard();
        let data_dir = unique_data_dir("policy-start-race");
        let runtime = create_runtime(&data_dir);
        for iteration in 0..12 {
            let barrier = Arc::new(Barrier::new(3));
            let start_barrier = Arc::clone(&barrier);
            let start = thread::spawn(move || {
                start_barrier.wait();
                let mut proxy = 0;
                // SAFETY: Output is thread-local and null scope is valid ICANN mode.
                let result = unsafe { hns_browser_proxy_start(runtime, null_slice(), &mut proxy) };
                (result, proxy)
            });
            let policy_barrier = Arc::clone(&barrier);
            let update = thread::spawn(move || {
                let mut policy = HnsBrowserPolicy::defaults();
                policy.experimental_p2p_dns_relay = u8::from(iteration % 2 == 0);
                let mut revision = 0;
                policy_barrier.wait();
                // SAFETY: Policy and output live for the complete call.
                let result =
                    unsafe { hns_browser_runtime_set_policy(runtime, &policy, &mut revision) };
                (result, revision)
            });
            barrier.wait();
            let (start_result, proxy) = start.join().expect("proxy start thread");
            let (update_result, revision) = update.join().expect("policy update thread");
            assert_eq!(update_result, HNS_BROWSER_RESULT_OK);
            assert_ne!(revision, 0);
            assert!(matches!(
                start_result,
                HNS_BROWSER_RESULT_OK | HNS_BROWSER_RESULT_PROXY_ERROR
            ));
            if start_result == HNS_BROWSER_RESULT_OK {
                let entry = match proxy_entry(proxy) {
                    Ok(entry) => entry,
                    Err(_) => panic!("published proxy handle must remain registered"),
                };
                if entry.active.load(Ordering::Acquire) {
                    assert_eq!(entry.policy_revision, revision);
                }
                assert_eq!(hns_browser_proxy_destroy(proxy), HNS_BROWSER_RESULT_OK);
            } else {
                assert_eq!(proxy, 0);
            }
        }

        assert_eq!(hns_browser_runtime_destroy(runtime), HNS_BROWSER_RESULT_OK);
        cleanup_dir(&data_dir);
    }

    #[test]
    fn relay_policy_fields_are_independent_and_have_safe_defaults() {
        let options = HnsBrowserRuntimeOptions::defaults();
        assert_eq!(options.experimental_p2p_dns_relay, 0);
        assert_eq!(options.resolution_mode, HNS_BROWSER_RESOLUTION_STRICT);
        assert_eq!(options.legacy_hns_doh_compatibility, 0);
        let policy = HnsBrowserPolicy::defaults();
        assert_eq!(policy.experimental_p2p_dns_relay, 0);
        assert_eq!(policy.resolution_mode, HNS_BROWSER_RESOLUTION_STRICT);
        assert_eq!(policy.legacy_hns_doh_compatibility, 0);

        // SAFETY: The empty endpoint has the documented null/zero representation.
        let runtime_policy = match unsafe {
            policy_from_fields(
                HNS_BROWSER_RESOLUTION_COMPATIBILITY,
                HnsBrowserSlice::empty(),
                0,
                1,
                1,
            )
        } {
            Ok(policy) => policy,
            Err(_) => panic!("valid independent relay controls"),
        };
        assert!(runtime_policy.experimental_p2p_dns_relay);
        assert_eq!(runtime_policy.resolution_mode, ResolutionMode::Strict);
        assert!(runtime_policy.hns_doh_resolver.is_none());
        assert!(!runtime_policy.legacy_hns_doh_compatibility);

        // SAFETY: The static endpoint bytes remain readable for the call.
        let configured_recovery = match unsafe {
            policy_from_fields(
                HNS_BROWSER_RESOLUTION_STRICT,
                ffi_slice(b"HTTPS://Resolver.Example.NET.:443/dns-query"),
                0,
                0,
                0,
            )
        } {
            Ok(policy) => policy,
            Err(_) => panic!("valid explicit recovery endpoint"),
        };
        assert_eq!(
            configured_recovery.hns_doh_resolver.as_deref(),
            Some("https://resolver.example.net/dns-query")
        );
        assert!(!configured_recovery.experimental_p2p_dns_relay);

        // SAFETY: The static endpoint bytes remain readable for the call.
        assert!(
            unsafe {
                policy_from_fields(
                    HNS_BROWSER_RESOLUTION_STRICT,
                    ffi_slice(b"http://resolver.example.net/dns-query"),
                    0,
                    0,
                    0,
                )
            }
            .is_err()
        );

        let mut invalid = HnsBrowserPolicy::defaults();
        invalid.experimental_p2p_dns_relay = 2;
        assert!(validate_policy(invalid).is_err());

        assert_eq!(
            security_path_code(Some(BrowserProxySecurityPath::DaneP2pDnsRelay)),
            HNS_BROWSER_SECURITY_PATH_DANE_P2P_DNS_RELAY
        );
        assert_eq!(
            security_path_code(Some(BrowserProxySecurityPath::HnsP2pDnsRelay)),
            HNS_BROWSER_SECURITY_PATH_HNS_P2P_DNS_RELAY
        );
    }

    #[test]
    fn status_take_is_exact_latest_and_host_isolated() {
        let _guard = test_guard();
        let data_dir = unique_data_dir("status-mailbox");
        let runtime = create_runtime(&data_dir);
        let proxy = start_icann_proxy(runtime);
        let entry = match proxy_entry(proxy) {
            Ok(entry) => entry,
            Err(_) => panic!("live proxy entry must exist"),
        };
        let generation = entry.proxy.generation();
        {
            let mut statuses = entry.mailbox.statuses.lock().expect("status mailbox");
            statuses.push_back(QueuedMainFrameStatus {
                generation,
                host: "other.welcome".to_owned(),
                http_status: 201,
                tls_policy: HNS_BROWSER_TLS_POLICY_DANE,
                resolver_policy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
                security_path: HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DOH,
                resolution_trace_json: "{\"other\":true}".to_owned(),
            });
            statuses.push_back(QueuedMainFrameStatus {
                generation,
                host: "www.welcome".to_owned(),
                http_status: 200,
                tls_policy: HNS_BROWSER_TLS_POLICY_DANE,
                resolver_policy: HNS_BROWSER_RESOLVER_POLICY_UNKNOWN,
                security_path: HNS_BROWSER_SECURITY_PATH_DANE_AUTHORITATIVE_DNS53,
                resolution_trace_json: "{\"old\":true}".to_owned(),
            });
            statuses.push_back(QueuedMainFrameStatus {
                generation,
                host: "www.welcome".to_owned(),
                http_status: 204,
                tls_policy: HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK,
                resolver_policy: HNS_BROWSER_RESOLVER_POLICY_HNS_DOH_COMPATIBILITY,
                security_path: HNS_BROWSER_SECURITY_PATH_HNS_THIRD_PARTY_DOH,
                resolution_trace_json: "{\"latest\":true}".to_owned(),
            });
        }
        let mut status = HnsBrowserProxyStatus::empty();
        // SAFETY: Host slice and output are valid for this call.
        assert_eq!(
            unsafe {
                hns_browser_proxy_take_main_frame_status(
                    proxy,
                    ffi_slice(b"www.welcome"),
                    &mut status,
                )
            },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(status.generation, generation);
        assert_eq!(status.http_status, 204);
        assert_eq!(status.tls_policy, HNS_BROWSER_TLS_POLICY_WEBPKI_FALLBACK);
        assert_eq!(owned_string(status.host), "www.welcome");
        assert_eq!(
            owned_string(status.resolution_trace_json),
            "{\"latest\":true}"
        );
        assert_eq!(hns_browser_buffer_free(status.host), HNS_BROWSER_RESULT_OK);
        assert_eq!(
            hns_browser_buffer_free(status.resolution_trace_json),
            HNS_BROWSER_RESULT_OK
        );

        // SAFETY: Host slice and output are valid for this call.
        assert_eq!(
            unsafe {
                hns_browser_proxy_take_main_frame_status(
                    proxy,
                    ffi_slice(b"other.welcome"),
                    &mut status,
                )
            },
            HNS_BROWSER_RESULT_OK
        );
        assert_eq!(status.http_status, 201);
        assert_eq!(hns_browser_buffer_free(status.host), HNS_BROWSER_RESULT_OK);
        assert_eq!(
            hns_browser_buffer_free(status.resolution_trace_json),
            HNS_BROWSER_RESULT_OK
        );

        assert_eq!(hns_browser_proxy_request_stop(proxy), HNS_BROWSER_RESULT_OK);
        // SAFETY: Host slice and output are valid; inactive record must fail closed.
        assert_eq!(
            unsafe {
                hns_browser_proxy_take_main_frame_status(
                    proxy,
                    ffi_slice(b"www.welcome"),
                    &mut status,
                )
            },
            HNS_BROWSER_RESULT_NOT_FOUND
        );
        assert_eq!(hns_browser_proxy_destroy(proxy), HNS_BROWSER_RESULT_OK);
        assert_eq!(hns_browser_runtime_destroy(runtime), HNS_BROWSER_RESULT_OK);
        cleanup_dir(&data_dir);
    }

    #[test]
    fn bitcoin_boundary_is_bounded_canonical_and_immediately_cancellable() {
        let mut activity = WalletBitcoinSyncActivityState::default();
        assert!(activity.begin());
        assert!(!activity.begin());
        assert!(activity.request_cancellation());
        assert!(activity.cancellation_requested);
        activity.finish();
        assert!(!activity.active);
        assert!(!activity.cancellation_requested);
        assert!(!activity.request_cancellation());

        let database = Path::new("/private/app/NativeWallet/mainnet/wallet.sqlite3");
        assert_eq!(
            ios_wallet_bitcoin_data_dir(database),
            PathBuf::from("/private/app/NativeWallet/mainnet/wallet.bitcoin-kyoto")
        );

        // SAFETY: Static test inputs remain readable for each bounded parse.
        assert_eq!(
            unsafe { wallet_nonzero_sats(ffi_slice(b"1")) }.ok(),
            Some(1)
        );
        // SAFETY: Static test inputs remain readable for each bounded parse.
        assert!(unsafe { wallet_nonzero_sats(ffi_slice(b"01")) }.is_err());
        // SAFETY: Static test inputs remain readable for each bounded parse.
        assert!(unsafe { wallet_nonzero_sats(ffi_slice(b"0")) }.is_err());
        // SAFETY: Static test inputs remain readable for each bounded parse.
        assert_eq!(
            unsafe { wallet_bitcoin_address(ffi_slice(b"bc1qexample123")) }
                .ok()
                .as_deref(),
            Some("bc1qexample123")
        );

        let bundle = match wallet_bitcoin_bundle(&json!({
            "network": "mainnet",
            "receiveAddress": "bc1qexample123",
        })) {
            Ok(bundle) => bundle,
            Err(_) => panic!("bounded Bitcoin bundle"),
        };
        assert_eq!(&bundle.0[..4], b"HNBW");
        assert_eq!(bundle.0[4], 1);
        assert_eq!(&bundle.0[5..8], &[0, 0, 0]);
        assert_eq!(
            u32::from_be_bytes([bundle.0[8], bundle.0[9], bundle.0[10], bundle.0[11]]) as usize,
            bundle.0.len() - WALLET_JSON_BUNDLE_HEADER_BYTES
        );
    }
}
