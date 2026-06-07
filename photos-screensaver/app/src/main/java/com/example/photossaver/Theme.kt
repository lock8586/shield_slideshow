package com.example.photossaver

enum class ThemeMode { WEIGHTED, DATE }

/**
 * Slideshow selection style.
 *  - WEIGHTED: bucket photos by age (recent = this/last year, mid = 1–3 yrs, old = older)
 *    and pick with the given weights.
 *  - DATE: anniversary-style — only photos near today's date, across all years.
 *    windowDays > 0 = within ±N days of today; windowDays == 0 = the whole current month.
 */
enum class Theme(
    val key: String,
    val label: String,
    val desc: String,
    val mode: ThemeMode,
    val wRecent: Int = 0,
    val wMid: Int = 0,
    val wOld: Int = 0,
    val windowDays: Int = 0
) {
    RECENT_MIX("recent_mix", "Recent Mix",
        "Mostly recent, with a steady sprinkle of older memories",
        ThemeMode.WEIGHTED, 50, 25, 25),
    LATEST("latest", "Latest",
        "Heavily favors your newest photos",
        ThemeMode.WEIGHTED, 80, 15, 5),
    THIS_MONTH("this_month", "This Month",
        "Photos taken this month — across all years",
        ThemeMode.DATE, windowDays = 0),
    THIS_WEEK("this_week", "This Week",
        "Photos from around today's date — across all years",
        ThemeMode.DATE, windowDays = 3);

    companion object {
        fun from(key: String?): Theme = values().firstOrNull { it.key == key } ?: RECENT_MIX
    }
}
