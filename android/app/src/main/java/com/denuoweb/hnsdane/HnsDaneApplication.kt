package com.denuoweb.hnsdane

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.denuoweb.hnsdane.net.BrowserProxyCoordinator
import com.denuoweb.hnsdane.net.BrowserProxyLifecycleWorker
import com.denuoweb.hnsdane.net.BundledHeaderSyncBridge
import com.denuoweb.hnsdane.net.HnsProxyController
import com.denuoweb.hnsdane.net.HnsSyncScheduler
import com.denuoweb.hnsdane.net.HnsSyncProgress
import com.denuoweb.hnsdane.net.HnsSyncSnapshot
import com.denuoweb.hnsdane.net.LocalBrowserProxyFactory
import com.denuoweb.hnsdane.net.RustBrowserProxy
import com.denuoweb.hnsdane.ui.BrowserThemePreferences
import com.denuoweb.hnsdane.ui.HnsResolutionPreferences
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class HnsDaneApplication : Application() {
    private val webViewStartupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hns-webview-startup")
    }
    private val syncListeners = CopyOnWriteArraySet<(HnsSyncSnapshot) -> Unit>()
    // This is presentation-only. It records that a foreground header-sync
    // pass has acquired its single-flight slot, before Rust can publish its
    // `syncInFlight` mailbox. It is never consulted for proxy admission.
    private val syncPreparationListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val proxyAvailabilityListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val foregroundActivities = ForegroundActivityCounter(
        onForeground = ::startForegroundSync,
        onBackground = ::stopForegroundSync,
    )
    @Volatile
    private var foregroundSync: HnsSyncScheduler? = null
    @Volatile
    private var foregroundSyncPreparing: Boolean = false

    /**
     * True while at least one app Activity is visible (including a transition
     * between two in-app screens). Wallet-owned work uses this to distinguish
     * an in-app navigation from the application actually moving background.
     */
    internal val isAppForeground: Boolean
        get() = foregroundActivities.isForeground

    @Volatile
    private var latestSyncSnapshot: HnsSyncSnapshot? = null
    @Volatile
    private var proxyAvailable: Boolean = false

    @Volatile
    internal var isHeaderRecoveryInProgress: Boolean = false
        private set

    @Volatile
    internal var headerResetGeneration: Long = 0L
        private set

    @Volatile
    private var headerResetMutationInFlight: Boolean = false

    internal lateinit var browserProxyCoordinator: BrowserProxyCoordinator
        private set

    override fun onCreate() {
        super.onCreate()
        HnsResolutionPreferences.migrateProhibitedHnsFallbackSettings(this)
        browserProxyCoordinator = BrowserProxyCoordinator(
            overrideController = HnsProxyController(this),
            proxyFactory = LocalBrowserProxyFactory(RustBrowserProxy::start),
            workerExecutor = BrowserProxyLifecycleWorker,
            callbackExecutor = ContextCompat.getMainExecutor(this),
            onAvailabilityChanged = { available ->
                proxyAvailable = available
                proxyAvailabilityListeners.forEach { listener -> listener(available) }
            },
        )
        // The process owns both the WebView override and its native backend.
        // Activity backgrounding must not clear one while stopping the other.
        browserProxyCoordinator.resume(null)
        registerActivityLifecycleCallbacks(AppLifecycleCallbacks(foregroundActivities))
        startWebViewInitialization()
    }

    internal fun observeSync(listener: (HnsSyncSnapshot) -> Unit): Closeable {
        syncListeners += listener
        latestSyncSnapshot?.let(listener)
        return Closeable { syncListeners -= listener }
    }

    /**
     * Observes the small local interval between foreground header work
     * acquiring its single-flight slot and Rust publishing factual in-flight
     * status. Consumers must use
     * this only for copy/progress presentation; authentication state still
     * comes exclusively from [observeSync].
     */
    internal fun observeSyncPreparation(listener: (Boolean) -> Unit): Closeable {
        syncPreparationListeners += listener
        listener(foregroundSyncPreparing)
        return Closeable { syncPreparationListeners -= listener }
    }

    internal fun observeProxyAvailability(listener: (Boolean) -> Unit): Closeable {
        proxyAvailabilityListeners += listener
        listener(proxyAvailable)
        return Closeable { proxyAvailabilityListeners -= listener }
    }

    internal fun onHandshakeNetworkChanged() {
        browserProxyCoordinator.ensure(null)
        restartForegroundSync()
    }

    /** Revokes the authority-bound proxy before native header storage is replaced. */
    @Synchronized
    internal fun beginHeadersReset() {
        headerResetMutationInFlight = true
        isHeaderRecoveryInProgress = true
        headerResetGeneration = if (headerResetGeneration == Long.MAX_VALUE) {
            1L
        } else {
            headerResetGeneration + 1L
        }
        browserProxyCoordinator.ensure(null)
        stopForegroundSync()
        latestSyncSnapshot = null
    }

    /** Immediately starts peer recovery after a reset attempt releases native maintenance. */
    @Synchronized
    internal fun finishHeadersReset() {
        headerResetMutationInFlight = false
        restartForegroundSync()
    }

    private fun restartForegroundSync() {
        stopForegroundSync()
        latestSyncSnapshot = null
        if (foregroundActivities.isForeground) {
            startForegroundSync()
        }
    }

    @Synchronized
    private fun startForegroundSync() {
        if (headerResetMutationInFlight || foregroundSync != null) {
            return
        }

        val scheduler = HnsSyncScheduler(
            filesDir,
            bridge = BundledHeaderSyncBridge(this),
            network = { HnsResolutionPreferences.handshakeNetworkId(this) },
        )
        foregroundSync = scheduler
        scheduler.start(
            onSnapshot = sync@{ snapshot ->
                val progress = HnsSyncProgress.fromJson(snapshot.statusJson)
                Log.i(
                    TAG,
                    "HNS sync status=${progress.status} best=${progress.bestHeight} " +
                        "target=${progress.effectiveTargetHeight} freshness=${progress.freshness} " +
                        "targetGroups=${progress.targetPeerGroups} attempted=${progress.attempted} " +
                        "successful=${progress.successful} accepted=${progress.accepted} " +
                        "failed=${progress.failed}",
                )
                val publish = synchronized(this) {
                    if (foregroundSync !== scheduler) {
                        false
                    } else {
                        latestSyncSnapshot = snapshot
                        if (progress.isAuthorityReady) {
                            isHeaderRecoveryInProgress = false
                        }
                        true
                    }
                }
                if (!publish) {
                    return@sync
                }
                publishForegroundSyncPreparation(false)
                syncListeners.forEach { listener -> listener(snapshot) }
            },
            onSyncStarting = {
                publishForegroundSyncPreparation(true, scheduler)
            },
        )
    }

    @Synchronized
    private fun stopForegroundSync() {
        val scheduler = foregroundSync ?: return
        foregroundSync = null
        scheduler.close()
        publishForegroundSyncPreparation(false)
    }

    private fun publishForegroundSyncPreparation(
        preparing: Boolean,
        expectedScheduler: HnsSyncScheduler? = null,
    ) {
        val listeners = synchronized(this) {
            if (expectedScheduler != null && foregroundSync !== expectedScheduler) {
                return
            }
            if (foregroundSyncPreparing == preparing) {
                return
            }
            foregroundSyncPreparing = preparing
            syncPreparationListeners.toList()
        }
        listeners.forEach { listener -> listener(preparing) }
    }

    private fun startWebViewInitialization() {
        val startupConfig = WebViewStartUpConfig.Builder(webViewStartupExecutor).build()
        runCatching {
            WebViewCompat.startUpWebView(
                this,
                startupConfig,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        logStartupAudit(result)
                    }

                    override fun onError(error: WebViewStartupException) {
                        Log.w(TAG, "WebView asynchronous startup failed", error)
                    }
                },
            )
        }.onFailure { error ->
            Log.w(TAG, "WebView asynchronous startup is unavailable", error)
        }
    }

    private fun logStartupAudit(result: WebViewStartUpResult) {
        if (!BuildConfig.DEBUG) {
            return
        }

        result.uiThreadBlockingStartUpLocations.orEmpty().forEach { location ->
            Log.d(TAG, "WebView startup blocked the UI thread", location.stackInformation)
        }
        result.nonUiThreadBlockingStartUpLocations.orEmpty().forEach { location ->
            Log.d(TAG, "WebView startup waited on a background thread", location.stackInformation)
        }
    }

    private companion object {
        const val TAG = "HnsDaneApplication"
    }
}

private class AppLifecycleCallbacks(
    private val foregroundActivities: ForegroundActivityCounter,
) : Application.ActivityLifecycleCallbacks {
    override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
        BrowserThemePreferences.applyTo(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) {
        foregroundActivities.activityStarted()
    }
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) {
        foregroundActivities.activityStopped(activity.isChangingConfigurations)
    }
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

internal class ForegroundActivityCounter(
    private val onForeground: () -> Unit,
    private val onBackground: () -> Unit,
) {
    private var startedActivities = 0
    private var changingConfigurations = 0

    val isForeground: Boolean
        get() = startedActivities > 0 || changingConfigurations > 0

    fun activityStarted() {
        val wasBackgrounded = startedActivities == 0
        startedActivities += 1
        if (changingConfigurations > 0) {
            changingConfigurations -= 1
        } else if (wasBackgrounded) {
            onForeground()
        }
    }

    fun activityStopped(isChangingConfigurations: Boolean) {
        check(startedActivities > 0) { "activity stop received without a matching start" }
        startedActivities -= 1
        if (isChangingConfigurations) {
            changingConfigurations += 1
        } else if (startedActivities == 0 && changingConfigurations == 0) {
            onBackground()
        }
    }
}
