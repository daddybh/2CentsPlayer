package com.twocents.player.data

import org.junit.Test

class RadioDiagnosticsTest {
    @Test
    fun noOpLogger_isSafeInJvmTests() {
        NoOpRadioDiagnosticLogger.debug("candidate recall completed")
    }
}
