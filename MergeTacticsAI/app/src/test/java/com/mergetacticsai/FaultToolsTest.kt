package com.mergetacticsai

import com.mergetacticsai.util.FailureTracker
import com.mergetacticsai.util.FaultCodec
import com.mergetacticsai.util.FaultEntry
import com.mergetacticsai.util.FaultLog
import com.mergetacticsai.util.FaultSim
import com.mergetacticsai.util.NetworkProbe
import com.mergetacticsai.util.SettingsSnapshot
import com.mergetacticsai.util.SimulatedNetworkException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FaultToolsTest {
    @Before fun setUp() { FaultSim.clock = System::currentTimeMillis; FaultSim.reset(); FaultLog.clear() }
    @After fun tearDown() { FaultSim.clock = System::currentTimeMillis; FaultSim.reset(); FaultLog.clear() }

    private fun fails(block: suspend () -> Unit): Boolean = try { runBlocking { block() }; false } catch (e: SimulatedNetworkException) { true }

    // ---- симуляция сети
    @Test fun disabledPassesThrough() = assertEquals(5, runBlocking { FaultSim.gate { 5 } })
    @Test fun offlineThrows() {
        FaultSim.setEnabled(true); FaultSim.offline = true
        assertTrue(fails { FaultSim.gate { } })
    }
    @Test fun offlineIgnoredWhenModeOff() {
        FaultSim.offline = true
        assertEquals(1, runBlocking { FaultSim.gate { 1 } })
    }
    @Test fun fullLossAlwaysThrows() {
        FaultSim.setEnabled(true); FaultSim.lossPercent = 100
        repeat(20) { assertTrue(fails { FaultSim.gate { } }) }
    }
    @Test fun zeroLossNeverThrows() {
        FaultSim.setEnabled(true); FaultSim.lossPercent = 0
        repeat(20) { assertEquals(7, runBlocking { FaultSim.gate { 7 } }) }
    }
    @Test fun latencyDelaysTheCall() {
        FaultSim.setEnabled(true); FaultSim.latencyMs = 80
        val t0 = System.currentTimeMillis()
        runBlocking { FaultSim.gate { } }
        assertTrue(System.currentTimeMillis() - t0 >= 70)
    }
    @Test fun turnsItselfOff() {
        var t = 1_000L
        FaultSim.clock = { t }
        FaultSim.setEnabled(true)
        assertTrue(FaultSim.enabled)
        assertEquals(FaultSim.AUTO_OFF_MS, FaultSim.remainingMs())
        t += FaultSim.AUTO_OFF_MS + 1
        assertFalse(FaultSim.enabled)
        assertEquals(0L, FaultSim.remainingMs())
    }
    @Test fun resetClearsEverything() {
        FaultSim.setEnabled(true); FaultSim.offline = true; FaultSim.latencyMs = 500; FaultSim.lossPercent = 30
        FaultSim.reset()
        assertFalse(FaultSim.enabled); assertFalse(FaultSim.offline)
        assertEquals(0L, FaultSim.latencyMs); assertEquals(0, FaultSim.lossPercent)
    }
    @Test fun describesConditions() {
        assertEquals("режим тестирования выключен", FaultSim.describe())
        FaultSim.setEnabled(true)
        assertEquals("сеть без помех", FaultSim.describe())
        FaultSim.offline = true; FaultSim.latencyMs = 300; FaultSim.lossPercent = 20
        assertEquals("нет сети, задержка 300 мс, потеря пакетов 20%", FaultSim.describe())
    }

    // ---- повторы и восстановление
    @Test fun backoffGrowsAndStops() {
        assertEquals(listOf(3000L, 6000L, 12000L, 24000L, 30000L, 30000L), (1..6).map { FailureTracker.backoffMs(it) })
    }
    @Test fun trackerReportsRecovery() {
        var t = 0L
        val tr = FailureTracker { t }
        assertNull(tr.onSuccess())
        assertEquals(3000L, tr.onFailure())
        t += 3000
        assertEquals(6000L, tr.onFailure())
        t += 6000
        val r = tr.onSuccess()
        assertNotNull(r)
        assertEquals(2, r!!.failures)
        assertEquals(9000L, r.downMs)
        assertEquals(0, tr.consecutive)
        assertNull(tr.onSuccess())
    }

    // ---- журнал сбоев
    @Test fun codecRoundTrip() {
        val list = listOf(
            FaultEntry(2, 2000, "причина\twith tab", "ANALYZING", "итог\nвторая строка", "a\\b"),
            FaultEntry(1, 1000, "x", "IDLE", "y"),
        )
        assertEquals(list, FaultCodec.decode(FaultCodec.encode(list)))
    }
    @Test fun codecSkipsGarbage() {
        assertTrue(FaultCodec.decode("мусор\nещё мусор").isEmpty())
        assertTrue(FaultCodec.decode("").isEmpty())
    }
    @Test fun logAddUpdateClear() {
        val a = FaultLog.add("первая", "IDLE", "ждёт")
        val b = FaultLog.add("вторая", "ANALYZING", "ждёт")
        assertTrue(b > a)
        assertEquals("вторая", FaultLog.entries.value.first().reason)
        FaultLog.update(a, "восстановлено")
        assertEquals("восстановлено", FaultLog.entries.value.first { it.id == a }.result)
        assertTrue(FaultLog.text().contains("первая"))
        FaultLog.clear()
        assertTrue(FaultLog.entries.value.isEmpty())
    }
    @Test fun logKeepsOnlyLastHundred() {
        repeat(130) { FaultLog.add("запись $it", "IDLE", "ok") }
        assertEquals(FaultLog.MAX, FaultLog.entries.value.size)
        assertEquals("запись 129", FaultLog.entries.value.first().reason)
    }

    // ---- пробные запросы
    @Test fun probeIsCleanWhenModeOff() {
        val r = runBlocking { NetworkProbe.run(requests = 3, retries = 1, backoffMs = 1) }
        assertEquals(3, r.ok); assertEquals(0, r.failedAttempts)
        assertTrue(r.verdict().contains("стабильна"))
    }
    @Test fun probeFailsWhenOffline() {
        FaultSim.setEnabled(true); FaultSim.offline = true
        val r = runBlocking { NetworkProbe.run(requests = 3, retries = 1, backoffMs = 1) }
        assertEquals(0, r.ok); assertEquals(3, r.lost)
        assertEquals(6, r.failedAttempts)
        assertTrue(r.verdict().contains("не удалось"))
        assertEquals(6, r.failures.size)
    }
    @Test fun probeSummaryMentionsCounts() {
        val r = runBlocking { NetworkProbe.run(requests = 2, retries = 0, backoffMs = 1) }
        assertTrue(r.summary().contains("Запросов: 2"))
    }

    // ---- сверка настроек
    @Test fun snapshotDiffFindsChanges() {
        val before = mapOf("a" to "1", "b" to "2", "c" to "3")
        val after = mapOf("a" to "1", "b" to "9", "d" to "4")
        assertEquals(listOf("b", "c", "d"), SettingsSnapshot.diff(before, after))
        assertTrue(SettingsSnapshot.diff(before, before).isEmpty())
    }
}
