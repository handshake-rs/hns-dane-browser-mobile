//! Share live public HNS transport with the wallet. Chain verification and
//! atomic browser-store publication still belong to the browser runtime.

use super::*;
use hns_sync::{HeaderPeerFailureStage, HeaderSyncRunResult};
use hns_wallet_hns::{HnsNetwork as WalletNetwork, HnsPublicHeaderTransport};

static TRANSPORTS: OnceLock<Mutex<Vec<(NetworkKind, HnsPublicHeaderTransport)>>> = OnceLock::new();

/// Register a weak, public-only transport handle from the selected wallet.
/// Closing the wallet and expiring its public-session cache releases the pool;
/// the browser never retains an unlocked wallet or its encrypted store.
pub fn register_wallet_header_transport(transport: HnsPublicHeaderTransport) {
    let network = match transport.network() {
        Some(WalletNetwork::Mainnet) => NetworkKind::Mainnet,
        Some(WalletNetwork::Testnet) => NetworkKind::Testnet,
        Some(WalletNetwork::Regtest) => NetworkKind::Regtest,
        _ => return,
    };
    if let Ok(mut transports) = TRANSPORTS.get_or_init(Default::default).lock() {
        transports
            .retain(|(stored, transport)| *stored != network && transport.network().is_some());
        transports.push((network, transport));
    }
}

fn transport_for(network: NetworkKind) -> Option<HnsPublicHeaderTransport> {
    TRANSPORTS
        .get()?
        .lock()
        .ok()?
        .iter()
        .find(|(stored, transport)| *stored == network && transport.network().is_some())
        .map(|(_, transport)| transport.clone())
}

/// Reuse existing wallet sockets instead of starting another browser peer
/// pool. Each response is independently validated against the staged browser
/// chain, and only actual canonical header responses refresh peer evidence.
pub(super) fn sync_with_wallet_transport(
    network: NetworkKind,
    coordinator: &mut HeaderSyncCoordinator<SqliteHeaderStore>,
    peers: &mut hns_p2p::PeerManager,
    store: &SqlitePeerStore,
    on_progress: &mut impl FnMut(Option<u32>, usize),
) -> Result<Option<HeaderSyncRunResult>, String> {
    let Some(transport) = transport_for(network) else {
        return Ok(None);
    };
    let addresses = transport.peer_addresses().unwrap_or_default();
    if addresses.is_empty() {
        return Ok(None);
    }
    let best = coordinator
        .chain()
        .best_header()
        .map_err(|error| error.to_string())?;
    // The wallet maintains verification peers and a public reserve. The
    // browser still requires its own three-address-group currentness quorum.
    let _ = transport.connect_reserve(
        best.as_ref().map_or(0, |header| header.height.0),
        now_unix_seconds(),
    );
    let addresses = transport
        .peer_addresses()
        .map_err(|error| error.to_string())?;
    sync_shared_headers(
        coordinator,
        peers,
        store,
        addresses,
        on_progress,
        |address, locator| {
            transport
                .request_headers(address, locator, [0; 32], now_unix_seconds())
                .map_err(|error| error.to_string())?
                .into_iter()
                .map(|header| {
                    BlockHeader::parse(&header.encode()).map_err(|error| error.to_string())
                })
                .collect()
        },
    )
    .map(Some)
}

fn sync_shared_headers(
    coordinator: &mut HeaderSyncCoordinator<SqliteHeaderStore>,
    peers: &mut hns_p2p::PeerManager,
    store: &SqlitePeerStore,
    addresses: Vec<SocketAddr>,
    on_progress: &mut impl FnMut(Option<u32>, usize),
    request: impl Fn(SocketAddr, Vec<[u8; 32]>) -> Result<Vec<BlockHeader>, String> + Sync,
) -> Result<HeaderSyncRunResult, String> {
    let best = coordinator
        .chain()
        .best_header()
        .map_err(|error| error.to_string())?;
    let mut result = HeaderSyncRunResult {
        attempted: addresses.len(),
        successful: 0,
        accepted: 0,
        best,
        failures: Vec::new(),
    };
    let mut successful = HashSet::new();
    let mut active = addresses;
    for _ in 0..192 {
        // Challenge the known tip through its predecessor. Stock HSD may
        // omit an empty HEADERS packet when queried directly at its tip.
        let mut locator = coordinator.locator().map_err(|error| error.to_string())?;
        if locator.len() > 1 {
            locator.remove(0);
        }
        let locator = locator
            .into_iter()
            .map(|hash| *hash.as_bytes())
            .collect::<Vec<_>>();
        let responses = thread::scope(|scope| {
            active
                .iter()
                .copied()
                .map(|address| {
                    let locator = locator.clone();
                    let request = &request;
                    scope.spawn(move || (address, request(address, locator)))
                })
                .collect::<Vec<_>>()
                .into_iter()
                .map(|task| task.join())
                .collect::<Vec<_>>()
        });
        let before = result.accepted;
        for response in responses {
            let (address, response) =
                response.map_err(|_| "shared HNS header worker panicked".to_owned())?;
            let headers = match response {
                Ok(headers) => headers,
                Err(error) => {
                    peers.clear_observed_height(address);
                    peers.record_transient_failure(address);
                    active.retain(|candidate| *candidate != address);
                    successful.remove(&address);
                    result.failures.push(HeaderPeerFailure {
                        address,
                        stage: HeaderPeerFailureStage::Headers,
                        error,
                    });
                    continue;
                }
            };
            let last_hash = headers.last().map(BlockHeader::hash);
            // An empty response cannot prove a height or refresh old evidence.
            peers.clear_observed_height(address);
            let batch = match coordinator.ingest_headers(headers) {
                Ok(batch) => batch,
                Err(hns_sync::SyncError::Chain(hns_chain::ChainError::Storage(error))) => {
                    return Err(format!("store shared HNS headers: {error}"));
                }
                Err(error) => {
                    peers.record_transient_failure(address);
                    active.retain(|candidate| *candidate != address);
                    successful.remove(&address);
                    result.failures.push(HeaderPeerFailure {
                        address,
                        stage: HeaderPeerFailureStage::Headers,
                        error: error.to_string(),
                    });
                    continue;
                }
            };
            result.accepted = result.accepted.saturating_add(batch.accepted);
            result.best = batch.best;
            if let Some(header) = last_hash.and_then(|hash| coordinator.chain().get_header(hash))
                && coordinator.chain().canonical_hash(header.height) == Some(header.hash)
            {
                peers.record_success(address, header.height, now_unix_seconds());
                successful.insert(address);
            }
            on_progress(
                result.best.as_ref().map(|header| header.height.0),
                result.accepted,
            );
        }
        if active.is_empty() || before == result.accepted {
            break;
        }
    }
    result.successful = successful.len();
    store
        .save_manager(peers)
        .map_err(|error| format!("save shared HNS peer observations: {error}"))?;
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use hns_core::pow::verify_pow;
    use hns_p2p::PeerManager;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn coordinator() -> HeaderSyncCoordinator<SqliteHeaderStore> {
        let mut chain = chain_for_network(
            SqliteHeaderStore::in_memory().unwrap(),
            NetworkKind::Regtest,
        );
        chain
            .insert_genesis(BlockHeader::genesis_for_network(NetworkKind::Regtest))
            .unwrap();
        HeaderSyncCoordinator::new(chain)
    }

    fn next_header(parent: &BlockHeader) -> BlockHeader {
        let mut header = parent.clone();
        header.prev_block = parent.hash();
        header.time += 1;
        header.nonce = 0;
        while !verify_pow(header.hash(), header.bits).unwrap() {
            header.nonce += 1;
        }
        header
    }

    #[test]
    fn shared_sync_verifies_new_headers_then_stops_on_tip_echo() {
        let mut coordinator = coordinator();
        let genesis = coordinator.chain().best_header().unwrap().unwrap().header;
        let header = next_header(&genesis);
        let addresses = ["1.1.1.1:12038", "8.8.8.8:12038", "9.9.9.9:12038"]
            .map(|address| address.parse().unwrap());
        let store = SqlitePeerStore::in_memory().unwrap();
        let mut peers = PeerManager::default();
        let calls = AtomicUsize::new(0);
        let result = sync_shared_headers(
            &mut coordinator,
            &mut peers,
            &store,
            addresses.to_vec(),
            &mut |_, _| {},
            |_, locator| {
                calls.fetch_add(1, Ordering::Relaxed);
                assert_eq!(locator.first(), Some(genesis.hash().as_bytes()));
                Ok(vec![header.clone()])
            },
        )
        .unwrap();
        assert_eq!(result.accepted, 1);
        assert_eq!(result.successful, 3);
        assert_eq!(result.best.unwrap().height, Height(1));
        assert_eq!(calls.load(Ordering::Relaxed), 6);
        for address in addresses {
            let evidence = store.load_peer(address).unwrap().unwrap();
            assert_eq!(evidence.last_height, Height(1));
            assert!(evidence.last_height_observed_at.is_some());
        }
    }

    #[test]
    fn empty_shared_response_clears_previous_height_evidence() {
        let mut coordinator = coordinator();
        let address = "1.1.1.1:12038".parse().unwrap();
        let store = SqlitePeerStore::in_memory().unwrap();
        let mut peers = PeerManager::default();
        peers.record_success(address, Height(999_999), 1);
        let result = sync_shared_headers(
            &mut coordinator,
            &mut peers,
            &store,
            vec![address],
            &mut |_, _| {},
            |_, _| Ok(Vec::new()),
        )
        .unwrap();
        assert_eq!(result.accepted, 0);
        assert_eq!(result.successful, 0);
        assert_eq!(
            store
                .load_peer(address)
                .unwrap()
                .unwrap()
                .last_height_observed_at,
            None
        );
    }

    #[test]
    fn invalid_peer_batch_does_not_abort_healthy_shared_sync() {
        let mut coordinator = coordinator();
        let genesis = coordinator.chain().best_header().unwrap().unwrap().header;
        let valid = next_header(&genesis);
        let mut invalid = valid.clone();
        invalid.prev_block = hns_core::Hash::new([37; 32]);
        let bad = "1.1.1.1:12038".parse().unwrap();
        let good = "8.8.8.8:12038".parse().unwrap();
        let store = SqlitePeerStore::in_memory().unwrap();
        let mut peers = PeerManager::default();
        peers.record_success(bad, Height(999_999), 1);
        let result = sync_shared_headers(
            &mut coordinator,
            &mut peers,
            &store,
            vec![bad, good],
            &mut |_, _| {},
            |address, _| {
                Ok(vec![if address == bad {
                    invalid.clone()
                } else {
                    valid.clone()
                }])
            },
        )
        .unwrap();
        assert_eq!(result.accepted, 1);
        assert_eq!(result.successful, 1);
        assert_eq!(result.failures.len(), 1);
        assert_eq!(result.failures[0].address, bad);
        assert_eq!(peers.get(bad).unwrap().last_height_observed_at, None);
        assert_eq!(peers.get(good).unwrap().last_height, Height(1));
    }
}
