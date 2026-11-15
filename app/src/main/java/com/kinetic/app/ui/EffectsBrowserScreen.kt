package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class EffectCategory(
    val title: String,
    val icon: IconKind,
    val accent: Color,
    val background: Color,
    val effects: List<String> = emptyList()
)

private val effectCategories = listOf(
    EffectCategory("COLOR & LIGHT", IconKind.ADJ, Color(0xFFFF82B2), Color(0xFF281C2A), listOf("Exposure And Gamma")),
    EffectCategory("STYLIZE", IconKind.EFFECTS, Color(0xFFC0A2FF), Color(0xFF241E35)),
    EffectCategory("DRAWING & EDGE", IconKind.GRAPH, Color(0xFFFFC078), Color(0xFF30251D), listOf("Deep Glow")),
    EffectCategory("MOTION", IconKind.MOTION, Color(0xFF86B7FF), Color(0xFF1D2940), listOf("Oscillate")),
    EffectCategory("DISTORTION & WARP", IconKind.BLEND, Color(0xFF70DAD4), Color(0xFF1A2D32),
        listOf("Motion Tile", "Wave Warp", "Warp Shake", "RGB Split")),
    EffectCategory("PROCEDURAL", IconKind.GRID, Color(0xFFB5A4FF), Color(0xFF252238)),
    EffectCategory("BLUR", IconKind.FILTER, Color(0xFF8BC8FF), Color(0xFF1D2B39)),
    EffectCategory("3D", IconKind.SHAPES, Color(0xFFFF987C), Color(0xFF332326)),
    EffectCategory("TEXT", IconKind.TEXT, Color(0xFFFFD77A), Color(0xFF332B1F)),
    EffectCategory("AUDIO", IconKind.WAVEFORM, Color(0xFF79D8A7), Color(0xFF1B302A)),
    EffectCategory("OTHER", IconKind.MORE, Color(0xFFB2C0D6), Color(0xFF242B37))
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EffectsBrowserScreen(onClose: () -> Unit, onAddEffect: (String) -> Unit) {
    val shelfPager = rememberPagerState(pageCount = { 2 })
    var selectedCategory by remember { mutableStateOf<EffectCategory?>(null) }

    Column(
        Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth().height(42.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selectedCategory != null) {
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { selectedCategory = null },
                    contentAlignment = Alignment.Center) {
                    KIcon(IconKind.BACK, KColors.Muted, Modifier.size(19.dp))
                }
                Spacer(Modifier.size(8.dp))
            }
            Text(selectedCategory?.title?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Effects",
                Modifier.weight(1f), color = KColors.Text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center) {
                KIcon(IconKind.CLOSE, KColors.Muted, Modifier.size(20.dp))
            }
        }

        val visibleCategories = if (selectedCategory == null) effectCategories else listOf(selectedCategory!!)
        visibleCategories.forEach { category ->
            Row(
                Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(category.title, color = KColors.Text, fontSize = 10.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Spacer(Modifier.weight(1f))
                Text("${category.effects.size}", color = KColors.Muted, fontSize = 9.sp)
            }
            category.effects.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { effect ->
                        EffectChoice(effect, category.accent, Modifier.weight(1f)) { onAddEffect(effect) }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ShelfSection(title: String, emptyMessage: String, icon: IconKind, accent: Color) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KIcon(icon, accent, Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(title, color = KColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(7.dp))
        Text(emptyMessage, color = KColors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EffectChoice(name: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier.aspectRatio(0.96f).clip(RoundedCornerShape(12.dp))
            .background(KColors.Surface)
            .border(1.dp, KColors.Line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(topStart = 11.dp, topEnd = 11.dp))) {
            EffectArtwork(name, accent, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(23.dp)
                    .clip(CircleShape).background(KColors.Bg.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center
            ) {
                KIcon(IconKind.PLUS, accent, Modifier.size(12.dp))
            }
        }
        Column(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 3.dp, vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(name, color = KColors.Text, fontSize = 8.sp, lineHeight = 9.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun EffectArtwork(name: String, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val seed = name.hashCode()
        val second = when (kotlin.math.abs(seed % 5)) {
            0 -> Color(0xFF75D5D0)
            1 -> Color(0xFFFFA66D)
            2 -> Color(0xFF9C8BFF)
            3 -> Color(0xFF8FC5FF)
            else -> Color(0xFFFF83B2)
        }
        drawRect(Brush.linearGradient(listOf(
            KColors.Bg, accent.copy(alpha = 0.62f), second.copy(alpha = 0.72f), KColors.Surface
        ), start = Offset(0f, 0f), end = Offset(w, h)))
        drawCircle(Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.30f), Color.Transparent),
            center = Offset(w * 0.32f, h * 0.34f), radius = w * 0.58f
        ), radius = w * 0.58f, center = Offset(w * 0.32f, h * 0.34f))
        when {
            name.contains("Glow", true) || name.contains("Light", true) -> {
                drawCircle(Color.White.copy(alpha = 0.22f), w * 0.22f, Offset(w * 0.5f, h * 0.48f))
                drawCircle(accent.copy(alpha = 0.60f), w * 0.14f, Offset(w * 0.5f, h * 0.48f))
            }
            name.contains("Warp", true) || name.contains("Wave", true) || name.contains("Shake", true) -> {
                for (i in 0..5) {
                    val y = h * (0.16f + i * 0.13f)
                    val p = androidx.compose.ui.graphics.Path().apply {
                        moveTo(0f, y)
                        cubicTo(w * 0.25f, y - h * 0.16f, w * 0.65f, y + h * 0.16f, w, y - h * 0.02f)
                    }
                    drawPath(p, Color.White.copy(alpha = 0.34f - i * 0.035f),
                        style = Stroke(width = (w * 0.018f).coerceAtLeast(1f)))
                }
            }
            name.contains("RGB", true) || name.contains("Color", true) || name.contains("Hue", true) -> {
                val sw = w / 3f
                drawRect(Color(0xFFFF6262).copy(alpha = 0.60f), Offset(0f, 0f), androidx.compose.ui.geometry.Size(sw, h))
                drawRect(Color(0xFF5FE5A5).copy(alpha = 0.48f), Offset(sw, 0f), androidx.compose.ui.geometry.Size(sw, h))
                drawRect(Color(0xFF65A8FF).copy(alpha = 0.60f), Offset(sw * 2f, 0f), androidx.compose.ui.geometry.Size(sw, h))
            }
            else -> {
                for (i in 0..7) {
                    val x = w * (0.10f + (i * 37 % 80) / 100f)
                    val y = h * (0.12f + (i * 23 % 72) / 100f)
                    val r = w * (0.045f + (i % 3) * 0.025f)
                    drawCircle(Color.White.copy(alpha = 0.28f), r, Offset(x, y))
                }
                drawRoundRect(Color.White.copy(alpha = 0.25f),
                    Offset(w * 0.16f, h * 0.28f), androidx.compose.ui.geometry.Size(w * 0.68f, h * 0.42f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.06f), style = Stroke(w * 0.018f))
            }
        }
        drawRect(Color.Black.copy(alpha = 0.08f))
    }
}
@Composable
private fun CategoryTile(category: EffectCategory, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(modifier.height(116.dp).clip(RoundedCornerShape(15.dp)).background(category.background)
        .border(1.dp, KColors.Line.copy(alpha = 0.65f), RoundedCornerShape(15.dp)).clickable(onClick = onClick)) {
        CategoryArtwork(category, Modifier.fillMaxSize())
        Column(Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            KIcon(category.icon, category.accent, Modifier.size(27.dp))
            Spacer(Modifier.height(9.dp))
            Text(category.title, color = Color.White.copy(alpha = 0.94f), fontSize = 10.sp, lineHeight = 12.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CategoryArtwork(category: EffectCategory, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val side = minOf(w, h)
        drawRect(category.background)
        drawCircle(Brush.radialGradient(
            listOf(category.accent.copy(alpha = 0.13f), Color.Transparent),
            center = Offset(w * 0.78f, h * 0.18f), radius = side * 0.88f
        ), radius = side * 0.88f, center = Offset(w * 0.78f, h * 0.18f))
        when (category.icon) {
            IconKind.ADJ -> {
                drawCircle(category.accent.copy(alpha = 0.09f), side * 0.34f, Offset(w * 0.82f, h * 0.72f))
                drawCircle(category.accent.copy(alpha = 0.14f), side * 0.17f, Offset(w * 0.82f, h * 0.72f), style = Stroke(side * 0.012f))
            }
            IconKind.EFFECTS -> drawRoundRect(category.accent.copy(alpha = 0.08f), Offset(w * 0.70f, h * 0.10f),
                androidx.compose.ui.geometry.Size(w * 0.25f, h * 0.72f), androidx.compose.ui.geometry.CornerRadius(side * 0.07f))
            IconKind.GRAPH -> {
                drawLine(category.accent.copy(alpha = 0.12f), Offset(w * 0.72f, h * 0.2f), Offset(w * 0.92f, h * 0.8f), side * 0.018f)
                drawLine(category.accent.copy(alpha = 0.12f), Offset(w * 0.78f, h * 0.8f), Offset(w * 0.94f, h * 0.25f), side * 0.018f)
            }
            IconKind.MOTION -> {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.68f, h * 0.62f)
                    cubicTo(w * 0.74f, h * 0.12f, w * 0.83f, h * 0.88f, w * 0.96f, h * 0.38f)
                }
                drawPath(path, category.accent.copy(alpha = 0.18f), style = Stroke(side * 0.035f))
                drawCircle(category.accent.copy(alpha = 0.12f), side * 0.19f, Offset(w * 0.82f, h * 0.52f), style = Stroke(side * 0.012f))
            }
            IconKind.BLEND -> {
                drawCircle(category.accent.copy(alpha = 0.08f), side * 0.27f, Offset(w * 0.82f, h * 0.68f))
                drawCircle(category.accent.copy(alpha = 0.11f), side * 0.18f, Offset(w * 0.82f, h * 0.68f), style = Stroke(side * 0.014f))
            }
            IconKind.GRID -> for (x in 0..2) for (y in 0..2) {
                drawCircle(category.accent.copy(alpha = 0.12f), side * 0.018f, Offset(w * (0.74f + x * 0.075f), h * (0.25f + y * 0.22f)))
            }
            IconKind.FILTER -> drawCircle(category.accent.copy(alpha = 0.10f), side * 0.27f, Offset(w * 0.82f, h * 0.66f), style = Stroke(side * 0.035f))
            IconKind.SHAPES -> drawRoundRect(category.accent.copy(alpha = 0.09f), Offset(w * 0.72f, h * 0.18f),
                androidx.compose.ui.geometry.Size(w * 0.22f, h * 0.58f), androidx.compose.ui.geometry.CornerRadius(side * 0.035f), style = Stroke(side * 0.018f))
            IconKind.TEXT -> {
                drawLine(category.accent.copy(alpha = 0.13f), Offset(w * 0.73f, h * 0.34f), Offset(w * 0.94f, h * 0.34f), side * 0.025f)
                drawLine(category.accent.copy(alpha = 0.13f), Offset(w * 0.73f, h * 0.52f), Offset(w * 0.88f, h * 0.52f), side * 0.025f)
            }
            IconKind.WAVEFORM -> for (i in 0..4) {
                val x = w * (0.74f + i * 0.045f)
                val half = h * (0.10f + (i % 3) * 0.055f)
                drawLine(category.accent.copy(alpha = 0.14f), Offset(x, h * 0.5f - half), Offset(x, h * 0.5f + half), side * 0.018f)
            }
            else -> drawCircle(category.accent.copy(alpha = 0.10f), side * 0.24f, Offset(w * 0.82f, h * 0.66f), style = Stroke(side * 0.018f))
        }
    }
}
