// SPDX-License-Identifier: AGPL-3.0-or-later
// Fork-level switches. This is the "CFMOTO-first" fork: the multi-brand paths (Zontes/Voge/Benelli/
// Morini/Kove…) stay in the source — disabled, not deleted — so the CFMoto route is direct and fast
// and upstream stays mergeable.
package dev.zanderp.opencfmoto.core

object AppFlavor {
    /** false → CFMoto-only fast path (skip non-CFMoto QR parsers / transports). true → general build. */
    const val MULTI_BIKE: Boolean = false
}
