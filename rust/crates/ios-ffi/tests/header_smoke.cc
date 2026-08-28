#include "hns_browser.h"

#include <cstddef>
#include <cstdint>
#include <type_traits>

static_assert(HNS_BROWSER_ABI_VERSION == 1u);
static_assert(HNS_BROWSER_WALLET_READ_BUNDLE_VERSION == 2u);
static_assert(HNS_BROWSER_WALLET_NAME_IMPORT_BUNDLE_VERSION == 1u);
static_assert(std::is_standard_layout_v<HnsBrowserSlice>);
static_assert(std::is_standard_layout_v<HnsBrowserBuffer>);
static_assert(sizeof(HnsBrowserRuntimeHandle) == sizeof(std::uint64_t));
static_assert(sizeof(HnsBrowserProxyHandle) == sizeof(std::uint64_t));
static_assert(sizeof(HnsBrowserWalletHandle) == sizeof(std::uint64_t));
static_assert(sizeof(HnsBrowserWalletHnsSyncProgress) == 40u);

int main() {
    auto *abiVersion = &hns_browser_abi_version;
    auto *runtimeCreate = &hns_browser_runtime_create;
    auto *proxyStart = &hns_browser_proxy_start;
    auto *canonicalHost = &hns_browser_canonical_host;
    auto *proxyStop = &hns_browser_proxy_request_stop;
    auto *walletCreate = &hns_browser_wallet_create;
    auto *walletRestore = &hns_browser_wallet_restore;
    auto *walletOpen = &hns_browser_wallet_open;
    auto *walletStatus = &hns_browser_wallet_status;
    auto *walletAccounts = &hns_browser_wallet_accounts;
    auto *walletConfigureReads = &hns_browser_wallet_configure_hns_reads;
    auto *walletHasReads = &hns_browser_wallet_has_hns_reads;
    auto *walletSynchronizeReads = &hns_browser_wallet_synchronize_hns_reads;
    auto *walletSyncProgress = &hns_browser_wallet_hns_sync_progress;
    auto *walletImportName = &hns_browser_wallet_import_hns_name_exact_text;
    auto *walletUnlock = &hns_browser_wallet_unlock;
    auto *walletLock = &hns_browser_wallet_lock;
    auto *walletRecovery = &hns_browser_wallet_take_recovery_phrase;
    auto *walletDestroy = &hns_browser_wallet_destroy;
    (void)abiVersion;
    (void)runtimeCreate;
    (void)proxyStart;
    (void)canonicalHost;
    (void)proxyStop;
    (void)walletCreate;
    (void)walletRestore;
    (void)walletOpen;
    (void)walletStatus;
    (void)walletAccounts;
    (void)walletConfigureReads;
    (void)walletHasReads;
    (void)walletSynchronizeReads;
    (void)walletSyncProgress;
    (void)walletImportName;
    (void)walletUnlock;
    (void)walletLock;
    (void)walletRecovery;
    (void)walletDestroy;
    return 0;
}
