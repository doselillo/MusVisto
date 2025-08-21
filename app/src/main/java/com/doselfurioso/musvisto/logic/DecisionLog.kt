package com.doselfurioso.musvisto.logic

import java.util.*


data class DecisionLog(
    val decisionId: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val playerId: String? = null,
    val phase: String,
    val hand: List<String> = emptyList(),
    val strengths: Map<String, Int>? = null,
    val chosenAction: String,
    val reason: String,
    val details: Map<String, Any?> = emptyMap()
)
