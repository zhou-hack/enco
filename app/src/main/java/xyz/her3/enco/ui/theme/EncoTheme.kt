package xyz.her3.enco.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

internal data class EncoColors(
    val backdrop: Color,
    val background: Color,
    val panel: Color,
    val inset: Color,
    val line: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentTint: Color,
    val positive: Color,
    val positiveTint: Color,
    val warning: Color,
    val error: Color,
)

private val Light =
    EncoColors(
        backdrop = Color(0xFFDCE6FC),
        background = Color(0xFFF3F3FC),
        panel = Color(0xFFFCFCFF),
        inset = Color(0xFFEDF0FA),
        line = Color(0xFFDCE1F1),
        text = Color(0xFF192235),
        muted = Color(0xFF525D75),
        accent = Color(0xFF435BA9),
        onAccent = Color.White,
        accentTint = Color(0xFFE1E8FC),
        positive = Color(0xFF29644F),
        positiveTint = Color(0xFFE0F1E9),
        warning = Color(0xFF8A5A16),
        error = Color(0xFFA23542),
    )

private val Dark =
    EncoColors(
        backdrop = Color(0xFF222E46),
        background = Color(0xFF161922),
        panel = Color(0xFF242A37),
        inset = Color(0xFF191E2A),
        line = Color(0xFF384153),
        text = Color(0xFFEDF0F8),
        muted = Color(0xFFB3BED2),
        accent = Color(0xFFB5C5FF),
        onAccent = Color(0xFF172855),
        accentTint = Color(0xFF313F64),
        positive = Color(0xFFA7DCC7),
        positiveTint = Color(0xFF253E38),
        warning = Color(0xFFFFD08A),
        error = Color(0xFFFFACB3),
    )

internal val LocalEncoColors = staticCompositionLocalOf { Light }

@Composable
internal fun EncoTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalEncoColors provides if (isSystemInDarkTheme()) Dark else Light, content = content)
}

@Composable
internal fun EncoText(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    color: Color = LocalEncoColors.current.text,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    BasicText(
        text,
        modifier,
        style = TextStyle(color = color, fontSize = size, lineHeight = size * 1.4f, fontWeight = weight),
        maxLines = maxLines,
        overflow = overflow,
    )
}
