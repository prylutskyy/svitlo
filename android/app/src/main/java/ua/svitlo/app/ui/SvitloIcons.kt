package ua.svitlo.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small vector subset; no icon font, bitmaps or full icon-library dependency. */
object SvitloIcons {
    private fun icon(name: String, data: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.Black)).build()
    val Settings = icon("Settings", "M19.4 13a7 7 0 0 0 0-2l2-1.5-2-3.5-2.3 1A7 7 0 0 0 15.4 6L15 3h-4l-.4 3a7 7 0 0 0-1.7 1L6.6 6l-2 3.5 2 1.5a7 7 0 0 0 0 2l-2 1.5 2 3.5 2.3-1a7 7 0 0 0 1.7 1l.4 3h4l.4-3a7 7 0 0 0 1.7-1l2.3 1 2-3.5zM13 8a4 4 0 1 1 0 8 4 4 0 0 1 0-8")
    val Bolt = icon("Bolt", "M13 2L4 14h7l-1 8 10-13h-7z")
    val Add = icon("Add", "M11 5h2v6h6v2h-6v6h-2v-6H5v-2h6z")
    val Close = icon("Close", "M6.4 5L12 10.6 17.6 5 19 6.4 13.4 12 19 17.6 17.6 19 12 13.4 6.4 19 5 17.6 10.6 12 5 6.4z")
    val Check = icon("Check", "M9 16.2L4.8 12 3.4 13.4 9 19 21 7 19.6 5.6z")
    val Refresh = icon("Refresh", "M17.7 6.3A8 8 0 1 0 20 12h-2a6 6 0 1 1-1.8-4.2L13 11h8V3z")
    val More = icon("More", "M12 4a2 2 0 1 0 0 4 2 2 0 0 0 0-4M12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4M12 16a2 2 0 1 0 0 4 2 2 0 0 0 0-4")
    val Info = icon("Info", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20m1 15h-2v-6h2zm0-8h-2V7h2z")
    val Warning = icon("Warning", "M12 2L1 21h22zm1 15h-2v-2h2zm0-4h-2V8h2z")
    val Back = icon("Back", "M20 11H7.8l5.6-5.6L12 4 4 12l8 8 1.4-1.4L7.8 13H20z")
    val ArrowForward = icon("ArrowForward", "M9 5 7.6 6.4 13.2 12l-5.6 5.6L9 19l7-7z")
    val History = icon("History", "M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20m0 2a8 8 0 1 1 0 16 8 8 0 0 1 0-16m-1 2h2v5.5l4 2.4-1 1.7-5-3z")
    val Home = icon("Home", "M12 3L2 12h3v9h6v-6h2v6h6v-9h3z")
    val Notifications = icon("Notifications", "M12 22a2 2 0 0 0 2-2h-4a2 2 0 0 0 2 2M18 16v-5a6 6 0 0 0-5-5.9V3h-2v2.1A6 6 0 0 0 6 11v5l-2 2v1h16v-1z")
    val Up = icon("Up", "M12 4 4 12l1.4 1.4L11 7.8V21h2V7.8l5.6 5.6L20 12z")
    val Down = icon("Down", "M13 3h-2v13.2l-5.6-5.6L4 12l8 8 8-8-1.4-1.4-5.6 5.6z")
    val Stop = icon("Stop", "M6 6h12v12H6z")
}
