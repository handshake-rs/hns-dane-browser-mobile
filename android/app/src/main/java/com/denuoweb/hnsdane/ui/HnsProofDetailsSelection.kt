package com.denuoweb.hnsdane.ui

import com.denuoweb.hnsdane.core.BrowserResolutionTrace
import com.denuoweb.hnsdane.core.BrowserResolvedNamespace

/**
 * Selects the proof-details mode from Rust's retained dual-root decision.
 *
 * Native-gateway routing is deliberately namespace-agnostic: every DNS host
 * uses that path until Rust has authenticated both roots and retained one.
 */
internal fun proofDetailsUsesIcann(traceJson: String?): Boolean =
    BrowserResolutionTrace.selectedNamespace(traceJson) == BrowserResolvedNamespace.Icann
