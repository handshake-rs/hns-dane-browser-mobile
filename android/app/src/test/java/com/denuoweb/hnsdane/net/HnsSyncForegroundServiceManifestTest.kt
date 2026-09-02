package com.denuoweb.hnsdane.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class HnsSyncForegroundServiceManifestTest {
    @Test
    fun manifestLimitsBackgroundWorkToVisibleReadOnlyWalletSync() {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(locateManifest())

        assertFalse(document.getElementsByTagName("uses-permission").hasAndroidName(POST_NOTIFICATIONS))
        assertTrue(document.getElementsByTagName("uses-permission").hasAndroidName(FOREGROUND_SERVICE))
        assertTrue(document.getElementsByTagName("uses-permission").hasAndroidName(FOREGROUND_SERVICE_DATA_SYNC))

        val service = document.getElementsByTagName("service")
            .elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == HNS_SYNC_SERVICE }

        assertNull(service)

        val walletSyncService = document.getElementsByTagName("service")
            .elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == WALLET_SYNC_SERVICE }

        assertNotNull(walletSyncService)
        assertEquals("false", walletSyncService?.getAttributeNS(ANDROID_NS, "exported"))
        assertEquals("dataSync", walletSyncService?.getAttributeNS(ANDROID_NS, "foregroundServiceType"))

        val settings = document.getElementsByTagName("activity")
            .elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == SETTINGS_ACTIVITY }

        assertNotNull(settings)
        assertEquals("false", settings?.getAttributeNS(ANDROID_NS, "exported"))

        val cookieSettings = document.getElementsByTagName("activity")
            .elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == COOKIE_SETTINGS_ACTIVITY }

        assertNotNull(cookieSettings)
        assertEquals("false", cookieSettings?.getAttributeNS(ANDROID_NS, "exported"))

        val legal = document.getElementsByTagName("activity")
            .elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == LEGAL_ACTIVITY }

        assertNotNull(legal)
        assertEquals("false", legal?.getAttributeNS(ANDROID_NS, "exported"))

        val thirdPartyNotices = document.getElementsByTagName("activity")
            .elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == THIRD_PARTY_NOTICES_ACTIVITY }

        assertNotNull(thirdPartyNotices)
        assertEquals("false", thirdPartyNotices?.getAttributeNS(ANDROID_NS, "exported"))

        val main = document.getElementsByTagName("activity")
            .elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == MAIN_ACTIVITY }

        assertNotNull(main)
        assertEquals("false", main?.getAttributeNS(ANDROID_NS, "exported"))

        val wallet = document.getElementsByTagName("activity")
            .elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == WALLET_ACTIVITY }

        assertNotNull(wallet)
        assertEquals("false", wallet?.getAttributeNS(ANDROID_NS, "exported"))
        assertEquals("true", wallet?.getAttributeNS(ANDROID_NS, "excludeFromRecents"))

        val launcher = document.getElementsByTagName("activity")
            .elements()
            .firstOrNull { it.getAttributeNS(ANDROID_NS, "name") == LAUNCHER_ACTIVITY }

        assertNotNull(launcher)
        assertEquals("true", launcher?.getAttributeNS(ANDROID_NS, "exported"))
        assertTrue(launcher.hasLauncherIntentFilter())
        assertTrue(launcher.hasDefaultBrowserViewFilter())
        assertTrue(launcher.hasAppBrowserIntentFilter())
    }

    private fun locateManifest(): File {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(workingDir) { it.parentFile }
            .flatMap { directory ->
                sequenceOf(
                    directory.resolve("src/main/AndroidManifest.xml"),
                    directory.resolve("app/src/main/AndroidManifest.xml"),
                    directory.resolve("android/app/src/main/AndroidManifest.xml"),
                )
            }
            .firstOrNull { it.isFile }
            ?: error("Unable to locate AndroidManifest.xml from $workingDir")
    }

    private fun NodeList.hasAndroidName(value: String): Boolean =
        elements().any { it.getAttributeNS(ANDROID_NS, "name") == value }

    private fun NodeList.elements(): Sequence<Element> =
        (0 until length).asSequence().mapNotNull { item(it) as? Element }

    private fun Element?.hasLauncherIntentFilter(): Boolean {
        val element = this ?: return false
        return element.getElementsByTagName("intent-filter")
            .elements()
            .any { filter ->
                filter.getElementsByTagName("action").hasAndroidName("android.intent.action.MAIN") &&
                    filter.getElementsByTagName("category").hasAndroidName("android.intent.category.LAUNCHER")
            }
    }

    private fun Element?.hasDefaultBrowserViewFilter(): Boolean {
        val element = this ?: return false
        return element.getElementsByTagName("intent-filter")
            .elements()
            .any { filter ->
                val categories = filter.getElementsByTagName("category")
                val schemes = filter.getElementsByTagName("data")
                    .elements()
                    .map { it.getAttributeNS(ANDROID_NS, "scheme") }
                    .toSet()
                filter.getElementsByTagName("action").hasAndroidName("android.intent.action.VIEW") &&
                    categories.hasAndroidName("android.intent.category.DEFAULT") &&
                    categories.hasAndroidName("android.intent.category.BROWSABLE") &&
                    schemes == setOf("http", "https")
            }
    }

    private fun Element?.hasAppBrowserIntentFilter(): Boolean {
        val element = this ?: return false
        return element.getElementsByTagName("intent-filter")
            .elements()
            .any { filter ->
                filter.getElementsByTagName("action").hasAndroidName("android.intent.action.MAIN") &&
                    filter.getElementsByTagName("category")
                        .hasAndroidName("android.intent.category.APP_BROWSER")
            }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val HNS_SYNC_SERVICE = ".net.HnsSyncForegroundService"
        const val WALLET_SYNC_SERVICE = ".wallet.WalletSyncForegroundService"
        const val SETTINGS_ACTIVITY = ".ui.SettingsActivity"
        const val COOKIE_SETTINGS_ACTIVITY = ".ui.CookieSettingsActivity"
        const val LEGAL_ACTIVITY = ".ui.LegalActivity"
        const val THIRD_PARTY_NOTICES_ACTIVITY = ".ui.ThirdPartyNoticesActivity"
        const val MAIN_ACTIVITY = ".ui.MainActivity"
        const val WALLET_ACTIVITY = ".ui.WalletActivity"
        const val LAUNCHER_ACTIVITY = ".ui.LauncherActivity"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val FOREGROUND_SERVICE = "android.permission.FOREGROUND_SERVICE"
        const val FOREGROUND_SERVICE_DATA_SYNC = "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
    }
}
