package com.sky.siplink.core.model

/**
 * A recorded call, as the list of recordings shows it.
 *
 * @param id the name of its file without the extension; unique
 * @param file the name of the file in the app's directory of recordings
 * @param number the other side's number (user part of its address); empty when it is not known
 * @param name the other side's name, as the call screen showed it; empty when it is not known
 * @param line the line (account) the call was on, as `user@domain`
 * @param startedAt when the recording started, in ms since the epoch
 * @param durationMs how long the recorded sound is
 * @param bytes the size of the file
 */
data class Recording(
    val id: String,
    val file: String,
    val number: String,
    val name: String,
    val incoming: Boolean,
    val line: String,
    val startedAt: Long,
    val durationMs: Long,
    val bytes: Long,
)
