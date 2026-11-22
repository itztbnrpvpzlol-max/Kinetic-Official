package com.kinetic.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kinetic.app.R
import com.kinetic.app.engine.EcEffect
import com.kinetic.app.engine.EffectCatalog

private data class EffectCategory(
    val title: String,
    val icon: IconKind,
    val accent: Color,
    val background: Color,
    val image: Int
)

private val effectCategories = listOf(
    EffectCategory("COLOR & LIGHT", IconKind.ADJ, Color(0xFFFF82B2), Color(0xFF281C2A), R.drawable.smp_lake),
    EffectCategory("STYLIZE", IconKind.EFFECTS, Color(0xFFC0A2FF), Color(0xFF241E35), R.drawable.smp_stars),
    EffectCategory("DRAWING & EDGE", IconKind.GRAPH, Color(0xFFFFC078), Color(0xFF30251D), R.drawable.smp_door),
    EffectCategory("MOTION", IconKind.MOTION, Color(0xFF86B7FF), Color(0xFF1D2940), R.drawable.smp_closeup),
    EffectCategory("DISTORTION & WARP", IconKind.BLEND, Color(0xFF70DAD4), Color(0xFF1A2D32), R.drawable.smp_blush),
    EffectCategory("PROCEDURAL", IconKind.GRID, Color(0xFFB5A4FF), Color(0xFF252238), R.drawable.smp_haze),
    EffectCategory("BLUR", IconKind.FILTER, Color(0xFF8BC8FF), Color(0xFF1D2B39), R.drawable.smp_laugh),
    EffectCategory("3D", IconKind.SHAPES, Color(0xFFFF987C), Color(0xFF332326), R.drawable.smp_lake),
    EffectCategory("TEXT", IconKind.TEXT, Color(0xFFFFD77A), Color(0xFF332B1F), R.drawable.smp_haze),
    EffectCategory("AUDIO", IconKind.WAVEFORM, Color(0xFF79D8A7), Color(0xFF1B302A), R.drawable.smp_laugh),
    EffectCategory("OTHER", IconKind.MORE, Color(0xFFB2C0D6), Color(0xFF242B37), R.drawable.smp_blush)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EffectsBrowserScreen(onClose: () -> Unit, onAddEffect: (String) -> Unit) {
    var selectedCategory by remember { mutableStateOf<EffectCategory?>(null) }
    var query by remember { mutableStateOf("") }
    val catalogEffects = EffectCatalog.effects

    Column(Modifier.fillMaxSize().background(KColors.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 14.dp),
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

        val category = selectedCategory
        if (category == null) {
            Box(Modifier.padding(horizontal = 18.dp)) { SearchField(query, onChange = { query = it }) }
            Spacer(Modifier.height(14.dp))
        }

        when {
            category != null -> {
                val inCategory = EffectCatalog.inCategory(category.title)
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(category.accent))
                    Spacer(Modifier.size(8.dp))
                    Text("${inCategory.size} effects", color = KColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("Tap to add to the selected layer", color = KColors.Dim, fontSize = 10.sp)
                }
                if (inCategory.isEmpty()) {
                    Text("No effects in this category.", color = KColors.Dim, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp))
                } else {
                    EffectGrid(inCategory, category.accent, onAddEffect)
                }
            }
            query.isNotBlank() -> {
                val results = remember(query) { EffectCatalog.search(query) }
                Text("${results.size} result${if (results.size == 1) "" else "s"}", color = KColors.Muted,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp))
                EffectGrid(results, KColors.Accent, onAddEffect)
            }
            else -> HomeContent(catalogEffects.isEmpty(), effectCategories, catalogEffects) { selectedCategory = it }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContent(empty: Boolean, categories: List<EffectCategory>, effects: List<EcEffect>, onPick: (EffectCategory) -> Unit) {
    val shelfPager = rememberPagerState(pageCount = { 2 })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SectionHeading("Suggested")
            Spacer(Modifier.weight(1f))
            KIcon(IconKind.EFFECTS, KColors.Dim, Modifier.size(17.dp))
        }
        Text("Suggestions will appear as you use effects.", color = KColors.Muted, fontSize = 11.sp)
        Spacer(Modifier.height(18.dp))
        HorizontalPager(state = shelfPager, modifier = Modifier.fillMaxWidth().height(80.dp), pageSpacing = 24.dp) { page ->
            if (page == 0) ShelfSection("Recent", "Your recently used effects will appear here.", IconKind.STOPWATCH, Color(0xFF79B9FF))
            else ShelfSection("Favourites", "Your saved effects will appear here.", IconKind.STAR, Color(0xFFFFC36B))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 20.dp),
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
        if (empty) {
            Text(
                EffectCatalog.error?.let { "Effects engine unavailable: ${it.take(96)}" } ?: "Loading effects…",
                color = KColors.Dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp)
            )
        }
        categories.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { category ->
                    CategoryTile(category, effects.count { it.category == category.title }, Modifier.weight(1f)) { onPick(category) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun EffectGrid(effects: List<EcEffect>, accent: Color, onAdd: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(effects, key = { it.id }) { effect -> EffectCard(effect, accent) { onAdd(effect.id) } }
    }
}

@Composable
private fun EffectCard(effect: EcEffect, accent: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            EffectThumb(effect, accent, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                    .clip(CircleShape).background(KColors.Bg.copy(alpha = 0.78f)),
                contentAlignment = Alignment.Center
            ) { KIcon(IconKind.PLUS, accent, Modifier.size(12.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            Text(effect.name, color = KColors.Text, fontSize = 11.sp, lineHeight = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EffectThumb(effect: EcEffect, accent: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val base = painterResource(EffectThumbs.imageFor(effect))
    if (effect.audio) {
        Box(modifier.background(KColors.Surface2)) {
            Image(base, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color(0xFF0E1A16).copy(alpha = 0.72f)))
            AudioArt(accent, effect.id.hashCode(), Modifier.fillMaxSize())
        }
        return
    }
    val image by produceState(EffectThumbs.peek(effect.id), effect.id) {
        if (value == null) value = EffectThumbs.load(ctx, effect)
    }
    Box(modifier.background(KColors.Surface2)) {
        Crossfade(image, label = "thumb") { img ->
            if (img == null) {
                Box(Modifier.fillMaxSize()) {
                    Image(base, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(KColors.Bg.copy(alpha = 0.55f)))
                }
            } else {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun AudioArt(accent: Color, seed: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val bars = 11
        val gap = size.width * 0.03f
        val bw = (size.width * 0.78f - gap * (bars - 1)) / bars
        val x0 = size.width * 0.11f
        for (i in 0 until bars) {
            val k = ((seed ushr (i % 16)) and 7) / 7f
            val env = 1f - kotlin.math.abs(i - bars / 2) / (bars / 2f) * 0.55f
            val bh = size.height * (0.16f + 0.5f * k * env)
            drawRoundRect(accent.copy(alpha = 0.55f + 0.4f * env), Offset(x0 + i * (bw + gap), (size.height - bh) / 2f),
                Size(bw, bh), CornerRadius(bw / 2f))
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(KColors.Surface)
            .border(1.dp, KColors.Line, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KIcon(IconKind.SEARCH, KColors.Dim, Modifier.size(16.dp))
        Spacer(Modifier.size(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text("Search all effects", color = KColors.Dim, fontSize = 13.sp)
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = TextStyle(color = KColors.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(KColors.Accent), modifier = Modifier.fillMaxWidth()
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(26.dp).clip(CircleShape).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                KIcon(IconKind.CLOSE, KColors.Muted, Modifier.size(14.dp))
            }
        }
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
private fun CategoryTile(category: EffectCategory, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.height(112.dp).clip(shape).background(category.background)
        .border(1.dp, KColors.Line.copy(alpha = 0.65f), shape).clickable(onClick = onClick)) {
        Image(painterResource(category.image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
            category.background.copy(alpha = 0.30f), category.background.copy(alpha = 0.94f)))))
        Box(Modifier.align(Alignment.TopStart).padding(10.dp).size(30.dp).clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.38f)), contentAlignment = Alignment.Center) {
            KIcon(category.icon, category.accent, Modifier.size(17.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(category.title, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (count > 0) Text("$count effects", color = Color.White.copy(alpha = 0.62f), fontSize = 9.sp)
        }
    }
}
