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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    val background: Color
)

private val effectCategories = listOf(
    EffectCategory("COLOR & LIGHT", IconKind.ADJ, Color(0xFFFF82B2), Color(0xFF281C2A)),
    EffectCategory("STYLIZE", IconKind.EFFECTS, Color(0xFFC0A2FF), Color(0xFF241E35)),
    EffectCategory("DRAWING & EDGE", IconKind.GRAPH, Color(0xFFFFC078), Color(0xFF30251D)),
    EffectCategory("DISTORTION & WARP", IconKind.BLEND, Color(0xFF70DAD4), Color(0xFF1A2D32)),
    EffectCategory("PROCEDURAL", IconKind.GRID, Color(0xFFB5A4FF), Color(0xFF252238)),
    EffectCategory("BLUR", IconKind.FILTER, Color(0xFF8BC8FF), Color(0xFF1D2B39)),
    EffectCategory("3D", IconKind.SHAPES, Color(0xFFFF987C), Color(0xFF332326)),
    EffectCategory("TEXT", IconKind.TEXT, Color(0xFFFFD77A), Color(0xFF332B1F)),
    EffectCategory("AUDIO", IconKind.WAVEFORM, Color(0xFF79D8A7), Color(0xFF1B302A)),
    EffectCategory("OTHER", IconKind.MORE, Color(0xFFB2C0D6), Color(0xFF242B37))
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EffectsBrowserScreen(onClose: () -> Unit) {
    val shelfPager = rememberPagerState(pageCount = { 2 })

    Column(
        Modifier.fillMaxSize()
            .background(KColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 22.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Effects",
                Modifier.weight(1f),
                color = KColors.Text,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                Modifier.size(38.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                KIcon(IconKind.CLOSE, KColors.Muted, Modifier.size(20.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeading("Suggested")
            Spacer(Modifier.weight(1f))
            KIcon(IconKind.EFFECTS, KColors.Dim, Modifier.size(17.dp))
        }
        Text(
            "Suggestions will appear as you use effects.",
            color = KColors.Muted,
            fontSize = 11.sp
        )

        Spacer(Modifier.height(22.dp))

        HorizontalPager(
            state = shelfPager,
            modifier = Modifier.fillMaxWidth().height(88.dp),
            pageSpacing = 24.dp
        ) { page ->
            if (page == 0) {
                ShelfSection(
                    title = "Recent",
                    emptyMessage = "Your recently used effects will appear here.",
                    icon = IconKind.STOPWATCH,
                    accent = Color(0xFF79B9FF)
                )
            } else {
                ShelfSection(
                    title = "Favourites",
                    emptyMessage = "Your saved effects will appear here.",
                    icon = IconKind.STAR,
                    accent = Color(0xFFFFC36B)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(2) { page ->
                Box(
                    Modifier.padding(horizontal = 3.dp)
                        .size(if (shelfPager.currentPage == page) 15.dp else 5.dp, 5.dp)
                        .clip(CircleShape)
                        .background(if (shelfPager.currentPage == page) KColors.Text else KColors.Dim.copy(alpha = 0.55f))
                )
            }
        }

        SectionHeading("Categories")
        Spacer(Modifier.height(12.dp))
        effectCategories.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { category -> CategoryTile(category, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(title, color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun ShelfSection(
    title: String,
    emptyMessage: String,
    icon: IconKind,
    accent: Color
) {
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
private fun CategoryTile(category: EffectCategory, modifier: Modifier = Modifier) {
    Box(
        modifier.height(116.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(category.background)
            .border(1.dp, KColors.Line.copy(alpha = 0.65f), RoundedCornerShape(15.dp))
    ) {
        CategoryArtwork(category, Modifier.fillMaxSize())
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            KIcon(category.icon, category.accent, Modifier.size(27.dp))
            Spacer(Modifier.height(9.dp))
            Text(
                category.title,
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.25.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CategoryArtwork(category: EffectCategory, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val side = minOf(w, h)
        // Keep category surfaces intentionally quiet: one flat base, a soft tint,
        // and a single restrained geometric motif.
        drawRect(category.background)
        drawCircle(
            Brush.radialGradient(
                listOf(category.accent.copy(alpha = 0.13f), Color.Transparent),
                center = androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.18f),
                radius = side * 0.88f
            ),
            radius = side * 0.88f,
            center = androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.18f)
        )
        when (category.icon) {
            IconKind.ADJ -> {
                drawCircle(category.accent.copy(alpha = 0.09f), side * 0.34f, androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.72f))
                drawCircle(category.accent.copy(alpha = 0.14f), side * 0.17f, androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.72f), style = Stroke(side * 0.012f))
            }
            IconKind.EFFECTS -> {
                drawRoundRect(category.accent.copy(alpha = 0.08f), androidx.compose.ui.geometry.Offset(w * 0.70f, h * 0.10f), androidx.compose.ui.geometry.Size(w * 0.25f, h * 0.72f), androidx.compose.ui.geometry.CornerRadius(side * 0.07f))
            }
            IconKind.GRAPH -> {
                drawLine(category.accent.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.2f), androidx.compose.ui.geometry.Offset(w * 0.92f, h * 0.8f), side * 0.018f)
                drawLine(category.accent.copy(alpha = 0.12f), androidx.compose.ui.geometry.Offset(w * 0.78f, h * 0.8f), androidx.compose.ui.geometry.Offset(w * 0.94f, h * 0.25f), side * 0.018f)
            }
            IconKind.BLEND -> {
                drawCircle(category.accent.copy(alpha = 0.08f), side * 0.27f, androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.68f))
                drawCircle(category.accent.copy(alpha = 0.11f), side * 0.18f, androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.68f), style = Stroke(side * 0.014f))
            }
            IconKind.GRID -> {
                for (x in 0..2) for (y in 0..2) {
                    drawCircle(category.accent.copy(alpha = 0.12f), side * 0.018f, androidx.compose.ui.geometry.Offset(w * (0.74f + x * 0.075f), h * (0.25f + y * 0.22f)))
                }
            }
            IconKind.FILTER -> {
                drawCircle(category.accent.copy(alpha = 0.10f), side * 0.27f, androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.66f), style = Stroke(side * 0.035f))
            }
            IconKind.SHAPES -> {
                drawRoundRect(category.accent.copy(alpha = 0.09f), androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.18f), androidx.compose.ui.geometry.Size(w * 0.22f, h * 0.58f), androidx.compose.ui.geometry.CornerRadius(side * 0.035f), style = Stroke(side * 0.018f))
            }
            IconKind.TEXT -> {
                drawLine(category.accent.copy(alpha = 0.13f), androidx.compose.ui.geometry.Offset(w * 0.73f, h * 0.34f), androidx.compose.ui.geometry.Offset(w * 0.94f, h * 0.34f), side * 0.025f)
                drawLine(category.accent.copy(alpha = 0.13f), androidx.compose.ui.geometry.Offset(w * 0.73f, h * 0.52f), androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.52f), side * 0.025f)
            }
            IconKind.WAVEFORM -> {
                for (i in 0..4) {
                    val x = w * (0.74f + i * 0.045f)
                    val half = h * (0.10f + (i % 3) * 0.055f)
                    drawLine(category.accent.copy(alpha = 0.14f), androidx.compose.ui.geometry.Offset(x, h * 0.5f - half), androidx.compose.ui.geometry.Offset(x, h * 0.5f + half), side * 0.018f)
                }
            }
            else -> {
                drawCircle(category.accent.copy(alpha = 0.10f), side * 0.24f, androidx.compose.ui.geometry.Offset(w * 0.82f, h * 0.66f), style = Stroke(side * 0.018f))
            }
        }
    }
}
