package com.sky.siplink.core.logic

/**
 * Recognises an incoming intercom / paging call: one whose SIP headers ask this phone to answer
 * automatically. The PBX adds them when someone dials its intercom feature code.
 *
 * There is no single standard, PBXs and phones use different headers:
 *  - `Call-Info: <sip:host>;answer-after=0`  (the one liblinphone itself recognises)
 *  - `Alert-Info: <http://www.notused.com>;info=alert-autoanswer;delay=0`
 *  - `Answer-Mode: Auto` (RFC 5373)
 */
object IntercomHeaders {
    const val CALL_INFO = "Call-Info"
    const val ALERT_INFO = "Alert-Info"
    const val ANSWER_MODE = "Answer-Mode"

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
