package com.denuoweb.hnsdane.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeRuntimeHandleRegistryTest {
    @Test
    fun initialLongOperationDowngradesToAReadLeaseSoStatusCanProceed() {
        val registry = NativeRuntimeHandleRegistry<String>()
        val creations = AtomicInteger(0)
        val initialOperationStarted = CountDownLatch(1)
        val releaseInitialOperation = CountDownLatch(1)
        val initialOperationFinished = CountDownLatch(1)
        val statusOperationStarted = CountDownLatch(1)
        val initialFailure = AtomicReference<Throwable?>(null)

        val initialOperation = thread(name = "initial-runtime-operation") {
            try {
                registry.withHandle(
                    key = "mainnet",
                    unavailable = "unavailable",
                    createHandle = { creations.incrementAndGet(); 7L },
                ) {
                    initialOperationStarted.countDown()
                    assertTrue(releaseInitialOperation.await(1, TimeUnit.SECONDS))
                    "synced"
                }
            } catch (failure: Throwable) {
                initialFailure.set(failure)
            } finally {
                initialOperationFinished.countDown()
            }
        }
        assertTrue(initialOperationStarted.await(1, TimeUnit.SECONDS))

        val statusOperation = thread(name = "initial-runtime-status") {
            registry.withHandle(
                key = "mainnet",
                unavailable = "unavailable",
                createHandle = { error("status must reuse the created handle") },
            ) {
                statusOperationStarted.countDown()
                "status"
            }
        }

        assertTrue(
            "a concurrent status read must not wait for the initial long operation",
            statusOperationStarted.await(1, TimeUnit.SECONDS),
        )
        releaseInitialOperation.countDown()
        initialOperation.join(1_000)
        statusOperation.join(1_000)

        assertNull(initialFailure.get())
        assertEquals(1, creations.get())
        assertEquals(false, initialOperation.isAlive)
        assertEquals(false, statusOperation.isAlive)
        assertEquals(0L, initialOperationFinished.count)
    }
}
