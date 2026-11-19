package com.sky.siplink.core.logic

import com.sky.siplink.core.model.SipAccount

/** The list of accounts the screens get, changed one row at a time while many accounts change at once. */
object AccountRows {
    /**
     * [rows] after the account that was [id] became [row], or went ([row] null), with [defaultId]
     * the default account now: a changed row keeps its place, a new one comes last, and only the
     * default one is marked as that. Rows that do not change stay the same objects.
     */
    fun replace(rows: List<SipAccount>, id: String, row: SipAccount?, defaultId: String?): List<SipAccount> {
        val list = rows.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        when {
            row == null -> if (index >= 0) list.removeAt(index)
            index >= 0 -> list[index] = row
            else -> list += row
        }
        return list.map { if (it.isDefault == (it.id == defaultId)) it else it.copy(isDefault = it.id == defaultId) }
    }
}
