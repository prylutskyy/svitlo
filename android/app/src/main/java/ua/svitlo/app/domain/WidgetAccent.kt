package ua.svitlo.app.domain

data class AccentChoice(val key: String, val name: String, val color: Int)

/** Persist a source key for system palettes, an opaque RGB value for user colours. */
object WidgetAccent {
    val presets = listOf(
        AccentChoice("green", "Зелений", 0xFF287453.toInt()),
        AccentChoice("blue", "Синій", 0xFF3567BE.toInt()),
        AccentChoice("purple", "Фіолетовий", 0xFF8055B4.toInt()),
        AccentChoice("teal", "Бірюзовий", 0xFF007D86.toInt()),
        AccentChoice("pink", "Рожевий", 0xFFB94D7E.toInt()),
        AccentChoice("ochre", "Охра", 0xFF936B19.toInt()),
    )

    fun parseHex(value: String): Int? {
        val digits = value.trim().removePrefix("#")
        return if (digits.matches(Regex("[0-9a-fA-F]{6}"))) digits.toLong(16).toInt() or 0xFF000000.toInt() else null
    }

    fun hex(color: Int) = "#%06X".format(color and 0xFFFFFF)
}

/** Widget rendering colours, resolved once and shared by preview, bitmap and RemoteViews. */
data class WidgetColors(val background: Int, val ink: Int, val muted: Int, val accent: Int?, val dark: Boolean)
