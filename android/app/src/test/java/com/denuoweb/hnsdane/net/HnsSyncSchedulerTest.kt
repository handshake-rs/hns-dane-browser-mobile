package com.denuoweb.hnsdane.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HnsSyncSchedulerTest {
    @Test
    fun requestSyncNowCoalescesIdleTimerIntoImmediateFreshnessPass() {
        val calls = AtomicInteger(0)
        val firstPass = CountDownLatch(1)
        val refreshedPass = CountDownLatch(1)
        val scheduler = HnsSyncScheduler(
            dataDir = File("/tmp/hns-dane-browser-refresh-test"),
            bridge = object : HnsSyncBridge {
                override fun syncOnce(dataDir: String): String {
                    when (calls.incrementAndGet()) {
                        1 -> firstPass.countDown()
                        2 -> refreshedPass.countDown()
                    }
                    return """{"status":"up_to_date"}"""
                }
            },
            idleIntervalMs = TimeUnit.HOURS.toMillis(1),
        )

        scheduler.start(onSnapshot = {})
        assertTrue(firstPass.await(1, TimeUnit.SECONDS))
        assertTrue(scheduler.requestSyncNow())
        assertTrue(refreshedPass.await(1, TimeUnit.SECONDS))
        // Replacing the idle timer must produce one requested pass, not leave
        // the superseded timer queued as another immediate invocation.
        Thread.sleep(50)
        assertEquals(2, calls.get())
        scheduler.close()
    }

    @Test
    fun requestSyncNowDuringActivePassRunsOnceImmediatelyAfterIt() {
        val calls = AtomicInteger(0)
        val activePassStarted = CountDownLatch(1)
        val releaseActivePass = CountDownLatch(1)
        val refreshedPass = CountDownLatch(1)
        val scheduler = HnsSyncScheduler(
            dataDir = File("/tmp/hns-dane-browser-active-refresh-test"),
            bridge = object : HnsSyncBridge {
                override fun syncOnce(dataDir: String): String {
                    when (calls.incrementAndGet()) {
                        1 -> {
                            activePassStarted.countDown()
                            releaseActivePass.await()
                        }
                        2 -> refreshedPass.countDown()
                    }
                    return """{"status":"up_to_date"}"""
                }
            },
            idleIntervalMs = TimeUnit.HOURS.toMillis(1),
        )

        scheduler.start(onSnapshot = {})
        assertTrue(activePassStarted.await(1, TimeUnit.SECONDS))
        assertTrue(scheduler.requestSyncNow())
        releaseActivePass.countDown()
        assertTrue(refreshedPass.await(1, TimeUnit.SECONDS))
        assertEquals(2, calls.get())
        scheduler.close()
    }

    @Test
    fun runOncePublishesNativeSyncSnapshot() {
        val dataDir = File("/tmp/hns-dane-browser-test")
        val bridge = RecordingSyncBridge(
            """{"status":"idle","attempted":0,"successful":0,"accepted":0,"peerCount":0,"peerGroups":0,"bestHeight":0,"bestPeerHeight":null,"resourceCacheEntries":0,"resourceCacheBytes":0,"resourceCacheEvicted":0,"error":null}""",
        )
        val scheduler = HnsSyncScheduler(
            dataDir = dataDir,
            bridge = bridge,
            clock = { 1234L },
        )
        var snapshot: HnsSyncSnapshot? = null
        var syncStartingCount = 0

        scheduler.runOnce(
            onSnapshot = { snapshot = it },
            onSyncStarting = { syncStartingCount += 1 },
        )

        assertEquals(dataDir.absolutePath, bridge.paths.single())
        assertEquals(1234L, snapshot?.updatedAtMillis)
        assertEquals(bridge.response, snapshot?.statusJson)
        assertSame(snapshot, scheduler.lastSnapshot)
        assertEquals(1, syncStartingCount)
        scheduler.close()
    }

    @Test
    fun nextDelayUsesActiveIntervalOnlyWhileHeadersAreAdvancing() {
        val scheduler = HnsSyncScheduler(
            dataDir = File("/tmp/hns-dane-browser-test"),
            bridge = RecordingSyncBridge("{}"),
            activeIntervalMs = 7L,
            retryIntervalMs = 11L,
            idleIntervalMs = 13L,
        )

        assertEquals(
            13L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"status":"syncing","bestHeight":45000,"bestPeerHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":290684,"freshness":"stale","freshnessThresholdBlocks":2,"targetSource":"corroboratedPeers"}""",
                    updatedAtMillis = 1L,
                ),
            ),
        )
        assertEquals(
            13L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"up_to_date","bestHeight":335684,"bestPeerHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
                    updatedAtMillis = 2L,
                ),
            ),
        )
        assertEquals(
            13L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"synced","accepted":1,"bestHeight":335684,"bestPeerHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
                    updatedAtMillis = 6L,
                ),
            ),
        )
        assertEquals(
            13L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"attempted","bestHeight":335684,"bestPeerHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":0,"freshness":"current","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
                    updatedAtMillis = 7L,
                ),
            ),
        )
        assertEquals(
            7L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"status":"syncing","accepted":2000,"bestHeight":92000,"bestPeerHeight":null,"estimatedTipHeight":335684,"effectiveTargetHeight":null,"lagBlocks":null,"freshness":"unknown","freshnessThresholdBlocks":2,"targetSource":"unknown","peerCount":0}""",
                    updatedAtMillis = 3L,
                ),
            ),
        )
        assertEquals(
            11L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"up_to_date","accepted":0,"bestHeight":335684,"bestPeerHeight":335684,"effectiveTargetHeight":null,"lagBlocks":null,"freshness":"unknown","freshnessThresholdBlocks":2,"targetSource":"unknown","peerCount":505}""",
                    updatedAtMillis = 9L,
                ),
            ),
        )
        assertEquals(
            13L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"syncStatusSchemaVersion":3,"network":"regtest","status":"syncing","accepted":0,"bestHeight":42,"effectiveTargetHeight":null,"freshness":"unknown","targetSource":"unknown","peerCount":1}""",
                    updatedAtMillis = 10L,
                ),
            ),
        )
        assertEquals(
            13L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"status":"up_to_date","accepted":0,"bestHeight":335680,"bestPeerHeight":null,"estimatedTipHeight":335684,"peerCount":23}""",
                    updatedAtMillis = 8L,
                ),
            ),
        )
        assertEquals(
            11L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"status":"peer_failed","bestHeight":45000,"bestPeerHeight":335684}""",
                    updatedAtMillis = 4L,
                ),
            ),
        )
        assertEquals(
            11L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"status":"idle","bestHeight":null,"bestPeerHeight":null,"estimatedTipHeight":335684,"peerCount":0}""",
                    updatedAtMillis = 5L,
                ),
            ),
        )
        assertEquals(
            13L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"syncing","accepted":16,"bestHeight":335670,"bestPeerHeight":335684,"effectiveTargetHeight":335684,"lagBlocks":14,"freshness":"stale","freshnessThresholdBlocks":2,"treeIntervalBlocks":36,"authoritativeTreeRootHeight":335665,"localTreeRootHeight":335665,"treeRootReady":true,"blocksUntilAuthoritativeTreeRoot":0,"targetSource":"corroboratedPeers","targetPeerGroups":3,"targetEvidenceExpired":false}""",
                    updatedAtMillis = 11L,
                ),
            ),
        )
        scheduler.close()
    }

    @Test
    fun postResetMainnetGenesisRetriesWithoutHotPollingLegacyOrRegtestStatus() {
        val scheduler = HnsSyncScheduler(
            dataDir = File("/tmp/hns-dane-browser-test"),
            bridge = RecordingSyncBridge("{}"),
            activeIntervalMs = 7L,
            retryIntervalMs = 11L,
            idleIntervalMs = 13L,
        )

        listOf("idle", "syncing", "up_to_date", "attempted", "synced").forEach { status ->
            assertEquals(
                11L,
                scheduler.nextDelayMs(
                    HnsSyncSnapshot(
                        statusJson = """{"syncStatusSchemaVersion":3,"network":"mainnet","status":"$status","bestHeight":0,"peerCount":23}""",
                        updatedAtMillis = 1L,
                    ),
                ),
            )
        }
        assertEquals(
            13L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"syncStatusSchemaVersion":3,"network":"regtest","status":"idle","bestHeight":0,"peerCount":2}""",
                    updatedAtMillis = 2L,
                ),
            ),
        )
        assertEquals(
            13L,
            scheduler.nextDelayMs(
                HnsSyncSnapshot(
                    statusJson = """{"network":"mainnet","status":"idle","bestHeight":0,"peerCount":23}""",
                    updatedAtMillis = 3L,
                ),
            ),
        )
        scheduler.close()
    }

    @Test
    fun singleFlightRejectsOverlappingNativeWorkWithoutBlocking() {
        val gate = HnsSyncSingleFlight()
        var nestedRan = false

        val outer = gate.tryRun {
            val nested = gate.tryRun { nestedRan = true }
            assertNull(nested)
            "done"
        }

        assertEquals("done", outer)
        assertEquals(false, nestedRan)
        assertEquals(false, gate.isRunning())
    }

    @Test
    fun explicitMaintenanceWaitsForCurrentSyncThenRunsExclusively() {
        val gate = HnsSyncSingleFlight()
        val syncStarted = CountDownLatch(1)
        val releaseSync = CountDownLatch(1)
        val maintenanceFinished = CountDownLatch(1)
        val order = mutableListOf<String>()
        val syncThread = Thread {
            gate.tryRun {
                synchronized(order) { order += "sync" }
                syncStarted.countDown()
                releaseSync.await()
            }
        }
        val maintenanceThread = Thread {
            gate.runExclusive {
                synchronized(order) { order += "maintenance" }
            }
            maintenanceFinished.countDown()
        }

        syncThread.start()
        assertTrue(syncStarted.await(1, TimeUnit.SECONDS))
        maintenanceThread.start()
        assertFalse(maintenanceFinished.await(100, TimeUnit.MILLISECONDS))
        releaseSync.countDown()

        assertTrue(maintenanceFinished.await(1, TimeUnit.SECONDS))
        syncThread.join(1_000)
        maintenanceThread.join(1_000)
        assertEquals(listOf("sync", "maintenance"), synchronized(order) { order.toList() })
        assertFalse(gate.isRunning())
    }

    private class RecordingSyncBridge(
        val response: String,
    ) : HnsSyncBridge {
        val paths = mutableListOf<String>()

        override fun syncOnce(dataDir: String): String {
            paths += dataDir
            return response
        }
    }
}
