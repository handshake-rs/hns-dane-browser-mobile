package com.denuoweb.hnsdane.net

import com.denuoweb.hnsdane.core.HnsPageResolverPolicy
import com.denuoweb.hnsdane.core.HnsPageSecurityPath
import com.denuoweb.hnsdane.core.HnsPageTlsPolicy
import com.denuoweb.hnsdane.core.TEST_BROWSER_NAMESPACE_POLICY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory

class HnsWebViewGatewayInterceptorTest {
    @Before
    fun clearGatewayEvents() {
        GatewayEventLog.clear()
    }

    @Test
    fun hnsHttpsGetUsesNativeGatewayBridge() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nX-Test: yes\r\nContent-Length: 2\r\n\r\nok"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-intercept-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/path?q=1#fragment",
            requestHeaders = mapOf(
                "Accept" to "text/html",
                "Accept-Encoding" to "gzip, deflate, br",
                "Host" to "ignored",
                "Connection" to "keep-alive",
            ),
        )

        assertEquals(
            GatewayCall(
                dataDir.absolutePath,
                "GET",
                "https",
                "welcome",
                443,
                "/path?q=1",
                forwardedGatewayHeaders("Accept" to "text/html"),
                "",
            ),
            bridge.calls.single(),
        )
        requireNotNull(response)
        assertEquals(200, response.statusCode)
        assertEquals("OK", response.reason)
        assertEquals("text/html", response.mimeType)
        assertEquals("utf-8", response.encoding)
        assertEquals("yes", response.headers["X-Test"])
        assertEquals("ok", response.body.toString(StandardCharsets.UTF_8))
        dataDir.deleteRecursively()
    }

    @Test
    fun webResponseHeadersDropContentTypeAndConnectionManagementHeaders() {
        // WebResourceResponse synthesizes Content-Type from mimeType/encoding; forwarding the
        // origin header as well makes Chromium see "text/javascript, text/javascript", which
        // fails the strict module-script MIME check and silently skips module execution.
        val bridge = RecordingGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/javascript\r\n" +
                    "Content-Length: 2\r\n" +
                    "Connection: close\r\n" +
                    "Transfer-Encoding: identity\r\n" +
                    "Keep-Alive: timeout=5\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "ETag: \"abc\"\r\n\r\nok"
                ).toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-header-dedup-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/app.js",
            requestHeaders = emptyMap(),
        )

        requireNotNull(response)
        assertEquals("text/javascript", response.mimeType)
        val forwarded = response.webResponseHeaders()
        assertFalse(forwarded.keys.any { it.equals("Content-Type", ignoreCase = true) })
        assertFalse(forwarded.keys.any { it.equals("Content-Length", ignoreCase = true) })
        assertFalse(forwarded.keys.any { it.equals("Connection", ignoreCase = true) })
        assertFalse(forwarded.keys.any { it.equals("Transfer-Encoding", ignoreCase = true) })
        assertFalse(forwarded.keys.any { it.equals("Keep-Alive", ignoreCase = true) })
        assertEquals("no-store", forwarded["Cache-Control"])
        assertEquals("\"abc\"", forwarded["ETag"])
        dataDir.deleteRecursively()
    }

    @Test
    fun hnsHttpsGetCanUseFileBackedNativeGatewayBody() {
        val bridge = FileGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: 8\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
            "streamed".toByteArray(StandardCharsets.UTF_8),
        )
        val dataDir = createTempDirectory("hns-webview-file-body-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/file",
            requestHeaders = mapOf("Accept" to "text/plain"),
        )

        requireNotNull(response)
        assertEquals(200, response.statusCode)
        assertEquals("text/plain", response.mimeType)
        assertEquals("", response.body.toString(StandardCharsets.UTF_8))
        val streamed = response.openBodyStream().use { input ->
            input.readBytes().toString(StandardCharsets.UTF_8)
        }
        assertEquals("streamed", streamed)
        assertFalse(bridge.bodyFile.exists())
        assertEquals(1, bridge.calls.size)
        dataDir.deleteRecursively()
    }

    @Test
    fun serviceWorkerPathReturnsValidatedHeadBeforeConsumingStreamingBody() {
        val responseBody = ByteArrayInputStream("streamed".toByteArray(StandardCharsets.UTF_8))
        val bridge = object : HnsGatewayBridge {
            override fun httpResponse(
                dataDir: String,
                config: HnsGatewayRuntimeConfig,
                method: String,
                scheme: String,
                host: String,
                port: Int,
                pathAndQuery: String,
                headers: List<Pair<String, String>>,
                body: ByteArray,
            ): ByteArray = error("buffered fallback should not be used")

            override fun httpResponseStreaming(
                dataDir: String,
                config: HnsGatewayRuntimeConfig,
                method: String,
                scheme: String,
                host: String,
                port: Int,
                pathAndQuery: String,
                headers: List<Pair<String, String>>,
                body: ByteArray,
            ) = HnsGatewayStreamingResponse(
                "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 8\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1),
                responseBody,
            )
        }
        val dataDir = createTempDirectory("hns-webview-streaming-body-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/stream",
            requestHeaders = emptyMap(),
            isForMainFrame = false,
            preferStreaming = true,
        )

        requireNotNull(response)
        assertEquals(200, response.statusCode)
        assertEquals("streamed", response.openBodyStream().readBytes().toString(StandardCharsets.UTF_8))
        dataDir.deleteRecursively()
    }

    @Test
    fun serviceWorkerStreamingFailureDoesNotRetryThroughBlockingFileGateway() {
        var streamingCalls = 0
        val bridge = object : HnsGatewayBridge {
            override fun httpResponse(
                dataDir: String,
                config: HnsGatewayRuntimeConfig,
                method: String,
                scheme: String,
                host: String,
                port: Int,
                pathAndQuery: String,
                headers: List<Pair<String, String>>,
                body: ByteArray,
            ): ByteArray = error("buffered fallback should not be used")

            override fun httpResponseBodyFile(
                dataDir: String,
                config: HnsGatewayRuntimeConfig,
                method: String,
                scheme: String,
                host: String,
                port: Int,
                pathAndQuery: String,
                headers: List<Pair<String, String>>,
                body: ByteArray,
            ): HnsGatewayFileResponse = error("file fallback should not be used")

            override fun httpResponseStreaming(
                dataDir: String,
                config: HnsGatewayRuntimeConfig,
                method: String,
                scheme: String,
                host: String,
                port: Int,
                pathAndQuery: String,
                headers: List<Pair<String, String>>,
                body: ByteArray,
            ): HnsGatewayStreamingResponse? {
                streamingCalls += 1
                return null
            }
        }
        val dataDir = createTempDirectory("hns-webview-streaming-failure-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/service-worker-fetch",
            requestHeaders = emptyMap(),
            isForMainFrame = false,
            preferStreaming = true,
            requireStreaming = true,
        )

        requireNotNull(response)
        assertEquals(503, response.statusCode)
        assertEquals("HNS Streaming Unavailable", response.reason)
        assertEquals(1, streamingCalls)
        dataDir.deleteRecursively()
    }

    @Test
    fun unavailableStreamingGatewayFallsBackToFileBackedResponse() {
        val bridge = FileGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 8\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
            "fallback".toByteArray(StandardCharsets.UTF_8),
        )
        val dataDir = createTempDirectory("hns-webview-streaming-fallback-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = requireNotNull(
            interceptor.intercept(
                method = "GET",
                url = "https://welcome/fallback",
                requestHeaders = emptyMap(),
                isForMainFrame = false,
                preferStreaming = true,
            ),
        )

        assertEquals("fallback", response.openBodyStream().use { it.readBytes() }.decodeToString())
        assertEquals(1, bridge.calls.size)
        dataDir.deleteRecursively()
    }

    @Test
    fun normalWebRequestUsesAutomaticNativeGatewayPolicy() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-normal-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        requireNotNull(interceptor.intercept("GET", "https://example.com/", emptyMap()))
        assertEquals("example.com", bridge.calls.single().host)
        dataDir.deleteRecursively()
    }

    @Test
    fun syntheticAssetOriginCanNeverFallThroughToTheNetworkGateway() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("local-asset-fallback-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://appassets.androidplatform.net/assets/missing.html",
            requestHeaders = emptyMap(),
        )

        requireNotNull(response)
        assertEquals(503, response.statusCode)
        assertTrue(bridge.calls.isEmpty())
        dataDir.deleteRecursively()
    }

    @Test
    fun everyIcannHostUsesNativeGatewayBridge() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("icann-dane-webview-intercept-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://example.com/path",
            requestHeaders = emptyMap(),
        )

        requireNotNull(response)
        assertEquals(
            GatewayCall(
                dataDir.absolutePath,
                "GET",
                "https",
                "example.com",
                443,
                "/path",
                forwardedGatewayHeaders(),
                "",
            ),
            bridge.calls.single(),
        )
        dataDir.deleteRecursively()
    }

    @Test
    fun serviceWorkerStyleHnsFetchUsesNativeGatewayPolicy() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-service-worker-intercept-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/sw-cache",
            requestHeaders = mapOf(
                "Accept" to "*/*",
                "Proxy-Connection" to "keep-alive",
            ),
        )

        assertEquals(
            GatewayCall(
                dataDir.absolutePath,
                "GET",
                "https",
                "welcome",
                443,
                "/sw-cache",
                forwardedGatewayHeaders("Accept" to "*/*"),
                "",
            ),
            bridge.calls.single(),
        )
        requireNotNull(response)
        assertEquals(204, response.statusCode)
        assertEquals("No Content", response.reason)
        dataDir.deleteRecursively()
    }

    @Test
    fun strictHnsModeUsesExplicitRuntimeConfigWithoutSyntheticOriginHeaders() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-strict-mode-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir = dataDir,
            hnsGatewayBridge = bridge,
            namespacePolicy = TEST_BROWSER_NAMESPACE_POLICY,
            strictHnsMode = { true },
        )

        interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = mapOf("X-HNS-Untrusted" to "spoofed"),
        )

        assertEquals(
            internalGatewayHeaders(),
            bridge.calls.single().headers,
        )
        assertEquals(true, bridge.configs.single().strictHnsMode)
        dataDir.deleteRecursively()
    }

    @Test
    fun dohResolverUsesExplicitRuntimeConfigWithoutSyntheticOriginHeaders() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-doh-resolver-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir = dataDir,
            hnsGatewayBridge = bridge,
            namespacePolicy = TEST_BROWSER_NAMESPACE_POLICY,
            dohResolverUrl = { "https://resolver.example/dns-query" },
        )

        interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = emptyMap(),
        )

        assertEquals(
            internalGatewayHeaders(),
            bridge.calls.single().headers,
        )
        assertEquals("https://resolver.example/dns-query", bridge.configs.single().dohResolverUrl)
        dataDir.deleteRecursively()
    }

    @Test
    fun statelessDaneUsesExplicitRuntimeConfigWithoutSyntheticOriginHeaders() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-stateless-dane").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir = dataDir,
            hnsGatewayBridge = bridge,
            namespacePolicy = TEST_BROWSER_NAMESPACE_POLICY,
            statelessDaneCertificates = { true },
        )

        interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = emptyMap(),
        )

        assertEquals(
            internalGatewayHeaders(),
            bridge.calls.single().headers,
        )
        assertEquals(true, bridge.configs.single().statelessDaneCertificates)
        dataDir.deleteRecursively()
    }

    @Test
    fun handshakeNetworkUsesExplicitRuntimeConfigWithoutSyntheticOriginHeaders() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-network-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir = dataDir,
            hnsGatewayBridge = bridge,
            namespacePolicy = TEST_BROWSER_NAMESPACE_POLICY,
            handshakeNetwork = { "regtest" },
        )

        interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = emptyMap(),
        )

        assertEquals(
            internalGatewayHeaders(),
            bridge.calls.single().headers,
        )
        assertEquals("regtest", bridge.configs.single().network)
        dataDir.deleteRecursively()
    }

    @Test
    fun gatewayStripsTheEntireInternalHeaderNamespaceBeforeInjectingTrustedControls() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-internal-header-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = mapOf(
                "Accept" to "text/html",
                "x-hns-future-control" to "spoofed",
                "X-HNS-TLS-Policy" to "dane",
                HNS_RESOLUTION_TRACE_HEADER to "private-trace",
            ),
        )

        assertEquals(
            forwardedGatewayHeaders("Accept" to "text/html"),
            bridge.calls.single().headers,
        )
        dataDir.deleteRecursively()
    }

    @Test
    fun dottedHnsFetchUsesNativeGatewayWhenTldIsNotCommonIcann() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-dotted-webview-intercept-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome.2d/path",
            requestHeaders = emptyMap(),
        )

        requireNotNull(response)
        assertEquals(
            GatewayCall(
                dataDir.absolutePath,
                "GET",
                "https",
                "welcome.2d",
                443,
                "/path",
                forwardedGatewayHeaders(),
                "",
            ),
            bridge.calls.single(),
        )
        dataDir.deleteRecursively()
    }

    @Test
    fun emojiHnsFetchUsesPunycodeNativeGatewayHost() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-emoji-webview-intercept-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept(
            method = "GET",
            url = "https://🤝/path",
            requestHeaders = emptyMap(),
        )

        requireNotNull(response)
        assertEquals("xn--5p9h", bridge.calls.single().host)
        dataDir.deleteRecursively()
    }

    @Test
    fun mainFrameHnsResponseReportsFinalStatus() {
        val statuses = mutableListOf<Int>()
        val tlsPolicies = mutableListOf<HnsPageTlsPolicy?>()
        val resolverPolicies = mutableListOf<HnsPageResolverPolicy?>()
        val statusUrls = mutableListOf<String>()
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 502 HNS Origin Address Missing\r\nX-HNS-TLS-Policy: webpki-fallback\r\nX-HNS-Resolver-Policy: hns-doh-compat\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-main-frame-status-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir,
            bridge,
            TEST_BROWSER_NAMESPACE_POLICY,
            onMainFrameHnsStatus = { status, tlsPolicy, resolverPolicy, _, _ ->
                statuses += status
                tlsPolicies += tlsPolicy
                resolverPolicies += resolverPolicy
            },
            onMainFrameHnsStatusForUrl = { url, _, _, _, _, _ -> statusUrls += url },
        )

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = emptyMap(),
            isForMainFrame = true,
        )

        requireNotNull(response)
        assertEquals(502, response.statusCode)
        assertEquals(listOf(502), statuses)
        assertEquals(listOf("https://welcome/"), statusUrls)
        assertEquals(listOf(HnsPageTlsPolicy.WebPkiFallback), tlsPolicies)
        assertEquals(listOf(HnsPageResolverPolicy.HnsDohCompatibility), resolverPolicies)
        dataDir.deleteRecursively()
    }

    @Test
    fun subresourceHnsResponseDoesNotReportMainFrameStatus() {
        val statuses = mutableListOf<Int>()
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-subresource-status-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir,
            bridge,
            TEST_BROWSER_NAMESPACE_POLICY,
            onMainFrameHnsStatus = { status, _, _, _, _ ->
                statuses += status
            },
        )

        interceptor.intercept(
            method = "GET",
            url = "https://welcome/app.css",
            requestHeaders = emptyMap(),
            isForMainFrame = false,
        )

        assertTrue(statuses.isEmpty())
        dataDir.deleteRecursively()
    }

    @Test
    fun mainFrameParsesMetadataWithoutExposingInternalHeadersToWebView() {
        val paths = mutableListOf<HnsPageSecurityPath?>()
        val tlsPolicies = mutableListOf<HnsPageTlsPolicy?>()
        val resolverPolicies = mutableListOf<HnsPageResolverPolicy?>()
        val traces = mutableListOf<String?>()
        val bridge = RecordingGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "$HNS_SECURITY_PATH_HEADER: dane-authoritative-doh\r\n" +
                    "X-HNS-TLS-Policy: dane\r\n" +
                    "$HNS_RESOLUTION_TRACE_HEADER: private-trace\r\n" +
                    "x-hns-future-metadata: private-future\r\n" +
                    "X-Public: visible\r\n" +
                    "Content-Length: 0\r\n\r\n"
                ).toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-security-path-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir,
            bridge,
            TEST_BROWSER_NAMESPACE_POLICY,
            onMainFrameHnsStatus = { _, tlsPolicy, resolverPolicy, securityPath, trace ->
                paths += securityPath
                tlsPolicies += tlsPolicy
                resolverPolicies += resolverPolicy
                traces += trace
            },
        )

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = emptyMap(),
            isForMainFrame = true,
        )

        requireNotNull(response)
        assertEquals(listOf(HnsPageSecurityPath.DaneAuthoritativeDoh), paths)
        assertEquals(listOf(HnsPageTlsPolicy.Dane), tlsPolicies)
        assertEquals(listOf(null), resolverPolicies)
        assertEquals(listOf("private-trace"), traces)
        assertFalse(response.webResponseHeaders().keys.any { it.startsWith("X-HNS-", ignoreCase = true) })
        assertEquals("visible", response.webResponseHeaders()["X-Public"])
        dataDir.deleteRecursively()
    }

    @Test
    fun successfulDisabledHnsTrustMetadataIsRejectedBeforeRendering() {
        val responses = listOf(
            "X-HNS-Resolver-Policy: hns-doh-compat\r\n",
            "X-HNS-TLS-Policy: webpki-fallback\r\n",
            "X-HNS-TLS-Policy: webpki-fallback\r\n" +
                "$HNS_RESOLUTION_TRACE_HEADER: not-json\r\n",
            "X-HNS-TLS-Policy: webpki-fallback\r\n" +
                "$HNS_RESOLUTION_TRACE_HEADER: $HNS_ONLY_RESOLUTION_TRACE\r\n",
        )

        responses.forEachIndexed { index, metadata ->
            val bridge = RecordingGatewayBridge(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        metadata +
                        "Content-Length: 6\r\n\r\nattack"
                    ).toByteArray(StandardCharsets.ISO_8859_1),
            )
            val dataDir =
                createTempDirectory("hns-disabled-trust-status-$index").toFile()
            val interceptor = HnsWebViewGatewayInterceptor(
                dataDir,
                bridge,
                TEST_BROWSER_NAMESPACE_POLICY,
            )

            val response = interceptor.intercept(
                method = "GET",
                url = "https://welcome/",
                requestHeaders = emptyMap(),
            )

            requireNotNull(response)
            assertEquals(502, response.statusCode)
            assertEquals("Disabled HNS Trust Path", response.reason)
            assertFalse(String(response.body).contains("attack"))
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun successfulUserConfiguredRecoveryTrustMetadataCanRender() {
        val bridge = RecordingGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "$HNS_SECURITY_PATH_HEADER: dane-third-party-doh\r\n" +
                    "X-HNS-TLS-Policy: dane\r\n" +
                    "Content-Length: 7\r\n\r\npayload"
                ).toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-recovery-doh-status").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir,
            bridge,
            TEST_BROWSER_NAMESPACE_POLICY,
        )

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = emptyMap(),
        )

        requireNotNull(response)
        assertEquals(200, response.statusCode)
        assertEquals("payload", String(response.body))
        dataDir.deleteRecursively()
    }

    @Test
    fun disabledHnsTrustMetadataIsRejectedBeforeFollowingRedirect() {
        val bridge = RecordingGatewayBridge(
            (
                "HTTP/1.1 302 Found\r\n" +
                    "Location: /attacker-selected\r\n" +
                    "X-HNS-TLS-Policy: webpki-fallback\r\n" +
                    "Content-Length: 0\r\n\r\n"
                ).toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-disabled-trust-redirect").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir,
            bridge,
            TEST_BROWSER_NAMESPACE_POLICY,
        )

        val response = interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = emptyMap(),
        )

        requireNotNull(response)
        assertEquals(502, response.statusCode)
        assertEquals("Disabled HNS Trust Path", response.reason)
        assertEquals(1, bridge.calls.size)
        dataDir.deleteRecursively()
    }

    @Test
    fun authenticatedIcannWebPkiStatusCanRender() {
        val bridge = RecordingGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "X-HNS-TLS-Policy: webpki-fallback\r\n" +
                    "$HNS_RESOLUTION_TRACE_HEADER: $ICANN_ONLY_RESOLUTION_TRACE\r\n" +
                    "Content-Length: 7\r\n\r\npayload"
                ).toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("icann-webpki-status").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir,
            bridge,
            TEST_BROWSER_NAMESPACE_POLICY,
        )

        val response = interceptor.intercept(
            method = "GET",
            url = "https://example.com/",
            requestHeaders = emptyMap(),
        )

        requireNotNull(response)
        assertEquals(200, response.statusCode)
        assertEquals("payload", response.body.toString(StandardCharsets.UTF_8))
        dataDir.deleteRecursively()
    }

    @Test
    fun unknownSecurityPathRetainsLegacyHeaderParsing() {
        val paths = mutableListOf<HnsPageSecurityPath?>()
        val tlsPolicies = mutableListOf<HnsPageTlsPolicy?>()
        val bridge = RecordingGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "X-HNS-TLS-Policy: dane\r\n" +
                    "$HNS_SECURITY_PATH_HEADER: future-path\r\n" +
                    "Content-Length: 0\r\n\r\n"
                ).toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-unknown-security-path-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir,
            bridge,
            TEST_BROWSER_NAMESPACE_POLICY,
            onMainFrameHnsStatus = { _, tlsPolicy, _, securityPath, _ ->
                tlsPolicies += tlsPolicy
                paths += securityPath
            },
        )

        interceptor.intercept(
            method = "GET",
            url = "https://welcome/",
            requestHeaders = emptyMap(),
            isForMainFrame = true,
        )

        assertEquals(listOf(HnsPageTlsPolicy.Dane), tlsPolicies)
        assertEquals(listOf(null), paths)
        dataDir.deleteRecursively()
    }

    @Test
    fun hnsRedirectToRelativeHnsPathIsFollowedThroughNativeGateway() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 302 Found\r\nLocation: /next?q=1\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: 4\r\n\r\ndone"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-redirect-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept("GET", "https://welcome/start", emptyMap())

        assertEquals(2, bridge.calls.size)
        assertEquals("/start", bridge.calls[0].pathAndQuery)
        assertEquals("/next?q=1", bridge.calls[1].pathAndQuery)
        requireNotNull(response)
        assertEquals(200, response.statusCode)
        assertEquals("done", response.body.toString(StandardCharsets.UTF_8))
        dataDir.deleteRecursively()
    }

    @Test
    fun crossOriginRedirectReentersGatewayPolicyBeforeFailingClosed() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 302 Found\r\nLocation: https://example.com/\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-external-redirect-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept("GET", "https://welcome/start", emptyMap())

        assertEquals(1, bridge.calls.size)
        requireNotNull(response)
        assertEquals(502, response.statusCode)
        assertEquals("HNS Redirect Unsupported", response.reason)
        assertTrue(response.body.toString(StandardCharsets.UTF_8).contains("same-origin"))
        dataDir.deleteRecursively()
    }

    @Test
    fun hnsRedirectCannotChangeOriginOrDowngradeTransport() {
        for (location in listOf("https://otherhns/final", "http://welcome/final", "https://welcome:8443/final")) {
            val bridge = RecordingGatewayBridge(
                "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\n\r\n"
                    .toByteArray(StandardCharsets.ISO_8859_1),
            )
            val dataDir = createTempDirectory("hns-webview-origin-redirect-test").toFile()
            val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

            val response = interceptor.intercept("GET", "https://welcome/start", emptyMap())

            assertEquals(1, bridge.calls.size)
            requireNotNull(response)
            assertEquals(502, response.statusCode)
            assertEquals("HNS Redirect Unsupported", response.reason)
            assertTrue(response.body.toString(StandardCharsets.UTF_8).contains("same-origin"))
            dataDir.deleteRecursively()
        }
    }

    @Test
    fun hnsPostFailsClosedBeforeNativeGatewayBecauseWebViewBodyIsUnavailable() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-method-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept("POST", "https://welcome/form", emptyMap())

        requireNotNull(response)
        assertEquals(501, response.statusCode)
        assertEquals("HNS Method Unsupported", response.reason)
        assertTrue(response.body.toString(StandardCharsets.UTF_8).contains("bodyless requests"))
        assertTrue(bridge.calls.isEmpty())
        val event = GatewayEventLog.snapshot().single()
        assertEquals("webview_reject", event.stage)
        assertEquals("welcome", event.host)
        assertEquals(501, event.status)
        assertEquals("HNS_Method_Unsupported", event.reason)
        assertFalse(GatewayEventLog.snapshotText().contains("form"))
        dataDir.deleteRecursively()
    }

    @Test
    fun hnsPostFallsThroughWhenProxyFallbackIsAvailable() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-proxy-fallback-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir,
            bridge,
            TEST_BROWSER_NAMESPACE_POLICY,
            allowProxyFallbackForBodyRequests = { true },
        )

        val response = interceptor.intercept("POST", "https://welcome/form", emptyMap())

        assertNull(response)
        assertTrue(bridge.calls.isEmpty())
        dataDir.deleteRecursively()
    }

    @Test
    fun serviceWorkerBodyRequestFailsClosedEvenWhenPageProxyFallbackIsAvailable() {
        val bridge = RecordingGatewayBridge(ByteArray(0))
        val dataDir = createTempDirectory("hns-service-worker-method-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir,
            bridge,
            TEST_BROWSER_NAMESPACE_POLICY,
            allowProxyFallbackForBodyRequests = { true },
        )

        val response = interceptor.intercept(
            method = "POST",
            url = "https://welcome/form",
            requestHeaders = emptyMap(),
            isForMainFrame = false,
            allowBodyRequestProxyFallback = false,
        )

        requireNotNull(response)
        assertEquals(501, response.statusCode)
        assertTrue(bridge.calls.isEmpty())
        dataDir.deleteRecursively()
    }

    @Test
    fun malformedNativeGatewayResponseFailsClosed() {
        val bridge = RecordingGatewayBridge("not http".toByteArray(StandardCharsets.ISO_8859_1))
        val dataDir = createTempDirectory("hns-webview-malformed-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept("GET", "http://welcome/", emptyMap())

        requireNotNull(response)
        assertEquals(502, response.statusCode)
        assertEquals("HNS Gateway Error", response.reason)
        val event = GatewayEventLog.snapshot().single()
        assertEquals("webview_malformed_response", event.stage)
        assertEquals("welcome", event.host)
        assertEquals(502, event.status)
        assertEquals("HNS_Gateway_Error", event.reason)
        dataDir.deleteRecursively()
    }

    @Test
    fun hnsWebViewGatewayFailureRecordsSanitizedEvent() {
        val bridge = RecordingGatewayBridge(
            "HTTP/1.1 502 HNS Origin Address Missing\r\nContent-Length: 11\r\n\r\nsecret-body"
                .toByteArray(StandardCharsets.ISO_8859_1),
        )
        val dataDir = createTempDirectory("hns-webview-event-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(dataDir, bridge, TEST_BROWSER_NAMESPACE_POLICY)

        val response = interceptor.intercept("GET", "https://welcome/private?q=token", emptyMap())

        requireNotNull(response)
        assertEquals(502, response.statusCode)
        val event = GatewayEventLog.snapshot().single()
        assertEquals("webview_native_response", event.stage)
        assertEquals("welcome", event.host)
        assertEquals(502, event.status)
        assertEquals("HNS_Origin_Address_Missing", event.reason)
        val text = GatewayEventLog.snapshotText()
        assertFalse(text.contains("private"))
        assertFalse(text.contains("token"))
        assertFalse(text.contains("secret-body"))
        dataDir.deleteRecursively()
    }

    @Test
    fun completedValidatedHashedAssetIsServedFromTheAppCache() {
        val body = "cached module".toByteArray()
        val bridge = FileGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/javascript\r\n" +
                    "Cache-Control: public, max-age=31536000, immutable\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1),
            body,
        )
        val dataDir = createTempDirectory("hns-validated-asset-cache-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir = dataDir,
            hnsGatewayBridge = bridge,
            namespacePolicy = TEST_BROWSER_NAMESPACE_POLICY,
            chainTipToken = { "100:hash:root" },
        )
        val url = "https://app.pirate/assets/client-Bk8h6irO.js"

        val first = requireNotNull(
            interceptor.intercept(
                "GET",
                url,
                emptyMap(),
                isForMainFrame = false,
                preferStreaming = true,
            ),
        )
        assertEquals("no-store", first.headerValue("Cache-Control"))
        val firstBytes = ByteArray("cached module".toByteArray().size)
        first.openBodyStream().use { input ->
            assertEquals(firstBytes.size, input.read(firstBytes))
        }
        assertEquals("cached module", firstBytes.decodeToString())
        val second = requireNotNull(
            interceptor.intercept(
                "GET",
                url,
                emptyMap(),
                isForMainFrame = false,
                preferStreaming = true,
                requireStreaming = true,
            ),
        )
        assertEquals("no-store", second.headerValue("Cache-Control"))
        assertEquals("cached module", second.openBodyStream().use { it.readBytes() }.decodeToString())
        assertEquals(1, bridge.calls.size)
        dataDir.deleteRecursively()
    }

    @Test
    fun chainTipChangeInvalidatesValidatedHashedAssets() {
        val body = "tip-bound module".toByteArray()
        val bridge = FileGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/javascript\r\n" +
                    "Cache-Control: public, max-age=31536000, immutable\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1),
            body,
        )
        val dataDir = createTempDirectory("hns-validated-asset-tip-test").toFile()
        var tip = "100:hash:root"
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir = dataDir,
            hnsGatewayBridge = bridge,
            namespacePolicy = TEST_BROWSER_NAMESPACE_POLICY,
            chainTipToken = { tip },
        )
        val url = "https://app.pirate/assets/client-Bk8h6irO.js"

        requireNotNull(
            interceptor.intercept(
                "GET",
                url,
                emptyMap(),
                isForMainFrame = false,
                preferStreaming = true,
            ),
        )
            .openBodyStream()
            .use { it.readBytes() }
        tip = "101:next-hash:next-root"
        requireNotNull(
            interceptor.intercept(
                "GET",
                url,
                emptyMap(),
                isForMainFrame = false,
                preferStreaming = true,
            ),
        )
            .openBodyStream()
            .use { it.readBytes() }

        assertEquals(2, bridge.calls.size)
        dataDir.deleteRecursively()
    }

    @Test
    fun resolverPolicyChangesProduceDifferentValidatedAssetScopes() {
        val config = HnsGatewayRuntimeConfig(
            network = "mainnet",
            strictHnsMode = true,
            dohResolverUrl = "https://resolver.invalid/dns-query",
            statelessDaneCertificates = false,
        )

        assertFalse(
            ValidatedAssetCache.scope(config, "100:hash:root") ==
                ValidatedAssetCache.scope(config.copy(strictHnsMode = false), "100:hash:root"),
        )
    }

    @Test
    fun validatedAssetCacheRejectsCredentialedAndConditionalRequests() {
        val url = "https://app.pirate/assets/client-Bk8h6irO.js"

        assertTrue(ValidatedAssetCache.requestEligible(url, emptyMap()))
        for (headers in listOf(
            mapOf("Authorization" to "Bearer secret"),
            mapOf("Cookie" to "session=secret"),
            mapOf("Range" to "bytes=0-10"),
            mapOf("If-None-Match" to "etag"),
            mapOf("Cache-Control" to "no-store"),
            mapOf("Pragma" to "no-cache"),
        )) {
            assertFalse(ValidatedAssetCache.requestEligible(url, headers))
        }
    }

    @Test
    fun validatedAssetCacheRequiresPublicImmutableNonVaryingResponses() {
        fun response(vararg headers: Pair<String, String>) = HnsInterceptedResponse(
            statusCode = 200,
            reason = "OK",
            mimeType = "application/javascript",
            encoding = null,
            headers = mapOf(*headers),
            body = ByteArray(0),
        )

        assertTrue(
            ValidatedAssetCache.responseEligible(
                response("Cache-Control" to "public, max-age=31536000, immutable"),
            ),
        )
        for (candidate in listOf(
            response(),
            response("Cache-Control" to "public, max-age=31536000"),
            response("Cache-Control" to "immutable"),
            response("Cache-Control" to "public, immutable"),
            response("Cache-Control" to "public, immutable, no-store"),
            response(
                "Cache-Control" to "public, max-age=60, immutable",
                "Age" to "60",
            ),
            response(
                "Cache-Control" to "public, max-age=31536000, immutable",
                "Set-Cookie" to "session=secret",
            ),
            response(
                "Cache-Control" to "public, max-age=31536000, immutable",
                "Vary" to "Accept-Language",
            ),
        )) {
            assertFalse(ValidatedAssetCache.responseEligible(candidate))
        }
    }

    @Test
    fun credentialedHashedAssetRequestsBypassTheValidatedCache() {
        val body = "private module".toByteArray()
        val bridge = FileGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/javascript\r\n" +
                    "Cache-Control: public, max-age=31536000, immutable\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1),
            body,
        )
        val dataDir = createTempDirectory("hns-credentialed-asset-cache-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir = dataDir,
            hnsGatewayBridge = bridge,
            namespacePolicy = TEST_BROWSER_NAMESPACE_POLICY,
            chainTipToken = { "100:hash:root" },
        )
        val url = "https://app.pirate/assets/client-Bk8h6irO.js"

        repeat(2) {
            requireNotNull(interceptor.intercept("GET", url, mapOf("Cookie" to "session=secret")))
                .openBodyStream()
                .use { input -> assertEquals("private module", input.readBytes().decodeToString()) }
        }

        assertEquals(2, bridge.calls.size)
        dataDir.deleteRecursively()
    }

    @Test
    fun conflictingCacheControlHeadersFailClosed() {
        val body = "uncached module".toByteArray()
        val bridge = FileGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/javascript\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Cache-Control: public, max-age=31536000, immutable\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1),
            body,
        )
        val dataDir = createTempDirectory("hns-conflicting-cache-control-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir = dataDir,
            hnsGatewayBridge = bridge,
            namespacePolicy = TEST_BROWSER_NAMESPACE_POLICY,
            chainTipToken = { "100:hash:root" },
        )
        val url = "https://app.pirate/assets/client-Bk8h6irO.js"

        repeat(2) {
            requireNotNull(interceptor.intercept("GET", url, emptyMap()))
                .openBodyStream()
                .use { input -> assertEquals("uncached module", input.readBytes().decodeToString()) }
        }

        assertEquals(2, bridge.calls.size)
        dataDir.deleteRecursively()
    }

    @Test
    fun completedFileBackedAssetIsPublishedBeforeReturningToWebView() {
        val body = "file-backed module".toByteArray()
        val bridge = FileGatewayBridge(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/javascript\r\n" +
                    "Cache-Control: public, max-age=31536000, immutable\r\n" +
                    "Content-Length: ${body.size}\r\n\r\n"
            ).toByteArray(StandardCharsets.ISO_8859_1),
            body,
        )
        val dataDir = createTempDirectory("hns-validated-file-asset-test").toFile()
        val interceptor = HnsWebViewGatewayInterceptor(
            dataDir = dataDir,
            hnsGatewayBridge = bridge,
            namespacePolicy = TEST_BROWSER_NAMESPACE_POLICY,
            chainTipToken = { "100:hash:root" },
        )
        val url = "https://app.pirate/assets/client-Bk8h6irO.js"

        val first = requireNotNull(interceptor.intercept("GET", url, emptyMap()))
        assertEquals("file-backed module", first.openBodyStream().use { it.readBytes() }.decodeToString())
        val second = requireNotNull(interceptor.intercept("GET", url, emptyMap()))
        assertEquals("file-backed module", second.openBodyStream().use { it.readBytes() }.decodeToString())
        assertEquals(1, bridge.calls.size)
        dataDir.deleteRecursively()
    }

    private data class GatewayCall(
        val dataDir: String,
        val method: String,
        val scheme: String,
        val host: String,
        val port: Int,
        val pathAndQuery: String,
        val headers: List<Pair<String, String>>,
        val body: String,
    )

    private companion object {
        const val HNS_ONLY_RESOLUTION_TRACE =
            """{"namespaceResolution":{"outcome":"hnsOnly","selected":"hns","reason":"onlyAvailableRoot","hnsState":"present","icannState":"authenticatedAbsent","fingerprint":null}}"""
        const val ICANN_ONLY_RESOLUTION_TRACE =
            """{"namespaceResolution":{"outcome":"icannOnly","selected":"icann","reason":"onlyAvailableRoot","hnsState":"authenticatedAbsent","icannState":"present","fingerprint":null}}"""
    }

    private fun forwardedGatewayHeaders(vararg headers: Pair<String, String>): List<Pair<String, String>> =
        headers.toList() + ("Accept-Encoding" to "identity")

    private fun internalGatewayHeaders(vararg headers: Pair<String, String>): List<Pair<String, String>> =
        listOf("Accept-Encoding" to "identity") + headers

    private class RecordingGatewayBridge(
        private vararg val responses: ByteArray,
    ) : HnsGatewayBridge {
        val calls = mutableListOf<GatewayCall>()
        val configs = mutableListOf<HnsGatewayRuntimeConfig>()

        override fun httpResponse(
            dataDir: String,
            config: HnsGatewayRuntimeConfig,
            method: String,
            scheme: String,
            host: String,
            port: Int,
            pathAndQuery: String,
            headers: List<Pair<String, String>>,
            body: ByteArray,
        ): ByteArray {
            val response = responses.getOrElse(calls.size) { responses.last() }
            configs += config
            calls += GatewayCall(
                dataDir,
                method,
                scheme,
                host,
                port,
                pathAndQuery,
                headers,
                body.toString(StandardCharsets.ISO_8859_1),
            )
            return response
        }
    }

    private class FileGatewayBridge(
        private val responseHead: ByteArray,
        private val responseBody: ByteArray,
    ) : HnsGatewayBridge {
        val calls = mutableListOf<GatewayCall>()
        val configs = mutableListOf<HnsGatewayRuntimeConfig>()
        lateinit var bodyFile: File

        override fun httpResponse(
            dataDir: String,
            config: HnsGatewayRuntimeConfig,
            method: String,
            scheme: String,
            host: String,
            port: Int,
            pathAndQuery: String,
            headers: List<Pair<String, String>>,
            body: ByteArray,
        ): ByteArray {
            error("byte-array fallback should not be used")
        }

        override fun httpResponseBodyFile(
            dataDir: String,
            config: HnsGatewayRuntimeConfig,
            method: String,
            scheme: String,
            host: String,
            port: Int,
            pathAndQuery: String,
            headers: List<Pair<String, String>>,
            body: ByteArray,
        ): HnsGatewayFileResponse {
            bodyFile = File.createTempFile("hns-test-", ".body", File(dataDir))
            configs += config
            bodyFile.writeBytes(responseBody)
            calls += GatewayCall(
                dataDir,
                method,
                scheme,
                host,
                port,
                pathAndQuery,
                headers,
                body.toString(StandardCharsets.ISO_8859_1),
            )
            return HnsGatewayFileResponse(responseHead, bodyFile)
        }
    }

    private class StreamingAssetGatewayBridge(body: String) : HnsGatewayBridge {
        private val responseBody = body.toByteArray(StandardCharsets.UTF_8)
        var calls = 0

        override fun httpResponse(
            dataDir: String,
            config: HnsGatewayRuntimeConfig,
            method: String,
            scheme: String,
            host: String,
            port: Int,
            pathAndQuery: String,
            headers: List<Pair<String, String>>,
            body: ByteArray,
        ): ByteArray = error("buffered fallback should not be used")

        override fun httpResponseStreaming(
            dataDir: String,
            config: HnsGatewayRuntimeConfig,
            method: String,
            scheme: String,
            host: String,
            port: Int,
            pathAndQuery: String,
            headers: List<Pair<String, String>>,
            body: ByteArray,
        ): HnsGatewayStreamingResponse {
            calls += 1
            return HnsGatewayStreamingResponse(
                (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/javascript\r\n" +
                        "Content-Length: ${responseBody.size}\r\n\r\n"
                ).toByteArray(StandardCharsets.ISO_8859_1),
                ByteArrayInputStream(responseBody),
            )
        }
    }
}
