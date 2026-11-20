package com.sky.siplink.core.model

import androidx.annotation.StringRes
import com.sky.siplink.R

/**
 * What a shortcut key does, modelled on the function keys (DSS keys) of desk phones and cut
 * down to the kinds that behave differently from one another.
 *
 * Anything the PBX offers through a feature code (intercom, paging, park, pickup, voice mail,
 * supervision, ...) is a speed dial key with that code as its number and a name of the user's
 * choice.
 *
 * @param hasNumber whether a key of this kind carries a number
 */
enum class ShortcutAction(@StringRes val label: Int, val hasNumber: Boolean = true) {
    /** Calls the number; during a call, holds that call and calls the number next to it. */
    SPEED_DIAL(R.string.shortcut_type_speed),

    /** During a call, hands the other party to the number at once; otherwise calls it. */
    BLIND_TRANSFER(R.string.shortcut_type_bxfer),

    /** During a call, calls the number first and transfers afterwards; otherwise calls it. */
    ATTENDED_TRANSFER(R.string.shortcut_type_axfer),

    /** Sends the digits into the call in progress. */
    DTMF(R.string.shortcut_type_dtmf),

    /** Switches "forward every call to the number" on and off. */
    FORWARD(R.string.shortcut_type_forward),

    /** Switches do not disturb on and off. */
    DND(R.string.shortcut_type_dnd, hasNumber = false),
}

/**
 * Pictures a key can carry instead of the one of its type. The name in lower case is how an
 * icon is written in the CSV exchange format.
 */
enum class ShortcutIcon(@StringRes val label: Int) {
    PHONE(R.string.shortcut_icon_phone), PERSON(R.string.shortcut_icon_person), GROUP(R.string.shortcut_icon_group),
    STAR(R.string.shortcut_icon_star), HOME(R.string.shortcut_icon_home), OFFICE(R.string.shortcut_icon_office),
    STORE(R.string.shortcut_icon_store), RECEPTION(R.string.shortcut_icon_reception), SUPPORT(R.string.shortcut_icon_support),
    SPEAKER(R.string.shortcut_icon_speaker), MEETING(R.string.shortcut_icon_meeting), DOOR(R.string.shortcut_icon_door),
    LOCK(R.string.shortcut_icon_lock), KEY(R.string.shortcut_icon_key), GARAGE(R.string.shortcut_icon_garage),
    CAR(R.string.shortcut_icon_car), ELEVATOR(R.string.shortcut_icon_elevator), SECURITY(R.string.shortcut_icon_security),
    CAMERA(R.string.shortcut_icon_camera), BELL(R.string.shortcut_icon_bell), ALARM(R.string.shortcut_icon_alarm),
    MEDICAL(R.string.shortcut_icon_medical), FIRE(R.string.shortcut_icon_fire), FOOD(R.string.shortcut_icon_food),
    HOTEL(R.string.shortcut_icon_hotel), WAREHOUSE(R.string.shortcut_icon_warehouse), FACTORY(R.string.shortcut_icon_factory),
    SCHOOL(R.string.shortcut_icon_school), REPAIR(R.string.shortcut_icon_repair), COMPUTER(R.string.shortcut_icon_computer),
    DELIVERY(R.string.shortcut_icon_delivery), LIGHT(R.string.shortcut_icon_light),
}

/** A programmable key, like the speed dial / function keys of a desk phone. */
data class Shortcut(
    val id: Long,
    val label: String,
    val number: String = "",
    val action: ShortcutAction = ShortcutAction.SPEED_DIAL,
    /** Free text used to group the keys; empty means "not grouped". */
    val category: String = "",
    /**
     * The account ("line") the key calls out with, by its name or its `user@domain`. Empty: the
     * user picks the line every time the key is pressed.
     */
    val line: String = "",
    /** The picture on the key; null shows the one of its [action]. */
    val icon: ShortcutIcon? = null,
    /**
     * The user made the key themselves next to a managed configuration. Such a key stays theirs
     * to change, and goes when that configuration goes. Never set while the configuration is
     * the phone's own, and no part of the CSV format.
     */
    val own: Boolean = false,
)
