// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

/** Shared dash-clock helpers (BLE name matching). */
internal object DashClock {
    fun nameLooksLikeDash(name: String?): Boolean {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) return false
        val u = n.uppercase()
        return DASH_NAME_MARKERS.any { u.contains(it) }
    }

    private val DASH_NAME_MARKERS = listOf(
        "MLN", "ZM_", "ZM-", "VOGE", "CFMOTO", "QJ", "GRIFFIN", "MORINI",
        "XCAPE", "X-CAPE", "CARBIT", "EASYCONN", "YUNMO", "ALLTR",
        "P2P", "SOFTAP",
        "ZT", // Zontes (e.g. ZT851066 — ZT125T-X 125X dash BT name), confirmed via Discord #clock-issue
    )
}
