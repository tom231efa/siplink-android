package com.sky.siplink.core.logic

import com.sky.siplink.core.model.SideCall

/**
 * Whom the PBX asserted the other side of each call to be (P-Asserted-Identity). The SIP core
 * shows the headers of the message last received on a call only, and many carry none: the BYE of
 * the PBX, the answer to our own BYE, a 100 Trying. Those do not say that the party changed back,
 * so an asserted identity stays the call's until the PBX asserts another one. It is forgotten as
 * the call is released, and nothing is taken in for a call after that.
 */
class AssertedParties {
    private val byCall = HashMap<Long, SideCall>()

    /**
     * Who names the other side of [call] now.
     *
     * @param heard whom the message last received on the call asserts; null: no one
     * @param ringingIn the call comes in and still rings: it is named by whom it says it is from
     * @param released the call is released: nothing is kept for it any more
     * @return the identity asserted last; null: the call is named by its own remote address
     */
    fun of(call: Long, heard: SideCall?, ringingIn: Boolean, released: Boolean): SideCall? {
        if (heard != null && !released) byCall[call] = heard
        return (heard ?: byCall[call]).takeUnless { ringingIn }
    }

    /** [call] is released. */
    fun forget(call: Long) {
        byCall.remove(call)
    }
}
