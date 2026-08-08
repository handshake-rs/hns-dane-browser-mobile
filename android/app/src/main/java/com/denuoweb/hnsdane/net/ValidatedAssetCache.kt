package com.denuoweb.hnsdane.net

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

internal class ValidatedAssetCache(
    dataDir: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxEntryBytes: Long = DEFAULT_MAX_ENTRY_BYTES,
) {
    private val root = File(dataDir, "hns/validated-assets/v1")
    private val lock = Any()
    private var activeScope: String? = null

    fun lookup(url: String, scope: String): HnsInterceptedResponse? = synchronized(lock) {
        activateScope(scope)
        val entryKey = digest(url)
        val directory = File(root, scope)
        val metadataFile = File(directory, "$entryKey.head")
        val bodyFile = File(directory, "$entryKey.body")
        if (!metadataFile.isFile || !bodyFile.isFile) return null

        val metadata = runCatching { readMetadata(metadataFile) }.getOrNull() ?: run {
            metadataFile.delete()
            bodyFile.delete()
            return null
        }
        metadataFile.setLastModified(System.currentTimeMillis())
        bodyFile.setLastModified(System.currentTimeMillis())
        metadata.toResponse(BufferedInputStream(FileInputStream(bodyFile)))
    }

    fun storeAfterComplete(
        url: String,
        scope: String,
        response: HnsInterceptedResponse,
    ): HnsInterceptedResponse {
        if (response.statusCode != 200) return response
        val declaredLength = response.headerValue("Content-Length")?.toLongOrNull()
        if (declaredLength != null && declaredLength > maxEntryBytes) return response

        val source = response.openBodyStream()
        val directory = synchronized(lock) {
            activateScope(scope)
            File(root, scope).also { it.mkdirs() }
        }
        val entryKey = digest(url)
        val temporaryBody = File(directory, ".$entryKey.${UUID.randomUUID()}.body.tmp")
        val output = runCatching {
            BufferedOutputStream(FileOutputStream(temporaryBody))
        }.getOrNull() ?: return response.withBodyStream(source)
        val metadata = CachedMetadata.from(response)
        return response.withBodyStream(
            PublishingInputStream(
                source = source,
                output = output,
                maxBytes = maxEntryBytes,
                expectedBytes = declaredLength?.takeIf { it >= 0L },
                onComplete = {
                    publish(directory, entryKey, temporaryBody, metadata)
                },
                onAbort = {
                    temporaryBody.delete()
                },
            ),
        )
    }

    fun storeCompletedFile(
        url: String,
        scope: String,
        response: HnsInterceptedResponse,
    ): HnsInterceptedResponse? {
        val sourceFile = response.bodyFile ?: return null
        if (sourceFile.length() > maxEntryBytes) return null
        val directory = synchronized(lock) {
            activateScope(scope)
            File(root, scope).also { it.mkdirs() }
        }
        val entryKey = digest(url)
        val temporaryBody = File(directory, ".$entryKey.${UUID.randomUUID()}.body.tmp")
        val copied = runCatching {
            FileInputStream(sourceFile).use { input ->
                BufferedOutputStream(FileOutputStream(temporaryBody)).use { output ->
                    input.copyTo(output)
                }
            }
            temporaryBody.length() <= maxEntryBytes
        }.getOrDefault(false)
        if (!copied) {
            temporaryBody.delete()
            return null
        }

        publish(directory, entryKey, temporaryBody, CachedMetadata.from(response))
        val cached = lookup(url, scope) ?: return null
        response.discardBody()
        return cached
    }

    private fun publish(
        directory: File,
        entryKey: String,
        temporaryBody: File,
        metadata: CachedMetadata,
    ) = synchronized(lock) {
        val temporaryMetadata = File(directory, ".$entryKey.${UUID.randomUUID()}.head.tmp")
        val bodyFile = File(directory, "$entryKey.body")
        val metadataFile = File(directory, "$entryKey.head")
        val wroteMetadata = runCatching {
            DataOutputStream(BufferedOutputStream(FileOutputStream(temporaryMetadata))).use {
                metadata.writeTo(it)
            }
            true
        }.getOrDefault(false)
        if (
            !wroteMetadata ||
            !replace(temporaryBody, bodyFile) ||
            !replace(temporaryMetadata, metadataFile)
        ) {
            temporaryBody.delete()
            temporaryMetadata.delete()
            bodyFile.delete()
            metadataFile.delete()
            return
        }
        val now = System.currentTimeMillis()
        bodyFile.setLastModified(now)
        metadataFile.setLastModified(now)
        prune(directory)
    }

    private fun activateScope(scope: String) {
        if (activeScope == scope) return
        root.mkdirs()
        root.listFiles()
            ?.filter { it.name != scope }
            ?.forEach(File::deleteRecursively)
        activeScope = scope
    }

    private fun prune(directory: File) {
        val entries = directory.listFiles { file -> file.name.endsWith(".body") }
            ?.sortedByDescending(File::lastModified)
            .orEmpty()
        var retainedBytes = 0L
        entries.forEachIndexed { index, bodyFile ->
            retainedBytes += bodyFile.length()
            if (index >= maxEntries || retainedBytes > maxBytes) {
                val key = bodyFile.name.removeSuffix(".body")
                bodyFile.delete()
                File(directory, "$key.head").delete()
            }
        }
        directory.listFiles { file -> file.name.endsWith(".tmp") }
            ?.filter { System.currentTimeMillis() - it.lastModified() > TEMP_FILE_MAX_AGE_MS }
            ?.forEach(File::delete)
    }

    private fun readMetadata(file: File): CachedMetadata =
        DataInputStream(BufferedInputStream(FileInputStream(file))).use(CachedMetadata::readFrom)

    private fun replace(source: File, target: File): Boolean {
        target.delete()
        return source.renameTo(target)
    }

    companion object {
        private const val DEFAULT_MAX_BYTES = 128L * 1024L * 1024L
        private const val DEFAULT_MAX_ENTRY_BYTES = 16L * 1024L * 1024L
        private const val DEFAULT_MAX_ENTRIES = 512
        private const val TEMP_FILE_MAX_AGE_MS = 60L * 60L * 1000L
        private val HASHED_ASSET_PATH = Regex("^/assets/.+-[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9]+$")

        fun eligible(url: String): Boolean {
            val path = runCatching { URI(url).rawPath }.getOrNull() ?: return false
            return HASHED_ASSET_PATH.matches(path)
        }

        fun scope(config: HnsGatewayRuntimeConfig, chainTipToken: String): String = digest(
            listOf(
                config.network,
                config.strictHnsMode,
                config.dohResolverUrl,
                config.statelessDaneCertificates,
                config.experimentalP2pDnsRelay,
                config.legacyHnsDohCompatibility,
                chainTipToken,
            ).joinToString("\u0000"),
        )

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}

private data class CachedMetadata(
    val statusCode: Int,
    val reason: String,
    val mimeType: String,
    val encoding: String?,
    val headers: Map<String, String>,
) {
    fun toResponse(body: InputStream) = HnsInterceptedResponse(
        statusCode = statusCode,
        reason = reason,
        mimeType = mimeType,
        encoding = encoding,
        headers = headers,
        body = ByteArray(0),
        bodyStream = body,
    )

    fun writeTo(output: DataOutputStream) {
        output.writeInt(MAGIC)
        output.writeInt(statusCode)
        output.writeUTF(reason)
        output.writeUTF(mimeType)
        output.writeBoolean(encoding != null)
        encoding?.let(output::writeUTF)
        output.writeInt(headers.size)
        headers.forEach { (name, value) ->
            output.writeUTF(name)
            output.writeUTF(value)
        }
    }

    companion object {
        private const val MAGIC = 0x484E5341
        private const val MAX_HEADERS = 256

        fun from(response: HnsInterceptedResponse) = CachedMetadata(
            response.statusCode,
            response.reason,
            response.mimeType,
            response.encoding,
            response.headers,
        )

        fun readFrom(input: DataInputStream): CachedMetadata {
            require(input.readInt() == MAGIC)
            val statusCode = input.readInt().also { require(it in 200..299) }
            val reason = input.readUTF()
            val mimeType = input.readUTF()
            val encoding = if (input.readBoolean()) input.readUTF() else null
            val count = input.readInt().also { require(it in 0..MAX_HEADERS) }
            val headers = linkedMapOf<String, String>()
            repeat(count) { headers[input.readUTF()] = input.readUTF() }
            return CachedMetadata(statusCode, reason, mimeType, encoding, headers)
        }
    }
}

private class PublishingInputStream(
    source: InputStream,
    private val output: BufferedOutputStream,
    private val maxBytes: Long,
    private val expectedBytes: Long?,
    private val onComplete: () -> Unit,
    private val onAbort: () -> Unit,
) : FilterInputStream(source) {
    private var bytesWritten = 0L
    private var cacheable = true
    private var complete = false
    private var closed = false

    override fun read(): Int {
        val value = super.read()
        if (value < 0) finish() else cache(byteArrayOf(value.toByte()), 0, 1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count < 0) finish() else if (count > 0) cache(buffer, offset, count)
        return count
    }

    private fun cache(buffer: ByteArray, offset: Int, length: Int) {
        if (!cacheable) return
        if (bytesWritten + length > maxBytes) {
            cacheable = false
            runCatching { output.close() }
            onAbort()
            return
        }
        try {
            output.write(buffer, offset, length)
            bytesWritten += length
        } catch (_: IOException) {
            cacheable = false
            runCatching { output.close() }
            onAbort()
        }
    }

    private fun finish() {
        if (complete) return
        complete = true
        if (cacheable) {
            runCatching {
                output.flush()
                output.close()
                onComplete()
            }.onFailure {
                onAbort()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (!complete && cacheable && expectedBytes == bytesWritten) {
            finish()
        }
        runCatching { super.close() }
        if (!complete) {
            runCatching { output.close() }
            onAbort()
        }
    }
}
