package dev.tentacle

/** Anchor tops are viewport coordinates, before and after layout respectively. */
internal fun anchoredScrollY(
    oldAnchorTop: Int,
    newAnchorTop: Int,
    currentScrollY: Int,
    maxScrollY: Int,
    wasAtTop: Boolean = currentScrollY == 0,
): Int {
    val target = if (wasAtTop) currentScrollY.toLong() else
        currentScrollY.toLong() + newAnchorTop.toLong() - oldAnchorTop.toLong()
    return target.coerceIn(0L, maxScrollY.coerceAtLeast(0).toLong()).toInt()
}
