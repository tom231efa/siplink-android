package com.sky.siplink.core.logic

import com.sky.siplink.core.model.IntercomHeaderMode

/**
 * SIP headers used to ask the remote phone to answer automatically (intercom / paging call),
 * and the matching detection for incoming calls.
 *
 * There is no single standard, vendors honour different headers:
 *  - `Call-Info: <sip:host>;answer-after=0`  (the one liblinphone itself recognises)
 *  - `Alert-Info: <http://www.notused.com>;info=alert-autoanswer;delay=0`
 *  - `Answer-Mode: Auto` (RFC 5373)
 */
object IntercomHeaders {
    const val CALL_INFO = "Call-Info"
    const val ALERT_INFO = "Alert-Info"
    const val ANSWER_MODE = "Answer-Mode"

    data class Header(val name: String, val value: String)

    fun outgoing(mode: IntercomHeaderMode, domain: String): List<Header> {
        val host = domain.ifBlank { "localhost" }
        val callInfo = Header(CALL_INFO, "<sip:$host>;answer-after=0")
        val alertInfo = Header(ALERT_INFO, "<http://www.notused.com>;info=alert-autoanswer;delay=0")
        val answerMode = Header(ANSWER_MODE, "Auto")
        return when (mode) {
            IntercomHeaderMode.CALL_INFO -> listOf(callInfo)
            IntercomHeaderMode.ALERT_INFO -> listOf(alertInfo)
            IntercomHeaderMode.ANSWER_MODE -> listOf(answerMode)
            IntercomHeaderMode.ALL -> listOf(callInfo, alertInfo, answerMode)
        }
    }

    /** True when any of the received header values asks for an automatic answer. */
    fun requestsAutoAnswer(callInfo: String?, alertInfo: String?, answerMode: String?): Boolean {
        if (callInfo != null && callInfo.contains("answer-after=", ignoreCase = true)) return true
        if (alertInfo != null) {
            val value = alertInfo.lowercase()
            if ("alert-autoanswer" in value || "auto answer" in value || "intercom" in value) return true
        }
        if (answerMode != null && answerMode.trim().substringBefore(';').trim().equals("auto", ignoreCase = true)) {
            return true
        }
        return false
    }
}
