package com.twocents.player.data

import android.util.Log

fun interface RadioDiagnosticLogger {
    fun debug(message: String)
}

object NoOpRadioDiagnosticLogger : RadioDiagnosticLogger {
    override fun debug(message: String) = Unit
}

object AndroidRadioDiagnosticLogger : RadioDiagnosticLogger {
    override fun debug(message: String) {
        Log.d(TAG, message)
    }

    private const val TAG = "RadioEngine"
}
