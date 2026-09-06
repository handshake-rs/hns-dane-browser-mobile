//! Best-effort inbound TCP reachability for a mobile ShakeScape node.
//!
//! A global interface address and a router-created mapping are discovered in
//! parallel. A global IPv6 assignment is only a candidate: it says nothing
//! about an access-network firewall, so it is never automatically advertised.
//! A router-created TCP mapping is publishable after the router acknowledges
//! the mapping and returns a public external IPv4 address. Neither path is
//! wallet or board authority: every connection still requires the normal
//! Handshake and ShakeScape negotiation. Manual operator-supplied endpoints
//! intentionally live above this module so they cannot silently override
//! automatic network changes.

use std::{
    collections::BTreeSet,
    io,
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, SocketAddrV4, SocketAddrV6},
    num::NonZeroU16,
    time::{Duration, Instant},
};

use crab_nat::{
    GatewayAddress, InternetProtocol, PortMapping as NatPortMapping, PortMappingOptions,
    PortMappingType, TimeoutConfig, natpmp, pcp,
};
use igd_next::{PortMappingProtocol, SearchOptions, aio as async_igd};
use portmapper::Client as PortMapperClient;
#[cfg(not(target_os = "android"))]
use portmapper::{Config as PortMapperConfig, Protocol};
use thiserror::Error;
use tokio::{runtime::Runtime, sync::watch, task::JoinHandle};

const MAPPING_RETRY_INTERVAL: Duration = Duration::from_secs(30);
const MAPPING_RELEASE_TIMEOUT: Duration = Duration::from_secs(2);
const EXPLICIT_MAPPING_PROTOCOL_TIMEOUT: Duration = Duration::from_millis(1_500);
const UPNP_SEARCH_TIMEOUT: Duration = Duration::from_secs(4);
const MAPPING_LEASE_SECONDS: u32 = 2 * 60 * 60;
const MAPPING_RENEW_INTERVAL: Duration = Duration::from_secs(60 * 60);
const MAPPING_DESCRIPTION: &str = "ShakeScape Handshake listener";

type AsyncUpnpGateway = async_igd::Gateway<async_igd::tokio::Tokio>;

/// An application-authoritative IPv4 route used for router mapping.
///
/// Android deliberately restricts the netlink route query used by many
/// portable networking crates. The Android host therefore supplies this pair
/// from `ConnectivityManager`/`LinkProperties`; the mapping protocol still
/// verifies every public address returned by the router.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ShakescapeRouterRoute {
    pub local_ipv4: Ipv4Addr,
    pub gateway_ipv4: Ipv4Addr,
}

impl ShakescapeRouterRoute {
    #[must_use]
    pub const fn new(local_ipv4: Ipv4Addr, gateway_ipv4: Ipv4Addr) -> Self {
        Self {
            local_ipv4,
            gateway_ipv4,
        }
    }

    fn usable(self) -> bool {
        !self.local_ipv4.is_unspecified()
            && !self.local_ipv4.is_loopback()
            && !self.local_ipv4.is_multicast()
            && !self.gateway_ipv4.is_unspecified()
            && !self.gateway_ipv4.is_loopback()
            && !self.gateway_ipv4.is_multicast()
    }
}

/// How the current automatic public TCP locator was obtained.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum ShakescapeReachabilityMethod {
    /// A globally routable IPv6 address was independently verified for inbound
    /// TCP reachability. The current automatic cascade does not promote an
    /// IPv6 candidate to this state without a future external verifier.
    PublicIpv6,
    /// A router acknowledged a PCP, NAT-PMP, or UPnP TCP mapping.
    RouterMappedIpv4,
}

/// One snapshot of the concurrently evaluated reachability paths.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct ShakescapeReachabilitySnapshot {
    /// Preferred automatic endpoint that may be published through ADDR.
    /// A bare global IPv6 assignment is deliberately excluded.
    pub endpoint: Option<SocketAddr>,
    /// Origin of `endpoint`.
    pub method: Option<ShakescapeReachabilityMethod>,
    /// Current globally routable IPv6 candidate, if one exists.
    pub public_ipv6: Option<SocketAddrV6>,
    /// Current router-created IPv4 mapping, if one exists.
    pub mapped_ipv4: Option<SocketAddrV4>,
    /// Interface enumeration failed during this snapshot. Router mapping
    /// remains usable in this state.
    pub ipv6_scan_failed: bool,
}

/// Failure to start the automatic reachability worker.
#[derive(Debug, Error)]
pub enum ShakescapeReachabilityError {
    /// A listener port of zero cannot be mapped or advertised.
    #[error("ShakeScape reachability requires a nonzero listener port")]
    InvalidListenerPort,
    /// The private asynchronous port-mapping worker could not be created.
    #[error("could not start the ShakeScape port-mapping worker: {0}")]
    Runtime(#[from] io::Error),
}

/// A live automatic reachability lease for one TCP listener port.
///
/// The mapper races PCP, NAT-PMP, and UPnP internally and renews the selected
/// mapping. IPv6 interface state is re-read on every snapshot so mobile
/// interface changes are reflected without retaining a stale address.
pub struct ShakescapeReachabilityCascade {
    local_port: NonZeroU16,
    mapping_client: Option<PortMapperClient>,
    mapping_watcher: watch::Receiver<Option<SocketAddrV4>>,
    explicit_mapping_route: Option<ShakescapeRouterRoute>,
    explicit_mapping_watcher: watch::Receiver<Option<SocketAddrV4>>,
    explicit_mapping_stop: Option<watch::Sender<bool>>,
    explicit_mapping_task: Option<JoinHandle<()>>,
    mapping_runtime: Runtime,
    last_mapping_retry: Instant,
}

impl ShakescapeReachabilityCascade {
    /// Start all automatic reachability methods for an already-bound TCP
    /// listener. The port-mapping attempt runs on one private worker thread and
    /// therefore does not block the Android or iOS lifecycle caller.
    pub fn start(local_port: u16) -> Result<Self, ShakescapeReachabilityError> {
        let local_port =
            NonZeroU16::new(local_port).ok_or(ShakescapeReachabilityError::InvalidListenerPort)?;
        let mapping_runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .thread_name("shakescape-portmap")
            .enable_all()
            .build()?;
        let (mapping_client, mapping_watcher) =
            portable_mapping_client(&mapping_runtime, local_port);
        let (_explicit_mapping_sender, explicit_mapping_watcher) = watch::channel(None);
        Ok(Self {
            local_port,
            mapping_client,
            mapping_watcher,
            explicit_mapping_route: None,
            explicit_mapping_watcher,
            explicit_mapping_stop: None,
            explicit_mapping_task: None,
            mapping_runtime,
            last_mapping_retry: Instant::now(),
        })
    }

    /// The concrete local TCP port whose router lease is maintained.
    #[must_use]
    pub const fn local_port(&self) -> u16 {
        self.local_port.get()
    }

    /// Replace the explicit router route used by PCP/NAT-PMP/UPnP.
    ///
    /// Supplying `None` stops and releases an existing explicit mapping. The
    /// worker is restarted only when the active route changes, so polling
    /// Android's network state does not churn router leases.
    pub fn update_router_route(&mut self, route: Option<ShakescapeRouterRoute>) {
        let route = route.filter(|route| route.usable());
        if route == self.explicit_mapping_route {
            return;
        }
        self.stop_explicit_mapping();
        self.explicit_mapping_route = route;
        let Some(route) = route else {
            let (_sender, watcher) = watch::channel(None);
            self.explicit_mapping_watcher = watcher;
            return;
        };

        let (mapping_sender, mapping_watcher) = watch::channel(None);
        let (stop_sender, stop_watcher) = watch::channel(false);
        let local_port = self.local_port;
        self.explicit_mapping_watcher = mapping_watcher;
        self.explicit_mapping_stop = Some(stop_sender);
        self.explicit_mapping_task = Some(self.mapping_runtime.spawn(run_explicit_mapping_worker(
            route,
            local_port,
            mapping_sender,
            stop_watcher,
        )));
    }

    /// Re-evaluate public IPv6 and read the latest router mapping without
    /// blocking for discovery. A missing mapping is retried at a bounded rate.
    #[must_use]
    pub fn snapshot(&mut self) -> ShakescapeReachabilitySnapshot {
        let explicit_mapped_ipv4 = *self.explicit_mapping_watcher.borrow_and_update();
        let portable_mapped_ipv4 = *self.mapping_watcher.borrow_and_update();
        let mapped_ipv4 = explicit_mapped_ipv4
            .or(portable_mapped_ipv4)
            .filter(|address| public_ipv4_allowed(*address.ip()));
        if mapped_ipv4.is_none() && self.last_mapping_retry.elapsed() >= MAPPING_RETRY_INTERVAL {
            if let Some(mapping_client) = self.mapping_client.as_ref() {
                mapping_client.procure_mapping();
            }
            self.last_mapping_retry = Instant::now();
        }

        let (public_ipv6, ipv6_scan_failed) = match discover_public_ipv6(self.local_port.get()) {
            Ok(address) => (address, false),
            Err(_) => (None, true),
        };
        let (endpoint, method) = preferred_publishable_endpoint(mapped_ipv4);
        ShakescapeReachabilitySnapshot {
            endpoint,
            method,
            public_ipv6,
            mapped_ipv4,
            ipv6_scan_failed,
        }
    }

    fn stop_explicit_mapping(&mut self) {
        if let Some(stop) = self.explicit_mapping_stop.take() {
            let _ = stop.send(true);
        }
        let Some(mut task) = self.explicit_mapping_task.take() else {
            return;
        };
        if self
            .mapping_runtime
            .block_on(tokio::time::timeout(MAPPING_RELEASE_TIMEOUT, &mut task))
            .is_err()
        {
            task.abort();
            let _ = self.mapping_runtime.block_on(task);
        }
    }
}

impl Drop for ShakescapeReachabilityCascade {
    fn drop(&mut self) {
        self.stop_explicit_mapping();
        let Some(mapping_client) = self.mapping_client.as_ref() else {
            return;
        };
        mapping_client.deactivate();
        let mut watcher = self.mapping_watcher.clone();
        self.mapping_runtime.block_on(async move {
            if watcher.borrow().is_none() {
                return;
            }
            let _ = tokio::time::timeout(MAPPING_RELEASE_TIMEOUT, async {
                while watcher.changed().await.is_ok() {
                    if watcher.borrow().is_none() {
                        break;
                    }
                }
            })
            .await;
        });
    }
}

#[cfg(not(target_os = "android"))]
fn portable_mapping_client(
    runtime: &Runtime,
    local_port: NonZeroU16,
) -> (
    Option<PortMapperClient>,
    watch::Receiver<Option<SocketAddrV4>>,
) {
    let client = {
        let _runtime_guard = runtime.enter();
        PortMapperClient::new(PortMapperConfig {
            enable_upnp: true,
            enable_pcp: true,
            enable_nat_pmp: true,
            protocol: Protocol::Tcp,
        })
    };
    let watcher = client.watch_external_address();
    client.update_local_port(local_port);
    (Some(client), watcher)
}

/// Android's application sandbox blocks the route-netlink query currently
/// used by `portmapper`. The Android host supplies the active route through
/// [`ShakescapeReachabilityCascade::update_router_route`] instead.
#[cfg(target_os = "android")]
fn portable_mapping_client(
    _runtime: &Runtime,
    _local_port: NonZeroU16,
) -> (
    Option<PortMapperClient>,
    watch::Receiver<Option<SocketAddrV4>>,
) {
    let (_sender, watcher) = watch::channel(None);
    (None, watcher)
}

enum ExplicitMappingLease {
    Nat {
        mapping: NatPortMapping,
        endpoint: SocketAddrV4,
    },
    Upnp {
        gateway: AsyncUpnpGateway,
        endpoint: SocketAddrV4,
        local_address: SocketAddr,
    },
}

impl ExplicitMappingLease {
    fn endpoint(&self) -> SocketAddrV4 {
        match self {
            Self::Nat { endpoint, .. } | Self::Upnp { endpoint, .. } => *endpoint,
        }
    }

    async fn renew(&mut self) -> bool {
        match self {
            Self::Nat { mapping, endpoint } => {
                if mapping.renew().await.is_err() {
                    return false;
                }
                endpoint.set_port(mapping.external_port().get());
                true
            }
            Self::Upnp {
                gateway,
                endpoint,
                local_address,
            } => gateway
                .add_port(
                    PortMappingProtocol::TCP,
                    endpoint.port(),
                    *local_address,
                    MAPPING_LEASE_SECONDS,
                    MAPPING_DESCRIPTION,
                )
                .await
                .is_ok(),
        }
    }

    async fn release(self) {
        match self {
            Self::Nat { mapping, .. } => {
                let _ = mapping.try_drop().await;
            }
            Self::Upnp {
                gateway, endpoint, ..
            } => {
                let _ = gateway
                    .remove_port(PortMappingProtocol::TCP, endpoint.port())
                    .await;
            }
        }
    }
}

async fn run_explicit_mapping_worker(
    route: ShakescapeRouterRoute,
    local_port: NonZeroU16,
    mapping_sender: watch::Sender<Option<SocketAddrV4>>,
    mut stop: watch::Receiver<bool>,
) {
    loop {
        if *stop.borrow() {
            return;
        }
        let acquisition = acquire_explicit_mapping(route, local_port);
        let Some(mut lease) = (tokio::select! {
            lease = acquisition => lease,
            _ = stop.changed() => return,
        }) else {
            let _ = mapping_sender.send(None);
            tokio::select! {
                _ = tokio::time::sleep(MAPPING_RETRY_INTERVAL) => continue,
                _ = stop.changed() => return,
            }
        };

        let _ = mapping_sender.send(Some(lease.endpoint()));
        loop {
            let renewed = tokio::select! {
                _ = tokio::time::sleep(MAPPING_RENEW_INTERVAL) => lease.renew().await,
                _ = stop.changed() => false,
            };
            if !renewed || *stop.borrow() {
                break;
            }
            let _ = mapping_sender.send(Some(lease.endpoint()));
        }
        let _ = mapping_sender.send(None);
        let _ = tokio::time::timeout(MAPPING_RELEASE_TIMEOUT, lease.release()).await;
        if *stop.borrow() {
            return;
        }
    }
}

async fn acquire_explicit_mapping(
    route: ShakescapeRouterRoute,
    local_port: NonZeroU16,
) -> Option<ExplicitMappingLease> {
    let gateway = GatewayAddress::from(route.gateway_ipv4);
    let preferred_external_port = preferred_external_port(route, local_port);
    let options = explicit_mapping_options(preferred_external_port);

    if let Ok(Ok(mapping)) = tokio::time::timeout(
        EXPLICIT_MAPPING_PROTOCOL_TIMEOUT,
        pcp::port_mapping(
            pcp::BaseMapRequest::new(
                gateway,
                IpAddr::V4(route.local_ipv4),
                InternetProtocol::Tcp,
                local_port,
            ),
            None,
            None,
            options,
        ),
    )
    .await
        && let PortMappingType::Pcp { external_ip, .. } = mapping.mapping_type()
        && let IpAddr::V4(external_ip) = external_ip
        && public_ipv4_allowed(external_ip)
    {
        return Some(ExplicitMappingLease::Nat {
            endpoint: SocketAddrV4::new(external_ip, mapping.external_port().get()),
            mapping,
        });
    }

    let external_ip = tokio::time::timeout(
        EXPLICIT_MAPPING_PROTOCOL_TIMEOUT,
        natpmp::external_address(gateway, Some(explicit_timeout_config())),
    )
    .await
    .ok()
    .and_then(Result::ok)
    .filter(|address| public_ipv4_allowed(*address));
    if let Some(external_ip) = external_ip
        && let Ok(Ok(mapping)) = tokio::time::timeout(
            EXPLICIT_MAPPING_PROTOCOL_TIMEOUT,
            natpmp::port_mapping(gateway, InternetProtocol::Tcp, local_port, options),
        )
        .await
    {
        return Some(ExplicitMappingLease::Nat {
            endpoint: SocketAddrV4::new(external_ip, mapping.external_port().get()),
            mapping,
        });
    }

    acquire_upnp_mapping(route.local_ipv4, local_port, preferred_external_port).await
}

async fn acquire_upnp_mapping(
    local_ipv4: Ipv4Addr,
    local_port: NonZeroU16,
    preferred_external_port: NonZeroU16,
) -> Option<ExplicitMappingLease> {
    let search = async_igd::tokio::search_gateway(SearchOptions {
        bind_addr: SocketAddr::V4(SocketAddrV4::new(local_ipv4, 0)),
        timeout: Some(UPNP_SEARCH_TIMEOUT),
        single_search_timeout: Some(Duration::from_secs(2)),
        ..SearchOptions::default()
    });
    let gateway = tokio::time::timeout(UPNP_SEARCH_TIMEOUT, search)
        .await
        .ok()
        .and_then(Result::ok)?;
    let external_ip =
        match tokio::time::timeout(EXPLICIT_MAPPING_PROTOCOL_TIMEOUT, gateway.get_external_ip())
            .await
            .ok()
            .and_then(Result::ok)?
        {
            IpAddr::V4(address) if public_ipv4_allowed(address) => address,
            _ => return None,
        };
    let local_address = SocketAddr::V4(SocketAddrV4::new(local_ipv4, local_port.get()));
    let preferred_port = preferred_external_port.get();
    let external_port = if gateway
        .add_port(
            PortMappingProtocol::TCP,
            preferred_port,
            local_address,
            MAPPING_LEASE_SECONDS,
            MAPPING_DESCRIPTION,
        )
        .await
        .is_ok()
    {
        preferred_port
    } else {
        gateway
            .add_any_port(
                PortMappingProtocol::TCP,
                local_address,
                MAPPING_LEASE_SECONDS,
                MAPPING_DESCRIPTION,
            )
            .await
            .ok()?
    };
    NonZeroU16::new(external_port)?;
    Some(ExplicitMappingLease::Upnp {
        gateway,
        endpoint: SocketAddrV4::new(external_ip, external_port),
        local_address,
    })
}

fn explicit_timeout_config() -> TimeoutConfig {
    TimeoutConfig {
        initial_timeout: Duration::from_millis(250),
        max_retries: 1,
        max_retry_timeout: Some(Duration::from_millis(500)),
    }
}

fn explicit_mapping_options(external_port: NonZeroU16) -> PortMappingOptions {
    PortMappingOptions {
        external_port: Some(external_port),
        lifetime_seconds: Some(MAPPING_LEASE_SECONDS),
        timeout_config: Some(explicit_timeout_config()),
    }
}

/// Pick a stable per-LAN-address port from the IANA dynamic range instead of
/// reusing the local Handshake port. Some consumer routers implement LAN
/// hairpin redirects too broadly: mapping external port 12038 can then capture
/// the mobile's unrelated outbound connections to every stock HSD peer. The
/// advertised ADDR record carries the actual router-returned port, so the
/// external port has no protocol requirement to equal the listener port.
fn preferred_external_port(route: ShakescapeRouterRoute, local_port: NonZeroU16) -> NonZeroU16 {
    const DYNAMIC_PORT_START: u16 = 49_152;
    const DYNAMIC_PORT_COUNT: u16 = 16_384;

    let octets = route.local_ipv4.octets();
    let address_component = u16::from_be_bytes([octets[2], octets[3]]);
    let offset = address_component
        .wrapping_mul(257)
        .wrapping_add(local_port.get())
        % DYNAMIC_PORT_COUNT;
    NonZeroU16::new(DYNAMIC_PORT_START + offset).expect("the dynamic port range is nonzero")
}

/// Reject private, carrier-NAT, link-local, documentation, multicast, and
/// otherwise non-Internet IPv4 locators before the UI or ADDR path can call a
/// router acknowledgement "public".
fn public_ipv4_allowed(address: Ipv4Addr) -> bool {
    let [a, b, c, _d] = address.octets();
    !(a == 0
        || a == 10
        || a == 127
        || a >= 224
        || (a == 100 && (64..=127).contains(&b))
        || (a == 169 && b == 254)
        || (a == 172 && (16..=31).contains(&b))
        || (a == 192 && b == 0 && c == 0)
        || (a == 192 && b == 0 && c == 2)
        || (a == 192 && b == 168)
        || (a == 198 && (b == 18 || b == 19))
        || (a == 198 && b == 51 && c == 100)
        || (a == 203 && b == 0 && c == 113))
}

fn discover_public_ipv6(port: u16) -> io::Result<Option<SocketAddrV6>> {
    let public: BTreeSet<Ipv6Addr> = getifs::public_ipv6_addrs()?
        .into_iter()
        .filter(|address| {
            getifs::ifindex_to_name(address.index())
                .is_ok_and(|name| automatic_ipv6_interface_allowed(&name))
        })
        .map(|address| address.addr())
        .collect();
    if public.is_empty() {
        return Ok(None);
    }

    // Prefer addresses on the kernel's best default route. If route
    // introspection is unavailable on a particular mobile build, retain the
    // aggressive fallback to a public interface address.
    let best = getifs::best_local_ipv6_addrs()
        .ok()
        .and_then(|addresses| {
            addresses
                .into_iter()
                .map(|address| address.addr())
                .filter(|address| public.contains(address))
                .min()
        })
        .or_else(|| public.iter().next().copied());
    Ok(best.map(|address| SocketAddrV6::new(address, port, 0, 0)))
}

/// Reject addresses owned by virtual tunnel interfaces. In particular,
/// Android can expose a globally scoped IPv6 address on an IMS-only `ipsec*`
/// network whose allowed UID set excludes this app. Such an address is public
/// in the RFC sense but cannot truthfully identify this listener.
fn automatic_ipv6_interface_allowed(name: &str) -> bool {
    const VIRTUAL_PREFIXES: &[&str] = &[
        "dummy", "ipsec", "tun", "utun", "tap", "vti", "xfrm", "wg", "clat", "sit", "gre",
        "gretap", "erspan", "ifb",
    ];

    let name = name.to_ascii_lowercase();
    name != "lo"
        && !VIRTUAL_PREFIXES
            .iter()
            .any(|prefix| name == *prefix || name.starts_with(prefix))
}

fn preferred_publishable_endpoint(
    mapped_ipv4: Option<SocketAddrV4>,
) -> (Option<SocketAddr>, Option<ShakescapeReachabilityMethod>) {
    if let Some(address) = mapped_ipv4 {
        return (
            Some(SocketAddr::V4(address)),
            Some(ShakescapeReachabilityMethod::RouterMappedIpv4),
        );
    }
    (None, None)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::Ipv4Addr;

    #[test]
    fn unverified_ipv6_is_never_promoted_to_a_publishable_endpoint() {
        assert_eq!(preferred_publishable_endpoint(None), (None, None));
    }

    #[test]
    fn router_mapping_is_publishable() {
        let mapped = SocketAddrV4::new(Ipv4Addr::new(203, 0, 113, 8), 42_000);
        assert_eq!(
            preferred_publishable_endpoint(Some(mapped)),
            (
                Some(SocketAddr::V4(mapped)),
                Some(ShakescapeReachabilityMethod::RouterMappedIpv4)
            )
        );
    }

    #[test]
    fn no_candidate_does_not_invent_an_endpoint() {
        assert_eq!(preferred_publishable_endpoint(None), (None, None));
    }

    #[test]
    fn ipv6_candidates_reject_tunnels_but_keep_mobile_interfaces() {
        for rejected in [
            "lo",
            "dummy0",
            "ipsec3010",
            "tun0",
            "utun4",
            "wg0",
            "clat4",
            "vti6",
        ] {
            assert!(!automatic_ipv6_interface_allowed(rejected), "{rejected}");
        }
        for allowed in ["wlan0", "eth0", "rmnet_data2", "pdp_ip0"] {
            assert!(automatic_ipv6_interface_allowed(allowed), "{allowed}");
        }
    }

    #[test]
    fn router_mapping_never_promotes_private_or_carrier_nat_addresses() {
        for rejected in [
            "0.0.0.0",
            "10.248.232.210",
            "100.64.0.1",
            "100.127.255.254",
            "127.0.0.1",
            "169.254.10.2",
            "172.16.0.1",
            "172.31.255.254",
            "192.168.8.1",
            "198.18.0.1",
            "203.0.113.8",
            "224.0.0.1",
        ] {
            assert!(
                !public_ipv4_allowed(rejected.parse().unwrap()),
                "{rejected}"
            );
        }
        for allowed in ["1.1.1.1", "8.8.8.8", "45.79.112.203"] {
            assert!(public_ipv4_allowed(allowed.parse().unwrap()), "{allowed}");
        }
    }

    #[test]
    fn explicit_router_route_requires_usable_unicast_addresses() {
        assert!(
            ShakescapeRouterRoute::new(
                "192.168.8.106".parse().unwrap(),
                "192.168.8.1".parse().unwrap()
            )
            .usable()
        );
        assert!(
            !ShakescapeRouterRoute::new(Ipv4Addr::UNSPECIFIED, "192.168.8.1".parse().unwrap())
                .usable()
        );
        assert!(
            !ShakescapeRouterRoute::new("192.168.8.106".parse().unwrap(), Ipv4Addr::LOCALHOST)
                .usable()
        );
    }

    #[test]
    fn router_mapping_never_requests_the_standard_handshake_port_externally() {
        let local_port = NonZeroU16::new(12_038).unwrap();
        let first = preferred_external_port(
            ShakescapeRouterRoute::new(
                "192.168.8.106".parse().unwrap(),
                "192.168.8.1".parse().unwrap(),
            ),
            local_port,
        );
        let second = preferred_external_port(
            ShakescapeRouterRoute::new(
                "192.168.8.242".parse().unwrap(),
                "192.168.8.1".parse().unwrap(),
            ),
            local_port,
        );

        assert!((49_152..=u16::MAX).contains(&first.get()));
        assert!((49_152..=u16::MAX).contains(&second.get()));
        assert_ne!(first.get(), local_port.get());
        assert_ne!(second.get(), local_port.get());
        assert_ne!(first, second);
        assert_eq!(explicit_mapping_options(first).external_port, Some(first));
    }
}
