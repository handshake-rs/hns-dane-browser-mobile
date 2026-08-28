#include "hns_browser.h"

#include <stddef.h>

_Static_assert(HNS_BROWSER_ABI_VERSION == 1u, "unexpected ABI version");
_Static_assert(HNS_BROWSER_WALLET_READ_BUNDLE_VERSION == 2u,
               "unexpected wallet read bundle version");
_Static_assert(HNS_BROWSER_WALLET_NAME_IMPORT_BUNDLE_VERSION == 1u,
               "unexpected wallet name import bundle version");
_Static_assert(sizeof(HnsBrowserRuntimeHandle) == sizeof(uint64_t), "runtime handle width");
_Static_assert(sizeof(HnsBrowserProxyHandle) == sizeof(uint64_t), "proxy handle width");
_Static_assert(sizeof(HnsBrowserWalletHandle) == sizeof(uint64_t), "wallet handle width");
_Static_assert(sizeof(HnsBrowserWalletHnsSyncProgress) == 40u,
               "wallet progress ABI width");
_Static_assert(offsetof(HnsBrowserBuffer, allocation_id) > offsetof(HnsBrowserBuffer, len),
               "buffer field order");

static void typecheck_api(void) {
    uint32_t (*abi_version)(void) = hns_browser_abi_version;
    HnsBrowserResult (*runtime_create)(const HnsBrowserRuntimeOptions *,
                                       HnsBrowserRuntimeHandle *) =
        hns_browser_runtime_create;
    HnsBrowserResult (*proxy_start)(HnsBrowserRuntimeHandle, HnsBrowserSlice,
                                    HnsBrowserProxyHandle *) = hns_browser_proxy_start;
    HnsBrowserResult (*canonical_host)(HnsBrowserSlice, HnsBrowserBuffer *) =
        hns_browser_canonical_host;
    HnsBrowserResult (*proxy_stop)(HnsBrowserProxyHandle) =
        hns_browser_proxy_request_stop;
    HnsBrowserResult (*wallet_create)(HnsBrowserSlice, HnsBrowserSlice,
                                      HnsBrowserNetwork, uint64_t,
                                      HnsBrowserWalletHandle *) =
        hns_browser_wallet_create;
    HnsBrowserResult (*wallet_restore)(HnsBrowserSlice, HnsBrowserSlice,
                                       HnsBrowserNetwork, uint64_t,
                                       HnsBrowserSlice,
                                       HnsBrowserWalletHandle *) =
        hns_browser_wallet_restore;
    HnsBrowserResult (*wallet_open)(HnsBrowserSlice, HnsBrowserSlice,
                                    HnsBrowserWalletHandle *) =
        hns_browser_wallet_open;
    HnsBrowserResult (*wallet_status)(HnsBrowserWalletHandle, HnsBrowserBuffer *) =
        hns_browser_wallet_status;
    HnsBrowserResult (*wallet_accounts)(HnsBrowserWalletHandle, HnsBrowserBuffer *) =
        hns_browser_wallet_accounts;
    HnsBrowserResult (*wallet_configure_reads)(HnsBrowserWalletHandle, uint16_t,
                                                HnsBrowserSlice) =
        hns_browser_wallet_configure_hns_reads;
    HnsBrowserResult (*wallet_has_reads)(HnsBrowserWalletHandle, uint8_t *) =
        hns_browser_wallet_has_hns_reads;
    HnsBrowserResult (*wallet_synchronize_reads)(HnsBrowserWalletHandle,
                                                  HnsBrowserBuffer *) =
        hns_browser_wallet_synchronize_hns_reads;
    HnsBrowserResult (*wallet_sync_progress)(
        HnsBrowserWalletHandle, HnsBrowserWalletHnsSyncProgress *) =
        hns_browser_wallet_hns_sync_progress;
    HnsBrowserResult (*wallet_import_name)(HnsBrowserWalletHandle,
                                           HnsBrowserSlice,
                                           HnsBrowserBuffer *) =
        hns_browser_wallet_import_hns_name_exact_text;
    HnsBrowserResult (*wallet_unlock)(HnsBrowserWalletHandle, HnsBrowserSlice) =
        hns_browser_wallet_unlock;
    HnsBrowserResult (*wallet_lock)(HnsBrowserWalletHandle) = hns_browser_wallet_lock;
    HnsBrowserResult (*wallet_recovery)(HnsBrowserWalletHandle, HnsBrowserBuffer *) =
        hns_browser_wallet_take_recovery_phrase;
    HnsBrowserResult (*wallet_destroy)(HnsBrowserWalletHandle) =
        hns_browser_wallet_destroy;

    (void)abi_version;
    (void)runtime_create;
    (void)proxy_start;
    (void)canonical_host;
    (void)proxy_stop;
    (void)wallet_create;
    (void)wallet_restore;
    (void)wallet_open;
    (void)wallet_status;
    (void)wallet_accounts;
    (void)wallet_configure_reads;
    (void)wallet_has_reads;
    (void)wallet_synchronize_reads;
    (void)wallet_sync_progress;
    (void)wallet_import_name;
    (void)wallet_unlock;
    (void)wallet_lock;
    (void)wallet_recovery;
    (void)wallet_destroy;
}

int main(void) {
    typecheck_api();
    return 0;
}
