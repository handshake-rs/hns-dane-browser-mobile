//! Android JNI adapter for the platform-neutral browser runtime.

#![cfg_attr(
    not(test),
    deny(clippy::expect_used, clippy::panic, clippy::unwrap_used)
)]

use hns_mobile_platform_runtime::*;
use hns_wallet_mobile::{
    HnsBootstrapPolicy, HnsNetwork, HnsNodeRpcBackend, HnsNodeRpcConfig,
    MAX_MOBILE_RECOVERY_PHRASE_BYTES, MobileDatabaseKey, MobileHnsReadController, MobilePlatform,
    MobileRecoveryPhrase, MobileWalletController,
};
use jni::JNIEnv;
use jni::objects::{JByteArray, JCharArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jcharArray, jint, jlong, jstring};
use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, SocketAddr};
use std::os::fd::{FromRawFd, RawFd};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};
use std::time::{Duration, Instant};

const MAX_LOCAL_CERTIFICATE_DER_BYTES: usize = 64 * 1024;
const MAX_BROWSER_NAMESPACE_INPUT_BYTES: usize = 1_024;
const ANDROID_BROWSER_NAMESPACE_INVALID: jint = 0;
const ANDROID_BROWSER_NAMESPACE_HNS: jint = 1;
const ANDROID_BROWSER_NAMESPACE_ICANN: jint = 2;
const ANDROID_BROWSER_NAMESPACE_NATIVE_GATEWAY: jint = 3;
const PROXY_ENDPOINT_BUNDLE_MAGIC: &[u8; 4] = b"HNSP";
const PROXY_ENDPOINT_BUNDLE_VERSION: u8 = 1;
const PROXY_STATUS_BUNDLE_MAGIC: &[u8; 4] = b"HNSS";
const PROXY_STATUS_BUNDLE_VERSION: u8 = 1;
const MAX_PROXY_STATUS_BUNDLE_BYTES: usize = 64 * 1024;
const MAX_PROXY_STATUS_HOSTS: usize = 8;
const MAX_PROXY_STATUS_RETAINED_TRACE_BYTES: usize = 64 * 1024;
const MAX_ANDROID_PROXY_HANDLES: usize = 8;
static NEXT_PROXY_HANDLE: AtomicU64 = AtomicU64::new(1);
static PROXY_HANDLES: OnceLock<Mutex<HashMap<jlong, Arc<AndroidProxyRecord>>>> = OnceLock::new();
const MAX_ANDROID_WALLET_HANDLES: usize = 4;
const MAX_ANDROID_WALLET_PATH_BYTES: usize = 4_096;
const MAX_ANDROID_WALLET_RPC_AUTHORIZATION_CHARACTERS: usize = 4_096;
const ANDROID_WALLET_RPC_CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
const ANDROID_WALLET_RPC_READ_TIMEOUT: Duration = Duration::from_secs(20);
const ANDROID_WALLET_RPC_WRITE_TIMEOUT: Duration = Duration::from_secs(20);
const ANDROID_WALLET_NETWORK_MAINNET: jint = 1;
const ANDROID_WALLET_NETWORK_TESTNET: jint = 2;
const ANDROID_WALLET_NETWORK_REGTEST: jint = 3;
const WALLET_STATUS_BUNDLE_MAGIC: &[u8; 4] = b"HNWS";
const WALLET_STATUS_BUNDLE_VERSION: u8 = 1;
const WALLET_STATUS_BUNDLE_BYTES: usize = 24;
const WALLET_ACCOUNT_BUNDLE_MAGIC: &[u8; 4] = b"HNWA";
const WALLET_ACCOUNT_BUNDLE_VERSION: u8 = 1;
const MAX_WALLET_ACCOUNT_LABEL_BYTES: usize = 128;
const WALLET_READ_BUNDLE_MAGIC: &[u8; 4] = b"HNWR";
const WALLET_READ_BUNDLE_VERSION: u8 = 1;
const WALLET_READ_BUNDLE_FLAGS: u8 = 1;
const WALLET_READ_BUNDLE_HEADER_BYTES: usize = 12;
const MAX_WALLET_READ_JSON_BYTES: usize = 4 * 1024 * 1024;
static WALLET_HANDLES: OnceLock<BoundedMonotonicRegistry<AndroidWalletRecord>> = OnceLock::new();
const MAX_STREAMING_GATEWAY_REQUESTS: usize = 8;
const STREAMING_GATEWAY_CAPACITY_WAIT: Duration = Duration::from_secs(30);
static STREAMING_GATEWAY_REQUESTS: StreamingGatewayLimiter =
    StreamingGatewayLimiter::new(MAX_STREAMING_GATEWAY_REQUESTS);

struct StreamingGatewayLimiter {
    active: Mutex<usize>,
    capacity_available: Condvar,
    limit: usize,
}

impl StreamingGatewayLimiter {
    const fn new(limit: usize) -> Self {
        Self {
            active: Mutex::new(0),
            capacity_available: Condvar::new(),
            limit,
        }
    }

    #[cfg(test)]
    fn try_acquire(&self) -> Option<StreamingGatewayPermit<'_>> {
        let mut active = self.active.lock().ok()?;
        if *active >= self.limit {
            return None;
        }
        *active += 1;
        Some(StreamingGatewayPermit { limiter: self })
    }

    fn acquire_timeout(&self, timeout: Duration) -> Option<StreamingGatewayPermit<'_>> {
        let active = self.active.lock().ok()?;
        let (mut active, wait) = self
            .capacity_available
            .wait_timeout_while(active, timeout, |active| *active >= self.limit)
            .ok()?;
        if wait.timed_out() && *active >= self.limit {
            return None;
        }
        *active += 1;
        Some(StreamingGatewayPermit { limiter: self })
    }
}

struct StreamingGatewayPermit<'a> {
    limiter: &'a StreamingGatewayLimiter,
}

impl Drop for StreamingGatewayPermit<'_> {
    fn drop(&mut self) {
        if let Ok(mut active) = self.limiter.active.lock() {
            *active = active.saturating_sub(1);
            drop(active);
            self.limiter.capacity_available.notify_one();
        }
    }
}

struct AndroidRuntimeRecord {
    runtime: BrowserRuntime,
}

struct AndroidProxyRecord {
    proxy: BrowserProxy,
    statuses: Arc<AndroidProxyStatusMailbox>,
}

struct SensitiveUtf16(Vec<u16>);

impl SensitiveUtf16 {
    fn from_recovery_phrase(mut phrase: String) -> Option<Self> {
        if phrase.is_empty() || phrase.len() > MAX_MOBILE_RECOVERY_PHRASE_BYTES {
            wipe_string(&mut phrase);
            return None;
        }
        let encoded = phrase.encode_utf16().collect::<Vec<_>>();
        wipe_string(&mut phrase);
        (!encoded.is_empty() && encoded.len() <= MAX_MOBILE_RECOVERY_PHRASE_BYTES)
            .then_some(Self(encoded))
    }

    fn as_slice(&self) -> &[u16] {
        self.0.as_slice()
    }
}

impl Drop for SensitiveUtf16 {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}

struct SensitiveString(String);

impl SensitiveString {
    fn take(&mut self) -> String {
        std::mem::take(&mut self.0)
    }
}

impl Drop for SensitiveString {
    fn drop(&mut self) {
        wipe_string(&mut self.0);
    }
}

fn lock_if_active<'a, T>(active: &AtomicBool, value: &'a Mutex<T>) -> Option<MutexGuard<'a, T>> {
    let guard = value.lock().ok()?;
    active.load(Ordering::Acquire).then_some(guard)
}

enum AndroidWalletController {
    Lifecycle(MobileWalletController),
    Reads(MobileHnsReadController<HnsNodeRpcBackend>),
    Failed,
}

impl AndroidWalletController {
    fn status_bundle(&mut self) -> Option<Vec<u8>> {
        let (status, read_mode) = match self {
            Self::Lifecycle(controller) => (controller.status().ok()?, false),
            Self::Reads(controller) => (controller.status().ok()?, true),
            Self::Failed => return None,
        };
        let active_wallet = status
            .active_wallet
            .as_ref()
            .map(|wallet| wallet.as_bytes());
        let enabled_modules_valid = if read_mode {
            status.enabled_modules.len() == 1
                && status
                    .enabled_modules
                    .iter()
                    .next()
                    .is_some_and(|module| format!("{module:?}") == "Handshake")
        } else {
            status.enabled_modules.is_empty()
        };
        wallet_status_bundle(
            status.locked,
            active_wallet,
            enabled_modules_valid,
            status.mainnet_settlement_enabled,
        )
    }

    fn account_bundle(&mut self) -> Option<Vec<u8>> {
        let mut accounts = match self {
            Self::Lifecycle(controller) => controller.accounts().ok()?,
            Self::Reads(controller) => controller.accounts().ok()?,
            Self::Failed => return None,
        };
        if accounts.len() != 1 {
            return None;
        }
        let account = accounts.pop()?;
        let module = format!("{:?}", account.module);
        wallet_account_bundle(
            account.account_id.as_bytes(),
            module.as_str(),
            account.label.as_str(),
            account.receive_display.is_some(),
        )
    }

    fn unlock(&mut self, key: &MobileDatabaseKey) -> bool {
        match self {
            Self::Lifecycle(controller) => controller.unlock(key).is_ok(),
            Self::Reads(controller) => controller.unlock(key).is_ok(),
            Self::Failed => false,
        }
    }

    fn lock(&mut self) -> bool {
        match self {
            Self::Lifecycle(controller) => controller.lock().is_ok(),
            Self::Reads(controller) => controller.lock().is_ok(),
            Self::Failed => false,
        }
    }

    fn install_hns_reads(&mut self, backend: HnsNodeRpcBackend) -> bool {
        if !matches!(self, Self::Lifecycle(_)) {
            return false;
        }
        let lifecycle = match std::mem::replace(self, Self::Failed) {
            Self::Lifecycle(controller) => controller,
            _ => return false,
        };
        match lifecycle.into_hns_reads(backend) {
            Ok(controller) => {
                *self = Self::Reads(controller);
                true
            }
            Err(error) => {
                android_log_error(&format!(
                    "wallet HNS read controller installation failed closed: {error}"
                ));
                false
            }
        }
    }

    const fn has_hns_reads(&self) -> bool {
        matches!(self, Self::Reads(_))
    }

    fn synchronize_hns_reads(&mut self) -> Option<Vec<u8>> {
        let Self::Reads(controller) = self else {
            return None;
        };
        let snapshot = match controller.synchronize() {
            Ok(snapshot) => snapshot,
            Err(error) => {
                android_log_error(&format!("wallet HNS read synchronization failed: {error}"));
                return None;
            }
        };
        let mut json = serde_json::to_vec(&snapshot).ok()?;
        let bundle = wallet_read_bundle(json.as_slice());
        json.fill(0);
        bundle
    }
}

struct AndroidWalletRecord {
    active: AtomicBool,
    controller: Arc<Mutex<AndroidWalletController>>,
    pending_recovery: Mutex<Option<SensitiveUtf16>>,
    hns_reads_installable: bool,
}

impl AndroidWalletRecord {
    fn new(controller: MobileWalletController, recovery: Option<SensitiveUtf16>) -> Self {
        // A newly generated wallet cannot acquire a network read backend until
        // its confirmed key has been reopened in a new native controller. This
        // keeps taking the one-shot recovery display distinct from durable
        // platform-key publication.
        let hns_reads_installable = recovery.is_none();
        Self {
            active: AtomicBool::new(true),
            controller: Arc::new(Mutex::new(AndroidWalletController::Lifecycle(controller))),
            pending_recovery: Mutex::new(recovery),
            hns_reads_installable,
        }
    }

    fn controller_if_active(&self) -> Option<MutexGuard<'_, AndroidWalletController>> {
        lock_if_active(&self.active, self.controller.as_ref())
    }

    fn pending_recovery_if_active(&self) -> Option<MutexGuard<'_, Option<SensitiveUtf16>>> {
        lock_if_active(&self.active, &self.pending_recovery)
    }

    fn deactivate(&self) {
        self.active.store(false, Ordering::Release);
    }
}

struct RegistryState<T> {
    records: HashMap<jlong, Arc<T>>,
    reservations: HashSet<jlong>,
}

struct BoundedMonotonicRegistry<T> {
    next: AtomicU64,
    limit: usize,
    state: Mutex<RegistryState<T>>,
}

impl<T> BoundedMonotonicRegistry<T> {
    fn new(limit: usize) -> Self {
        Self {
            next: AtomicU64::new(1),
            limit,
            state: Mutex::new(RegistryState {
                records: HashMap::new(),
                reservations: HashSet::new(),
            }),
        }
    }

    fn reserve(&self) -> Option<jlong> {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if state.records.len().saturating_add(state.reservations.len()) >= self.limit {
            return None;
        }
        let handle = self
            .next
            .fetch_update(Ordering::AcqRel, Ordering::Acquire, |current| {
                (current <= i64::MAX as u64).then_some(current.saturating_add(1))
            })
            .ok()
            .and_then(|value| jlong::try_from(value).ok())
            .filter(|value| *value > 0)?;
        if !state.reservations.insert(handle) {
            return None;
        }
        Some(handle)
    }

    fn finish(&self, handle: jlong, record: Arc<T>) -> bool {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !state.reservations.remove(&handle) || state.records.len() >= self.limit {
            return false;
        }
        match state.records.entry(handle) {
            std::collections::hash_map::Entry::Vacant(entry) => {
                entry.insert(record);
                true
            }
            std::collections::hash_map::Entry::Occupied(_) => false,
        }
    }

    fn cancel(&self, handle: jlong) {
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .reservations
            .remove(&handle);
    }

    fn get(&self, handle: jlong) -> Option<Arc<T>> {
        if handle <= 0 {
            return None;
        }
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .records
            .get(&handle)
            .cloned()
    }

    fn remove(&self, handle: jlong) -> Option<Arc<T>> {
        if handle <= 0 {
            return None;
        }
        self.state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .records
            .remove(&handle)
    }
}

struct WalletHandleReservation {
    handle: jlong,
    active: bool,
}

impl WalletHandleReservation {
    fn new() -> Option<Self> {
        wallet_registry().reserve().map(|handle| Self {
            handle,
            active: true,
        })
    }

    fn finish(mut self, record: AndroidWalletRecord) -> Option<jlong> {
        let registered = wallet_registry().finish(self.handle, Arc::new(record));
        self.active = false;
        registered.then_some(self.handle)
    }
}

impl Drop for WalletHandleReservation {
    fn drop(&mut self) {
        if self.active {
            wallet_registry().cancel(self.handle);
        }
    }
}

fn wallet_registry() -> &'static BoundedMonotonicRegistry<AndroidWalletRecord> {
    WALLET_HANDLES.get_or_init(|| BoundedMonotonicRegistry::new(MAX_ANDROID_WALLET_HANDLES))
}

fn wallet_from_handle(handle: jlong) -> Option<Arc<AndroidWalletRecord>> {
    wallet_registry().get(handle)
}

fn wipe_string(value: &mut String) {
    // SAFETY: every byte is replaced by NUL, which is valid UTF-8, before the
    // String is cleared. Capacity is retained until drop and contains no phrase.
    unsafe { value.as_mut_vec().fill(0) };
    value.clear();
}

fn android_wallet_network(code: jint) -> Option<HnsNetwork> {
    match code {
        ANDROID_WALLET_NETWORK_MAINNET => Some(HnsNetwork::Mainnet),
        ANDROID_WALLET_NETWORK_TESTNET => Some(HnsNetwork::Testnet),
        ANDROID_WALLET_NETWORK_REGTEST => Some(HnsNetwork::Regtest),
        _ => None,
    }
}

fn android_wallet_path(env: &mut JNIEnv<'_>, path: &JString<'_>) -> Option<PathBuf> {
    let path = env.get_string(path).ok()?.to_string_lossy().into_owned();
    if path.is_empty() || path.len() > MAX_ANDROID_WALLET_PATH_BYTES {
        return None;
    }
    let path = PathBuf::from(path);
    if !path.is_absolute()
        || path
            .components()
            .any(|component| matches!(component, Component::ParentDir))
    {
        return None;
    }
    Some(path)
}

fn android_wallet_database_key(
    env: &mut JNIEnv<'_>,
    input: &JByteArray<'_>,
) -> Option<MobileDatabaseKey> {
    if env.get_array_length(input).ok()? != 32 {
        return None;
    }
    let mut bytes = env.convert_byte_array(input).ok()?;
    let key = MobileDatabaseKey::from_slice(bytes.as_slice()).ok();
    bytes.fill(0);
    key
}

fn android_wallet_recovery_phrase(
    env: &mut JNIEnv<'_>,
    input: &JCharArray<'_>,
) -> Option<MobileRecoveryPhrase> {
    let length = usize::try_from(env.get_array_length(input).ok()?).ok()?;
    if length == 0 || length > MAX_MOBILE_RECOVERY_PHRASE_BYTES {
        return None;
    }
    let mut characters = vec![0_u16; length];
    if env
        .get_char_array_region(input, 0, characters.as_mut_slice())
        .is_err()
    {
        characters.fill(0);
        return None;
    }
    let phrase = String::from_utf16(characters.as_slice()).ok();
    characters.fill(0);
    let mut phrase = phrase?;
    if phrase.is_empty() || phrase.len() > MAX_MOBILE_RECOVERY_PHRASE_BYTES {
        wipe_string(&mut phrase);
        return None;
    }
    MobileRecoveryPhrase::new(phrase).ok()
}

fn android_wallet_rpc_authorization(
    env: &mut JNIEnv<'_>,
    input: &JCharArray<'_>,
) -> Option<SensitiveString> {
    let length = usize::try_from(env.get_array_length(input).ok()?).ok()?;
    if length == 0 || length > MAX_ANDROID_WALLET_RPC_AUTHORIZATION_CHARACTERS {
        return None;
    }
    let mut characters = vec![0_u16; length];
    let read = env.get_char_array_region(input, 0, characters.as_mut_slice());
    let zeros = vec![0_u16; length];
    let wiped = env.set_char_array_region(input, 0, zeros.as_slice());
    if read.is_err() || wiped.is_err() {
        characters.fill(0);
        return None;
    }
    let authorization = String::from_utf16(characters.as_slice()).ok();
    characters.fill(0);
    authorization.map(SensitiveString)
}

fn wallet_status_bundle(
    locked: bool,
    active_wallet: Option<&[u8; 16]>,
    enabled_modules_valid: bool,
    mainnet_settlement_enabled: bool,
) -> Option<Vec<u8>> {
    if !enabled_modules_valid || mainnet_settlement_enabled || locked == active_wallet.is_some() {
        return None;
    }
    let mut bundle = Vec::with_capacity(WALLET_STATUS_BUNDLE_BYTES);
    bundle.extend_from_slice(WALLET_STATUS_BUNDLE_MAGIC);
    bundle.push(WALLET_STATUS_BUNDLE_VERSION);
    let mut flags = u8::from(locked);
    if active_wallet.is_some() {
        flags |= 1 << 1;
    }
    bundle.push(flags);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(active_wallet.copied().unwrap_or([0; 16]).as_slice());
    (bundle.len() == WALLET_STATUS_BUNDLE_BYTES).then_some(bundle)
}

fn wallet_account_bundle(
    account_id: &[u8; 16],
    module_name: &str,
    label: &str,
    has_receive_display: bool,
) -> Option<Vec<u8>> {
    if module_name != "Handshake" || has_receive_display {
        return None;
    }
    let label = label.as_bytes();
    if label.is_empty() || label.len() > MAX_WALLET_ACCOUNT_LABEL_BYTES {
        return None;
    }
    let label_length = u16::try_from(label.len()).ok()?;
    let mut bundle = Vec::with_capacity(28 + label.len());
    bundle.extend_from_slice(WALLET_ACCOUNT_BUNDLE_MAGIC);
    bundle.push(WALLET_ACCOUNT_BUNDLE_VERSION);
    bundle.push(1);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(account_id);
    bundle.push(1);
    bundle.push(0);
    bundle.extend_from_slice(&label_length.to_be_bytes());
    bundle.extend_from_slice(label);
    Some(bundle)
}

fn wallet_read_bundle(json: &[u8]) -> Option<Vec<u8>> {
    if json.is_empty()
        || json.len() > MAX_WALLET_READ_JSON_BYTES
        || json.first() != Some(&b'{')
        || json.last() != Some(&b'}')
    {
        return None;
    }
    let json_length = u32::try_from(json.len()).ok()?;
    let mut bundle = Vec::with_capacity(WALLET_READ_BUNDLE_HEADER_BYTES + json.len());
    bundle.extend_from_slice(WALLET_READ_BUNDLE_MAGIC);
    bundle.push(WALLET_READ_BUNDLE_VERSION);
    bundle.push(WALLET_READ_BUNDLE_FLAGS);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(&json_length.to_be_bytes());
    bundle.extend_from_slice(json);
    (bundle.len() == WALLET_READ_BUNDLE_HEADER_BYTES + json.len()).then_some(bundle)
}

/// Saturating whole-milliseconds since `started`. Diagnostics only.
fn elapsed_millis(started: Instant) -> u64 {
    u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX)
}

/// Classifies a streaming failure into a stable, privacy-safe code.
///
/// The message is inspected but never logged: gateway errors can embed host,
/// path, and query material. Only the returned code reaches the log.
fn classify_streaming_failure(message: &str) -> &'static str {
    let lowered = message.to_ascii_lowercase();
    if lowered.contains("head receiver closed") {
        "head_receiver_closed"
    } else if lowered.contains("authority") {
        "authority_revoked"
    } else if lowered.contains("permission denied") {
        "permission_denied"
    } else if lowered.contains("broken pipe") || lowered.contains("os error 32") {
        "broken_pipe"
    } else if lowered.contains("connection reset") || lowered.contains("os error 104") {
        "connection_reset"
    } else if lowered.contains("timed out") || lowered.contains("timeout") {
        "timed_out"
    } else {
        "other"
    }
}

struct AndroidRequestMetricsObserver;

impl BrowserRequestMetricsObserver for AndroidRequestMetricsObserver {
    fn observe_request_metrics(&self, metrics: &BrowserRequestMetrics) {
        android_log_request_metrics(&format!(
            "request_id={} route={:?} host={} method={} active={} queued={} admission_wait_ms={} prepare_ms={} dns_timings_available={} hns_dns_ms={} icann_dns_ms={} live_proof_timings_available={} live_proof_selection_ms={} live_proof_connect_ms={} live_proof_handshake_ms={} live_proof_verify_store_ms={} live_proof_persistence_ms={} live_proof_total_ms={} live_proof_peers_started={} live_proof_peers_completed={} gateway_ms={} origin_timing_available={} origin_ms={} stream_timing_available={} stream_head_ms={} stream_drain_ms={} publish_ms={} total_ms={} status={} outcome={}",
            metrics.request_id,
            metrics.route,
            metrics.host,
            metrics.method,
            metrics.active_requests,
            metrics.queued_requests,
            metrics.admission_wait_ms,
            metrics.prepare_ms,
            metrics.dns_timings_available,
            metrics.hns_dns_ms,
            metrics.icann_dns_ms,
            metrics.live_proof_timings_available,
            metrics.live_proof_selection_ms,
            metrics.live_proof_connect_ms,
            metrics.live_proof_handshake_ms,
            metrics.live_proof_verify_store_ms,
            metrics.live_proof_persistence_ms,
            metrics.live_proof_total_ms,
            metrics.live_proof_peers_started,
            metrics.live_proof_peers_completed,
            metrics.gateway_ms,
            metrics.origin_timing_available,
            metrics.origin_ms,
            metrics.stream_timing_available,
            metrics.stream_head_ms,
            metrics.stream_drain_ms,
            metrics.publish_ms,
            metrics.total_ms,
            metrics
                .status_code
                .map(|status| status.to_string())
                .unwrap_or_else(|| "none".to_owned()),
            metrics.outcome,
        ));
    }

    fn observe_publication_rejection(&self, rejection: &PublicationRejection) {
        android_log_request_metrics(&format!(
            "publication_rejected sequence={} reason={} family={}",
            rejection.sequence,
            rejection.reason.code(),
            rejection.reason.family(),
        ));
    }
}

#[derive(Clone, Eq, PartialEq)]
struct AndroidProxyStatus {
    generation: u64,
    host: String,
    status_code: u16,
    likely_main_frame: bool,
    tls_policy: Option<BrowserProxyTlsPolicy>,
    resolver_policy: Option<BrowserProxyResolverPolicy>,
    security_path: Option<BrowserProxySecurityPath>,
    resolution_trace_json: Option<String>,
}

impl From<&BrowserProxyStatus> for AndroidProxyStatus {
    fn from(status: &BrowserProxyStatus) -> Self {
        Self {
            generation: status.generation(),
            host: status.host().to_owned(),
            status_code: status.status_code(),
            likely_main_frame: status.is_likely_main_frame(),
            tls_policy: status.tls_policy(),
            resolver_policy: status.resolver_policy(),
            security_path: status.security_path(),
            resolution_trace_json: status.resolution_trace_json().map(str::to_owned),
        }
    }
}

impl std::fmt::Debug for AndroidProxyStatus {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("AndroidProxyStatus")
            .field("generation", &self.generation)
            .field("host", &self.host)
            .field("status_code", &self.status_code)
            .field("likely_main_frame", &self.likely_main_frame)
            .field("tls_policy", &self.tls_policy)
            .field("resolver_policy", &self.resolver_policy)
            .field("security_path", &self.security_path)
            .field(
                "resolution_trace_bytes",
                &self.resolution_trace_json.as_ref().map(String::len),
            )
            .finish()
    }
}

#[derive(Clone, Eq, PartialEq)]
struct PendingAndroidProxyStatus {
    sequence: u64,
    status: AndroidProxyStatus,
}

struct AndroidProxyStatusMailboxState {
    active: bool,
    next_sequence: u64,
    retained_trace_bytes: usize,
    latest_by_host: HashMap<String, PendingAndroidProxyStatus>,
}

struct AndroidProxyStatusMailbox {
    state: Mutex<AndroidProxyStatusMailboxState>,
}

impl AndroidProxyStatusMailbox {
    fn new() -> Self {
        Self {
            state: Mutex::new(AndroidProxyStatusMailboxState {
                active: true,
                next_sequence: 0,
                retained_trace_bytes: 0,
                latest_by_host: HashMap::new(),
            }),
        }
    }

    fn deactivate(&self) {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        state.active = false;
        state.retained_trace_bytes = 0;
        state.latest_by_host.clear();
    }

    fn peek_matching(&self, generation: u64, host: &str) -> Option<PendingAndroidProxyStatus> {
        let state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !state.active {
            return None;
        }
        state
            .latest_by_host
            .get(host)
            .filter(|pending| pending.status.generation == generation)
            .cloned()
    }

    fn acknowledge_matching(&self, generation: u64, host: &str, sequence: u64) -> bool {
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !state.active
            || !state.latest_by_host.get(host).is_some_and(|pending| {
                pending.sequence == sequence && pending.status.generation == generation
            })
        {
            return false;
        }
        if let Some(removed) = state.latest_by_host.remove(host) {
            state.retained_trace_bytes = state
                .retained_trace_bytes
                .saturating_sub(proxy_status_trace_bytes(&removed.status));
        }
        true
    }

    fn discard_matching(&self, generation: u64, host: &str) -> bool {
        let pending = self.peek_matching(generation, host);
        pending.is_some_and(|pending| self.acknowledge_matching(generation, host, pending.sequence))
    }

    fn record_status(&self, mut status: AndroidProxyStatus) {
        if !status.likely_main_frame {
            return;
        }
        if proxy_status_trace_bytes(&status) > MAX_PROXY_STATUS_RETAINED_TRACE_BYTES {
            status.resolution_trace_json = None;
        }
        let host = status.host.clone();
        let trace_bytes = proxy_status_trace_bytes(&status);
        let mut state = self
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if !state.active {
            return;
        }
        if state.next_sequence == u64::MAX {
            state.next_sequence = 0;
            state.retained_trace_bytes = 0;
            state.latest_by_host.clear();
        }
        state.next_sequence += 1;
        let sequence = state.next_sequence;

        if let Some(previous) = state.latest_by_host.remove(&host) {
            state.retained_trace_bytes = state
                .retained_trace_bytes
                .saturating_sub(proxy_status_trace_bytes(&previous.status));
        }
        while state.latest_by_host.len() >= MAX_PROXY_STATUS_HOSTS
            || state.retained_trace_bytes.saturating_add(trace_bytes)
                > MAX_PROXY_STATUS_RETAINED_TRACE_BYTES
        {
            let Some(oldest_host) = state
                .latest_by_host
                .iter()
                .min_by_key(|(_, pending)| pending.sequence)
                .map(|(candidate, _)| candidate.clone())
            else {
                break;
            };
            if let Some(removed) = state.latest_by_host.remove(&oldest_host) {
                state.retained_trace_bytes = state
                    .retained_trace_bytes
                    .saturating_sub(proxy_status_trace_bytes(&removed.status));
            }
        }
        state.retained_trace_bytes = state.retained_trace_bytes.saturating_add(trace_bytes);
        state
            .latest_by_host
            .insert(host, PendingAndroidProxyStatus { sequence, status });
    }
}

impl BrowserProxyStatusObserver for AndroidProxyStatusMailbox {
    fn observe_status(&self, status: &BrowserProxyStatus) {
        self.record_status(AndroidProxyStatus::from(status));
    }
}

fn proxy_status_trace_bytes(status: &AndroidProxyStatus) -> usize {
    status.resolution_trace_json.as_ref().map_or(0, String::len)
}

fn runtime_error_message(error: RuntimeError) -> String {
    match error {
        RuntimeError::InvalidConfiguration(message)
        | RuntimeError::Operation(message)
        | RuntimeError::PublicationSuppressed(message) => message,
        error @ RuntimeError::Synchronization(_) => error.to_string(),
    }
}

// Diagnostic-only logging: the JNI boundary maps every failure to null, which
// makes device-side failures indistinguishable. Log the real error to logcat
// (tag hns-ffi) before discarding it. No-op formatting guarantees apply.
#[cfg(target_os = "android")]
fn android_log_error(message: &str) {
    use std::ffi::CString;
    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(
            prio: i32,
            tag: *const std::ffi::c_char,
            text: *const std::ffi::c_char,
        ) -> i32;
    }
    const ANDROID_LOG_ERROR: i32 = 6;
    let (Ok(tag), Ok(text)) = (CString::new("hns-ffi"), CString::new(message)) else {
        return;
    };
    // SAFETY: tag and text are valid NUL-terminated strings for the call.
    unsafe {
        __android_log_write(ANDROID_LOG_ERROR, tag.as_ptr(), text.as_ptr());
    }
}

#[cfg(target_os = "android")]
fn android_log_request_metrics(message: &str) {
    use std::ffi::CString;
    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(
            prio: i32,
            tag: *const std::ffi::c_char,
            text: *const std::ffi::c_char,
        ) -> i32;
    }
    const ANDROID_LOG_INFO: i32 = 4;
    let (Ok(tag), Ok(text)) = (CString::new("hns-request-metrics"), CString::new(message)) else {
        return;
    };
    // SAFETY: tag and text are valid NUL-terminated strings for the call.
    unsafe {
        __android_log_write(ANDROID_LOG_INFO, tag.as_ptr(), text.as_ptr());
    }
}

#[cfg(not(target_os = "android"))]
fn android_log_error(message: &str) {
    eprintln!("hns-ffi: {message}");
}

#[cfg(not(target_os = "android"))]
fn android_log_request_metrics(message: &str) {
    eprintln!("hns-request-metrics: {message}");
}

fn log_panic_payload(context: &str, payload: &(dyn std::any::Any + Send)) {
    let detail = payload
        .downcast_ref::<&str>()
        .map(|value| (*value).to_owned())
        .or_else(|| payload.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "non-string payload".to_owned());
    android_log_error(&format!("panic in {context}: {detail}"));
}

fn runtime_status_json(network: NetworkKind, result: Result<SyncStatus, RuntimeError>) -> String {
    result
        .unwrap_or_else(|error| NativeSyncStatus::error_for(network, runtime_error_message(error)))
        .to_json()
}

fn runtime_from_handle(handle: jlong) -> Option<BrowserRuntime> {
    if handle == 0 {
        return None;
    }
    let record = handle as usize as *const AndroidRuntimeRecord;
    // SAFETY: handles are created from Box<AndroidRuntimeRecord> below. Platform callers serialize
    // destroy against calls, and cloning only retains the Arc-backed runtime inner state.
    unsafe { record.as_ref().map(|record| record.runtime.clone()) }
}

fn proxy_registry() -> &'static Mutex<HashMap<jlong, Arc<AndroidProxyRecord>>> {
    PROXY_HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn next_proxy_handle() -> Option<jlong> {
    let handle = NEXT_PROXY_HANDLE
        .fetch_update(Ordering::AcqRel, Ordering::Acquire, |current| {
            (current <= i64::MAX as u64).then_some(current + 1)
        })
        .ok()?;
    jlong::try_from(handle).ok().filter(|handle| *handle != 0)
}

fn register_proxy(
    proxy: BrowserProxy,
    statuses: Arc<AndroidProxyStatusMailbox>,
) -> Option<(jlong, Arc<AndroidProxyRecord>)> {
    let handle = next_proxy_handle()?;
    let record = Arc::new(AndroidProxyRecord { proxy, statuses });
    let mut registry = proxy_registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if registry.len() >= MAX_ANDROID_PROXY_HANDLES {
        return None;
    }
    match registry.entry(handle) {
        std::collections::hash_map::Entry::Vacant(entry) => {
            entry.insert(Arc::clone(&record));
        }
        std::collections::hash_map::Entry::Occupied(_) => return None,
    }
    Some((handle, record))
}

fn proxy_from_handle(handle: jlong) -> Option<Arc<AndroidProxyRecord>> {
    if handle <= 0 {
        return None;
    }
    proxy_registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .get(&handle)
        .cloned()
}

fn remove_proxy(handle: jlong) -> Option<Arc<AndroidProxyRecord>> {
    if handle <= 0 {
        return None;
    }
    proxy_registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .remove(&handle)
}

fn destroy_proxy(handle: jlong) -> bool {
    let Some(record) = proxy_from_handle(handle) else {
        return false;
    };
    record.statuses.deactivate();
    record.proxy.request_stop();
    let Some(record) = remove_proxy(handle) else {
        return false;
    };
    record.proxy.stop();
    true
}

fn destroy_all_proxies() {
    let proxies: Vec<_> = proxy_registry()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .drain()
        .map(|(_, proxy)| proxy)
        .collect();
    for record in &proxies {
        record.statuses.deactivate();
        record.proxy.request_stop();
    }
    for record in proxies {
        record.proxy.stop();
    }
}

fn proxy_endpoint_bundle(handle: jlong, proxy: &BrowserProxy) -> Option<Vec<u8>> {
    let mut bundle = Vec::with_capacity(128);
    bundle.extend_from_slice(PROXY_ENDPOINT_BUNDLE_MAGIC);
    bundle.push(PROXY_ENDPOINT_BUNDLE_VERSION);
    bundle.extend_from_slice(&handle.to_be_bytes());
    bundle.extend_from_slice(&proxy.port().to_be_bytes());
    bundle.extend_from_slice(&proxy.generation().to_be_bytes());
    for value in [
        proxy.session_id(),
        proxy.authorization_realm(),
        proxy.authorization_username(),
        proxy.authorization_password(),
    ] {
        let length = u16::try_from(value.len()).ok()?;
        bundle.extend_from_slice(&length.to_be_bytes());
        bundle.extend_from_slice(value.as_bytes());
    }
    Some(bundle)
}

fn canonical_proxy_status_host(host: &str) -> Option<String> {
    let host = host.trim().trim_end_matches('.');
    if host.is_empty() || host.len() > 253 || !host.is_ascii() {
        return None;
    }
    for label in host.split('.') {
        if label.is_empty() || label.len() > 63 {
            return None;
        }
        let bytes = label.as_bytes();
        if !bytes.first().is_some_and(u8::is_ascii_alphanumeric)
            || !bytes.last().is_some_and(u8::is_ascii_alphanumeric)
            || !bytes
                .iter()
                .all(|byte| byte.is_ascii_alphanumeric() || *byte == b'-')
        {
            return None;
        }
    }
    Some(host.to_ascii_lowercase())
}

fn proxy_tls_policy_code(policy: Option<BrowserProxyTlsPolicy>) -> Option<u8> {
    match policy {
        None => Some(0),
        Some(BrowserProxyTlsPolicy::Dane) => Some(1),
        Some(BrowserProxyTlsPolicy::WebPkiFallback) => Some(2),
        Some(_) => None,
    }
}

fn proxy_resolver_policy_code(policy: Option<BrowserProxyResolverPolicy>) -> Option<u8> {
    match policy {
        None => Some(0),
        Some(BrowserProxyResolverPolicy::HnsDohCompatibility) => Some(1),
        Some(_) => None,
    }
}

fn proxy_security_path_code(path: Option<BrowserProxySecurityPath>) -> Option<u8> {
    match path {
        None => Some(0),
        Some(BrowserProxySecurityPath::DaneAuthoritativeDoh) => Some(1),
        Some(BrowserProxySecurityPath::DaneAuthoritativeDns53) => Some(2),
        Some(BrowserProxySecurityPath::DaneThirdPartyDoh) => Some(3),
        Some(BrowserProxySecurityPath::StatelessDane) => Some(4),
        Some(BrowserProxySecurityPath::DaneIcannDoh) => Some(5),
        Some(BrowserProxySecurityPath::HnsAuthoritativeDoh) => Some(6),
        Some(BrowserProxySecurityPath::HnsAuthoritativeDns53) => Some(7),
        Some(BrowserProxySecurityPath::HnsThirdPartyDoh) => Some(8),
        Some(BrowserProxySecurityPath::DaneP2pDnsRelay) => Some(9),
        Some(BrowserProxySecurityPath::HnsP2pDnsRelay) => Some(10),
        Some(_) => None,
    }
}

fn proxy_status_bundle(pending: &PendingAndroidProxyStatus) -> Option<Vec<u8>> {
    let status = &pending.status;
    if pending.sequence == 0 || status.generation == 0 || !(100..=599).contains(&status.status_code)
    {
        return None;
    }
    let host = canonical_proxy_status_host(&status.host)?;
    if host != status.host {
        return None;
    }
    let host_length = u16::try_from(host.len()).ok()?;
    let fixed_length = PROXY_STATUS_BUNDLE_MAGIC.len()
        + 1
        + std::mem::size_of::<u64>()
        + std::mem::size_of::<u64>()
        + std::mem::size_of::<u16>()
        + 4
        + std::mem::size_of::<u16>()
        + host.len()
        + std::mem::size_of::<u32>();
    let trace = status
        .resolution_trace_json
        .as_deref()
        .filter(|trace| fixed_length.saturating_add(trace.len()) <= MAX_PROXY_STATUS_BUNDLE_BYTES);
    let trace_length = u32::try_from(trace.map_or(0, str::len)).ok()?;

    let mut bundle = Vec::with_capacity(fixed_length + trace.map_or(0, str::len));
    bundle.extend_from_slice(PROXY_STATUS_BUNDLE_MAGIC);
    bundle.push(PROXY_STATUS_BUNDLE_VERSION);
    bundle.extend_from_slice(&status.generation.to_be_bytes());
    bundle.extend_from_slice(&pending.sequence.to_be_bytes());
    bundle.extend_from_slice(&status.status_code.to_be_bytes());
    bundle.push(u8::from(status.likely_main_frame));
    bundle.push(proxy_tls_policy_code(status.tls_policy)?);
    bundle.push(proxy_resolver_policy_code(status.resolver_policy)?);
    bundle.push(proxy_security_path_code(status.security_path)?);
    bundle.extend_from_slice(&host_length.to_be_bytes());
    bundle.extend_from_slice(host.as_bytes());
    bundle.extend_from_slice(&trace_length.to_be_bytes());
    if let Some(trace) = trace {
        bundle.extend_from_slice(trace.as_bytes());
    }
    (bundle.len() <= MAX_PROXY_STATUS_BUNDLE_BYTES).then_some(bundle)
}

struct JniRuntimeGatewayHttpRequest<'local> {
    method: JString<'local>,
    scheme: JString<'local>,
    host: JString<'local>,
    port: jint,
    path_and_query: JString<'local>,
    header_text: JString<'local>,
    body: JByteArray<'local>,
}

struct RuntimeGatewayPolicyInput<'local> {
    strict_hns_mode: jboolean,
    doh_resolver_url: JString<'local>,
    stateless_dane_certificates: jboolean,
    experimental_p2p_dns_relay: jboolean,
    legacy_hns_doh_compatibility: jboolean,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeVersion(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    env.new_string(core_version())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

fn android_browser_namespace_code(input: &str) -> jint {
    if input.len() > MAX_BROWSER_NAMESPACE_INPUT_BYTES {
        return ANDROID_BROWSER_NAMESPACE_INVALID;
    }
    match classify_browser_host(input) {
        BrowserHostClass::Hns => ANDROID_BROWSER_NAMESPACE_HNS,
        BrowserHostClass::Icann => ANDROID_BROWSER_NAMESPACE_ICANN,
        BrowserHostClass::NativeGateway => ANDROID_BROWSER_NAMESPACE_NATIVE_GATEWAY,
        BrowserHostClass::Search => ANDROID_BROWSER_NAMESPACE_INVALID,
    }
}

fn android_proxy_hns_scope(input: &str) -> Option<Option<String>> {
    // The Java value is an opaque whole-browser lifecycle identity. Complete
    // DNS names are classified by the retained Rust resolver plan per origin;
    // no shell-provided suffix scope is authoritative.
    canonical_browser_host(input).map(|_| None)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeClassifyBrowserHost(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    host: JString<'_>,
) -> jint {
    catch_unwind(AssertUnwindSafe(|| {
        env.get_string(&host)
            .ok()
            .map(|host| android_browser_namespace_code(&host.to_string_lossy()))
            .unwrap_or(ANDROID_BROWSER_NAMESPACE_INVALID)
    }))
    .unwrap_or(ANDROID_BROWSER_NAMESPACE_INVALID)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeBrowserWebSocketScopePolicyScript(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    catch_unwind(AssertUnwindSafe(|| {
        env.new_string(browser_websocket_scope_policy_script())
            .map(|value| value.into_raw())
            .unwrap_or(std::ptr::null_mut())
    }))
    .unwrap_or(std::ptr::null_mut())
}

fn jni_runtime_gateway_request(
    env: &mut JNIEnv<'_>,
    input: JniRuntimeGatewayHttpRequest<'_>,
) -> Option<RawGatewayHttpRequest> {
    let method = env
        .get_string(&input.method)
        .ok()?
        .to_string_lossy()
        .into_owned();
    let scheme = env
        .get_string(&input.scheme)
        .ok()?
        .to_string_lossy()
        .into_owned();
    let host = env
        .get_string(&input.host)
        .ok()?
        .to_string_lossy()
        .into_owned();
    let path_and_query = env
        .get_string(&input.path_and_query)
        .ok()?
        .to_string_lossy()
        .into_owned();
    let header_text = env
        .get_string(&input.header_text)
        .ok()?
        .to_string_lossy()
        .into_owned();
    let body = env.convert_byte_array(&input.body).ok()?;
    Some(RawGatewayHttpRequest {
        method,
        scheme,
        host,
        port: input.port,
        path_and_query,
        header_text,
        body,
    })
}

fn runtime_gateway_policy(
    env: &mut JNIEnv<'_>,
    input: RuntimeGatewayPolicyInput<'_>,
) -> Option<RuntimePolicy> {
    let doh_resolver_url = env
        .get_string(&input.doh_resolver_url)
        .ok()?
        .to_string_lossy()
        .trim()
        .to_owned();
    runtime_gateway_policy_from_values(
        input.strict_hns_mode,
        doh_resolver_url,
        input.stateless_dane_certificates,
        input.experimental_p2p_dns_relay,
        input.legacy_hns_doh_compatibility,
    )
}

fn runtime_gateway_policy_from_values(
    _strict_hns_mode: jboolean,
    doh_resolver_url: String,
    stateless_dane_certificates: jboolean,
    experimental_p2p_dns_relay: jboolean,
    _legacy_hns_doh_compatibility: jboolean,
) -> Option<RuntimePolicy> {
    Some(RuntimePolicy {
        resolution_mode: ResolutionMode::Strict,
        hns_doh_resolver: normalize_hns_doh_recovery_url(&doh_resolver_url).ok()?,
        experimental_p2p_dns_relay: experimental_p2p_dns_relay != 0,
        legacy_hns_doh_compatibility: false,
        stateless_dane_certificates: stateless_dane_certificates != 0,
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeCreate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    data_dir: JString<'_>,
    network: JString<'_>,
) -> jlong {
    let (Ok(data_dir), Ok(network)) = (env.get_string(&data_dir), env.get_string(&network)) else {
        android_log_error("runtime create failed: unreadable data dir or network argument");
        return 0;
    };
    let Ok(network) = parse_network_kind(&network.to_string_lossy()) else {
        android_log_error("runtime create failed: unknown network");
        return 0;
    };
    let runtime = match BrowserRuntime::open(RuntimeConfiguration::new(
        data_dir.to_string_lossy().into_owned(),
        network,
    )) {
        Ok(runtime) => runtime,
        Err(error) => {
            android_log_error(&format!(
                "runtime create failed: {}",
                runtime_error_message(error),
            ));
            return 0;
        }
    };
    if let Err(error) =
        runtime.set_request_metrics_observer(Arc::new(AndroidRequestMetricsObserver))
    {
        android_log_error(&format!(
            "runtime create failed to install request metrics observer: {}",
            runtime_error_message(error),
        ));
    }
    Box::into_raw(Box::new(AndroidRuntimeRecord { runtime })) as usize as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeDestroy(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    let runtime = handle as usize as *mut AndroidRuntimeRecord;
    // SAFETY: the pointer was returned by nativeRuntimeCreate and the platform lifecycle lock
    // guarantees exactly one destroy after all calls have released their cloned runtime.
    unsafe { drop(Box::from_raw(runtime)) };
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeSyncOnce(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let status = runtime_from_handle(handle)
        .map(|runtime| runtime_status_json(runtime.network(), runtime.sync_once()))
        .unwrap_or_else(|| NativeSyncStatus::error("invalid runtime handle".to_owned()).to_json());
    env.new_string(status)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeSyncStatus(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let status = runtime_from_handle(handle)
        .map(|runtime| runtime_status_json(runtime.network(), runtime.sync_status()))
        .unwrap_or_else(|| NativeSyncStatus::error("invalid runtime handle".to_owned()).to_json());
    env.new_string(status)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeChainTipToken(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let token = runtime_from_handle(handle)
        .and_then(|runtime| runtime.chain_tip_token().ok())
        .unwrap_or_default();
    env.new_string(token)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeAddStaticRelayPeer(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    endpoint: JString<'_>,
) -> jstring {
    let status = match (runtime_from_handle(handle), env.get_string(&endpoint)) {
        (Some(runtime), Ok(endpoint)) => runtime_status_json(
            runtime.network(),
            runtime.add_static_relay_peer(endpoint.to_string_lossy().as_ref()),
        ),
        _ => NativeSyncStatus::error("invalid static relay peer input".to_owned()).to_json(),
    };
    env.new_string(status)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeClearResolverCache(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let status = runtime_from_handle(handle)
        .map(|runtime| runtime_status_json(runtime.network(), runtime.clear_resolver_cache()))
        .unwrap_or_else(|| NativeSyncStatus::error("invalid runtime handle".to_owned()).to_json());
    env.new_string(status)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeInstallHeaderSnapshot(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    snapshot_path: JString<'_>,
) -> jstring {
    let status = match (runtime_from_handle(handle), env.get_string(&snapshot_path)) {
        (Some(runtime), Ok(snapshot_path)) => runtime_status_json(
            runtime.network(),
            runtime.install_header_snapshot(snapshot_path.to_string_lossy().as_ref()),
        ),
        _ => NativeSyncStatus::error("invalid runtime snapshot input".to_owned()).to_json(),
    };
    env.new_string(status)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeResetHeadersFromPeers(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jstring {
    let status = runtime_from_handle(handle)
        .map(|runtime| runtime_status_json(runtime.network(), runtime.reset_headers_from_peers()))
        .unwrap_or_else(|| NativeSyncStatus::error("invalid runtime handle".to_owned()).to_json());
    env.new_string(status)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeHnsProofDetails(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    host: JString<'_>,
) -> jstring {
    let details = match (runtime_from_handle(handle), env.get_string(&host)) {
        (Some(runtime), Ok(host)) => {
            let host = host.to_string_lossy();
            runtime.proof_details(&host).unwrap_or_else(|error| {
                hns_proof_details_error_json(&host, &runtime_error_message(error))
            })
        }
        _ => hns_proof_details_error_json("", "invalid runtime proof detail input"),
    };
    env.new_string(details)
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeStartProxy(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    strict_hns_mode: jboolean,
    doh_resolver_url: JString<'_>,
    stateless_dane_certificates: jboolean,
    experimental_p2p_dns_relay: jboolean,
    legacy_hns_doh_compatibility: jboolean,
    scope_root: JString<'_>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let result = runtime_from_handle(handle)
            .zip(runtime_gateway_policy(
                &mut env,
                RuntimeGatewayPolicyInput {
                    strict_hns_mode,
                    doh_resolver_url,
                    stateless_dane_certificates,
                    experimental_p2p_dns_relay,
                    legacy_hns_doh_compatibility,
                },
            ))
            .zip(env.get_string(&scope_root).ok())
            .and_then(|((runtime, policy), scope_root)| {
                let scope_root = scope_root.to_string_lossy();
                let hns_scope = android_proxy_hns_scope(&scope_root)?;
                let statuses = Arc::new(AndroidProxyStatusMailbox::new());
                runtime
                    .start_whole_browser_proxy_with_policy_and_observer(
                        hns_scope.as_deref(),
                        policy,
                        statuses.clone(),
                    )
                    .ok()
                    .map(|proxy| (proxy, statuses))
            })
            .and_then(|(proxy, statuses)| register_proxy(proxy, statuses));
        let Some((proxy_handle, record)) = result else {
            return std::ptr::null_mut();
        };
        let Some(bundle) = proxy_endpoint_bundle(proxy_handle, &record.proxy) else {
            let _destroyed = destroy_proxy(proxy_handle);
            return std::ptr::null_mut();
        };
        match env.byte_array_from_slice(&bundle) {
            Ok(array) => array.into_raw(),
            Err(_) => {
                let _destroyed = destroy_proxy(proxy_handle);
                std::ptr::null_mut()
            }
        }
    }))
    .unwrap_or(std::ptr::null_mut())
}

#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeGatewayHttpResponse(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    strict_hns_mode: jboolean,
    doh_resolver_url: JString<'_>,
    stateless_dane_certificates: jboolean,
    experimental_p2p_dns_relay: jboolean,
    legacy_hns_doh_compatibility: jboolean,
    method: JString<'_>,
    scheme: JString<'_>,
    host: JString<'_>,
    port: jint,
    path_and_query: JString<'_>,
    header_text: JString<'_>,
    body: JByteArray<'_>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let response = runtime_from_handle(handle)
            .zip(runtime_gateway_policy(
                &mut env,
                RuntimeGatewayPolicyInput {
                    strict_hns_mode,
                    doh_resolver_url,
                    stateless_dane_certificates,
                    experimental_p2p_dns_relay,
                    legacy_hns_doh_compatibility,
                },
            ))
            .zip(jni_runtime_gateway_request(
                &mut env,
                JniRuntimeGatewayHttpRequest {
                    method,
                    scheme,
                    host,
                    port,
                    path_and_query,
                    header_text,
                    body,
                },
            ))
            .and_then(|((runtime, policy), request)| {
                match runtime.raw_gateway_request(request, policy) {
                    Ok(response) => Some(GatewayHttpResponse::into_bytes(response)),
                    Err(error) => {
                        android_log_error(&format!(
                            "raw_gateway_request failed: {}",
                            runtime_error_message(error),
                        ));
                        None
                    }
                }
            });
        match response.and_then(|bytes| env.byte_array_from_slice(&bytes).ok()) {
            Some(array) => array.into_raw(),
            None => std::ptr::null_mut(),
        }
    }))
    .unwrap_or_else(|panic| {
        log_panic_payload("nativeRuntimeGatewayHttpResponse", &*panic);
        std::ptr::null_mut()
    })
}

#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeGatewayHttpResponseBodyToFile(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    strict_hns_mode: jboolean,
    doh_resolver_url: JString<'_>,
    stateless_dane_certificates: jboolean,
    experimental_p2p_dns_relay: jboolean,
    legacy_hns_doh_compatibility: jboolean,
    method: JString<'_>,
    scheme: JString<'_>,
    host: JString<'_>,
    port: jint,
    path_and_query: JString<'_>,
    header_text: JString<'_>,
    body: JByteArray<'_>,
    body_path: JString<'_>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let body_path = env
            .get_string(&body_path)
            .ok()
            .map(|value| value.to_string_lossy().into_owned());
        let response = runtime_from_handle(handle)
            .zip(runtime_gateway_policy(
                &mut env,
                RuntimeGatewayPolicyInput {
                    strict_hns_mode,
                    doh_resolver_url,
                    stateless_dane_certificates,
                    experimental_p2p_dns_relay,
                    legacy_hns_doh_compatibility,
                },
            ))
            .zip(jni_runtime_gateway_request(
                &mut env,
                JniRuntimeGatewayHttpRequest {
                    method,
                    scheme,
                    host,
                    port,
                    path_and_query,
                    header_text,
                    body,
                },
            ))
            .zip(body_path)
            .and_then(|(((runtime, policy), request), body_path)| {
                match runtime.raw_gateway_request_body_to_file(
                    request,
                    policy,
                    Path::new(&body_path),
                ) {
                    Ok(head) => Some(head),
                    Err(error) => {
                        android_log_error(&format!(
                            "raw_gateway_request_body_to_file failed: {}",
                            runtime_error_message(error),
                        ));
                        None
                    }
                }
            });
        match response.and_then(|bytes| env.byte_array_from_slice(&bytes).ok()) {
            Some(array) => array.into_raw(),
            None => std::ptr::null_mut(),
        }
    }))
    .unwrap_or_else(|panic| {
        log_panic_payload("nativeRuntimeGatewayHttpResponseBodyToFile", &*panic);
        std::ptr::null_mut()
    })
}

#[allow(clippy::too_many_arguments)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeRuntimeGatewayHttpResponseStreaming(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    strict_hns_mode: jboolean,
    doh_resolver_url: JString<'_>,
    stateless_dane_certificates: jboolean,
    experimental_p2p_dns_relay: jboolean,
    legacy_hns_doh_compatibility: jboolean,
    method: JString<'_>,
    scheme: JString<'_>,
    host: JString<'_>,
    port: jint,
    path_and_query: JString<'_>,
    header_text: JString<'_>,
    body: JByteArray<'_>,
    write_fd: jint,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let writer = (write_fd >= 0).then(|| {
            // Ownership was transferred by ParcelFileDescriptor.detachFd().
            unsafe { std::fs::File::from_raw_fd(write_fd as RawFd) }
        });
        let inputs = runtime_from_handle(handle)
            .zip(runtime_gateway_policy(
                &mut env,
                RuntimeGatewayPolicyInput {
                    strict_hns_mode,
                    doh_resolver_url,
                    stateless_dane_certificates,
                    experimental_p2p_dns_relay,
                    legacy_hns_doh_compatibility,
                },
            ))
            .zip(jni_runtime_gateway_request(
                &mut env,
                JniRuntimeGatewayHttpRequest {
                    method,
                    scheme,
                    host,
                    port,
                    path_and_query,
                    header_text,
                    body,
                },
            ))
            .zip(writer);
        let Some((((runtime, policy), request), mut writer)) = inputs else {
            return std::ptr::null_mut();
        };
        // The permit is held for the whole streaming call below, which includes
        // writing every body byte into the WebView pipe. Time spent waiting for
        // one is therefore invisible to the runtime's own request metrics, which
        // only begin once the permit is held.
        let capacity_wait_started = Instant::now();
        let Some(permit) =
            STREAMING_GATEWAY_REQUESTS.acquire_timeout(STREAMING_GATEWAY_CAPACITY_WAIT)
        else {
            android_log_request_metrics(&format!(
                "stream_capacity outcome=timed_out capacity_wait_ms={} limit={}",
                elapsed_millis(capacity_wait_started),
                MAX_STREAMING_GATEWAY_REQUESTS,
            ));
            android_log_error("streaming gateway capacity wait timed out");
            return std::ptr::null_mut();
        };
        let capacity_wait_ms = elapsed_millis(capacity_wait_started);
        android_log_request_metrics(&format!(
            "stream_capacity outcome=acquired capacity_wait_ms={capacity_wait_ms} limit={MAX_STREAMING_GATEWAY_REQUESTS}",
        ));
        let (head_tx, head_rx) = std::sync::mpsc::sync_channel::<Vec<u8>>(1);
        std::thread::spawn(move || {
            let _permit = permit;
            let mut head_sent = false;
            let stream_started = Instant::now();
            let result = runtime.raw_gateway_request_body_streaming(
                request,
                policy,
                &mut writer,
                &mut |head| {
                    head_tx.send(head).map_err(|_| {
                        RuntimeError::Operation("stream head receiver closed".into())
                    })?;
                    head_sent = true;
                    Ok(())
                },
            );
            if let Err(error) = result {
                // Classify only. The underlying message can carry host and path
                // material, so it is never logged.
                let failure = classify_streaming_failure(&runtime_error_message(error));
                android_log_error(&format!(
                    "raw_gateway_request_body_streaming failed head_sent={head_sent} failure={failure} capacity_wait_ms={capacity_wait_ms} stream_ms={}",
                    elapsed_millis(stream_started),
                ));
            }
        });
        let head_wait_started = Instant::now();
        match head_rx.recv_timeout(Duration::from_secs(30)) {
            Ok(head) => match env.byte_array_from_slice(&head) {
                Ok(array) => array.into_raw(),
                Err(_) => {
                    android_log_error(&format!(
                        "streaming gateway head conversion failed head_wait_ms={} capacity_wait_ms={capacity_wait_ms}",
                        elapsed_millis(head_wait_started),
                    ));
                    std::ptr::null_mut()
                }
            },
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                // The worker deliberately remains alive here and still owns its
                // permit. Record the stacked wait without changing that behavior.
                android_log_request_metrics(&format!(
                    "stream_head outcome=timed_out head_wait_ms={} capacity_wait_ms={capacity_wait_ms}",
                    elapsed_millis(head_wait_started),
                ));
                std::ptr::null_mut()
            }
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                android_log_request_metrics(&format!(
                    "stream_head outcome=disconnected head_wait_ms={} capacity_wait_ms={capacity_wait_ms}",
                    elapsed_millis(head_wait_started),
                ));
                std::ptr::null_mut()
            }
        }
    }))
    .unwrap_or_else(|panic| {
        log_panic_payload("nativeRuntimeGatewayHttpResponseStreaming", &*panic);
        std::ptr::null_mut()
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeProxyRequestStop(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    session_id: JString<'_>,
    generation: jlong,
) -> jboolean {
    let Some(record) = proxy_from_handle(handle) else {
        return 0;
    };
    let (Ok(session_id), Ok(generation)) = (env.get_string(&session_id), u64::try_from(generation))
    else {
        return 0;
    };
    if !record
        .proxy
        .matches_instance(&session_id.to_string_lossy(), generation)
    {
        return 0;
    }
    record.statuses.deactivate();
    record.proxy.request_stop();
    1
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeProxyDestroy(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    let _destroyed = destroy_proxy(handle);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeProxyDestroyAll(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
) {
    destroy_all_proxies();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeProxyTakeMainFrameStatus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    session_id: JString<'_>,
    generation: jlong,
    host: JString<'_>,
) -> jbyteArray {
    let Some(record) = proxy_from_handle(handle) else {
        return std::ptr::null_mut();
    };
    let (Ok(session_id), Ok(generation), Ok(host)) = (
        env.get_string(&session_id),
        u64::try_from(generation),
        env.get_string(&host),
    ) else {
        return std::ptr::null_mut();
    };
    if !record
        .proxy
        .matches_instance(&session_id.to_string_lossy(), generation)
    {
        return std::ptr::null_mut();
    }
    let Some(host) = canonical_proxy_status_host(&host.to_string_lossy()) else {
        return std::ptr::null_mut();
    };
    let Some(pending) = record.statuses.peek_matching(generation, &host) else {
        return std::ptr::null_mut();
    };
    let Some(bundle) = proxy_status_bundle(&pending) else {
        return std::ptr::null_mut();
    };
    let Ok(array) = env.byte_array_from_slice(&bundle) else {
        return std::ptr::null_mut();
    };
    if !record
        .statuses
        .acknowledge_matching(generation, &host, pending.sequence)
    {
        return std::ptr::null_mut();
    }
    array.into_raw()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeProxyDiscardMainFrameStatus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    session_id: JString<'_>,
    generation: jlong,
    host: JString<'_>,
) -> jboolean {
    let Some(record) = proxy_from_handle(handle) else {
        return 0;
    };
    let (Ok(session_id), Ok(generation), Ok(host)) = (
        env.get_string(&session_id),
        u64::try_from(generation),
        env.get_string(&host),
    ) else {
        return 0;
    };
    if !record
        .proxy
        .matches_instance(&session_id.to_string_lossy(), generation)
    {
        return 0;
    }
    let Some(host) = canonical_proxy_status_host(&host.to_string_lossy()) else {
        return 0;
    };
    if record.statuses.discard_matching(generation, &host) {
        1
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeProxyMatchesLocalCertificate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    session_id: JString<'_>,
    generation: jlong,
    host: JString<'_>,
    certificate_der: JByteArray<'_>,
) -> jboolean {
    let Some(record) = proxy_from_handle(handle) else {
        return 0;
    };
    let (Ok(session_id), Ok(generation)) = (env.get_string(&session_id), u64::try_from(generation))
    else {
        return 0;
    };
    if !record
        .proxy
        .matches_instance(&session_id.to_string_lossy(), generation)
    {
        return 0;
    }
    let Ok(length) = env.get_array_length(&certificate_der) else {
        return 0;
    };
    let Ok(length) = usize::try_from(length) else {
        return 0;
    };
    if length == 0 || length > MAX_LOCAL_CERTIFICATE_DER_BYTES {
        return 0;
    }
    let (Ok(host), Ok(certificate_der)) = (
        env.get_string(&host),
        env.convert_byte_array(&certificate_der),
    ) else {
        return 0;
    };
    let host = host.to_string_lossy();
    if host.len() > 253 {
        return 0;
    }
    if record
        .proxy
        .matches_local_certificate(&host, &certificate_der)
    {
        1
    } else {
        0
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeCreate(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    database_path: JString<'_>,
    database_key: JByteArray<'_>,
    network: jint,
    birthday_height: jlong,
) -> jlong {
    let Some(reservation) = WalletHandleReservation::new() else {
        android_log_error("wallet create failed: native wallet handle limit reached");
        return 0;
    };
    match catch_unwind(AssertUnwindSafe(|| {
        let Some(path) = android_wallet_path(&mut env, &database_path) else {
            android_log_error("wallet create failed: invalid database path");
            return 0;
        };
        let Some(key) = android_wallet_database_key(&mut env, &database_key) else {
            android_log_error("wallet create failed: invalid database key");
            return 0;
        };
        let Some(network) = android_wallet_network(network) else {
            android_log_error("wallet create failed: invalid network");
            return 0;
        };
        let Ok(birthday_height) = u64::try_from(birthday_height) else {
            android_log_error("wallet create failed: invalid birthday height");
            return 0;
        };
        let creation = match MobileWalletController::create(
            path,
            &key,
            MobilePlatform::Android,
            HnsBootstrapPolicy::new(network, birthday_height),
        ) {
            Ok(creation) => creation,
            Err(error) => {
                android_log_error(&format!("wallet create failed: {error}"));
                return 0;
            }
        };
        let (controller, recovery) = creation.into_parts();
        let recovery = recovery.expose_for_dedicated_display();
        let Some(recovery) = SensitiveUtf16::from_recovery_phrase(recovery) else {
            android_log_error("wallet create failed: invalid generated recovery display");
            return 0;
        };
        reservation
            .finish(AndroidWalletRecord::new(controller, Some(recovery)))
            .unwrap_or_else(|| {
                android_log_error("wallet create failed: native wallet registration failed");
                0
            })
    })) {
        Ok(handle) => handle,
        Err(payload) => {
            log_panic_payload("native wallet create", payload.as_ref());
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeRestore(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    database_path: JString<'_>,
    database_key: JByteArray<'_>,
    network: jint,
    birthday_height: jlong,
    recovery_phrase: JCharArray<'_>,
) -> jlong {
    let Some(reservation) = WalletHandleReservation::new() else {
        android_log_error("wallet restore failed: native wallet handle limit reached");
        return 0;
    };
    match catch_unwind(AssertUnwindSafe(|| {
        let Some(path) = android_wallet_path(&mut env, &database_path) else {
            android_log_error("wallet restore failed: invalid database path");
            return 0;
        };
        let Some(key) = android_wallet_database_key(&mut env, &database_key) else {
            android_log_error("wallet restore failed: invalid database key");
            return 0;
        };
        let Some(network) = android_wallet_network(network) else {
            android_log_error("wallet restore failed: invalid network");
            return 0;
        };
        let Ok(birthday_height) = u64::try_from(birthday_height) else {
            android_log_error("wallet restore failed: invalid birthday height");
            return 0;
        };
        let Some(recovery_phrase) = android_wallet_recovery_phrase(&mut env, &recovery_phrase)
        else {
            android_log_error("wallet restore failed: invalid recovery phrase input");
            return 0;
        };
        let controller = match MobileWalletController::restore(
            path,
            &key,
            MobilePlatform::Android,
            HnsBootstrapPolicy::new(network, birthday_height),
            recovery_phrase,
        ) {
            Ok(controller) => controller,
            Err(error) => {
                android_log_error(&format!("wallet restore failed: {error}"));
                return 0;
            }
        };
        reservation
            .finish(AndroidWalletRecord::new(controller, None))
            .unwrap_or_else(|| {
                android_log_error("wallet restore failed: native wallet registration failed");
                0
            })
    })) {
        Ok(handle) => handle,
        Err(payload) => {
            log_panic_payload("native wallet restore", payload.as_ref());
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeOpen(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    database_path: JString<'_>,
    database_key: JByteArray<'_>,
) -> jlong {
    let Some(reservation) = WalletHandleReservation::new() else {
        android_log_error("wallet open failed: native wallet handle limit reached");
        return 0;
    };
    match catch_unwind(AssertUnwindSafe(|| {
        let Some(path) = android_wallet_path(&mut env, &database_path) else {
            android_log_error("wallet open failed: invalid database path");
            return 0;
        };
        let Some(key) = android_wallet_database_key(&mut env, &database_key) else {
            android_log_error("wallet open failed: invalid database key");
            return 0;
        };
        let controller = match MobileWalletController::open(path, &key, MobilePlatform::Android) {
            Ok(controller) => controller,
            Err(error) => {
                android_log_error(&format!("wallet open failed: {error}"));
                return 0;
            }
        };
        reservation
            .finish(AndroidWalletRecord::new(controller, None))
            .unwrap_or_else(|| {
                android_log_error("wallet open failed: native wallet registration failed");
                0
            })
    })) {
        Ok(handle) => handle,
        Err(payload) => {
            log_panic_payload("native wallet open", payload.as_ref());
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeStatus(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let record = wallet_from_handle(handle)?;
        let mut controller = record.controller_if_active()?;
        let bundle = controller.status_bundle()?;
        env.byte_array_from_slice(bundle.as_slice())
            .ok()
            .map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeAccounts(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let record = wallet_from_handle(handle)?;
        let mut controller = record.controller_if_active()?;
        let bundle = controller.account_bundle()?;
        env.byte_array_from_slice(bundle.as_slice())
            .ok()
            .map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeConfigureHnsReads(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    loopback_port: jint,
    authorization: JCharArray<'_>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(mut authorization) = android_wallet_rpc_authorization(&mut env, &authorization)
        else {
            return false;
        };
        let Ok(loopback_port) = u16::try_from(loopback_port) else {
            return false;
        };
        if loopback_port == 0 {
            return false;
        }
        let Some(record) = wallet_from_handle(handle) else {
            return false;
        };
        if !record.active.load(Ordering::Acquire) || !record.hns_reads_installable {
            return false;
        }
        let Some(pending_recovery) = record.pending_recovery_if_active() else {
            return false;
        };
        if pending_recovery.is_some() {
            return false;
        }
        drop(pending_recovery);
        let endpoint = SocketAddr::from((Ipv4Addr::LOCALHOST, loopback_port));
        let config =
            match HnsNodeRpcConfig::new(endpoint, authorization.take()).and_then(|config| {
                config.with_timeouts(
                    ANDROID_WALLET_RPC_CONNECT_TIMEOUT,
                    ANDROID_WALLET_RPC_READ_TIMEOUT,
                    ANDROID_WALLET_RPC_WRITE_TIMEOUT,
                )
            }) {
                Ok(config) => config,
                Err(error) => {
                    android_log_error(&format!("wallet HNS read configuration rejected: {error}"));
                    return false;
                }
            };
        let backend = match HnsNodeRpcBackend::new(config) {
            Ok(backend) => backend,
            Err(error) => {
                android_log_error(&format!("wallet HNS read backend rejected: {error}"));
                return false;
            }
        };
        let Some(mut controller) = record.controller_if_active() else {
            return false;
        };
        controller.install_hns_reads(backend)
    }))
    .unwrap_or(false)
    .into()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeHasHnsReads(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(record) = wallet_from_handle(handle) else {
            return false;
        };
        let Some(controller) = record.controller_if_active() else {
            return false;
        };
        controller.has_hns_reads()
    }))
    .unwrap_or(false)
    .into()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeSynchronizeHnsReads(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let record = wallet_from_handle(handle)?;
        let mut controller = record.controller_if_active()?;
        let mut bundle = controller.synchronize_hns_reads()?;
        let array = env.byte_array_from_slice(bundle.as_slice()).ok();
        bundle.fill(0);
        array.map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeUnlock(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    database_key: JByteArray<'_>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(record) = wallet_from_handle(handle) else {
            return false;
        };
        let Some(key) = android_wallet_database_key(&mut env, &database_key) else {
            return false;
        };
        let Some(mut controller) = record.controller_if_active() else {
            return false;
        };
        controller.unlock(&key)
    }))
    .unwrap_or(false)
    .into()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeLock(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(record) = wallet_from_handle(handle) else {
            return false;
        };
        let Some(mut controller) = record.controller_if_active() else {
            return false;
        };
        controller.lock()
    }))
    .unwrap_or(false)
    .into()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeTakeRecovery(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jcharArray {
    catch_unwind(AssertUnwindSafe(|| {
        let record = wallet_from_handle(handle)?;
        let mut pending = record.pending_recovery_if_active()?;
        let recovery = pending.as_ref()?;
        let length = i32::try_from(recovery.as_slice().len()).ok()?;
        let array = env.new_char_array(length).ok()?;
        env.set_char_array_region(&array, 0, recovery.as_slice())
            .ok()?;
        pending.take();
        Some(array.into_raw())
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeDestroy(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(record) = wallet_registry().remove(handle) else {
            return false;
        };
        record.deactivate();
        if let Ok(mut pending) = record.pending_recovery.lock() {
            pending.take();
        }
        if let Ok(mut controller) = record.controller.lock() {
            let _ = controller.lock();
        }
        true
    }))
    .unwrap_or(false)
    .into()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_net_NativeBridge_nativeDiagnostics(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    env.new_string(diagnostics_json())
        .map(|value| value.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Barrier;
    use std::thread;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn deactivated_wallet_gate_rejects_a_call_queued_on_its_state_mutex() {
        let active = Arc::new(AtomicBool::new(true));
        let state = Arc::new(Mutex::new(()));
        let held = state.lock().expect("hold state mutex");
        let ready = Arc::new(Barrier::new(2));
        let call_active = Arc::clone(&active);
        let call_state = Arc::clone(&state);
        let call_ready = Arc::clone(&ready);

        let queued_call = thread::spawn(move || {
            call_ready.wait();
            lock_if_active(call_active.as_ref(), call_state.as_ref()).is_some()
        });
        ready.wait();
        active.store(false, Ordering::Release);
        drop(held);

        assert!(!queued_call.join().expect("queued call completes"));
    }

    #[test]
    fn wallet_registry_is_bounded_monotonic_and_revocable() {
        let registry = BoundedMonotonicRegistry::new(2);
        let first = registry.reserve().expect("first reservation");
        let second = registry.reserve().expect("second reservation");
        assert!(second > first);
        assert!(registry.reserve().is_none());

        assert!(registry.finish(first, Arc::new(11_u8)));
        registry.cancel(second);
        let third = registry.reserve().expect("capacity after cancellation");
        assert!(third > second);
        assert!(registry.finish(third, Arc::new(12_u8)));
        assert_eq!(registry.get(first).as_deref(), Some(&11));
        assert_eq!(registry.remove(first).as_deref(), Some(&11));
        assert!(registry.get(first).is_none());

        let fourth = registry.reserve().expect("capacity after revocation");
        assert!(fourth > third);
    }

    #[test]
    fn wallet_control_bundles_reject_value_or_non_hns_state() {
        let wallet_id = [7_u8; 16];
        let status = wallet_status_bundle(false, Some(&wallet_id), true, false)
            .expect("unlocked non-value status");
        assert_eq!(status.len(), WALLET_STATUS_BUNDLE_BYTES);
        assert_eq!(&status[..4], WALLET_STATUS_BUNDLE_MAGIC);
        assert_eq!(status[4], WALLET_STATUS_BUNDLE_VERSION);
        assert_eq!(status[5], 0b10);
        assert_eq!(&status[8..], &wallet_id);
        assert!(wallet_status_bundle(true, None, true, false).is_some());
        assert!(wallet_status_bundle(true, Some(&wallet_id), true, false).is_none());
        assert!(wallet_status_bundle(false, None, false, false).is_none());
        assert!(wallet_status_bundle(false, None, true, false).is_none());
        assert!(wallet_status_bundle(false, None, true, true).is_none());

        let account_id = [9_u8; 16];
        let account = wallet_account_bundle(&account_id, "Handshake", "Handshake", false)
            .expect("one HNS account");
        assert_eq!(&account[..4], WALLET_ACCOUNT_BUNDLE_MAGIC);
        assert_eq!(account[4], WALLET_ACCOUNT_BUNDLE_VERSION);
        assert_eq!(account[5], 1);
        assert_eq!(&account[8..24], &account_id);
        assert!(wallet_account_bundle(&account_id, "Bitcoin", "Bitcoin", false).is_none());
        assert!(wallet_account_bundle(&account_id, "Handshake", "Handshake", true).is_none());
    }

    #[test]
    fn wallet_read_bundle_is_versioned_exact_and_bounded() {
        let json = br#"{"balance":{}}"#;
        let bundle = wallet_read_bundle(json).expect("bounded wallet read bundle");
        assert_eq!(&bundle[..4], WALLET_READ_BUNDLE_MAGIC);
        assert_eq!(bundle[4], WALLET_READ_BUNDLE_VERSION);
        assert_eq!(bundle[5], WALLET_READ_BUNDLE_FLAGS);
        assert_eq!(&bundle[6..8], &[0, 0]);
        assert_eq!(
            u32::from_be_bytes(bundle[8..12].try_into().expect("length field")),
            json.len() as u32
        );
        assert_eq!(&bundle[WALLET_READ_BUNDLE_HEADER_BYTES..], json);

        assert!(wallet_read_bundle(b"").is_none());
        assert!(wallet_read_bundle(b"[]").is_none());
        assert!(wallet_read_bundle(b"{broken").is_none());
        assert!(wallet_read_bundle(&vec![b' '; MAX_WALLET_READ_JSON_BYTES + 1]).is_none());
    }

    #[test]
    fn wallet_network_codes_exclude_simnet_and_unknown_values() {
        assert_eq!(
            android_wallet_network(ANDROID_WALLET_NETWORK_MAINNET),
            Some(HnsNetwork::Mainnet)
        );
        assert_eq!(
            android_wallet_network(ANDROID_WALLET_NETWORK_TESTNET),
            Some(HnsNetwork::Testnet)
        );
        assert_eq!(
            android_wallet_network(ANDROID_WALLET_NETWORK_REGTEST),
            Some(HnsNetwork::Regtest)
        );
        assert_eq!(android_wallet_network(0), None);
        assert_eq!(android_wallet_network(4), None);
    }

    #[test]
    fn streaming_failure_classes_separate_authority_pipe_and_receiver_faults() {
        assert_eq!(
            classify_streaming_failure("runtime authority changed during response streaming"),
            "authority_revoked"
        );
        assert_eq!(
            classify_streaming_failure("stream authority changed during body delivery"),
            "authority_revoked"
        );
        assert_eq!(
            classify_streaming_failure("stream head receiver closed"),
            "head_receiver_closed"
        );
        assert_eq!(
            classify_streaming_failure("stream gateway: Permission denied (os error 13)"),
            "permission_denied"
        );
        assert_eq!(
            classify_streaming_failure("stream gateway: Broken pipe (os error 32)"),
            "broken_pipe"
        );
        assert_eq!(
            classify_streaming_failure("stream gateway: Connection reset by peer"),
            "connection_reset"
        );
        assert_eq!(
            classify_streaming_failure("stream gateway: operation timed out"),
            "timed_out"
        );
        assert_eq!(classify_streaming_failure("stream gateway: eof"), "other");
    }

    #[test]
    fn streaming_failure_class_never_echoes_the_underlying_message() {
        // Gateway errors can embed host and path material; only the code is
        // ever logged, so no classification may return borrowed input.
        let message = "stream gateway: failed to reach app.example path /secret?token=abc";
        let class = classify_streaming_failure(message);
        assert!(!message.contains(class));
        assert_eq!(class, "other");
    }

    #[test]
    fn streaming_gateway_limiter_fails_fast_when_exhausted() {
        let limiter = StreamingGatewayLimiter::new(MAX_STREAMING_GATEWAY_REQUESTS);
        let mut permits = Vec::new();
        for _ in 0..MAX_STREAMING_GATEWAY_REQUESTS {
            permits.push(limiter.try_acquire().expect("permit below the limit"));
        }

        assert!(limiter.try_acquire().is_none());
        permits.pop();
        assert!(limiter.try_acquire().is_some());
    }

    #[test]
    fn streaming_gateway_limiter_queues_until_capacity_is_released() {
        let limiter = StreamingGatewayLimiter::new(MAX_STREAMING_GATEWAY_REQUESTS);
        let mut permits = (0..MAX_STREAMING_GATEWAY_REQUESTS)
            .map(|_| limiter.try_acquire().expect("permit below the limit"))
            .collect::<Vec<_>>();

        std::thread::scope(|scope| {
            let (result_tx, result_rx) = std::sync::mpsc::sync_channel(1);
            let waiting_limiter = &limiter;
            scope.spawn(move || {
                let permit = waiting_limiter.acquire_timeout(Duration::from_secs(1));
                result_tx.send(permit.is_some()).expect("result receiver");
            });

            assert!(result_rx.recv_timeout(Duration::from_millis(20)).is_err());
            permits.pop();
            assert!(
                result_rx
                    .recv_timeout(Duration::from_secs(1))
                    .expect("released capacity wakes the queued request")
            );
        });
    }

    #[test]
    fn gateway_policy_preserves_only_explicit_valid_recovery_and_relay_controls() {
        let policy = runtime_gateway_policy_from_values(
            0,
            "https://Resolver.Example.NET:443/dns-query".to_owned(),
            0,
            1,
            1,
        )
        .expect("valid recovery endpoint");

        assert_eq!(policy.resolution_mode, ResolutionMode::Strict);
        assert_eq!(
            policy.hns_doh_resolver.as_deref(),
            Some("https://resolver.example.net/dns-query")
        );
        assert!(policy.experimental_p2p_dns_relay);
        assert!(!policy.legacy_hns_doh_compatibility);
        assert!(!policy.stateless_dane_certificates);
        assert!(
            runtime_gateway_policy_from_values(
                1,
                "http://resolver.example.net/dns-query".to_owned(),
                0,
                0,
                0,
            )
            .is_none()
        );
    }

    #[test]
    fn browser_namespace_jni_routes_every_dns_name_to_the_dual_root_gateway() {
        assert_eq!(
            android_browser_namespace_code("welcome"),
            ANDROID_BROWSER_NAMESPACE_NATIVE_GATEWAY
        );
        assert_eq!(
            android_browser_namespace_code("sub.welcome"),
            ANDROID_BROWSER_NAMESPACE_NATIVE_GATEWAY
        );
        assert_eq!(
            android_browser_namespace_code("TLSA.EXAMPLE.COM."),
            ANDROID_BROWSER_NAMESPACE_NATIVE_GATEWAY
        );
        for host in ["example.com", "home.arpa", "printer.local"] {
            assert_eq!(
                android_browser_namespace_code(host),
                ANDROID_BROWSER_NAMESPACE_NATIVE_GATEWAY,
                "{host}"
            );
        }
        for host in ["127.0.0.1", "::1"] {
            assert_eq!(
                android_browser_namespace_code(host),
                ANDROID_BROWSER_NAMESPACE_ICANN,
                "{host}"
            );
        }
        for host in ["", "two words", "https://"] {
            assert_eq!(
                android_browser_namespace_code(host),
                ANDROID_BROWSER_NAMESPACE_INVALID,
                "{host}"
            );
        }
        assert_eq!(
            android_browser_namespace_code(&"a".repeat(MAX_BROWSER_NAMESPACE_INPUT_BYTES + 1)),
            ANDROID_BROWSER_NAMESPACE_INVALID
        );
    }

    #[test]
    fn proxy_scope_is_only_a_validated_whole_browser_lifecycle_identity() {
        assert_eq!(android_proxy_hns_scope("Sub.Welcome."), Some(None));
        assert_eq!(android_proxy_hns_scope("example.com"), Some(None));
        assert_eq!(android_proxy_hns_scope("two words"), None);
    }

    #[test]
    fn websocket_policy_jni_payload_is_the_shared_runtime_script() {
        let script = browser_websocket_scope_policy_script();
        assert!(script.contains("window.__hnsRustNamespacePolicyVersion = 2"));
        assert!(!script.contains("requiresHnsResolution"));
        assert!(!script.contains("icannTlds"));
    }

    #[test]
    fn runtime_handle_call_clone_outlives_platform_handle() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let data_dir = std::env::temp_dir().join(format!(
            "hns-dane-browser-android-runtime-handle-{}-{unique}",
            std::process::id()
        ));
        let runtime =
            BrowserRuntime::open(RuntimeConfiguration::new(&data_dir, NetworkKind::Regtest))
                .unwrap();
        let handle = Box::into_raw(Box::new(AndroidRuntimeRecord { runtime })) as usize as jlong;

        let call_runtime = runtime_from_handle(handle).unwrap();
        // SAFETY: this test owns the unique Box pointer and destroys it exactly once.
        unsafe { drop(Box::from_raw(handle as usize as *mut AndroidRuntimeRecord)) };

        assert_eq!(
            call_runtime.sync_status().unwrap().network,
            NetworkKind::Regtest
        );
        let _ = std::fs::remove_dir_all(data_dir);
    }

    #[test]
    fn proxy_registry_uses_revocable_non_pointer_handles() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let data_dir = std::env::temp_dir().join(format!(
            "hns-dane-browser-android-proxy-handle-{}-{unique}",
            std::process::id()
        ));
        let runtime =
            BrowserRuntime::open(RuntimeConfiguration::new(&data_dir, NetworkKind::Regtest))
                .unwrap();
        let proxy = runtime.start_proxy("welcome").unwrap();
        let statuses = Arc::new(AndroidProxyStatusMailbox::new());
        let (handle, record) = register_proxy(proxy, statuses).unwrap();

        assert!(handle > 0);
        assert!(proxy_from_handle(handle).is_some());
        record.proxy.request_stop();
        assert!(
            !record
                .proxy
                .matches_local_certificate("welcome", b"certificate")
        );
        assert!(destroy_proxy(handle));
        assert!(proxy_from_handle(handle).is_none());
        assert!(!destroy_proxy(handle));
        let _ = std::fs::remove_dir_all(data_dir);
    }

    #[test]
    fn proxy_endpoint_bundle_is_versioned_bounded_and_complete() {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let data_dir = std::env::temp_dir().join(format!(
            "hns-dane-browser-android-proxy-bundle-{}-{unique}",
            std::process::id()
        ));
        let runtime =
            BrowserRuntime::open(RuntimeConfiguration::new(&data_dir, NetworkKind::Regtest))
                .unwrap();
        let proxy = runtime.start_proxy("welcome").unwrap();
        let statuses = Arc::new(AndroidProxyStatusMailbox::new());
        let (handle, record) = register_proxy(proxy, statuses).unwrap();

        let bundle = proxy_endpoint_bundle(handle, &record.proxy).unwrap();
        assert_eq!(&bundle[..4], PROXY_ENDPOINT_BUNDLE_MAGIC);
        assert_eq!(bundle[4], PROXY_ENDPOINT_BUNDLE_VERSION);
        assert_eq!(
            jlong::from_be_bytes(bundle[5..13].try_into().unwrap()),
            handle
        );
        assert_eq!(
            u16::from_be_bytes(bundle[13..15].try_into().unwrap()),
            record.proxy.port()
        );
        assert_eq!(
            u64::from_be_bytes(bundle[15..23].try_into().unwrap()),
            record.proxy.generation()
        );
        for value in [
            record.proxy.session_id(),
            record.proxy.authorization_realm(),
            record.proxy.authorization_username(),
            record.proxy.authorization_password(),
        ] {
            assert!(
                bundle
                    .windows(value.len())
                    .any(|window| window == value.as_bytes())
            );
        }
        assert!(bundle.len() < 1024);

        assert!(destroy_proxy(handle));
        let _ = std::fs::remove_dir_all(data_dir);
    }

    fn test_proxy_status(
        generation: u64,
        host: &str,
        likely_main_frame: bool,
    ) -> AndroidProxyStatus {
        AndroidProxyStatus {
            generation,
            host: host.to_owned(),
            status_code: 200,
            likely_main_frame,
            tls_policy: Some(BrowserProxyTlsPolicy::Dane),
            resolver_policy: Some(BrowserProxyResolverPolicy::HnsDohCompatibility),
            security_path: Some(BrowserProxySecurityPath::DaneAuthoritativeDoh),
            resolution_trace_json: Some(r#"{"mode":"strict"}"#.to_owned()),
        }
    }

    #[test]
    fn proxy_status_mailbox_is_main_frame_bounded_exact_and_revocable() {
        let mailbox = AndroidProxyStatusMailbox::new();
        mailbox.record_status(test_proxy_status(7, "welcome", false));
        assert!(mailbox.peek_matching(7, "welcome").is_none());

        mailbox.record_status(test_proxy_status(7, "welcome", true));
        assert!(mailbox.peek_matching(8, "welcome").is_none());
        assert!(mailbox.peek_matching(7, "other").is_none());
        let pending = mailbox.peek_matching(7, "welcome").unwrap();
        assert_eq!(pending.status, test_proxy_status(7, "welcome", true));
        assert!(!mailbox.acknowledge_matching(7, "welcome", pending.sequence + 1));
        assert!(mailbox.acknowledge_matching(7, "welcome", pending.sequence));
        assert!(mailbox.peek_matching(7, "welcome").is_none());

        mailbox.record_status(test_proxy_status(7, "welcome", true));
        let superseded = mailbox.peek_matching(7, "welcome").unwrap();
        let mut newer = test_proxy_status(7, "welcome", true);
        newer.status_code = 204;
        mailbox.record_status(newer);
        assert!(!mailbox.acknowledge_matching(7, "welcome", superseded.sequence));
        assert_eq!(
            mailbox
                .peek_matching(7, "welcome")
                .unwrap()
                .status
                .status_code,
            204
        );
        assert!(mailbox.discard_matching(7, "welcome"));
        assert!(!mailbox.discard_matching(7, "welcome"));
        mailbox.record_status(test_proxy_status(7, "welcome", true));
        mailbox.deactivate();
        assert!(mailbox.peek_matching(7, "welcome").is_none());
        mailbox.record_status(test_proxy_status(7, "welcome", true));
        assert!(mailbox.peek_matching(7, "welcome").is_none());
    }

    #[test]
    fn proxy_status_mailbox_is_per_host_ordered_and_aggregate_bounded() {
        let mailbox = AndroidProxyStatusMailbox::new();
        for index in 0..(MAX_PROXY_STATUS_HOSTS + 2) {
            let mut status = test_proxy_status(3, &format!("host{index}"), true);
            status.resolution_trace_json = Some("x".repeat(12 * 1024));
            mailbox.record_status(status);
        }

        let state = mailbox
            .state
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        assert!(state.latest_by_host.len() <= MAX_PROXY_STATUS_HOSTS);
        assert!(state.retained_trace_bytes <= MAX_PROXY_STATUS_RETAINED_TRACE_BYTES);
        assert!(!state.latest_by_host.contains_key("host0"));
        assert!(state.latest_by_host.contains_key("host9"));
    }

    #[test]
    fn proxy_status_bundle_is_versioned_typed_redacted_and_bounded() {
        let status = test_proxy_status(9, "welcome", true);
        let diagnostic = format!("{status:?}");
        assert!(!diagnostic.contains("strict"));
        assert!(diagnostic.contains("resolution_trace_bytes"));
        let pending = PendingAndroidProxyStatus {
            sequence: 12,
            status: status.clone(),
        };
        let bundle = proxy_status_bundle(&pending).unwrap();

        assert_eq!(&bundle[..4], PROXY_STATUS_BUNDLE_MAGIC);
        assert_eq!(bundle[4], PROXY_STATUS_BUNDLE_VERSION);
        assert_eq!(u64::from_be_bytes(bundle[5..13].try_into().unwrap()), 9);
        assert_eq!(u64::from_be_bytes(bundle[13..21].try_into().unwrap()), 12);
        assert_eq!(u16::from_be_bytes(bundle[21..23].try_into().unwrap()), 200);
        assert_eq!(&bundle[23..27], &[1, 1, 1, 1]);
        let host_length = u16::from_be_bytes(bundle[27..29].try_into().unwrap()) as usize;
        assert_eq!(&bundle[29..29 + host_length], b"welcome");
        let trace_length_offset = 29 + host_length;
        let trace_length = u32::from_be_bytes(
            bundle[trace_length_offset..trace_length_offset + 4]
                .try_into()
                .unwrap(),
        ) as usize;
        assert_eq!(
            &bundle[trace_length_offset + 4..],
            status.resolution_trace_json.as_deref().unwrap().as_bytes()
        );
        assert_eq!(
            trace_length,
            status.resolution_trace_json.as_deref().unwrap().len()
        );
        assert!(bundle.len() <= MAX_PROXY_STATUS_BUNDLE_BYTES);

        for (path, code) in [
            (BrowserProxySecurityPath::DaneAuthoritativeDoh, 1),
            (BrowserProxySecurityPath::DaneAuthoritativeDns53, 2),
            (BrowserProxySecurityPath::DaneThirdPartyDoh, 3),
            (BrowserProxySecurityPath::StatelessDane, 4),
            (BrowserProxySecurityPath::DaneIcannDoh, 5),
            (BrowserProxySecurityPath::HnsAuthoritativeDoh, 6),
            (BrowserProxySecurityPath::HnsAuthoritativeDns53, 7),
            (BrowserProxySecurityPath::HnsThirdPartyDoh, 8),
            (BrowserProxySecurityPath::DaneP2pDnsRelay, 9),
            (BrowserProxySecurityPath::HnsP2pDnsRelay, 10),
        ] {
            let mapped = proxy_status_bundle(&PendingAndroidProxyStatus {
                sequence: 12,
                status: AndroidProxyStatus {
                    security_path: Some(path),
                    ..status.clone()
                },
            })
            .unwrap();
            assert_eq!(mapped[26], code);
        }

        let oversized = PendingAndroidProxyStatus {
            sequence: 12,
            status: AndroidProxyStatus {
                resolution_trace_json: Some("x".repeat(MAX_PROXY_STATUS_BUNDLE_BYTES)),
                ..status
            },
        };
        let bounded = proxy_status_bundle(&oversized).unwrap();
        let trace_length_offset = 29 + "welcome".len();
        assert_eq!(
            u32::from_be_bytes(
                bounded[trace_length_offset..trace_length_offset + 4]
                    .try_into()
                    .unwrap()
            ),
            0
        );
        assert!(bounded.len() <= MAX_PROXY_STATUS_BUNDLE_BYTES);
    }

    #[test]
    fn proxy_status_host_canonicalization_rejects_unsafe_names() {
        assert_eq!(
            canonical_proxy_status_host(" Welcome. ").as_deref(),
            Some("welcome")
        );
        for invalid in ["", ".", "-welcome", "welcome-", "wel_come", "a..b"] {
            assert!(canonical_proxy_status_host(invalid).is_none(), "{invalid}");
        }
    }
}
