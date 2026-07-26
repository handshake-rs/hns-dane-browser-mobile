package com.denuoweb.hnsdane.core

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUrlClassifierTest {
    private val classifier = BrowserUrlClassifier(TEST_BROWSER_NAMESPACE_POLICY)

    @Test
    fun singleLabelUsesDualRootGateway() {
        val target = classifier.classify("welcome")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://welcome/", target.url)
        assertEquals("welcome", target.displayHost)
    }

    @Test
    fun hnsPathPreservesSuffix() {
        val target = classifier.classify("welcome/path?q=1#top")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://welcome/path?q=1#top", target.url)
    }

    @Test
    fun dottedHostDoesNotUseItsRightmostLabelAsNamespaceAuthority() {
        val target = classifier.classify("welcome.2d/path?q=1")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://welcome.2d/path?q=1", target.url)
        assertEquals("welcome.2d", target.displayHost)
    }

    @Test
    fun explicitHnsHttpUrlUsesHnsMode() {
        val target = classifier.classify("http://welcome/path")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("http://welcome/path", target.url)
        assertEquals("welcome", target.displayHost)
    }

    @Test
    fun explicitHnsHttpsUrlUsesHnsModeForFailClosedUi() {
        val target = classifier.classify("https://welcome/path")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://welcome/path", target.url)
        assertEquals("welcome", target.displayHost)
    }

    @Test
    fun emojiHnsNameDefaultsToPunycodeHnsHttpsGateway() {
        val target = classifier.classify("🤝")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://xn--5p9h/", target.url)
        assertEquals("xn--5p9h", target.displayHost)
    }

    @Test
    fun explicitEmojiHnsUrlUsesPunycodeHnsMode() {
        val target = classifier.classify("https://🤝")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://xn--5p9h", target.url)
        assertEquals("xn--5p9h", target.displayHost)
    }

    @Test
    fun dottedHostUsesDualRootGateway() {
        val target = classifier.classify("example.com")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://example.com/", target.url)
    }

    @Test
    fun icannDaneHostUsesDualRootGateway() {
        val target = classifier.classify("tlsa.example.com")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://tlsa.example.com/", target.url)
        assertEquals("tlsa.example.com", target.displayHost)
    }

    @Test
    fun discordGgUsesNormalWebMode() {
        val target = classifier.classify("discord.gg")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://discord.gg/", target.url)
        assertEquals("discord.gg", target.displayHost)
    }

    @Test
    fun currentIcannTldsUseNormalWebMode() {
        for (host in listOf(
            "example.zip",
            "example.museum",
            "example.arpa",
            "example.xn--p1ai",
            "example.google",
        )) {
            val target = classifier.classify(host)

            assertEquals(host, BrowserTargetKind.NativeGateway, target.kind)
            assertEquals("https://$host/", target.url)
        }
    }

    @Test
    fun appAssetHttpsUrlsStayInTheLocalAssetPath() {
        val target = classifier.classify(
            "https://appassets.androidplatform.net/assets/example.txt",
        )

        assertEquals(BrowserTargetKind.LocalAsset, target.kind)
        assertEquals(
            "https://appassets.androidplatform.net/assets/example.txt",
            target.url,
        )
    }

    @Test
    fun appAssetOriginSpellingsOutsideTheOwnedSurfaceFailClosed() {
        for (url in listOf(
            "http://appassets.androidplatform.net/assets/start.html",
            "https://appassets.androidplatform.net/",
            "https://appassets.androidplatform.net/not-assets/start.html",
            "https://appassets.androidplatform.net:444/assets/start.html",
        )) {
            assertEquals(
                url,
                BrowserTargetKind.Blocked,
                classifier.classify(url).kind,
            )
        }
    }

    @Test
    fun publicIpLiteralUsesBoundedOpaqueAddressPath() {
        val target = classifier.classify("https://192.0.2.1/path")

        assertEquals(BrowserTargetKind.ExactUrl, target.kind)
        assertEquals("192.0.2.1", target.displayHost)
    }

    @Test
    fun fileUrlsDoNotLoadAsExactUrls() {
        val target = classifier.classify("file:///android_asset/example.txt")

        assertEquals(BrowserTargetKind.Search, target.kind)
    }

    @Test
    fun malformedHttpUrlsBecomeSearches() {
        for (input in listOf("https://", "https:///path", "http://example.com:bad/")) {
            val target = classifier.classify(input)

            assertEquals(input, BrowserTargetKind.Search, target.kind)
        }
    }

    @Test
    fun userInfoHttpUrlsBecomeSearches() {
        val target = classifier.classify("https://example.com@welcome/path")

        assertEquals(BrowserTargetKind.Search, target.kind)
    }

    @Test
    fun explicitDottedHnsUrlUsesHnsModeWhenTldIsNotCommonIcann() {
        val target = classifier.classify("https://welcome.2d/path")

        assertEquals(BrowserTargetKind.NativeGateway, target.kind)
        assertEquals("https://welcome.2d/path", target.url)
        assertEquals("welcome.2d", target.displayHost)
    }

    @Test
    fun reservedSingleLabelsUseNormalWebMode() {
        for (host in listOf(
            "localhost", "example", "invalid", "local", "test",
            "app.alt", "foo.example", "foo.internal", "foo.invalid", "foo.local", "foo.localhost", "foo.onion", "foo.test",
        )) {
            val target = classifier.classify(host)

            assertEquals(host, BrowserTargetKind.NativeGateway, target.kind)
            assertEquals("https://$host/", target.url)
        }
    }

    @Test
    fun wordsBecomeSearch() {
        val target = classifier.classify("two words")

        assertEquals(BrowserTargetKind.Search, target.kind)
        assertEquals("duckduckgo.com", target.displayHost)
        assertEquals("https://duckduckgo.com/?q=two+words", target.url)
    }

    @Test
    fun unavailableRustAdmissionFailsClosed() {
        val classifier = BrowserUrlClassifier(
            FixedBrowserNamespacePolicy(emptyMap(), BrowserNamespaceClass.Unavailable),
        )

        val target = classifier.classify("https://unknown.example/path")

        assertEquals(BrowserTargetKind.Blocked, target.kind)
        assertEquals("https://unknown.example/path", target.url)
    }
}
