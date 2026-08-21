//! Android JNI adapter for the platform-neutral browser runtime.

#![cfg_attr(
    not(test),
    deny(clippy::expect_used, clippy::panic, clippy::unwrap_used)
)]

use hns_header_consensus::{HEADER_SIZE, Header, Network};
use hns_mobile_platform_runtime::*;
use hns_wallet_ffi::ServiceErrorCode;
use hns_wallet_mobile::{
    EmbeddedHnsBackend, HnsBackend, HnsBootstrapPolicy, HnsClock, HnsDirectPeerConfig,
    HnsDirectPeerCoordinator, HnsLightFloor, HnsNetwork, HnsNodeRpcBackend, HnsNodeRpcConfig,
    HnsReadSystemClock, MAX_MOBILE_RECOVERY_PHRASE_BYTES, MAX_MOBILE_SHAKEDEX_POLICY_BYTES,
    MobileDatabaseKey, MobileHnsReadController, MobileHnsValueController, MobileHnsValueIntent,
    MobilePlatform, MobileRecoveryPhrase, MobileShakedexQuery, MobileWalletController,
    MobileWalletError,
};
use hns_wallet_types::BaseUnits;
use jni::JNIEnv;
use jni::objects::{JByteArray, JCharArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jcharArray, jint, jlong, jstring};
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{BufReader, Read};
use std::net::{Ipv4Addr, SocketAddr};
use std::os::fd::{FromRawFd, RawFd};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::{Component, Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, MutexGuard, OnceLock};
use std::time::Duration;

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
const WALLET_READ_BUNDLE_VERSION: u8 = 2;
const WALLET_READ_BUNDLE_FLAGS: u8 = 1;
const WALLET_READ_BUNDLE_HEADER_BYTES: usize = 12;
const MAX_WALLET_READ_JSON_BYTES: usize = 4 * 1024 * 1024;
const MAX_ANDROID_WALLET_NAME_BYTES: usize = 63;
const MAX_ANDROID_WALLET_RECIPIENT_BYTES: usize = 512;
const MAX_ANDROID_WALLET_BASE_UNITS_BYTES: usize = 39;
const MAX_ANDROID_WALLET_VALUE_INTENT_JSON_BYTES: usize = 8 * 1024;
const MAX_ANDROID_WALLET_SHAKEDEX_QUERY_JSON_BYTES: usize = 4 * 1024;
const ANDROID_HNS_LIGHT_FLOOR_BYTES: usize = 36;
const ANDROID_MAINNET_GENESIS_BOOTSTRAP_MAGIC: &[u8; 11] = b"HNSHDRSNAP1";
const ANDROID_MAINNET_GENESIS_BOOTSTRAP_HEIGHT: u32 = 300_000;
const ANDROID_MAINNET_GENESIS_BOOTSTRAP_BYTES: u64 = 70_800_287;
const ANDROID_MAINNET_GENESIS_BOOTSTRAP_HASH: [u8; 32] = [
    0, 0, 0, 0, 0, 0, 0, 12, 52, 107, 32, 60, 77, 216, 102, 166, 136, 26, 130, 156, 157, 202, 16,
    190, 31, 89, 123, 179, 142, 19, 43, 169,
];
/// HSD serves at most 2,000 headers per response. The bundled mainnet
/// checkpoint leaves substantially less than this budget on ordinary first
/// install, while the cap prevents a JNI read operation from becoming an
/// unbounded network task.
const DIRECT_HNS_MAX_HEADER_ROUNDS_PER_SYNC: usize = 32;
/// Each direct scan call verifies at most 2,000 wallet-filtered blocks. Keep
/// first-run catch-up self-contained without allowing an unbounded JNI call.
const DIRECT_HNS_MAX_SCAN_CHUNKS_PER_SYNC: usize = 32;
const DIRECT_HNS_SCAN_BLOCKS_PER_CHUNK: u32 = 2_000;
const ANDROID_WALLET_ACTION_TOKEN_BYTES: usize = 64;
const WALLET_NAME_IMPORT_BUNDLE_MAGIC: &[u8; 4] = b"HNWI";
const WALLET_NAME_IMPORT_BUNDLE_VERSION: u8 = 1;
const WALLET_NAME_IMPORT_BUNDLE_FLAGS: u8 = 0;
const WALLET_NAME_IMPORT_BUNDLE_HEADER_BYTES: usize = 12;
const MAX_WALLET_NAME_IMPORT_JSON_BYTES: usize = 4 * 1024;
const WALLET_VALUE_APPROVAL_BUNDLE_MAGIC: &[u8; 4] = b"HNVP";
const WALLET_VALUE_APPROVAL_BUNDLE_VERSION: u8 = 1;
const WALLET_VALUE_APPROVAL_BUNDLE_FLAGS: u8 = 0;
const WALLET_VALUE_APPROVAL_BUNDLE_HEADER_BYTES: usize = 12;
const MAX_WALLET_VALUE_APPROVAL_JSON_BYTES: usize = 16 * 1024;
const WALLET_VALUE_RESULT_BUNDLE_MAGIC: &[u8; 4] = b"HNVX";
const WALLET_VALUE_RESULT_BUNDLE_VERSION: u8 = 1;
const WALLET_VALUE_RESULT_BUNDLE_FLAGS: u8 = 0;
const WALLET_VALUE_RESULT_BUNDLE_HEADER_BYTES: usize = 12;
const MAX_WALLET_VALUE_RESULT_JSON_BYTES: usize = 256 * 1024;
const WALLET_SHAKEDEX_QUERY_BUNDLE_MAGIC: &[u8; 4] = b"HNVQ";
const WALLET_SHAKEDEX_QUERY_BUNDLE_VERSION: u8 = 1;
const WALLET_SHAKEDEX_QUERY_BUNDLE_FLAGS: u8 = 0;
const WALLET_SHAKEDEX_QUERY_BUNDLE_HEADER_BYTES: usize = 12;
const MAX_WALLET_SHAKEDEX_QUERY_RESULT_JSON_BYTES: usize = 256 * 1024;
static WALLET_HANDLES: OnceLock<BoundedMonotonicRegistry<AndroidWalletRecord>> = OnceLock::new();
const MAX_STREAMING_GATEWAY_REQUESTS: usize = 8;
static STREAMING_GATEWAY_REQUESTS: StreamingGatewayLimiter =
    StreamingGatewayLimiter::new(MAX_STREAMING_GATEWAY_REQUESTS);

struct StreamingGatewayLimiter {
    active: Mutex<usize>,
    limit: usize,
}

impl StreamingGatewayLimiter {
    const fn new(limit: usize) -> Self {
        Self {
            active: Mutex::new(0),
            limit,
        }
    }

    fn try_acquire(&self) -> Option<StreamingGatewayPermit<'_>> {
        let mut active = self.active.lock().ok()?;
        if *active >= self.limit {
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
    Value(MobileHnsValueController<HnsNodeRpcBackend>),
    /// The installed wallet's self-contained HNS path. Header agreement,
    /// filtered-block discovery, fee observations, and transaction broadcast
    /// all use ordinary HNS peers through the same encrypted wallet store.
    DirectValue {
        coordinator: HnsDirectPeerCoordinator,
        controller: MobileHnsValueController<EmbeddedHnsBackend>,
    },
    Failed,
}

impl AndroidWalletController {
    fn status_bundle(&mut self) -> Option<Vec<u8>> {
        let (status, hns_reads_enabled, hns_value_enabled, shakedex_enabled) = match self {
            Self::Lifecycle(controller) => (controller.status().ok()?, false, false, false),
            Self::Reads(controller) => (controller.status().ok()?, true, false, false),
            Self::Value(controller) => (controller.status().ok()?, true, true, true),
            Self::DirectValue { controller, .. } => (controller.status().ok()?, true, true, false),
            Self::Failed => return None,
        };
        let active_wallet = status
            .active_wallet
            .as_ref()
            .map(|wallet| wallet.as_bytes());
        let enabled_modules_valid = if hns_reads_enabled {
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
            hns_reads_enabled,
            hns_value_enabled,
            shakedex_enabled,
            status.mainnet_settlement_enabled,
        )
    }

    fn account_bundle(&mut self) -> Option<Vec<u8>> {
        let mut accounts = match self {
            Self::Lifecycle(controller) => controller.accounts().ok()?,
            Self::Reads(controller) => controller.accounts().ok()?,
            Self::Value(controller) => controller.accounts().ok()?,
            Self::DirectValue { controller, .. } => controller.accounts().ok()?,
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
            Self::Value(controller) => controller.unlock(key).is_ok(),
            Self::DirectValue { controller, .. } => controller.unlock(key).is_ok(),
            Self::Failed => false,
        }
    }

    fn lock(&mut self) -> bool {
        match self {
            Self::Lifecycle(controller) => controller.lock().is_ok(),
            Self::Reads(controller) => controller.lock().is_ok(),
            Self::Value(controller) => controller.lock().is_ok(),
            Self::DirectValue { controller, .. } => controller.lock().is_ok(),
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

    fn install_hns_value(
        &mut self,
        database_key: &MobileDatabaseKey,
        backend: HnsNodeRpcBackend,
        shakedex_policy_json: &[u8],
    ) -> bool {
        if !matches!(self, Self::Lifecycle(_)) {
            return false;
        }
        let lifecycle = match std::mem::replace(self, Self::Failed) {
            Self::Lifecycle(controller) => controller,
            _ => return false,
        };
        match lifecycle.into_hns_value_with_shakedex_policy(
            database_key,
            backend,
            shakedex_policy_json,
        ) {
            Ok(controller) => {
                *self = Self::Value(controller);
                true
            }
            Err(error) => {
                android_log_error(&format!(
                    "wallet HNS value controller installation failed closed: {error}"
                ));
                false
            }
        }
    }

    /// Replace the lifecycle controller with the wallet-owned direct HNS
    /// composition. No loopback endpoint, token, index service, relay, or
    /// caller-supplied peer is accepted on this Android boundary.
    fn install_direct_hns_value(
        &mut self,
        database_key: &MobileDatabaseKey,
        rollback_floor: HnsLightFloor,
        bootstrap_snapshot_path: Option<&Path>,
    ) -> bool {
        if !matches!(self, Self::Lifecycle(_)) {
            return false;
        }
        let Self::Lifecycle(lifecycle) = self else {
            return false;
        };
        let requires_genesis_bootstrap = lifecycle.account_config().network == HnsNetwork::Mainnet
            && lifecycle.account_config().birthday_height
                == u64::from(ANDROID_MAINNET_GENESIS_BOOTSTRAP_HEIGHT);
        if requires_genesis_bootstrap && bootstrap_snapshot_path.is_none() {
            android_log_error(
                "wallet-owned direct HNS bootstrap asset was unavailable for a checkpoint-born wallet",
            );
            return false;
        }
        let bootstrap_headers = if let Some(path) = bootstrap_snapshot_path {
            if requires_genesis_bootstrap {
                match load_android_mainnet_genesis_bootstrap(path) {
                    Ok(headers) => Some(headers),
                    Err(error) => {
                        android_log_error(&format!(
                            "wallet-owned direct HNS bootstrap rejected before controller replacement: {error}"
                        ));
                        return false;
                    }
                }
            } else {
                // Pre-bootstrap wallets and recovery accounts keep their
                // honest birthday. They fall back to direct peer sync rather
                // than letting a packaged accelerator discard discovery.
                None
            }
        } else {
            None
        };
        let mut lifecycle = match std::mem::replace(self, Self::Failed) {
            Self::Lifecycle(controller) => controller,
            _ => return false,
        };
        let peer_config = HnsDirectPeerConfig::for_network(lifecycle.account_config().network);
        let coordinator_result = match bootstrap_headers {
            Some(headers) => lifecycle
                .open_direct_hns_peer_coordinator_with_floor_and_genesis_bootstrap(
                    database_key,
                    peer_config,
                    rollback_floor,
                    ANDROID_MAINNET_GENESIS_BOOTSTRAP_HEIGHT,
                    ANDROID_MAINNET_GENESIS_BOOTSTRAP_HASH,
                    headers,
                ),
            None => lifecycle.open_direct_hns_peer_coordinator_with_floor(
                database_key,
                peer_config,
                rollback_floor,
            ),
        };
        let coordinator = match coordinator_result {
            Ok(coordinator) => coordinator,
            Err(error) => {
                android_log_error(&format!(
                    "wallet-owned direct HNS coordinator installation failed closed: {error}"
                ));
                return false;
            }
        };
        let backend = coordinator.backend().clone();
        match lifecycle.into_hns_value(database_key, backend, None) {
            Ok(controller) => {
                *self = Self::DirectValue {
                    coordinator,
                    controller,
                };
                true
            }
            Err(error) => {
                android_log_error(&format!(
                    "wallet-owned direct HNS value controller installation failed closed: {error}"
                ));
                false
            }
        }
    }

    const fn has_hns_reads(&self) -> bool {
        matches!(
            self,
            Self::Reads(_) | Self::Value(_) | Self::DirectValue { .. }
        )
    }

    const fn has_hns_value(&self) -> bool {
        matches!(self, Self::Value(_) | Self::DirectValue { .. })
    }

    fn direct_hns_rollback_floor(&self) -> Option<HnsLightFloor> {
        let Self::DirectValue { coordinator, .. } = self else {
            return None;
        };
        coordinator.rollback_floor().ok()
    }

    fn synchronize_hns_reads(&mut self) -> Option<Vec<u8>> {
        let snapshot = match self {
            Self::Reads(controller) => controller.synchronize(),
            Self::Value(controller) => controller.synchronize(),
            Self::DirectValue {
                coordinator,
                controller,
            } => (|| -> Result<_, MobileWalletError> {
                let now_unix = HnsReadSystemClock.now_unix()?;
                coordinator.connect_available(now_unix)?;
                // Converge through the direct peers until they agree that no
                // extension remains. Both the round count and every peer
                // response are bounded; a later sync resumes if a much older
                // recovery wallet needs more work.
                for _ in 0..DIRECT_HNS_MAX_HEADER_ROUNDS_PER_SYNC {
                    let progress = coordinator.synchronize_headers_once(now_unix)?;
                    if matches!(
                        progress,
                        hns_wallet_mobile::HnsHeaderRoundProgress::Committed(ref round)
                            if round.accepted.is_empty()
                    ) {
                        break;
                    }
                }
                // A wallet becomes fund-ready only after its exact local watch
                // set has reached the locally agreed header tip. The direct
                // peer coordinator independently verifies every block view.
                for _ in 0..DIRECT_HNS_MAX_SCAN_CHUNKS_PER_SYNC {
                    let progress = coordinator
                        .scan_wallet_blocks(DIRECT_HNS_SCAN_BLOCKS_PER_CHUNK, now_unix)?;
                    if progress.blocks_applied == 0 {
                        break;
                    }
                }
                let _ = coordinator.refresh_mempool(now_unix)?;
                controller.synchronize()
            })(),
            Self::Lifecycle(_) | Self::Failed => return None,
        };
        let snapshot = match snapshot {
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

    fn import_hns_name_exact_text(&mut self, name: &str) -> Option<Vec<u8>> {
        let summary = match self {
            Self::Reads(controller) => controller.import_name_exact_text(name),
            Self::Value(controller) => controller.import_name_exact_text(name),
            Self::DirectValue {
                coordinator,
                controller,
            } => {
                let now_unix = HnsReadSystemClock.now_unix().ok()?;
                coordinator.connect_available(now_unix).ok()?;
                coordinator
                    .synchronize_name_proof_exact_text(name, now_unix)
                    .ok()?;
                controller.import_name_exact_text(name)
            }
            Self::Lifecycle(_) | Self::Failed => return None,
        };
        let summary = match summary {
            Ok(summary) if summary.name.as_bytes() == name.as_bytes() => summary,
            Ok(_) => {
                android_log_error("wallet HNS name import returned a non-exact name summary");
                let _ = self.lock();
                return None;
            }
            Err(error) if wallet_name_import_is_invalid(&error) => {
                // Exact syntactic rejection is intentionally non-poisoning.
                return None;
            }
            Err(error) => {
                android_log_error(&format!("wallet HNS name import failed closed: {error}"));
                // Do not depend on Kotlin to complete fail-closed handling. In
                // particular, projection/evidence failures can occur after the
                // service call itself and therefore are not guaranteed to have
                // locked the controller upstream.
                let _ = self.lock();
                return None;
            }
        };
        let mut json = match serde_json::to_vec(&summary) {
            Ok(json) => json,
            Err(error) => {
                android_log_error(&format!(
                    "wallet HNS name import projection failed closed: {error}"
                ));
                let _ = self.lock();
                return None;
            }
        };
        let bundle = wallet_name_import_bundle(json.as_slice());
        json.fill(0);
        if bundle.is_none() {
            let _ = self.lock();
        }
        bundle
    }

    fn prepare_hns_send(
        &mut self,
        recipient: String,
        amount: BaseUnits,
        maximum_fee: BaseUnits,
    ) -> Option<Vec<u8>> {
        self.prepare_hns_value_action(MobileHnsValueIntent::Send {
            recipient,
            amount,
            maximum_fee,
        })
    }

    fn prepare_hns_value_action(&mut self, intent: MobileHnsValueIntent) -> Option<Vec<u8>> {
        match self {
            Self::Value(controller) => prepare_hns_value_action(controller, intent),
            Self::DirectValue { controller, .. } => prepare_hns_value_action(controller, intent),
            Self::Lifecycle(_) | Self::Reads(_) | Self::Failed => None,
        }
    }

    fn query_shakedex(&mut self, query: MobileShakedexQuery) -> Option<Vec<u8>> {
        match self {
            Self::Value(controller) => query_shakedex(controller, query),
            Self::DirectValue { controller, .. } => query_shakedex(controller, query),
            Self::Lifecycle(_) | Self::Reads(_) | Self::Failed => None,
        }
    }

    fn approve_hns_value_action(&mut self, action_token: &str) -> Option<Vec<u8>> {
        match self {
            Self::Value(controller) => approve_hns_value_action(controller, action_token),
            Self::DirectValue { controller, .. } => {
                approve_hns_value_action(controller, action_token)
            }
            Self::Lifecycle(_) | Self::Reads(_) | Self::Failed => None,
        }
    }

    fn approve_hns_value_action_result(&mut self, action_token: &str) -> Option<Vec<u8>> {
        match self {
            Self::Value(controller) => approve_hns_value_action_result(controller, action_token),
            Self::DirectValue { controller, .. } => {
                approve_hns_value_action_result(controller, action_token)
            }
            Self::Lifecycle(_) | Self::Reads(_) | Self::Failed => None,
        }
    }

    fn reject_hns_value_action(&mut self, action_token: &str) -> bool {
        match self {
            Self::Value(controller) => reject_hns_value_action(controller, action_token),
            Self::DirectValue { controller, .. } => {
                reject_hns_value_action(controller, action_token)
            }
            Self::Lifecycle(_) | Self::Reads(_) | Self::Failed => false,
        }
    }
}

fn prepare_hns_value_action<B: HnsBackend>(
    controller: &mut MobileHnsValueController<B>,
    intent: MobileHnsValueIntent,
) -> Option<Vec<u8>> {
    let approval = match controller.prepare_value_action(intent) {
        Ok(approval) => approval,
        Err(error) => {
            android_log_error(&format!("wallet HNS value preparation failed: {error}"));
            return None;
        }
    };
    if approval.summary.validate().is_err() {
        android_log_error("wallet HNS value approval failed its native summary validation");
        let _ = controller.lock();
        return None;
    }
    let mut json = match serde_json::to_vec(&approval) {
        Ok(json) => json,
        Err(error) => {
            android_log_error(&format!(
                "wallet HNS send approval projection failed closed: {error}"
            ));
            let _ = controller.lock();
            return None;
        }
    };
    let bundle = wallet_value_approval_bundle(json.as_slice());
    json.fill(0);
    if bundle.is_none() {
        let _ = controller.lock();
    }
    bundle
}

fn query_shakedex<B: HnsBackend>(
    controller: &mut MobileHnsValueController<B>,
    query: MobileShakedexQuery,
) -> Option<Vec<u8>> {
    let result = match controller.query_shakedex(query) {
        Ok(result) if result.is_object() => result,
        Ok(_) => {
            android_log_error("wallet Shakedex query returned a non-object result");
            let _ = controller.lock();
            return None;
        }
        Err(error) => {
            android_log_error(&format!("wallet Shakedex query failed closed: {error}"));
            return None;
        }
    };
    let mut json = serde_json::to_vec(&result).ok()?;
    let bundle = wallet_shakedex_query_bundle(json.as_slice());
    json.fill(0);
    if bundle.is_none() {
        let _ = controller.lock();
    }
    bundle
}

fn approve_hns_value_action<B: HnsBackend>(
    controller: &mut MobileHnsValueController<B>,
    action_token: &str,
) -> Option<Vec<u8>> {
    let result = match controller.approve_value_action(action_token) {
        Ok(result) => result,
        Err(error) => {
            android_log_error(&format!("wallet HNS value approval failed closed: {error}"));
            return None;
        }
    };
    let Some(result) = android_hns_send_receipt(result) else {
        android_log_error("wallet HNS send result failed its closed native projection");
        let _ = controller.lock();
        return None;
    };
    let mut json = match serde_json::to_vec(&result) {
        Ok(json) => json,
        Err(error) => {
            android_log_error(&format!(
                "wallet HNS value result projection failed closed: {error}"
            ));
            let _ = controller.lock();
            return None;
        }
    };
    let bundle = wallet_value_result_bundle(json.as_slice());
    json.fill(0);
    if bundle.is_none() {
        let _ = controller.lock();
    }
    bundle
}

fn approve_hns_value_action_result<B: HnsBackend>(
    controller: &mut MobileHnsValueController<B>,
    action_token: &str,
) -> Option<Vec<u8>> {
    let result = match controller.approve_value_action(action_token) {
        Ok(result) if result.is_object() => result,
        Ok(_) => {
            android_log_error("wallet HNS value result was not an object");
            let _ = controller.lock();
            return None;
        }
        Err(error) => {
            android_log_error(&format!("wallet HNS value approval failed closed: {error}"));
            return None;
        }
    };
    let mut json = match serde_json::to_vec(&result) {
        Ok(json) => json,
        Err(error) => {
            android_log_error(&format!(
                "wallet HNS value result encoding failed closed: {error}"
            ));
            let _ = controller.lock();
            return None;
        }
    };
    let bundle = wallet_value_result_bundle(json.as_slice());
    json.fill(0);
    if bundle.is_none() {
        let _ = controller.lock();
    }
    bundle
}

fn reject_hns_value_action<B: HnsBackend>(
    controller: &mut MobileHnsValueController<B>,
    action_token: &str,
) -> bool {
    match controller.reject_value_action(action_token) {
        Ok(()) => true,
        Err(error) => {
            android_log_error(&format!(
                "wallet HNS value rejection failed closed: {error}"
            ));
            false
        }
    }
}

fn android_hns_send_receipt(result: Value) -> Option<Value> {
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
    if accepted_at_unix > i64::MAX as u64 {
        return None;
    }
    Some(json!({
        "module": "handshake",
        "txid": txid,
        "acceptedAtUnix": accepted_at_unix,
    }))
}

fn wallet_name_import_is_invalid(error: &MobileWalletError) -> bool {
    matches!(
        error,
        MobileWalletError::ServiceFailure {
            code: ServiceErrorCode::InvalidRequest,
            ..
        }
    )
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

fn android_wallet_optional_path(
    env: &mut JNIEnv<'_>,
    path: &JString<'_>,
) -> Option<Option<PathBuf>> {
    let path = env.get_string(path).ok()?.to_string_lossy().into_owned();
    if path.is_empty() {
        return Some(None);
    }
    if path.len() > MAX_ANDROID_WALLET_PATH_BYTES {
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
    Some(Some(path))
}

fn read_exact_array<const N: usize>(reader: &mut impl Read) -> Result<[u8; N], &'static str> {
    let mut bytes = [0_u8; N];
    reader
        .read_exact(&mut bytes)
        .map_err(|_| "bundled header bootstrap is truncated")?;
    Ok(bytes)
}

/// Decode the Android-shipped header stream before its contents can cross into
/// the wallet authority. The envelope's fixed height/hash are compiled into
/// this app version; the wallet core then independently validates every
/// header from canonical genesis before committing it.
fn load_android_mainnet_genesis_bootstrap(path: &Path) -> Result<Vec<Header>, &'static str> {
    let metadata = std::fs::metadata(path).map_err(|_| "bundled header bootstrap is unreadable")?;
    if !metadata.is_file() || metadata.len() != ANDROID_MAINNET_GENESIS_BOOTSTRAP_BYTES {
        return Err("bundled header bootstrap has an unexpected length");
    }
    let file = File::open(path).map_err(|_| "bundled header bootstrap cannot be opened")?;
    let mut reader = BufReader::new(file);
    if read_exact_array::<11>(&mut reader)? != *ANDROID_MAINNET_GENESIS_BOOTSTRAP_MAGIC {
        return Err("bundled header bootstrap has an invalid magic");
    }
    let target_height = u32::from_be_bytes(read_exact_array::<4>(&mut reader)?);
    let header_count = u32::from_be_bytes(read_exact_array::<4>(&mut reader)?);
    let target_hash = read_exact_array::<32>(&mut reader)?;
    if target_height != ANDROID_MAINNET_GENESIS_BOOTSTRAP_HEIGHT
        || header_count != target_height.saturating_add(1)
        || target_hash != ANDROID_MAINNET_GENESIS_BOOTSTRAP_HASH
    {
        return Err("bundled header bootstrap metadata does not match this app");
    }
    let genesis = Header::decode(&read_exact_array::<HEADER_SIZE>(&mut reader)?)
        .map_err(|_| "bundled header bootstrap has an invalid genesis header")?;
    if genesis.block_hash() != Network::Mainnet.parameters().genesis_hash {
        return Err("bundled header bootstrap has a non-mainnet genesis header");
    }

    let mut headers = Vec::with_capacity(target_height as usize);
    for _ in 0..target_height {
        let bytes = read_exact_array::<HEADER_SIZE>(&mut reader)?;
        let header = Header::decode(&bytes)
            .map_err(|_| "bundled header bootstrap contains an invalid header encoding")?;
        headers.push(header);
    }
    let mut trailing = [0_u8; 1];
    if reader
        .read(&mut trailing)
        .map_err(|_| "bundled header bootstrap could not be finalized")?
        != 0
    {
        return Err("bundled header bootstrap has trailing data");
    }
    Ok(headers)
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

fn wipe_android_byte_array(env: &mut JNIEnv<'_>, input: &JByteArray<'_>, length: usize) -> bool {
    const ZERO_CHUNK: [i8; 256] = [0; 256];
    let mut offset = 0_usize;
    while offset < length {
        let chunk = (length - offset).min(ZERO_CHUNK.len());
        let Ok(jni_offset) = i32::try_from(offset) else {
            return false;
        };
        if env
            .set_byte_array_region(input, jni_offset, &ZERO_CHUNK[..chunk])
            .is_err()
        {
            return false;
        }
        offset += chunk;
    }
    true
}

fn android_wallet_consumed_bytes(
    env: &mut JNIEnv<'_>,
    input: &JByteArray<'_>,
    maximum: usize,
) -> Option<Vec<u8>> {
    let length = usize::try_from(env.get_array_length(input).ok()?).ok()?;
    if length == 0 || length > maximum {
        let _ = wipe_android_byte_array(env, input, length);
        return None;
    }
    let converted = env.convert_byte_array(input);
    let wiped = wipe_android_byte_array(env, input, length);
    let mut bytes = converted.ok()?;
    if !wiped {
        bytes.fill(0);
        return None;
    }
    Some(bytes)
}

fn android_wallet_consumed_database_key(
    env: &mut JNIEnv<'_>,
    input: &JByteArray<'_>,
) -> Option<MobileDatabaseKey> {
    let mut bytes = android_wallet_consumed_bytes(env, input, 32)?;
    let key = MobileDatabaseKey::from_slice(bytes.as_slice()).ok();
    bytes.fill(0);
    key
}

fn android_wallet_consumed_hns_light_floor(
    env: &mut JNIEnv<'_>,
    input: &JByteArray<'_>,
) -> Option<HnsLightFloor> {
    let mut bytes = android_wallet_consumed_bytes(env, input, ANDROID_HNS_LIGHT_FLOOR_BYTES)?;
    if bytes.len() != ANDROID_HNS_LIGHT_FLOOR_BYTES {
        bytes.fill(0);
        return None;
    }
    let height = u32::from_be_bytes(bytes[..4].try_into().ok()?);
    let mut chainwork = [0_u8; 32];
    chainwork.copy_from_slice(&bytes[4..]);
    bytes.fill(0);
    Some(HnsLightFloor { height, chainwork })
}

fn android_hns_light_floor_bundle(floor: HnsLightFloor) -> [u8; ANDROID_HNS_LIGHT_FLOOR_BYTES] {
    let mut encoded = [0_u8; ANDROID_HNS_LIGHT_FLOOR_BYTES];
    encoded[..4].copy_from_slice(&floor.height.to_be_bytes());
    encoded[4..].copy_from_slice(&floor.chainwork);
    encoded
}

fn bounded_visible_ascii(mut bytes: Vec<u8>, maximum: usize) -> Option<SensitiveString> {
    if bytes.is_empty()
        || bytes.len() > maximum
        || bytes.iter().any(|byte| !(0x21..=0x7e).contains(byte))
    {
        bytes.fill(0);
        return None;
    }
    match String::from_utf8(bytes) {
        Ok(text) => Some(SensitiveString(text)),
        Err(error) => {
            let mut bytes = error.into_bytes();
            bytes.fill(0);
            None
        }
    }
}

fn canonical_nonzero_base_units(mut bytes: Vec<u8>) -> Option<BaseUnits> {
    let valid = !bytes.is_empty()
        && bytes.len() <= MAX_ANDROID_WALLET_BASE_UNITS_BYTES
        && bytes.iter().all(u8::is_ascii_digit)
        && (bytes.len() == 1 || bytes.first() != Some(&b'0'));
    if !valid {
        bytes.fill(0);
        return None;
    }
    let value = std::str::from_utf8(bytes.as_slice())
        .ok()
        .and_then(|text| text.parse::<u128>().ok())
        .filter(|value| *value != 0)
        .map(BaseUnits::new);
    bytes.fill(0);
    value
}

fn canonical_action_token(bytes: Vec<u8>) -> Option<SensitiveString> {
    if bytes.len() != ANDROID_WALLET_ACTION_TOKEN_BYTES
        || bytes
            .iter()
            .any(|byte| !byte.is_ascii_digit() && !(b'a'..=b'f').contains(byte))
    {
        let mut bytes = bytes;
        bytes.fill(0);
        return None;
    }
    match String::from_utf8(bytes) {
        Ok(token) => Some(SensitiveString(token)),
        Err(error) => {
            let mut bytes = error.into_bytes();
            bytes.fill(0);
            None
        }
    }
}

fn bounded_exact_wallet_name(mut bytes: Vec<u8>) -> Option<SensitiveString> {
    if bytes.is_empty() || bytes.len() > MAX_ANDROID_WALLET_NAME_BYTES {
        bytes.fill(0);
        return None;
    }
    match String::from_utf8(bytes) {
        Ok(name) => Some(SensitiveString(name)),
        Err(error) => {
            let mut bytes = error.into_bytes();
            bytes.fill(0);
            None
        }
    }
}

fn android_wallet_name_text(
    env: &mut JNIEnv<'_>,
    input: &JByteArray<'_>,
) -> Option<SensitiveString> {
    let length = usize::try_from(env.get_array_length(input).ok()?).ok()?;
    if length == 0 || length > MAX_ANDROID_WALLET_NAME_BYTES {
        return None;
    }
    let mut bytes = env.convert_byte_array(input).ok()?;
    let zeros = [0_i8; MAX_ANDROID_WALLET_NAME_BYTES];
    if env
        .set_byte_array_region(input, 0, &zeros[..length])
        .is_err()
    {
        bytes.fill(0);
        return None;
    }
    bounded_exact_wallet_name(bytes)
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

fn android_wallet_rpc_backend(
    loopback_port: jint,
    mut authorization: SensitiveString,
) -> Option<HnsNodeRpcBackend> {
    let loopback_port = u16::try_from(loopback_port)
        .ok()
        .filter(|port| *port != 0)?;
    let endpoint = SocketAddr::from((Ipv4Addr::LOCALHOST, loopback_port));
    let config = HnsNodeRpcConfig::new(endpoint, authorization.take())
        .and_then(|config| {
            config.with_timeouts(
                ANDROID_WALLET_RPC_CONNECT_TIMEOUT,
                ANDROID_WALLET_RPC_READ_TIMEOUT,
                ANDROID_WALLET_RPC_WRITE_TIMEOUT,
            )
        })
        .map_err(|error| {
            android_log_error(&format!("wallet HNS node configuration rejected: {error}"));
        })
        .ok()?;
    HnsNodeRpcBackend::new(config)
        .map_err(|error| {
            android_log_error(&format!("wallet HNS node backend rejected: {error}"));
        })
        .ok()
}

fn wallet_status_bundle(
    locked: bool,
    active_wallet: Option<&[u8; 16]>,
    enabled_modules_valid: bool,
    hns_reads_enabled: bool,
    hns_value_enabled: bool,
    shakedex_enabled: bool,
    mainnet_settlement_enabled: bool,
) -> Option<Vec<u8>> {
    if !enabled_modules_valid
        || (hns_value_enabled && !hns_reads_enabled)
        || (shakedex_enabled && !hns_value_enabled)
        || (mainnet_settlement_enabled && !hns_value_enabled)
        || locked == active_wallet.is_some()
    {
        return None;
    }
    let mut bundle = Vec::with_capacity(WALLET_STATUS_BUNDLE_BYTES);
    bundle.extend_from_slice(WALLET_STATUS_BUNDLE_MAGIC);
    bundle.push(WALLET_STATUS_BUNDLE_VERSION);
    let mut flags = u8::from(locked);
    if active_wallet.is_some() {
        flags |= 1 << 1;
    }
    if hns_reads_enabled {
        flags |= 1 << 2;
    }
    if hns_value_enabled {
        flags |= 1 << 3;
    }
    if shakedex_enabled {
        flags |= 1 << 4;
    }
    if mainnet_settlement_enabled {
        flags |= 1 << 5;
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

fn wallet_name_import_bundle(json: &[u8]) -> Option<Vec<u8>> {
    if json.is_empty()
        || json.len() > MAX_WALLET_NAME_IMPORT_JSON_BYTES
        || json.first() != Some(&b'{')
        || json.last() != Some(&b'}')
    {
        return None;
    }
    let json_length = u32::try_from(json.len()).ok()?;
    let mut bundle = Vec::with_capacity(WALLET_NAME_IMPORT_BUNDLE_HEADER_BYTES + json.len());
    bundle.extend_from_slice(WALLET_NAME_IMPORT_BUNDLE_MAGIC);
    bundle.push(WALLET_NAME_IMPORT_BUNDLE_VERSION);
    bundle.push(WALLET_NAME_IMPORT_BUNDLE_FLAGS);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(&json_length.to_be_bytes());
    bundle.extend_from_slice(json);
    (bundle.len() == WALLET_NAME_IMPORT_BUNDLE_HEADER_BYTES + json.len()).then_some(bundle)
}

fn wallet_value_approval_bundle(json: &[u8]) -> Option<Vec<u8>> {
    wallet_json_bundle(
        json,
        WALLET_VALUE_APPROVAL_BUNDLE_MAGIC,
        WALLET_VALUE_APPROVAL_BUNDLE_VERSION,
        WALLET_VALUE_APPROVAL_BUNDLE_FLAGS,
        WALLET_VALUE_APPROVAL_BUNDLE_HEADER_BYTES,
        MAX_WALLET_VALUE_APPROVAL_JSON_BYTES,
    )
}

fn wallet_value_result_bundle(json: &[u8]) -> Option<Vec<u8>> {
    wallet_json_bundle(
        json,
        WALLET_VALUE_RESULT_BUNDLE_MAGIC,
        WALLET_VALUE_RESULT_BUNDLE_VERSION,
        WALLET_VALUE_RESULT_BUNDLE_FLAGS,
        WALLET_VALUE_RESULT_BUNDLE_HEADER_BYTES,
        MAX_WALLET_VALUE_RESULT_JSON_BYTES,
    )
}

fn wallet_shakedex_query_bundle(json: &[u8]) -> Option<Vec<u8>> {
    wallet_json_bundle(
        json,
        WALLET_SHAKEDEX_QUERY_BUNDLE_MAGIC,
        WALLET_SHAKEDEX_QUERY_BUNDLE_VERSION,
        WALLET_SHAKEDEX_QUERY_BUNDLE_FLAGS,
        WALLET_SHAKEDEX_QUERY_BUNDLE_HEADER_BYTES,
        MAX_WALLET_SHAKEDEX_QUERY_RESULT_JSON_BYTES,
    )
}

fn wallet_json_bundle(
    json: &[u8],
    magic: &[u8; 4],
    version: u8,
    flags: u8,
    header_bytes: usize,
    maximum_json_bytes: usize,
) -> Option<Vec<u8>> {
    if header_bytes != 12
        || json.is_empty()
        || json.len() > maximum_json_bytes
        || json.first() != Some(&b'{')
        || json.last() != Some(&b'}')
    {
        return None;
    }
    let json_length = u32::try_from(json.len()).ok()?;
    let mut bundle = Vec::with_capacity(header_bytes + json.len());
    bundle.extend_from_slice(magic);
    bundle.push(version);
    bundle.push(flags);
    bundle.extend_from_slice(&[0, 0]);
    bundle.extend_from_slice(&json_length.to_be_bytes());
    bundle.extend_from_slice(json);
    (bundle.len() == header_bytes + json.len()).then_some(bundle)
}

struct AndroidRequestMetricsObserver;

impl BrowserRequestMetricsObserver for AndroidRequestMetricsObserver {
    fn observe_request_metrics(&self, metrics: &BrowserRequestMetrics) {
        android_log_request_metrics(&format!(
            "request_id={} route={:?} host={} method={} active={} queued={} admission_wait_ms={} prepare_ms={} dns_timings_available={} hns_dns_ms={} icann_dns_ms={} live_proof_timings_available={} live_proof_selection_ms={} live_proof_connect_ms={} live_proof_handshake_ms={} live_proof_verify_store_ms={} live_proof_persistence_ms={} live_proof_total_ms={} live_proof_peers_started={} live_proof_peers_completed={} gateway_ms={} origin_timing_available={} origin_ms={} publish_ms={} total_ms={} status={} outcome={}",
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
            metrics.publish_ms,
            metrics.total_ms,
            metrics
                .status_code
                .map(|status| status.to_string())
                .unwrap_or_else(|| "none".to_owned()),
            metrics.outcome,
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
        let Some(permit) = STREAMING_GATEWAY_REQUESTS.try_acquire() else {
            return std::ptr::null_mut();
        };
        let (head_tx, head_rx) = std::sync::mpsc::sync_channel::<Vec<u8>>(1);
        std::thread::spawn(move || {
            let _permit = permit;
            let mut head_sent = false;
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
            if let Err(error) = result
                && !head_sent
            {
                android_log_error(&format!(
                    "raw_gateway_request_body_streaming failed before head: {}",
                    runtime_error_message(error),
                ));
            }
        });
        match head_rx
            .recv_timeout(Duration::from_secs(30))
            .ok()
            .and_then(|head| env.byte_array_from_slice(&head).ok())
        {
            Some(array) => array.into_raw(),
            None => std::ptr::null_mut(),
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
        let Some(authorization) = android_wallet_rpc_authorization(&mut env, &authorization) else {
            return false;
        };
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
        let Some(backend) = android_wallet_rpc_backend(loopback_port, authorization) else {
            return false;
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
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeConfigureHnsValue(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    database_key: JByteArray<'_>,
    loopback_port: jint,
    authorization: JCharArray<'_>,
    shakedex_policy_json: JByteArray<'_>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        // Consume both caller-owned capabilities before any admission result or
        // potentially blocking native operation can be observed.
        let database_key = android_wallet_consumed_database_key(&mut env, &database_key);
        let authorization = android_wallet_rpc_authorization(&mut env, &authorization);
        let shakedex_policy_json = android_wallet_consumed_bytes(
            &mut env,
            &shakedex_policy_json,
            MAX_MOBILE_SHAKEDEX_POLICY_BYTES,
        );
        let (Some(database_key), Some(authorization), Some(mut shakedex_policy_json)) =
            (database_key, authorization, shakedex_policy_json)
        else {
            return false;
        };
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
        let Some(backend) = android_wallet_rpc_backend(loopback_port, authorization) else {
            return false;
        };
        let Some(mut controller) = record.controller_if_active() else {
            return false;
        };
        let installed =
            controller.install_hns_value(&database_key, backend, shakedex_policy_json.as_slice());
        shakedex_policy_json.fill(0);
        installed
    }))
    .unwrap_or(false)
    .into()
}

/// Install the production wallet-owned direct HNS path. The JNI boundary
/// accepts only the one-time database key; peers, header authority, filtering,
/// and broadcast are all constructed from the selected encrypted account.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeConfigureWalletOwnedDirectHnsValue(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    database_key: JByteArray<'_>,
    rollback_floor: JByteArray<'_>,
    bootstrap_snapshot_path: JString<'_>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(database_key) = android_wallet_consumed_database_key(&mut env, &database_key)
        else {
            let _ = android_wallet_consumed_hns_light_floor(&mut env, &rollback_floor);
            return false;
        };
        let Some(rollback_floor) =
            android_wallet_consumed_hns_light_floor(&mut env, &rollback_floor)
        else {
            return false;
        };
        let Some(bootstrap_snapshot_path) =
            android_wallet_optional_path(&mut env, &bootstrap_snapshot_path)
        else {
            return false;
        };
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
        let Some(mut controller) = record.controller_if_active() else {
            return false;
        };
        controller.install_direct_hns_value(
            &database_key,
            rollback_floor,
            bootstrap_snapshot_path.as_deref(),
        )
    }))
    .unwrap_or(false)
    .into()
}

/// Return the direct coordinator's latest local rollback floor as a fixed
/// network-order `(height, chainwork)` record. Only the Android platform
/// holder persists it; no wallet private material crosses this boundary.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeDirectHnsRollbackFloor(
    env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let record = wallet_from_handle(handle)?;
        let controller = record.controller_if_active()?;
        let mut floor = android_hns_light_floor_bundle(controller.direct_hns_rollback_floor()?);
        let array = env.byte_array_from_slice(floor.as_slice()).ok();
        floor.fill(0);
        array.map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
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
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeHasHnsValue(
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
        controller.has_hns_value()
    }))
    .unwrap_or(false)
    .into()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeImportHnsNameExactText(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    exact_utf8: JByteArray<'_>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        // Consume and clear the Java-owned mutable bytes before any controller
        // lookup or potentially blocking native read operation.
        let name = android_wallet_name_text(&mut env, &exact_utf8)?;
        let record = wallet_from_handle(handle)?;
        let mut controller = record.controller_if_active()?;
        let mut bundle = controller.import_hns_name_exact_text(name.0.as_str())?;
        let array = env.byte_array_from_slice(bundle.as_slice()).ok();
        bundle.fill(0);
        if array.is_none() {
            // A successful import may already be durable; inability to return
            // its exact minimized projection poisons this controller.
            let _ = controller.lock();
        }
        array.map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
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
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativePrepareHnsSend(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    recipient_utf8: JByteArray<'_>,
    amount_base_units_ascii: JByteArray<'_>,
    maximum_fee_base_units_ascii: JByteArray<'_>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        // Consume every mutable Java input before validating any sibling, so
        // malformed calls cannot retain a transaction field for later reuse.
        let recipient = android_wallet_consumed_bytes(
            &mut env,
            &recipient_utf8,
            MAX_ANDROID_WALLET_RECIPIENT_BYTES,
        );
        let amount = android_wallet_consumed_bytes(
            &mut env,
            &amount_base_units_ascii,
            MAX_ANDROID_WALLET_BASE_UNITS_BYTES,
        );
        let maximum_fee = android_wallet_consumed_bytes(
            &mut env,
            &maximum_fee_base_units_ascii,
            MAX_ANDROID_WALLET_BASE_UNITS_BYTES,
        );
        let (Some(recipient), Some(amount), Some(maximum_fee)) = (recipient, amount, maximum_fee)
        else {
            return None;
        };
        let mut recipient = bounded_visible_ascii(recipient, MAX_ANDROID_WALLET_RECIPIENT_BYTES)?;
        let amount = canonical_nonzero_base_units(amount)?;
        let maximum_fee = canonical_nonzero_base_units(maximum_fee)?;
        let record = wallet_from_handle(handle)?;
        let mut controller = record.controller_if_active()?;
        let mut bundle = controller.prepare_hns_send(recipient.take(), amount, maximum_fee)?;
        let array = env.byte_array_from_slice(bundle.as_slice()).ok();
        bundle.fill(0);
        if array.is_none() {
            // Preparation installed a pending native approval; never leave it
            // executable when its exact display projection was not delivered.
            let _ = controller.lock();
        }
        array.map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativePrepareHnsValueAction(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    intent_json: JByteArray<'_>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let mut json = android_wallet_consumed_bytes(
            &mut env,
            &intent_json,
            MAX_ANDROID_WALLET_VALUE_INTENT_JSON_BYTES,
        )?;
        let intent = if json.first() == Some(&b'{') && json.last() == Some(&b'}') {
            serde_json::from_slice::<MobileHnsValueIntent>(json.as_slice()).ok()
        } else {
            None
        };
        json.fill(0);
        let intent = intent?;
        let record = wallet_from_handle(handle)?;
        let mut controller = record.controller_if_active()?;
        let mut bundle = controller.prepare_hns_value_action(intent)?;
        let array = env.byte_array_from_slice(bundle.as_slice()).ok();
        bundle.fill(0);
        if array.is_none() {
            let _ = controller.lock();
        }
        array.map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeQueryShakedex(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    query_json: JByteArray<'_>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let mut json = android_wallet_consumed_bytes(
            &mut env,
            &query_json,
            MAX_ANDROID_WALLET_SHAKEDEX_QUERY_JSON_BYTES,
        )?;
        let query = if json.first() == Some(&b'{') && json.last() == Some(&b'}') {
            serde_json::from_slice::<MobileShakedexQuery>(json.as_slice()).ok()
        } else {
            None
        };
        json.fill(0);
        let query = query?;
        let record = wallet_from_handle(handle)?;
        let mut controller = record.controller_if_active()?;
        let mut bundle = controller.query_shakedex(query)?;
        let array = env.byte_array_from_slice(bundle.as_slice()).ok();
        bundle.fill(0);
        array.map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeApproveHnsValueAction(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    action_token_ascii: JByteArray<'_>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let token = android_wallet_consumed_bytes(
            &mut env,
            &action_token_ascii,
            ANDROID_WALLET_ACTION_TOKEN_BYTES,
        )?;
        let token = canonical_action_token(token)?;
        let record = wallet_from_handle(handle)?;
        let mut controller = record.controller_if_active()?;
        let mut bundle = controller.approve_hns_value_action(token.0.as_str())?;
        let array = env.byte_array_from_slice(bundle.as_slice()).ok();
        bundle.fill(0);
        if array.is_none() {
            // The broadcast may already be durable. Locking prevents a caller
            // from continuing with an ambiguous value session.
            let _ = controller.lock();
        }
        array.map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeApproveHnsValueActionResult(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    action_token_ascii: JByteArray<'_>,
) -> jbyteArray {
    catch_unwind(AssertUnwindSafe(|| {
        let token = android_wallet_consumed_bytes(
            &mut env,
            &action_token_ascii,
            ANDROID_WALLET_ACTION_TOKEN_BYTES,
        )?;
        let token = canonical_action_token(token)?;
        let record = wallet_from_handle(handle)?;
        let mut controller = record.controller_if_active()?;
        let mut bundle = controller.approve_hns_value_action_result(token.0.as_str())?;
        let array = env.byte_array_from_slice(bundle.as_slice()).ok();
        bundle.fill(0);
        if array.is_none() {
            let _ = controller.lock();
        }
        array.map(JByteArray::into_raw)
    }))
    .ok()
    .flatten()
    .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_denuoweb_hnsdane_wallet_NativeWalletBridge_nativeRejectHnsValueAction(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    action_token_ascii: JByteArray<'_>,
) -> jboolean {
    catch_unwind(AssertUnwindSafe(|| {
        let Some(token) = android_wallet_consumed_bytes(
            &mut env,
            &action_token_ascii,
            ANDROID_WALLET_ACTION_TOKEN_BYTES,
        ) else {
            return false;
        };
        let Some(token) = canonical_action_token(token) else {
            return false;
        };
        let Some(record) = wallet_from_handle(handle) else {
            return false;
        };
        let Some(mut controller) = record.controller_if_active() else {
            return false;
        };
        controller.reject_hns_value_action(token.0.as_str())
    }))
    .unwrap_or(false)
    .into()
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
    fn wallet_control_bundles_project_full_hns_value_state() {
        let wallet_id = [7_u8; 16];
        let status = wallet_status_bundle(false, Some(&wallet_id), true, true, true, true, true)
            .expect("unlocked HNS value status");
        assert_eq!(status.len(), WALLET_STATUS_BUNDLE_BYTES);
        assert_eq!(&status[..4], WALLET_STATUS_BUNDLE_MAGIC);
        assert_eq!(status[4], WALLET_STATUS_BUNDLE_VERSION);
        assert_eq!(status[5], 0b11_1110);
        assert_eq!(&status[8..], &wallet_id);
        assert!(wallet_status_bundle(true, None, true, false, false, false, false).is_some());
        assert!(
            wallet_status_bundle(true, Some(&wallet_id), true, false, false, false, false)
                .is_none()
        );
        assert!(wallet_status_bundle(false, None, false, false, false, false, false).is_none());
        assert!(wallet_status_bundle(false, None, true, false, false, false, false).is_none());
        assert!(
            wallet_status_bundle(false, Some(&wallet_id), true, false, true, false, false)
                .is_none()
        );
        assert!(
            wallet_status_bundle(false, Some(&wallet_id), true, true, false, true, false).is_none()
        );

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
    fn wallet_name_import_bundle_is_versioned_closed_and_bounded() {
        let json = br#"{"name":"alpha","nameHash":"0000000000000000000000000000000000000000000000000000000000000000","proofHeight":7,"resourceStatus":"empty","ownershipStatus":"notWalletOwned","registered":true,"expired":false}"#;
        let bundle = wallet_name_import_bundle(json).expect("bounded name import bundle");
        assert_eq!(&bundle[..4], WALLET_NAME_IMPORT_BUNDLE_MAGIC);
        assert_eq!(bundle[4], WALLET_NAME_IMPORT_BUNDLE_VERSION);
        assert_eq!(bundle[5], WALLET_NAME_IMPORT_BUNDLE_FLAGS);
        assert_eq!(&bundle[6..8], &[0, 0]);
        assert_eq!(
            u32::from_be_bytes(bundle[8..12].try_into().expect("length field")),
            json.len() as u32
        );
        assert_eq!(&bundle[WALLET_NAME_IMPORT_BUNDLE_HEADER_BYTES..], json);

        assert!(wallet_name_import_bundle(b"").is_none());
        assert!(wallet_name_import_bundle(b"[]").is_none());
        assert!(wallet_name_import_bundle(b"{broken").is_none());
        assert!(
            wallet_name_import_bundle(&vec![b' '; MAX_WALLET_NAME_IMPORT_JSON_BYTES + 1]).is_none()
        );
    }

    #[test]
    fn wallet_value_bundles_are_distinct_versioned_and_bounded() {
        let approval_json = br#"{"actionToken":"00","expiresAtUnix":7,"summary":{}}"#;
        let approval = wallet_value_approval_bundle(approval_json).expect("value approval bundle");
        assert_eq!(&approval[..4], WALLET_VALUE_APPROVAL_BUNDLE_MAGIC);
        assert_eq!(approval[4], WALLET_VALUE_APPROVAL_BUNDLE_VERSION);
        assert_eq!(approval[5], WALLET_VALUE_APPROVAL_BUNDLE_FLAGS);
        assert_eq!(&approval[6..8], &[0, 0]);
        assert_eq!(
            u32::from_be_bytes(approval[8..12].try_into().expect("approval length")),
            approval_json.len() as u32
        );
        assert_eq!(
            &approval[WALLET_VALUE_APPROVAL_BUNDLE_HEADER_BYTES..],
            approval_json
        );

        let result_json = br#"{"module":"handshake","txid":"00"}"#;
        let result = wallet_value_result_bundle(result_json).expect("value result bundle");
        assert_eq!(&result[..4], WALLET_VALUE_RESULT_BUNDLE_MAGIC);
        assert_eq!(result[4], WALLET_VALUE_RESULT_BUNDLE_VERSION);
        assert_eq!(result[5], WALLET_VALUE_RESULT_BUNDLE_FLAGS);
        assert_eq!(
            &result[WALLET_VALUE_RESULT_BUNDLE_HEADER_BYTES..],
            result_json
        );
        assert_ne!(&approval[..4], &result[..4]);

        for invalid in [b"".as_slice(), b"[]", b"{broken"] {
            assert!(wallet_value_approval_bundle(invalid).is_none());
            assert!(wallet_value_result_bundle(invalid).is_none());
        }
        assert!(
            wallet_value_approval_bundle(&vec![b' '; MAX_WALLET_VALUE_APPROVAL_JSON_BYTES + 1])
                .is_none()
        );
        assert!(
            wallet_value_result_bundle(&vec![b' '; MAX_WALLET_VALUE_RESULT_JSON_BYTES + 1])
                .is_none()
        );
    }

    #[test]
    fn wallet_send_inputs_are_canonical_and_closed() {
        assert_eq!(
            bounded_visible_ascii(b"hs1qqqqqq".to_vec(), MAX_ANDROID_WALLET_RECIPIENT_BYTES)
                .expect("visible recipient")
                .0,
            "hs1qqqqqq"
        );
        for invalid in [Vec::new(), b" address".to_vec(), b"address\n".to_vec()] {
            assert!(bounded_visible_ascii(invalid, MAX_ANDROID_WALLET_RECIPIENT_BYTES).is_none());
        }

        assert_eq!(
            canonical_nonzero_base_units(b"1".to_vec())
                .expect("one base unit")
                .get(),
            1
        );
        assert_eq!(
            canonical_nonzero_base_units(u128::MAX.to_string().into_bytes())
                .expect("maximum u128")
                .get(),
            u128::MAX
        );
        for invalid in [
            "",
            "0",
            "01",
            "+1",
            "340282366920938463463374607431768211456",
        ] {
            assert!(canonical_nonzero_base_units(invalid.as_bytes().to_vec()).is_none());
        }

        let token = "ab".repeat(ANDROID_WALLET_ACTION_TOKEN_BYTES / 2);
        assert_eq!(
            canonical_action_token(token.as_bytes().to_vec())
                .expect("lowercase token")
                .0,
            token
        );
        let uppercase_token = "AB".repeat(ANDROID_WALLET_ACTION_TOKEN_BYTES / 2);
        for invalid in ["ab", uppercase_token.as_str()] {
            assert!(canonical_action_token(invalid.as_bytes().to_vec()).is_none());
        }
    }

    #[test]
    fn wallet_name_import_error_classification_preserves_invalid_non_poisoning() {
        assert!(wallet_name_import_is_invalid(
            &MobileWalletError::ServiceFailure {
                code: ServiceErrorCode::InvalidRequest,
                message: "invalid".to_owned(),
            }
        ));
        assert!(!wallet_name_import_is_invalid(
            &MobileWalletError::ServiceFailure {
                code: ServiceErrorCode::RuntimeFailure,
                message: "failed".to_owned(),
            }
        ));
        assert!(!wallet_name_import_is_invalid(
            &MobileWalletError::ControllerFailed
        ));
    }

    #[test]
    fn wallet_name_input_preserves_exact_utf8_and_rejects_invalid_or_oversize_text() {
        for exact in ["Alpha", "alpha.", " alpha", "é"] {
            let retained =
                bounded_exact_wallet_name(exact.as_bytes().to_vec()).expect("bounded exact UTF-8");
            assert_eq!(retained.0.as_bytes(), exact.as_bytes());
        }
        assert!(bounded_exact_wallet_name(Vec::new()).is_none());
        assert!(bounded_exact_wallet_name(vec![b'a'; MAX_ANDROID_WALLET_NAME_BYTES + 1]).is_none());
        assert!(bounded_exact_wallet_name(vec![0xff]).is_none());
    }

    #[test]
    fn wallet_name_import_is_unavailable_without_the_read_controller_variant() {
        let mut controller = AndroidWalletController::Failed;
        assert!(!controller.has_hns_reads());
        assert!(!controller.has_hns_value());
        assert!(controller.import_hns_name_exact_text("alpha").is_none());
        assert!(
            controller
                .prepare_hns_send("recipient".to_owned(), BaseUnits::new(1), BaseUnits::new(1))
                .is_none()
        );
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
