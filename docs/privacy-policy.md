# HNS DANE Browser Privacy Policy

Last updated: 2026-08-13

HNS DANE Browser is published by Denuo Web, LLC. For privacy questions or deletion requests, email <info@denuoweb.com> or use the developer contact listed in the app's store listing. Do not post personal information to the public project issue tracker.

## Summary

HNS DANE Browser is a Handshake-first browser for local HNS proofs, authoritative DNS, optional requester-only HNS P2P DNS relay consumption, optional user-configured recursive HNS DoH recovery, DNSSEC, and DANE diagnostics. It also provides native controls to create or restore one device-local HNS account identity and to open, unlock, or lock that local wallet. The wallet screen contains read-only fields for balance, receive target, transaction history, tracked names, and module status, but this build installs no scoped companion credential or indexed wallet backend, so those fields remain unavailable and no wallet-specific network request is made. Name import, sending or value movement, website-provider access, HNSA/HNSR service roles, settlement, exchange features, and P2P marketplaces are unavailable. The requester-only P2P DNS relay is separate from HNSR and does not make the device a relay endpoint or output node. The app has no advertising SDKs, analytics SDKs, developer-operated accounts, or paid feature unlocks. The Android edition may show an optional external donation link that does not unlock functionality; the iOS app has no donation or payment flow.

The app stores browser and native wallet data locally on the device and sends
network requests needed to load sites and keep HNS resolution data current.

## Data Stored Locally

The app may store the following data on the device:

- Browsing history and navigation state: page URLs, page titles, visit times, or the current session's back-forward list, depending on the platform.
- Website data: cookies and other storage managed by Android WebView or Apple WebKit.
- Downloads: files saved at your request and platform-specific local records needed to complete or present those downloads. Android records may include the URL, file name, MIME type, DownloadManager ID, and queued time; iOS saves completed files in the app's local Documents/Downloads directory until you export or remove the app.
- HNS data: synced headers, peer records (including manually added relay-peer IP endpoints), verified resource values, resolver cache, and resolver diagnostics.
- Settings: homepage, cookie preference, optional HNS P2P DNS relay requester, optional user-configured recursive HNS DoH recovery URL, and related app preferences. Relay consumption and recursive recovery are independently off by default and require separate explicit choices. Upgrades erase the historical resolver key and never copy it into the new recovery setting or treat it as relay consent.
- Native wallet data: a network-scoped encrypted wallet database, one HNS account identity, and the key material needed to reopen it. This build does not provision the separate indexed read backend, so no synchronized balance, receive, history, or name data is populated. Android keeps the database under app-private no-backup storage and wraps its 32-byte database key with Android Keystore. iOS uses an app-private, backup-excluded database with complete file protection and a ThisDeviceOnly Keychain item requiring user presence. A newly generated recovery phrase is shown once for offline backup; restore input and the one-time display are cleared when the wallet screen leaves its protected lifecycle. If the screen closes before that recovery display is confirmed, the app wipes its unconfirmed database-key buffer and deletes the incomplete wallet database instead of retaining an unrecoverable wallet. Swift/UIKit-managed text on iOS cannot be claimed to be deterministically zeroized, although app-owned mutable buffers are wiped.

This local data is used only to provide browser functionality, native wallet
controls, diagnostics, and HNS resolution. It is not sold. It is not sent to a
Denuo Web analytics or advertising service.

## Network Requests

To provide browser functionality, HNS DANE Browser may connect to:

- Websites and web services that you choose to open.
- Handshake peers and DNS seed hosts for header sync, peer discovery, and proof retrieval.
- Relay-capable Handshake peers for recursive HNS DNS queries after local proof validation and authoritative DNS attempts fail, but only after the user opts into requester consumption. Upgrades preserve an independent relay choice and never convert a former public-DoH/compatibility choice into consent. A manual relay peer must be entered as an IP-literal endpoint and is stored only after its live HSD handshake advertises the relay capability. The browser does not become an output node.
- Authoritative DNS nameservers for delegated HNS names.
- Proof-bootstrapped or RFC 9461-discovered RFC 8484 authoritative DoH endpoints for delegated HNS names.
- A recursive HNS DNS-over-HTTPS endpoint entered explicitly by the user, but only after direct authoritative DNS, owner-published proof-anchored authoritative DoH, and any independently enabled P2P requester path fail because port 53 is intercepted or DNS transport is unavailable. Leaving the setting blank makes no request to such a service. `https://hnsdoh.com/dns-query` is an example only; it is never prefilled, selected automatically, or contacted unless the user enters it.
- Security or reputation services exposed by the platform web engine. In particular, an installed Android WebView provider may check URLs with its Safe Browsing service and apply its own privacy policy. Apple WebKit and the operating system may apply their own browser-security protections. HNS DANE Browser does not operate those platform services.
- The non-routable `192.0.2.1` TEST-NET DNS sentinel after delegated DNS failure; a matching reply confirms transparent outbound port 53 interception, while no reply is reported only as not detected.
- Cloudflare's DNS-over-HTTPS service at `cloudflare-dns.com` (bootstrapped through the documented `1.1.1.1` addresses) for ordinary internet DNS resolution.
- Platform download services and the destination you choose when you download or export a file.

The native wallet controller in this version does not synchronize a balance,
send a transaction, contact a website wallet provider, settle a trade, or make
a wallet-specific network request. Its account controls are device-local.

These network endpoints may receive technical information that is normal for network communication, such as your IP address, the requested host or URL, protocol metadata, and any data you submit to websites. Cloudflare controls its own resolver logging, retention, and privacy practices; Denuo Web does not operate that service. In particular, an HNS relay peer or a user-configured recursive HNS DoH operator can observe queried DNS names and record types, request timing, and the source IP address. An ordinary Handshake TCP connection is not query-confidential; encrypted peer transport should be preferred where available. Relay and configured-recursive responses are still validated locally through the app's Handshake proof, DNSSEC, TLSA, and DANE checks; neither a peer's DNS authenticated-data bit nor a resolver's trust assertion is accepted as proof.

The app has no automatic or default recursive HNS resolver. If the user explicitly configures a recovery endpoint, the app validates its bounded HTTPS URL, resolves its hostname only through validating ICANN DoH, connects only to public addresses with WebPKI, and still validates HNS answers locally. Bogus DNSSEC, invalid DNS, DNS response codes, and stale or missing HNS proof state remain terminal instead of activating recovery. HNS WebPKI fallback remains prohibited. Every complete DNS hostname is also resolved through bounded validating ICANN DoH for dual-root classification; ICANN WebPKI is allowed only after authenticated TLSA denial or a proven unsigned zone.

HTTPS, DNSSEC, and DANE are used where applicable. If you intentionally open a cleartext `http://` site, that site connection is not encrypted by HTTPS.

## Cookies and Website Data

Websites may set cookies or use platform web-engine storage. Android provides settings controls to block third-party cookies and delete cookies plus WebView origin storage. iOS uses a persistent WebKit profile and provides a settings action that deletes its cookies and website data. Remaining website data is removed when the app is uninstalled. Websites are responsible for their own privacy practices.

## Data Sharing

Denuo Web does not sell personal or sensitive user data. HNS DANE Browser shares data only as necessary for user-requested browser functionality, such as loading a website, syncing HNS data, resolving a name, or downloading a file. Native wallet databases, recovery phrases, device-bound database keys, and account identities are not sent to Denuo Web, websites, analytics services, or a wallet provider by this version.

## Retention and Deletion

Local browser data remains on the device until you clear it using an available platform/app control or uninstall the app. Android provides controls for clearing cookies and WebView origin storage, browsing history, download records, gateway diagnostics, and the HNS resolver cache; Android system settings can also clear all app storage. iOS provides controls for clearing cookies and WebKit website data, browsing history, download-list records, locally stored gateway diagnostics, and the HNS resolver cache. Clearing the iOS download list does not delete the downloaded files themselves; those app-local files remain until the app is uninstalled. Files you export to another location are then controlled by that destination.

An unconfirmed newly created wallet is automatically removed when its protected recovery screen closes: the app wipes its unconfirmed database-key buffer and deletes the incomplete database. A confirmed native wallet can be deleted from its protected wallet screen after it is unlocked. The app shows the exact network and account identity in two destructive confirmations and requires the user to type `DELETE` exactly. This removes only that device-local network namespace; it does not remove a recovery phrase or wallet backup saved elsewhere.

Confirmed-wallet deletion first revokes the screen's wallet/read authority and closes the native controller. It then deletes the device-bound database key before deleting the encrypted database and its sidecar files. If key deletion fails, file removal does not begin and deletion remains incomplete; Android keeps the confirmed request blocked for retry, while iOS may reopen the verified-intact wallet after protected lifecycle access resumes. An ambiguous native close blocks that network wallet until the app process restarts. If file cleanup fails after the key is gone, the remaining encrypted orphan cannot be reopened and cleanup is retried before another wallet can use that network namespace. Android records this pending state in app-private preferences; iOS reconciles the Keychain item and database artifacts. Clearing all app storage or uninstalling remains a platform-level deletion option, subject to normal iOS Keychain retention semantics and subsequent reconciliation. Save the recovery phrase before deletion, clearing storage, or uninstalling, because the app cannot show it again and cannot recover the wallet for you.

HNS DANE Browser does not create developer-operated user accounts, so there is no app account deletion flow.

## Children

HNS DANE Browser is not directed to children. Because it is a general-purpose browser, websites opened by users may contain third-party content outside Denuo Web's control.

## Changes

This policy may be updated as the app changes. Material privacy changes should be reflected in this file, the in-app privacy text, Google Play's Data safety form, and Apple's App Privacy answers as applicable.
