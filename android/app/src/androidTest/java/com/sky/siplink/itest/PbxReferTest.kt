package com.sky.siplink.itest

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The PBX asks the app to call somebody else (REFER sent by the Transfer() application).
 *
 * Not part of the default run of testenv/run-tests.sh: the SIP library reports the progress of
 * the new call in a NOTIFY without a Contact header, which Asterisk answers with
 * "400 Missing Contact header". Transfer() then never learns the result and Asterisk 22 keeps
 * listing the released channel, which makes the other tests wait for a PBX that never looks
 * idle. Run this class on its own, from an emulator on the test network (the PBX refers to its
 * address there), and restart the PBX afterwards:
 *
 *   ./run-tests.sh com.sky.siplink.itest.PbxReferTest && docker compose restart asterisk
 */
@RunWith(AndroidJUnit4::class)
class PbxReferTest : PbxTestBase() {
    @Test
    fun followsTransferRequestedByThePbx() {
        register()
        dial("2030") // answers, then asks us to call 2000 instead
        awaitConnected("2030")
        awaitConnected("2000")
        awaitAppHearsAudio() // 2000 echoes our tone
        await("the original call to be released") { onCore { engine.core.callsNb } == 1 }
    }
}
