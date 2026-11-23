package com.typefree.ime.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.typefree.ime.ui.theme.LocalTypeFreeKeyboardPalette

/**
 * Pure geometric G2-like continuous rounded corner (squircle-blended cubic Bézier outline).
 * Each corner uses two symmetric cubic Bézier segments meeting at the 45° midpoint so
 * curvature transitions smoothly from zero along every straight edge without kinks.
 */
data class ContinuousRoundedCornerShape(val radius: Dp) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) {
            return Outline.Rectangle(size.toRect())
        }
        val radiusPx = with(density) { radius.toPx() }.coerceAtLeast(0f)
        val halfW = w * 0.5f
        val halfH = h * 0.5f
        val r = minOf(radiusPx, halfW, halfH)
        if (r <= 0.01f) {
            return Outline.Rectangle(size.toRect())
        }

        // Extension beyond nominal radius r gives the soft G2 lead-in from straight segments.
        val pX = minOf(r * 1.28f, halfW)
        val pY = minOf(r * 1.28f, halfH)
        // Midpoint at 45° inside each corner; c2 = 2 * m guarantees exact 45° tangent continuity.
        val m = r * 0.285f
        val c2 = 2f * m
        val c1X = c2 + (pX - c2) * 0.55f
        val c1Y = c2 + (pY - c2) * 0.55f

        val path = Path().apply {
            moveTo(pX, 0f)
            // Top edge -> Top-right corner
            lineTo(w - pX, 0f)
            cubicTo(w - c1X, 0f, w - c2, 0f, w - m, m)
            cubicTo(w, c2, w, c1Y, w, pY)
            // Right edge -> Bottom-right corner
            lineTo(w, h - pY)
            cubicTo(w, h - c1Y, w, h - c2, w - m, h - m)
            cubicTo(w - c2, h, w - c1X, h, w - pX, h)
            // Bottom edge -> Bottom-left corner
            lineTo(pX, h)
            cubicTo(c1X, h, c2, h, m, h - m)
            cubicTo(0f, h - c2, 0f, h - c1Y, 0f, h - pY)
            // Left edge -> Top-left corner
            lineTo(0f, pY)
            cubicTo(0f, c1Y, 0f, c2, m, m)
            cubicTo(c2, 0f, c1X, 0f, pX, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}

/**
 * Mechanical-style rolling counter that animates each digit vertically with spring physics
 * while keeping punctuation/unit characters (`,`, `.`, `w`, `%`, etc.) rock-steady.
 */
@Composable
fun RollingDigitCounter(
    text: String,
    style: TextStyle,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    modifier: Modifier = Modifier
) {
    var previousText by remember { mutableStateOf(text) }
    val rollingUp = remember(text, previousText) {
        val currentVal = extractNumericMagnitude(text)
        val prevVal = extractNumericMagnitude(previousText)
        currentVal >= prevVal
    }
    SideEffect {
        previousText = text
    }

    val resolvedStyle = remember(style, fontWeight) {
        style.copy(
            fontWeight = fontWeight ?: style.fontWeight,
            fontFeatureSettings = "tnum"
        )
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        text.forEachIndexed { index, ch ->
            // Key from the right so units/tens/hundreds columns remain aligned across length changes.
            val columnKey = text.length - 1 - index
            key(columnKey) {
                if (ch.isDigit()) {
                    AnimatedContent(
                        targetState = ch,
                        transitionSpec = {
                            val direction = if (rollingUp) 1 else -1
                            val slideSpec = spring<IntOffset>(
                                dampingRatio = 0.78f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                            val fadeSpec = spring<Float>(
                                dampingRatio = 0.85f,
                                stiffness = Spring.StiffnessMediumLow
                            )
                            val enter = slideInVertically(animationSpec = slideSpec) { height ->
                                height * direction
                            } + fadeIn(animationSpec = fadeSpec)
                            val exit = slideOutVertically(animationSpec = slideSpec) { height ->
                                -height * direction
                            } + fadeOut(animationSpec = fadeSpec)
                            (enter togetherWith exit).using(SizeTransform(clip = true))
                        },
                        label = "rollingDigit_$columnKey"
                    ) { digit ->
                        Text(
                            text = digit.toString(),
                            style = resolvedStyle,
                            color = color,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                } else {
                    Text(
                        text = ch.toString(),
                        style = resolvedStyle,
                        color = color,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private fun extractNumericMagnitude(text: String): Double {
    val cleaned = buildString {
        text.forEach { c ->
            if (c.isDigit() || c == '.') append(c)
        }
    }
    val base = cleaned.toDoubleOrNull() ?: 0.0
    return if (text.contains('w', ignoreCase = true)) base * 10_000.0 else base
}

/**
 * Monochrome pill segmented control with a spring-driven sliding thumb indicator.
 */
@Composable
fun SpringSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    if (options.isEmpty()) return

    val safeSelected = selectedIndex.coerceIn(0, options.lastIndex)
    val isDark = LocalTypeFreeKeyboardPalette.current.isDark
    val trackPadding = if (compact) 2.5.dp else 3.dp
    val controlHeight = if (compact) 32.dp else 40.dp
    val trackColor = if (isDark) {
        Color(0xFF14151B)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f)
    }
    val thumbColor = if (isDark) {
        Color(0xFF2B2D37)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.5f else 0.45f)

    Box(
        modifier = modifier
            .height(controlHeight)
            .clip(CircleShape)
            .background(trackColor)
            .border(0.5.dp, borderColor, CircleShape)
            .padding(trackPadding)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val segmentWidth = maxWidth / options.size
            val thumbOffset by animateDpAsState(
                targetValue = segmentWidth * safeSelected,
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = 420f
                ),
                label = "springSegmentThumbOffset"
            )

            Box(
                modifier = Modifier
                    .offset(x = thumbOffset)
                    .width(segmentWidth)
                    .fillMaxHeight()
                    .shadow(
                        elevation = if (isDark) 0.dp else 1.5.dp,
                        shape = CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.14f),
                        spotColor = Color.Black.copy(alpha = 0.12f)
                    )
                    .clip(CircleShape)
                    .background(thumbColor)
                    .border(
                        width = 0.5.dp,
                        color = if (isDark) Color.White.copy(alpha = 0.08f) else borderColor.copy(alpha = 0.4f),
                        shape = CircleShape
                    )
            )

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                options.forEachIndexed { index, label ->
                    val isSelected = index == safeSelected
                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "segmentLabelColor_$index"
                    )
                    val interactionSource = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                if (index != safeSelected) {
                                    onSelect(index)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = if (compact) 11.5.sp else 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Tactile spring press scale bounce driven by an [InteractionSource].
 */
@Composable
fun Modifier.springPressScale(
    pressedScale: Float = 0.965f,
    interactionSource: InteractionSource
): Modifier {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "springPressScale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Soft horizontal alpha mask along the leading and trailing edges of horizontally scrollable rails.
 */
fun Modifier.horizontalFadeEdge(
    startFadeWidth: Dp = 10.dp,
    endFadeWidth: Dp = 18.dp
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        if (size.width <= 0f) return@drawWithContent
        val startFrac = (startFadeWidth.toPx() / size.width).coerceIn(0f, 0.45f)
        val endFrac = (endFadeWidth.toPx() / size.width).coerceIn(0f, 0.45f)
        if (startFrac <= 0f && endFrac <= 0f) return@drawWithContent

        val stops = buildList {
            if (startFrac > 0f) {
                add(0f to Color.Transparent)
                add(startFrac to Color.Black)
            } else {
                add(0f to Color.Black)
            }
            if (endFrac > 0f) {
                add((1f - endFrac).coerceAtLeast(startFrac) to Color.Black)
                add(1f to Color.Transparent)
            } else {
                add(1f to Color.Black)
            }
        }.toTypedArray()

        drawRect(
            brush = Brush.horizontalGradient(colorStops = stops),
            blendMode = BlendMode.DstIn
        )
    }

/**
 * Spring-driven vertical expand/collapse container with synchronized fade.
 */
@Composable
fun SmoothDisclosure(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier.fillMaxWidth(),
        enter = expandVertically(
            animationSpec = spring(
                dampingRatio = 0.82f,
                stiffness = Spring.StiffnessMediumLow
            )
        ) + fadeIn(
            animationSpec = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMedium
            )
        ),
        exit = shrinkVertically(
            animationSpec = spring(
                dampingRatio = 0.86f,
                stiffness = Spring.StiffnessMedium
            )
        ) + fadeOut(
            animationSpec = spring(
                dampingRatio = 0.9f,
                stiffness = Spring.StiffnessMedium
            )
        )
    ) {
        content()
    }
}

/**
 * Mochi-inspired G2 continuous-corner selection card with interruptible spring press bounce,
 * animated border & container tint, and optional expandable body slot.
 */
@Composable
fun MochiSelectCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    cornerRadius: Dp = 20.dp,
    showCheckIndicator: Boolean = true,
    headerContent: @Composable () -> Unit,
    expandedContent: (@Composable () -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            accentColor.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "mochiSelectBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (selected) {
            accentColor
        } else {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "mochiSelectBorder"
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 1.6.dp else 1.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "mochiSelectBorderWidth"
    )
    val shape = remember(cornerRadius) { ContinuousRoundedCornerShape(cornerRadius) }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        modifier = modifier
            .fillMaxWidth()
            .springPressScale(pressedScale = 0.98f, interactionSource = interactionSource),
        shape = shape,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(borderWidth, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = if (showCheckIndicator) 12.dp else 0.dp)
                ) {
                    headerContent()
                }
                if (showCheckIndicator) {
                    val radioFill by animateColorAsState(
                        targetValue = if (selected) accentColor else Color.Transparent,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "mochiRadioFill"
                    )
                    val radioBorder by animateColorAsState(
                        targetValue = if (selected) {
                            accentColor
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
                        },
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "mochiRadioBorder"
                    )
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(radioFill)
                            .border(1.5.dp, radioBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
            if (expandedContent != null) {
                SmoothDisclosure(expanded = selected) {
                    expandedContent()
                }
            }
        }
    }
}

/**
 * Mochi-inspired G2 grouped settings container that replaces heavy boxy borders with soft
 * continuous squircle geometry and subtle hairline outlines.
 */
@Composable
fun MochiGroupedCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    borderColor: Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = remember(cornerRadius) { ContinuousRoundedCornerShape(cornerRadius) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}
