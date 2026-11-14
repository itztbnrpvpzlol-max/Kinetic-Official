package com.kinetic.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
            .verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (selectedCategory != null) {
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { selectedCategory = null },
                    contentAlignment = Alignment.Center) {
                    KIcon(IconKind.BACK, KColors.Muted, Modifier.size(19.dp))
                }
                Spacer(Modifier.size(8.dp))
            }
            Text(selectedCategory?.title?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Effects",
                Modifier.weight(1f), color = KColors.Text, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center) {
                KIcon(IconKind.CLOSE, KColors.Muted, Modifier.size(20.dp))
            }
        }

        if (selectedCategory == null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionHeading("Suggested")
                Spacer(Modifier.weight(1f))
                KIcon(IconKind.EFFECTS, KColors.Dim, Modifier.size(17.dp))
            }
            Text("Suggestions will appear as you use effects.", color = KColors.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(22.dp))
            HorizontalPager(state = shelfPager, modifier = Modifier.fillMaxWidth().height(88.dp), pageSpacing = 24.dp) { page ->
                if (page == 0) ShelfSection("Recent", "Your recently used effects will appear here.", IconKind.STOPWATCH, Color(0xFF79B9FF))
                else ShelfSection("Favourites", "Your saved effects will appear here.", IconKind.STAR, Color(0xFFFFC36B))
            }
            Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 22.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(2) { page ->
                    Box(Modifier.padding(horizontal = 3.dp)
                        .size(if (shelfPager.currentPage == page) 15.dp else 5.dp, 5.dp)
                        .clip(CircleShape)
                        .background(if (shelfPager.currentPage == page) KColors.Text else KColors.Dim.copy(alpha = 0.55f)))
                }
            }
            SectionHeading("Categories")
            Spacer(Modifier.height(12.dp))
            effectCategories.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { category -> CategoryTile(category, Modifier.weight(1f)) { selectedCategory = category } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
            }
        } else {
            val category = selectedCategory!!
            Text("Choose an effect to add it to the selected layer.", color = KColors.Muted, fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))
            if (category.effects.isEmpty()) {
                Text("No effects in this category yet.", color = KColors.Dim, fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 20.dp))
            } else {
                category.effects.forEach { effect ->
                    EffectChoice(effect, category.accent) { onAddEffect(effect) }
                    Spacer(Modifier.height(8.dp))
                }
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
private fun EffectChoice(name: String, accent: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(KColors.Surface)
        .border(1.dp, KColors.Line, RoundedCornerShape(10.dp)).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        KIcon(IconKind.EFFECTS, accent, Modifier.size(19.dp))
        Spacer(Modifier.size(10.dp))
        Text(name, color = KColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        KIcon(IconKind.PLUS, KColors.Muted, Modifier.size(17.dp))
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
