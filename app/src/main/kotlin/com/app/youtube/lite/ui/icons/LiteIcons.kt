package com.app.youtube.lite.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The handful of icons this app needs that are *not* in `material-icons-core`.
 *
 * The alternative — pulling in `material-icons-extended` — adds roughly 4 MB of dex and tens of
 * thousands of generated classes to the APK for glyphs, of which this app uses about ten. Each
 * vector below is therefore declared by hand: a `moveTo`/`lineTo`/`arcTo` path whose coordinate
 * space is the 24×24 grid Material uses, so they line up pixel-for-pixel with the core icons next
 * to them (`Icons.Filled.PlayArrow`, `Icons.Filled.Settings`, and so on).
 *
 * Geometry notes worth keeping: the play/pause pair is deliberately drawn *fat* (bars 4 units wide
 * against a 24-unit grid) so that the play/pause affordance stays legible over bright video, and
 * the skip icons keep a clear gap between the bar and the triangle — at 20 dp the two elements
 * otherwise merge into one blob.
 */
object LiteIcons {

    /** Two rounded bars. */
    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Pause",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 5f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(-4f)
            close()
            moveTo(14f, 5f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(-4f)
            close()
        }.build()
    }

    /** Triangle plus a bar: "skip to next". */
    val SkipNext: ImageVector by lazy {
        ImageVector.Builder(
            name = "SkipNext",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black)) {
            moveTo(6f, 6f)
            lineTo(14.5f, 12f)
            lineTo(6f, 18f)
            close()
            moveTo(16f, 6f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(12f)
            horizontalLineToRelative(-2f)
            close()
        }.build()
    }

    /** Mirror of [SkipNext]. */
    val SkipPrevious: ImageVector by lazy {
        ImageVector.Builder(
            name = "SkipPrevious",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black)) {
            moveTo(18f, 6f)
            lineTo(9.5f, 12f)
            lineTo(18f, 18f)
            close()
            moveTo(6f, 6f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(12f)
            horizontalLineToRelative(-2f)
            close()
        }.build()
    }

    /** Four corner brackets: enter fullscreen. */
    val Fullscreen: ImageVector by lazy {
        ImageVector.Builder(
            name = "Fullscreen",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 5f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-4f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(13f, 5f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-4f)
            horizontalLineToRelative(-4f)
            close()
            moveTo(5f, 13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-6f)
            close()
            moveTo(17f, 13f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-6f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(4f)
            close()
        }.build()
    }

    /** Brackets pointing inward: leave fullscreen. */
    val FullscreenExit: ImageVector by lazy {
        ImageVector.Builder(
            name = "FullscreenExit",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black)) {
            moveTo(5f, 5f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-6f)
            close()
            moveTo(17f, 5f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-6f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(4f)
            close()
            moveTo(5f, 13f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-4f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(13f, 13f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-4f)
            horizontalLineToRelative(-4f)
            close()
        }.build()
    }

    /** A stacked deck with a play triangle: "Subscriptions" / "Library" style affordances. */
    val Subscriptions: ImageVector by lazy {
        ImageVector.Builder(
            name = "Subscriptions",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            // Top rail.
            moveTo(4f, 4f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-16f)
            close()
            // Middle rail.
            moveTo(5f, 8f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-14f)
            close()
            // Body with a play hole punched out (EvenOdd).
            moveTo(3f, 12f)
            horizontalLineToRelative(18f)
            verticalLineToRelative(8f)
            horizontalLineToRelative(-18f)
            close()
            moveTo(10f, 14.5f)
            lineTo(15f, 16f)
            lineTo(10f, 17.5f)
            close()
        }.build()
    }

    /** A film-strip frame with a play triangle. */
    val VideoLibrary: ImageVector by lazy {
        ImageVector.Builder(
            name = "VideoLibrary",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd) {
            moveTo(4f, 5f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(12f)
            horizontalLineToRelative(-16f)
            close()
            moveTo(10f, 8f)
            lineTo(15f, 11f)
            lineTo(10f, 14f)
            close()
            moveTo(6f, 19f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(1.5f)
            horizontalLineToRelative(-12f)
            close()
        }.build()
    }


    /** Headphones: the "audio only" toggle. */
    val Headphones: ImageVector by lazy {
        ImageVector.Builder(
            name = "Headphones",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 3f)
            curveTo(8.1f, 3f, 5f, 6.1f, 5f, 10f)
            verticalLineToRelative(9f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(-8f)
            horizontalLineTo(7f)
            verticalLineToRelative(-1f)
            curveToRelative(0f, -2.8f, 2.2f, -5f, 5f, -5f)
            reflectiveCurveToRelative(5f, 2.2f, 5f, 5f)
            verticalLineToRelative(1f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(8f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(-9f)
            curveTo(19f, 6.1f, 15.9f, 3f, 12f, 3f)
            close()
        }.build()
    }



}
