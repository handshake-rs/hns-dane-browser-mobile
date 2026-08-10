#ifndef HnsDaneBrowser_Bridging_Header_h
#define HnsDaneBrowser_Bridging_Header_h

@import HnsBrowserRuntime;
#include <stddef.h>
#include <zlib.h>

// Swift's Darwin overlay does not consistently expose explicit_bzero across
// supported Xcode SDKs. Keep the primitive in C, where volatile stores make
// clearing process-local wallet secrets observable to the optimizer.
static inline void hns_wallet_secure_zero(void *bytes, size_t count) {
    volatile unsigned char *cursor = (volatile unsigned char *)bytes;
    while (count > 0) {
        *cursor = 0;
        cursor += 1;
        count -= 1;
    }
}

#endif
