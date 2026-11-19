package com.sky.siplink.core.model

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
enum class ShortcutAction(val label: String, val hasNumber: Boolean = true) {
    /** Calls the number; during a call, holds that call and calls the number next to it. */
    SPEED_DIAL("速拨"),

    /** During a call, hands the other party to the number at once; otherwise calls it. */
    BLIND_TRANSFER("盲转"),

    /** During a call, calls the number first and transfers afterwards; otherwise calls it. */
    ATTENDED_TRANSFER("咨询转"),

    /** Sends the digits into the call in progress. */
    DTMF("发送按键"),

    /** Switches "forward every call to the number" on and off. */
    FORWARD("呼叫前转"),

    /** Switches do not disturb on and off. */
    DND("免打扰", hasNumber = false),
}

/**
 * Pictures a key can carry instead of the one of its type. The name in lower case is how an
 * icon is written in the CSV exchange format.
 */
enum class ShortcutIcon(val label: String) {
    PHONE("电话"), PERSON("个人"), GROUP("团队"), STAR("收藏"), HOME("家"), OFFICE("办公楼"), STORE("门店"),
    RECEPTION("前台"), SUPPORT("客服"), SPEAKER("广播"), MEETING("会议"), DOOR("门"), LOCK("锁"), KEY("钥匙"),
    GARAGE("车库"), CAR("车辆"), ELEVATOR("电梯"), SECURITY("保安"), CAMERA("监控"), BELL("门铃"), ALARM("报警"),
    MEDICAL("医务"), FIRE("消防"), FOOD("餐厅"), HOTEL("客房"), WAREHOUSE("仓库"), FACTORY("车间"), SCHOOL("教室"),
    REPAIR("维修"), COMPUTER("机房"), DELIVERY("物流"), LIGHT("照明"),
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
