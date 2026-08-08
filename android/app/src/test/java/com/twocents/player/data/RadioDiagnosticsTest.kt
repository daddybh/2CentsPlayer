package com.twocents.player.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioDiagnosticsTest {
    @Test
    fun noOpLogger_isSafeInJvmTests() {
        NoOpRadioDiagnosticLogger.debug("candidate recall completed")
    }

    @Test
    fun diagnosticHistory_persistsOnlyTheMostRecentEntries() {
        var storedRaw: String? = null
        var nowMs = 100L
        val history = RadioDiagnosticHistory(
            loadRaw = { storedRaw },
            saveRaw = { raw -> storedRaw = raw },
            clock = { nowMs++ },
            maximumEntries = 3,
        )

        listOf("one", "two", "three", "four").forEach(history::append)

        assertEquals(listOf("two", "three", "four"), history.readRecent().map { it.message })
        assertEquals(listOf(101L, 102L, 103L), history.readRecent().map { it.timestampMs })
    }
}
