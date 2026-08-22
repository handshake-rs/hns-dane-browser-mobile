package com.denuoweb.hnsdane.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import com.denuoweb.hnsdane.HnsDaneApplication
import com.denuoweb.hnsdane.R
import com.denuoweb.hnsdane.BuildConfig
import com.denuoweb.hnsdane.net.NativeBridge
import com.denuoweb.hnsdane.net.ProcessHnsSyncSingleFlight
import org.json.JSONObject
import kotlin.concurrent.thread

class SettingsActivity : ComponentActivity() {
    private lateinit var homepageStatus: TextView
    private lateinit var cookieStatus: TextView
    private lateinit var hnsNetworkStatus: TextView
    private lateinit var statelessDaneStatus: TextView
    private lateinit var hnsDohRecoveryStatus: TextView
    private lateinit var experimentalP2pRelayStatus: TextView
    private lateinit var staticRelayPeerStatus: TextView
    private lateinit var resolverCacheStatus: TextView
    private lateinit var historyStatus: TextView
    private lateinit var downloadStatus: TextView
    private lateinit var themeStatus: TextView
    private var resolverCacheClearInProgress = false
    private var staticRelayPeerAddInProgress = false

    private val destination: SettingsDestination
        get() = SettingsDestination.fromId(
            intent.getStringExtra(EXTRA_DESTINATION) ?: DESTINATION_BROWSER,
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        homepageStatus = preferenceSummary(BrowserPreferences.homepage(this))
        cookieStatus = preferenceSummary(cookieSummary())
        hnsNetworkStatus = preferenceSummary(hnsNetworkText())
        statelessDaneStatus = preferenceSummary(statelessDaneText())
        hnsDohRecoveryStatus = preferenceSummary(hnsDohRecoveryText())
        experimentalP2pRelayStatus = preferenceSummary(experimentalP2pRelayText())
        staticRelayPeerStatus = preferenceSummary(getString(R.string.settings_static_relay_peer_summary))
        resolverCacheStatus = preferenceSummary(getString(R.string.settings_resolver_cache_ready))
        historyStatus = preferenceSummary(historySummary())
        downloadStatus = preferenceSummary(downloadSummary())
        themeStatus = preferenceSummary(themeText())

        when (destination) {
            SettingsDestination.Browser -> showBrowserSettings()
            SettingsDestination.Homepage -> showHomepageSettings()
            SettingsDestination.Privacy -> showPrivacySettings()
            SettingsDestination.Handshake -> showHandshakeSettings()
            SettingsDestination.HandshakeAdvanced -> showHandshakeAdvancedSettings()
            SettingsDestination.Advanced -> showAdvancedSettings()
            SettingsDestination.About -> showAboutSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::homepageStatus.isInitialized) {
            refreshHomepageStatus()
            refreshCookieStatus()
            refreshHnsNetworkStatus()
            refreshStatelessDaneStatus()
            refreshHnsDohRecoveryStatus()
            refreshExperimentalP2pRelayStatus()
            refreshHistoryStatus()
            refreshDownloadStatus()
            refreshThemeStatus()
        }
    }

    private fun showBrowserSettings() {
        setSettingsScreen(getString(R.string.settings_destination_browser)) {
            addView(settingsGroup(getString(R.string.section_start_page)) {
                addSettingsRow(navRow(getString(R.string.row_homepage), homepageStatus) {
                    openDestination(SettingsDestination.Homepage)
                })
            })
            addView(settingsGroup(getString(R.string.section_appearance)) {
                addSettingsRow(navRow(getString(R.string.row_theme), themeStatus) { showThemeDialog() })
                addSettingsRow(navRow(
                    getString(R.string.row_app_language),
                    getString(R.string.settings_language_system_default),
                ) { openAppLanguageSettings() })
            })
        }
    }

    private fun showHomepageSettings() {
        setSettingsScreen(getString(R.string.row_homepage)) {
            addView(settingsGroup(getString(R.string.settings_current_homepage)) {
                addSettingsRow(valueRow(getString(R.string.row_homepage), BrowserPreferences.homepage(this@SettingsActivity)))
            })
            currentUrlFromIntent()?.let { currentUrl ->
                addView(settingsGroup {
                    addSettingsRow(actionRow(getString(R.string.settings_use_current_page)) {
                        useCurrentPageAsHomepage(currentUrl)
                    })
                })
            }
            addView(settingsGroup {
                addSettingsRow(actionRow(getString(R.string.settings_change_homepage)) { showEditHomepageDialog() })
            })
            addView(settingsGroup(getString(R.string.settings_reset)) {
                addSettingsRow(actionRow(
                    title = getString(R.string.row_reset_homepage),
                    summary = getString(R.string.row_reset_homepage_summary),
                    destructive = true,
                ) { confirmResetHomepage() })
            })
        }
    }

    private fun showPrivacySettings() {
        setSettingsScreen(getString(R.string.section_privacy_and_data)) {
            addView(settingsGroup {
                addSettingsRow(navRow(getString(R.string.row_cookies), cookieStatus) {
                    startActivity(Intent(this@SettingsActivity, CookieSettingsActivity::class.java))
                })
                addSettingsRow(navRow(getString(R.string.row_history), historyStatus) {
                    startActivity(Intent(this@SettingsActivity, HistoryActivity::class.java))
                })
                addSettingsRow(navRow(getString(R.string.row_downloads), downloadStatus) {
                    startActivity(Intent(this@SettingsActivity, DownloadsActivity::class.java))
                })
            })
            addView(settingsGroup(getString(R.string.settings_clear_data)) {
                addSettingsRow(actionRow(
                    title = getString(R.string.settings_clear_browsing_data),
                    summary = getString(R.string.settings_clear_browsing_data_summary),
                    destructive = true,
                ) { confirmClearBrowsingData() })
            })
        }
    }

    private fun showHandshakeSettings() {
        val network = HnsResolutionPreferences.handshakeNetwork(this)
        setSettingsScreen(getString(R.string.settings_destination_handshake)) {
            addView(statusCard(
                label = getString(R.string.settings_handshake_status_label, network.displayName(this@SettingsActivity)),
                detail = preferenceSummary(getString(R.string.settings_handshake_status_detail)),
            ))
            addView(settingsGroup(getString(R.string.settings_connection)) {
                addSettingsRow(navRow(getString(R.string.row_handshake_network), hnsNetworkStatus) { showNetworkDialog() })
                addSettingsRow(navRow(getString(R.string.settings_synchronization), getString(R.string.settings_sync_summary)) {
                    startActivity(Intent(this@SettingsActivity, HnsSyncActivity::class.java))
                })
            })
            addView(settingsGroup(getString(R.string.settings_security)) {
                addSettingsRow(toggleRow(
                    title = getString(R.string.settings_dane),
                    summary = statelessDaneText(),
                    checked = HnsResolutionPreferences.statelessDaneCertificates(this@SettingsActivity),
                ) { checked ->
                    HnsResolutionPreferences.setStatelessDaneCertificates(this@SettingsActivity, checked)
                    refreshStatelessDaneStatus()
                })
                addSettingsRow(navRow(getString(R.string.settings_recovery_dns), hnsDohRecoveryStatus) {
                    showHnsDohRecoveryDialog()
                })
            })
            addView(settingsGroup(getString(R.string.settings_advanced_networking)) {
                addSettingsRow(toggleRow(
                    title = getString(R.string.settings_p2p_dns_relay),
                    summary = experimentalP2pRelayText(),
                    checked = HnsResolutionPreferences.experimentalP2pDnsRelay(this@SettingsActivity),
                ) { checked ->
                    HnsResolutionPreferences.setExperimentalP2pDnsRelay(this@SettingsActivity, checked)
                    refreshExperimentalP2pRelayStatus()
                })
                addSettingsRow(navRow(getString(R.string.settings_relay_peers), staticRelayPeerStatus) {
                    showAddStaticRelayPeerDialog()
                })
                addSettingsRow(navRow(getString(R.string.settings_handshake_advanced), getString(R.string.settings_resolver_cache_ready)) {
                    openDestination(SettingsDestination.HandshakeAdvanced)
                })
            })
        }
    }

    private fun showHandshakeAdvancedSettings() {
        setSettingsScreen(getString(R.string.settings_handshake_advanced)) {
            addView(settingsGroup(getString(R.string.settings_resolver_cache)) {
                addSettingsRow(valueRow(getString(R.string.settings_resolver_cache), resolverCacheStatus.text.toString()))
                addSettingsRow(actionRow(
                    title = getString(R.string.row_clear_resolver_cache),
                    summary = getString(R.string.settings_resolver_cache_detail),
                    destructive = true,
                ) { confirmClearResolverCache() })
            })
        }
    }

    private fun showAdvancedSettings() {
        setSettingsScreen(getString(R.string.settings_destination_advanced)) {
            addView(settingsGroup(getString(R.string.settings_handshake_tools)) {
                addSettingsRow(navRow(getString(R.string.settings_domain_checker), getString(R.string.row_hns_domain_setup_summary)) {
                    startActivity(Intent(this@SettingsActivity, HnsDomainWizardActivity::class.java))
                })
                addSettingsRow(navRow(getString(R.string.settings_connection_details), getString(R.string.settings_connection_details_summary)) {
                    startActivity(Intent(this@SettingsActivity, HnsResolverTraceActivity::class.java))
                })
            })
            addView(settingsGroup(getString(R.string.settings_app_diagnostics)) {
                addSettingsRow(navRow(getString(R.string.row_diagnostics), getString(R.string.row_diagnostics_summary)) {
                    startActivity(Intent(this@SettingsActivity, DiagnosticsActivity::class.java))
                })
                addSettingsRow(navRow(getString(R.string.settings_gateway_log), getString(R.string.row_gateway_summary)) {
                    startActivity(Intent(this@SettingsActivity, GatewayActivity::class.java))
                })
            })
        }
    }

    private fun showAboutSettings() {
        setSettingsScreen(getString(R.string.settings_destination_about)) {
            addView(settingsGroup(getString(R.string.app_name)) {
                addSettingsRow(valueRow(getString(R.string.settings_version), buildLabel()))
                addSettingsRow(navRow(getString(R.string.row_privacy_policy), BrowserAppInfo.PRIVACY_POLICY_URL) {
                    openLink(
                        Uri.parse(BrowserAppInfo.PRIVACY_POLICY_URL),
                        getString(R.string.legal_copy_privacy_policy_url),
                        BrowserAppInfo.PRIVACY_POLICY_URL,
                    )
                })
                addSettingsRow(navRow(getString(R.string.row_legal), getString(R.string.row_legal_summary)) {
                    startActivity(Intent(this@SettingsActivity, LegalActivity::class.java))
                })
                addSettingsRow(navRow(getString(R.string.settings_open_source_licenses), getString(R.string.row_third_party_notices_summary)) {
                    startActivity(Intent(this@SettingsActivity, ThirdPartyNoticesActivity::class.java))
                })
                addSettingsRow(navRow(getString(R.string.row_source_code), BrowserAppInfo.SOURCE_CODE_URL) {
                    openLink(
                        Uri.parse(BrowserAppInfo.SOURCE_CODE_URL),
                        getString(R.string.legal_copy_source_code_url),
                        BrowserAppInfo.SOURCE_CODE_URL,
                    )
                })
            })
            addView(settingsGroup(getString(R.string.settings_support)) {
                addSettingsRow(navRow(getString(R.string.settings_support_shakescape), getString(R.string.row_donate_hns_summary)) {
                    openLink(
                        Uri.parse(BrowserAppInfo.HNS_DONATION_URI),
                        getString(R.string.legal_copy_hns_donation_address),
                        BrowserAppInfo.HNS_DONATION_ADDRESS,
                    )
                })
            })
        }
    }

    private fun openDestination(destination: SettingsDestination) {
        startActivity(Intent(this, SettingsActivity::class.java).apply {
            putExtra(EXTRA_DESTINATION, destination.id)
            currentUrlFromIntent()?.let { putExtra(EXTRA_CURRENT_URL, it) }
        })
    }

    private fun openLink(uri: Uri, copyLabel: String, copyText: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText(copyLabel, copyText))
            Toast.makeText(this, getString(R.string.common_copied_label, copyLabel), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmClearBrowsingData() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_browsing_data)
            .setMessage(R.string.settings_clear_browsing_data_confirmation)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ -> clearBrowsingData() }
            .show()
    }

    private fun clearBrowsingData() {
        BrowserHistoryStore.clear(this)
        BrowserDownloadStore.clear(this)
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            CookieManager.getInstance().flush()
            runOnUiThread {
                refreshCookieStatus()
                refreshHistoryStatus()
                refreshDownloadStatus()
                Toast.makeText(
                    this,
                    R.string.settings_browsing_data_cleared,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun openAppLanguageSettings() {
        val packageUri = Uri.fromParts("package", packageName, null)
        val languageSettings = Intent(ACTION_APP_LOCALE_SETTINGS).setData(packageUri)
        try {
            startActivity(languageSettings)
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to app details on Android builds without a direct app-language panel.
        }

        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.settings_open_language_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEditHomepageDialog() {
        val input = EditText(this).apply {
            setText(BrowserPreferences.homepage(this@SettingsActivity))
            setSingleLine(true)
            setSelection(0, text.length)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_homepage_edit_title)
            .setMessage(R.string.settings_homepage_edit_message)
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val saved = BrowserPreferences.setHomepage(this, input.text.toString(), NativeBridge)
                if (saved == null) {
                    input.error = getString(R.string.settings_homepage_error)
                    return@setOnClickListener
                }
                refreshHomepageStatus()
                Toast.makeText(this, getString(R.string.settings_homepage_saved), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showAddStaticRelayPeerDialog() {
        if (staticRelayPeerAddInProgress) {
            Toast.makeText(this, getString(R.string.settings_static_relay_peer_in_progress), Toast.LENGTH_SHORT).show()
            return
        }
        val network = HnsResolutionPreferences.handshakeNetwork(this)
        val networkName = network.displayName(this)
        val input = EditText(this).apply {
            hint = getString(
                R.string.settings_static_relay_peer_hint,
                defaultPeerPort(network),
                defaultPeerPort(network),
            )
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_static_relay_peer_title)
            .setMessage(getString(R.string.settings_static_relay_peer_message, networkName))
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_add, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val endpoint = HnsResolutionPreferences.normalizeStaticRelayPeerEndpoint(
                    input.text.toString(),
                )
                if (endpoint == null) {
                    input.error = getString(R.string.settings_static_relay_peer_error)
                    return@setOnClickListener
                }
                staticRelayPeerAddInProgress = true
                input.isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
                staticRelayPeerStatus.text = getString(R.string.settings_static_relay_peer_verifying, networkName)
                val dataDir = filesDir.absolutePath
                thread(name = "hns-static-relay-peer-add") {
                    val result = ProcessHnsSyncSingleFlight.tryRun {
                        NativeBridge.addStaticRelayPeer(dataDir, network.id, endpoint)
                    }
                    val status = result?.let {
                        runCatching { JSONObject(it).optString("status") }.getOrDefault("")
                    }
                    runOnUiThread {
                        staticRelayPeerAddInProgress = false
                        if (isDestroyed) {
                            return@runOnUiThread
                        }
                        if (status == "peer_added") {
                            staticRelayPeerStatus.text = getString(
                                R.string.settings_static_relay_peer_saved_status,
                                networkName,
                            )
                            Toast.makeText(
                                this,
                                getString(R.string.settings_static_relay_peer_saved, networkName),
                                Toast.LENGTH_SHORT,
                            ).show()
                            dialog.dismiss()
                            return@runOnUiThread
                        }

                        staticRelayPeerStatus.text = getString(R.string.settings_static_relay_peer_summary)
                        input.isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        input.error = if (status == null) {
                            getString(R.string.sync_already_running)
                        } else {
                            getString(R.string.settings_static_relay_peer_verify_failed)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showHnsDohRecoveryDialog() {
        val relayEnabled = HnsResolutionPreferences.experimentalP2pDnsRelay(this)
        val input = EditText(this).apply {
            setText(HnsResolutionPreferences.dohResolverUrl(this@SettingsActivity))
            hint = getString(R.string.settings_hns_doh_recovery_example)
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setSingleLine(true)
            setSelection(0, text.length)
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.settings_hns_doh_recovery_title)
            .setMessage(
                if (relayEnabled) {
                    R.string.settings_hns_doh_recovery_message_relay_enabled
                } else {
                    R.string.settings_hns_doh_recovery_message_relay_off
                },
            )
            .setView(input)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
        if (!relayEnabled) {
            builder.setNeutralButton(R.string.settings_enable_p2p_requester) { _, _ ->
                HnsResolutionPreferences.setExperimentalP2pDnsRelay(this, true)
                refreshExperimentalP2pRelayStatus()
                Toast.makeText(
                    this,
                    R.string.settings_p2p_requester_enabled,
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val saved = HnsResolutionPreferences.setHnsDohRecoveryUrl(
                    this,
                    input.text.toString(),
                )
                if (saved == null) {
                    input.error = getString(R.string.settings_hns_doh_recovery_error)
                    return@setOnClickListener
                }
                refreshHnsDohRecoveryStatus()
                Toast.makeText(
                    this,
                    if (saved.isEmpty()) {
                        R.string.settings_hns_doh_recovery_disabled
                    } else {
                        R.string.settings_hns_doh_recovery_saved
                    },
                    Toast.LENGTH_SHORT,
                ).show()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun showNetworkDialog() {
        val networks = HandshakeNetwork.entries.toTypedArray()
        val labels = networks
            .map { getString(R.string.settings_network_choice, it.displayName(this), it.summary(this)) }
            .toTypedArray()
        val current = HnsResolutionPreferences.handshakeNetwork(this)
        val selectedIndex = networks.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.row_handshake_network)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, index ->
                val selected = networks[index]
                if (selected != current) {
                    HnsResolutionPreferences.setHandshakeNetwork(this, selected)
                    (application as? HnsDaneApplication)?.onHandshakeNetworkChanged()
                    refreshHnsNetworkStatus()
                    staticRelayPeerStatus.text = getString(R.string.settings_static_relay_peer_summary)
                    val selectedName = selected.displayName(this)
                    resolverCacheStatus.text = getString(R.string.settings_resolver_cache_ready_network, selectedName)
                    Toast.makeText(this, getString(R.string.settings_network_set, selectedName), Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showThemeDialog() {
        val modes = BrowserThemeMode.entries.toTypedArray()
        val labels = modes
            .map { themeChoiceText(it) }
            .toTypedArray()
        val current = BrowserThemePreferences.themeMode(this)
        val selectedIndex = modes.indexOf(current).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme_dialog_title)
            .setSingleChoiceItems(labels, selectedIndex) { dialog, index ->
                val selected = modes[index]
                if (selected != current) {
                    BrowserThemePreferences.setThemeMode(this, selected)
                    refreshThemeStatus()
                    Toast.makeText(
                        this,
                        getString(R.string.settings_theme_set, themeChoiceText(selected)),
                        Toast.LENGTH_SHORT,
                    ).show()
                    BrowserThemePreferences.applyTo(this)
                    recreate()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun useCurrentPageAsHomepage(currentUrl: String) {
        val saved = BrowserPreferences.setHomepage(this, currentUrl, NativeBridge)
        if (saved == null) {
            Toast.makeText(this, getString(R.string.settings_homepage_current_unsupported), Toast.LENGTH_SHORT).show()
            return
        }
        refreshHomepageStatus()
        Toast.makeText(this, getString(R.string.settings_homepage_saved), Toast.LENGTH_SHORT).show()
    }

    private fun confirmResetHomepage() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_homepage_reset_title)
            .setMessage(R.string.settings_homepage_reset_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_reset) { _, _ ->
                BrowserPreferences.resetHomepage(this)
                refreshHomepageStatus()
                Toast.makeText(this, getString(R.string.settings_homepage_reset), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun confirmClearResolverCache() {
        val network = HnsResolutionPreferences.handshakeNetwork(this)
        val networkName = network.displayName(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_resolver_cache_clear_title)
            .setMessage(getString(R.string.settings_resolver_cache_clear_message, networkName))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                clearResolverCache()
            }
            .show()
    }

    private fun clearResolverCache() {
        if (resolverCacheClearInProgress) {
            Toast.makeText(this, getString(R.string.sync_already_running), Toast.LENGTH_SHORT).show()
            return
        }
        val network = HnsResolutionPreferences.handshakeNetwork(this)
        val networkName = network.displayName(this)
        val dataDir = filesDir.absolutePath
        resolverCacheClearInProgress = true
        resolverCacheStatus.text = getString(R.string.common_running)
        thread(name = "hns-resolver-cache-clear") {
            val result = ProcessHnsSyncSingleFlight.tryRun {
                NativeBridge.clearResolverCache(dataDir, network.id)
            }
            val status = result?.let { runCatching { JSONObject(it).optString("status") }.getOrDefault("") }
            runOnUiThread {
                resolverCacheClearInProgress = false
                if (isDestroyed) {
                    return@runOnUiThread
                }
                if (status == null) {
                    resolverCacheStatus.text = getString(R.string.settings_resolver_cache_ready)
                    Toast.makeText(this, getString(R.string.sync_already_running), Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val message = if (status == "cleared") {
                    getString(R.string.settings_resolver_cache_cleared, networkName)
                } else {
                    getString(R.string.settings_resolver_cache_clear_failed)
                }
                resolverCacheStatus.text = if (status == "cleared") {
                    getString(R.string.settings_resolver_cache_cleared_status, networkName)
                } else {
                    getString(R.string.settings_resolver_cache_clear_failed_status)
                }
                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshHomepageStatus() {
        homepageStatus.text = BrowserPreferences.homepage(this)
    }

    private fun refreshCookieStatus() {
        cookieStatus.text = cookieSummary()
    }

    private fun refreshHnsNetworkStatus() {
        hnsNetworkStatus.text = hnsNetworkText()
    }

    private fun refreshStatelessDaneStatus() {
        statelessDaneStatus.text = statelessDaneText()
    }

    private fun refreshHnsDohRecoveryStatus() {
        hnsDohRecoveryStatus.text = hnsDohRecoveryText()
    }

    private fun refreshExperimentalP2pRelayStatus() {
        experimentalP2pRelayStatus.text = experimentalP2pRelayText()
    }

    private fun refreshHistoryStatus() {
        historyStatus.text = historySummary()
    }

    private fun refreshDownloadStatus() {
        downloadStatus.text = downloadSummary()
    }

    private fun refreshThemeStatus() {
        themeStatus.text = themeText()
    }

    private fun hnsNetworkText(): String {
        val network = HnsResolutionPreferences.handshakeNetwork(this)
        return getString(R.string.settings_hns_network_summary, network.displayName(this), network.summary(this))
    }

    private fun statelessDaneText(): String =
        if (HnsResolutionPreferences.statelessDaneCertificates(this)) {
            getString(R.string.settings_stateless_dane_on)
        } else {
            getString(R.string.settings_stateless_dane_off)
        }

    private fun experimentalP2pRelayText(): String =
        if (HnsResolutionPreferences.experimentalP2pDnsRelay(this)) {
            getString(R.string.settings_experimental_p2p_dns_relay_on)
        } else {
            getString(R.string.settings_experimental_p2p_dns_relay_off)
        }

    private fun hnsDohRecoveryText(): String =
        HnsResolutionPreferences.dohResolverUrl(this).let { endpoint ->
            if (endpoint.isEmpty()) {
                getString(R.string.settings_hns_doh_recovery_off)
            } else {
                getString(R.string.settings_hns_doh_recovery_on, endpoint)
            }
        }

    private fun defaultPeerPort(network: HandshakeNetwork): Int = when (network) {
        HandshakeNetwork.Mainnet -> 12_038
        HandshakeNetwork.Testnet -> 13_038
        HandshakeNetwork.Regtest -> 14_038
    }

    private fun cookieSummary(): String =
        if (BrowserCookiePreferences.blockThirdPartyCookies(this)) {
            getString(R.string.settings_cookie_summary_blocking)
        } else {
            getString(R.string.cookie_summary_allowing_all)
        }

    private fun historySummary(): String {
        val count = BrowserHistoryStore.entries(this).size
        return resources.getQuantityString(R.plurals.settings_saved_pages, count, count)
    }

    private fun downloadSummary(): String {
        val count = BrowserDownloadStore.records(this).size
        return resources.getQuantityString(R.plurals.settings_app_queued_records, count, count)
    }

    private fun themeText(): String =
        when (BrowserThemePreferences.themeMode(this)) {
            BrowserThemeMode.System -> getString(R.string.row_theme_summary_system)
            BrowserThemeMode.Light -> getString(R.string.row_theme_summary_light)
            BrowserThemeMode.Dark -> getString(R.string.row_theme_summary_dark)
        }

    private fun themeChoiceText(mode: BrowserThemeMode): String =
        when (mode) {
            BrowserThemeMode.System -> getString(R.string.theme_choice_system)
            BrowserThemeMode.Light -> getString(R.string.theme_choice_light)
            BrowserThemeMode.Dark -> getString(R.string.theme_choice_dark)
        }

    private fun currentUrlFromIntent(): String? =
        intent.getStringExtra(EXTRA_CURRENT_URL)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun buildLabel(): String {
        val channel = if (BuildConfig.DEBUG) {
            getString(R.string.common_debug_demo)
        } else {
            getString(R.string.common_release)
        }
        return getString(R.string.common_build_label, channel, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    companion object {
        const val EXTRA_CURRENT_URL = "com.denuoweb.hnsdane.CURRENT_URL"
        const val EXTRA_DESTINATION = "com.denuoweb.hnsdane.SETTINGS_DESTINATION"
        const val DESTINATION_BROWSER = "browser"
        const val DESTINATION_PRIVACY = "privacy"
        const val DESTINATION_HANDSHAKE = "handshake"
        const val DESTINATION_ADVANCED = "advanced"
        const val DESTINATION_ABOUT = "about"
        private const val ACTION_APP_LOCALE_SETTINGS = "android.settings.APP_LOCALE_SETTINGS"
    }

    private enum class SettingsDestination(val id: String) {
        Browser(DESTINATION_BROWSER),
        Homepage("homepage"),
        Privacy(DESTINATION_PRIVACY),
        Handshake(DESTINATION_HANDSHAKE),
        HandshakeAdvanced("handshake_advanced"),
        Advanced(DESTINATION_ADVANCED),
        About(DESTINATION_ABOUT),
        ;

        companion object {
            fun fromId(id: String?): SettingsDestination =
                entries.firstOrNull { it.id == id } ?: Browser
        }
    }
}
