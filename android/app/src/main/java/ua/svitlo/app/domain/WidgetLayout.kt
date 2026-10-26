package ua.svitlo.app.domain

/** Choose composition from the actual host bounds, never from the widget's marketing name. */
enum class WidgetLayout { COMPACT, WIDE, LARGE;
    companion object {
        fun forSize(width: Int, height: Int) = when {
            width < 220 -> COMPACT
            height < 180 -> WIDE
            else -> LARGE
        }
    }
}
