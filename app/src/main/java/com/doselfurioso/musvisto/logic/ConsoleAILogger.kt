package com.doselfurioso.musvisto.logic

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConsoleAILogger @Inject constructor(): AILogger {
    override fun log(decision: DecisionLog) {
        val msg = buildString {
            append("AI_DECISION id=${decision.decisionId} time=${decision.timestamp}\n")
            append(" player=${decision.playerId} phase=${decision.phase}\n")
            append(" hand=${decision.hand}\n")
            append(" strengths=${decision.strengths}\n")
            append(" action=${decision.chosenAction}\n")
            append(" reason=${decision.reason}\n")
            if (decision.details.isNotEmpty()) {
                append(" details=${decision.details}\n")
            }
        }
        Log.d("AILogic", msg)
    }
}