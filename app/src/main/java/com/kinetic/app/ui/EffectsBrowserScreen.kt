package com.kinetic.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.kinetic.app.engine.EffectCategories
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
import androidx.compose.foundation.lazy.items as rowItems
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
import com.kinetic.app.model.LayerType
import com.kinetic.app.model.effectFitsLayer

private data class EffectCategory(
    val title: String,
    val icon: IconKind,
    val accent: Color,
    val background: Color,
    val image: Int
)

private val effectCategories = listOf(
    EffectCategory("COLOR & LIGHT", IconKind.ADJ, Color(0xFFFF82B2), Color(0xFF281C2A), R.drawable.cat_color),
    EffectCategory("STYLIZE", IconKind.EFFECTS, Color(0xFFC0A2FF), Color(0xFF241E35), R.drawable.cat_stylize),
    EffectCategory("DRAWING & EDGE", IconKind.GRAPH, Color(0xFFFFC078), Color(0xFF30251D), R.drawable.cat_drawing),
    EffectCategory("MOTION", IconKind.MOTION, Color(0xFF86B7FF), Color(0xFF1D2940), R.drawable.cat_motion),
    EffectCategory("DISTORTION & WARP", IconKind.BLEND, Color(0xFF70DAD4), Color(0xFF1A2D32), R.drawable.cat_distortion),
    EffectCategory("PROCEDURAL", IconKind.GRID, Color(0xFFB5A4FF), Color(0xFF252238), R.drawable.cat_procedural),
    EffectCategory("BLUR", IconKind.FILTER, Color(0xFF8BC8FF), Color(0xFF1D2B39), R.drawable.cat_blur),
    EffectCategory("3D", IconKind.SHAPES, Color(0xFFFF987C), Color(0xFF332326), R.drawable.cat_three_d),
    EffectCategory("AUDIO", IconKind.WAVEFORM, Color(0xFF79D8A7), Color(0xFF1B302A), R.drawable.cat_audio),
    EffectCategory("OTHER", IconKind.MORE, Color(0xFFB2C0D6), Color(0xFF242B37), R.drawable.cat_other)
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EffectsBrowserScreen(layerType: LayerType?, onClose: () -> Unit, onAddEffect: (String) -> Unit) {
    var selectedCategory by remember {
        mutableStateOf<EffectCategory?>(if (layerType == LayerType.AUDIO) effectCategories.firstOrNull { it.title == "AUDIO" } else null)
    }
    var query by remember { mutableStateOf("") }
    val allEffects = EffectCatalog.effects
    // Text effects were retired from the browser (text now has its own Edit / Animate panels).
    val catalogEffects = remember(layerType, allEffects) {
        allEffects.filter { it.category != EffectCategories.TEXT && effectFitsLayer(it, layerType) }
    }
    val ctx = LocalContext.current
    var favs by remember { mutableStateOf(EffectPrefs.favourites(ctx)) }
    val recentIds = remember { EffectPrefs.recent(ctx) }
    val counts = remember { EffectPrefs.counts(ctx) }
    val toggleFav: (String) -> Unit = { id -> favs = EffectPrefs.toggleFavourite(ctx, id) }
    // Back steps out one level at a time: category -> search -> browser.
    androidx.activity.compose.BackHandler {
        when {
            selectedCategory != null -> selectedCategory = null
            query.isNotBlank() -> query = ""
            else -> onClose()
        }
    }

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
                val inCategory = remember(catalogEffects, category) { catalogEffects.filter { it.category == category.title }.sortedBy { it.name.lowercase() } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(category.accent))
                    Spacer(Modifier.size(8.dp))
                    Text("${inCategory.size} effects", color = KColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("Tap to add · hold to favourite", color = KColors.Dim, fontSize = 10.sp)
                }
                if (inCategory.isEmpty()) {
                    Text("No effects in this category.", color = KColors.Dim, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp))
                } else {
                    EffectGrid(inCategory, category.accent, favs, toggleFav, onAddEffect)
                }
            }
            query.isNotBlank() -> {
                val results = remember(query, catalogEffects) { EffectCatalog.search(query).filter { it.category != EffectCategories.TEXT && effectFitsLayer(it, layerType) } }
                Text("${results.size} result${if (results.size == 1) "" else "s"}", color = KColors.Muted,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 18.dp))
                EffectGrid(results, KColors.Accent, favs, toggleFav, onAddEffect)
            }
            else -> HomeContent(catalogEffects, effectCategories, recentIds, counts, favs, toggleFav, onAddEffect) { selectedCategory = it }
        }
    }
}

private fun accentOf(effect: EcEffect): Color =
    effectCategories.firstOrNull { it.title == effect.category }?.accent ?: KColors.Accent

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeContent(
    effects: List<EcEffect>, categories: List<EffectCategory>, recentIds: List<String>, counts: Map<String, Int>,
    favs: Set<String>, onToggleFav: (String) -> Unit, onAdd: (String) -> Unit, onPick: (EffectCategory) -> Unit
) {
    val byId = remember(effects) { effects.associateBy { it.id } }
    val recent = remember(effects, recentIds) { recentIds.mapNotNull { byId[it] } }
    val favList = remember(effects, favs) { favs.mapNotNull { byId[it] }.sortedBy { it.name } }
    val sug = remember(effects, counts) { EffectPrefs.suggestions(effects, counts) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (sug.effects.isNotEmpty()) {
            ShelfHeader("Suggested", if (sug.personal) "Picked from the effects you use most" else "Popular effects to get you started",
                IconKind.EFFECTS, Color(0xFFC0A2FF))
            EffectRow(sug.effects, favs, onToggleFav, onAdd)
            Spacer(Modifier.height(20.dp))
        }
        ShelfHeader("Recent", if (recent.isEmpty()) "Effects you add will show up here." else "Your latest effects", IconKind.STOPWATCH, Color(0xFF79B9FF))
        if (recent.isNotEmpty()) EffectRow(recent, favs, onToggleFav, onAdd)
        Spacer(Modifier.height(20.dp))
        ShelfHeader("Favourites", if (favList.isEmpty()) "Hold any effect to save it here." else "Your saved effects", IconKind.STAR, Color(0xFFFFC36B))
        if (favList.isNotEmpty()) EffectRow(favList, favs, onToggleFav, onAdd)
        Spacer(Modifier.height(22.dp))
        Column(Modifier.padding(horizontal = 18.dp)) {
            SectionHeading("Categories")
            Spacer(Modifier.height(12.dp))
            if (effects.isEmpty()) {
                Text(
                    EffectCatalog.error?.let { "Effects engine unavailable: ${it.take(96)}" } ?: "Loading effects…",
                    color = KColors.Dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp)
                )
            }
            categories.filter { c -> effects.isEmpty() || effects.any { it.category == c.title } }.chunked(2).forEach { row ->
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
}

@Composable
private fun ShelfHeader(title: String, subtitle: String, icon: IconKind, accent: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        KIcon(icon, accent, Modifier.size(17.dp))
        Spacer(Modifier.size(8.dp))
        Text(title, color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.size(10.dp))
        Text(subtitle, color = KColors.Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EffectRow(effects: List<EcEffect>, favs: Set<String>, onToggleFav: (String) -> Unit, onAdd: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
        rowItems(effects, key = { it.id }) { effect ->
            EffectCard(effect, accentOf(effect), effect.id in favs, { onToggleFav(effect.id) }, Modifier.width(106.dp)) { onAdd(effect.id) }
        }
    }
}

@Composable
private fun EffectGrid(effects: List<EcEffect>, accent: Color, favs: Set<String>, onToggleFav: (String) -> Unit, onAdd: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(effects, key = { it.id }) { effect ->
            EffectCard(effect, accent, effect.id in favs, { onToggleFav(effect.id) }) { onAdd(effect.id) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EffectCard(
    effect: EcEffect, accent: Color, fav: Boolean, onToggleFav: () -> Unit, modifier: Modifier = Modifier, onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val haptic = LocalHapticFeedback.current
    Column(modifier.clip(shape).background(KColors.Surface).border(1.dp, KColors.Line, shape)
        .combinedClickable(onClick = onClick, onLongClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onToggleFav()
        })) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            EffectThumb(effect, Modifier.fillMaxSize())
            if (fav) Box(
                Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape).background(KColors.Bg.copy(alpha = 0.78f)),
                contentAlignment = Alignment.Center
            ) { KIcon(IconKind.STAR, Color(0xFFFFC36B), Modifier.size(12.dp)) }
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
private fun EffectThumb(effect: EcEffect, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val image by produceState(EffectThumbs.peek(effect.id), effect.id) {
        if (value == null) value = EffectThumbs.load(ctx, effect)
    }
    val photo = EffectThumbs.usesPhoto(effect)
    val base = if (photo) painterResource(EffectThumbs.imageFor(effect)) else null
    val flat = when {
        effect.audio -> Color(0xFF101C17)
        effect.category == EffectCategories.TEXT -> Color.White
        else -> Color.Black
    }
    Box(modifier.background(KColors.Surface2)) {
        Crossfade(image, label = "thumb") { img ->
            if (img == null) {
                Box(Modifier.fillMaxSize().background(flat)) {
                    if (base != null) {
                        Image(base, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.fillMaxSize().background(KColors.Bg.copy(alpha = 0.55f)))
                    }
                }
            } else {
                Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
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
