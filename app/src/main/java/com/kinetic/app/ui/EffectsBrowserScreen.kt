package com.kinetic.app.ui

import com.kinetic.app.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class EffectCategory(val title: String, val image: Int, val accent: Color)

private val effectCategories = listOf(
    EffectCategory("COLOR & LIGHT", R.drawable.effect_category_color_light, Color(0xFFFF6C9D)),
    EffectCategory("STYLIZE", R.drawable.effect_category_stylize, Color(0xFF9D8BFF)),
    EffectCategory("DRAWING & EDGE", R.drawable.effect_category_drawing, Color(0xFFFFB86B)),
    EffectCategory("DISTORTION & WARP", R.drawable.effect_category_distortion, Color(0xFF65D9D1)),
    EffectCategory("PROCEDURAL", R.drawable.effect_category_procedural, Color(0xFFB2A0FF)),
    EffectCategory("BLUR", R.drawable.effect_category_blur, Color(0xFF77B9FF)),
    EffectCategory("3D", R.drawable.effect_category_3d, Color(0xFFFF8D70)),
    EffectCategory("TEXT", R.drawable.effect_category_text, Color(0xFFFFD16D)),
    EffectCategory("AUDIO", R.drawable.effect_category_audio, Color(0xFF71D8A5)),
    EffectCategory("OTHER / MISCELLANEOUS", R.drawable.effect_category_other, Color(0xFF9DA8BC))
)

@Composable
fun EffectsBrowserScreen(onClose: () -> Unit) {
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
            Column(Modifier.weight(1f)) {
                Text("Effects", color = KColors.Text, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    "Shape the look. Set the mood.",
                    color = KColors.Muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Box(
                Modifier.size(42.dp)
                    .clip(CircleShape)
                    .background(KColors.Surface2)
                    .border(1.dp, KColors.Line, CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = KColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Light)
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeading("Suggested for you")
            Spacer(Modifier.weight(1f))
            Eyebrow("COMING INTO FOCUS")
        }
        SuggestedPanel()

        Spacer(Modifier.height(22.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val shelfWidth = (maxWidth - 10.dp) / 2
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ShelfCard(
                    title = "Recently used",
                    eyebrow = "YOUR HISTORY",
                    description = "Your recent effects will show up here.",
                    symbol = "↻",
                    accent = Color(0xFF79B9FF),
                    modifier = Modifier.width(shelfWidth)
                )
                ShelfCard(
                    title = "Favourites",
                    eyebrow = "SAVED BY YOU",
                    description = "Keep your go-to looks close.",
                    symbol = "☆",
                    accent = Color(0xFFFFC36B),
                    modifier = Modifier.width(shelfWidth)
                )
            }
        }

        Spacer(Modifier.height(26.dp))
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeading("Explore categories")
            Spacer(Modifier.weight(1f))
            Eyebrow("10 COLLECTIONS")
        }
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
    Text(title, color = KColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        color = KColors.Dim,
        fontSize = 8.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.8.sp
    )
}

@Composable
private fun SuggestedPanel() {
    Box(
        Modifier.fillMaxWidth()
            .height(142.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF24213C), Color(0xFF171B2A), Color(0xFF202B38))
                )
            )
            .border(1.dp, Color(0xFF3B3A55), RoundedCornerShape(18.dp))
            .padding(17.dp)
    ) {
        Box(
            Modifier.align(Alignment.CenterEnd)
                .size(116.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0x557C78FF), Color(0x227C78FF), Color.Transparent)
                    )
                )
        )
        Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.76f)) {
            Text(
                "A LITTLE CREATIVE SPARK",
                color = Color(0xFFB7B2FF),
                fontSize = 8.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.1.sp
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "Your next look starts here.",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "Suggested effects will appear as your library grows.",
                color = Color(0xFFB4B8C8),
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
        }
        Text(
            "✦",
            Modifier.align(Alignment.CenterEnd).padding(end = 15.dp),
            color = Color(0xFFD0C7FF),
            fontSize = 43.sp,
            fontWeight = FontWeight.Light
        )
    }
}

@Composable
private fun ShelfCard(
    title: String,
    eyebrow: String,
    description: String,
    symbol: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.height(126.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(KColors.Surface)
            .border(1.dp, KColors.Line, RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(28.dp).clip(RoundedCornerShape(9.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Text(symbol, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                eyebrow,
                color = KColors.Dim,
                fontSize = 7.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.45.sp
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(title, color = KColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(
            description,
            color = KColors.Muted,
            fontSize = 9.sp,
            lineHeight = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CategoryTile(category: EffectCategory, modifier: Modifier = Modifier) {
    Box(
        modifier.height(124.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(KColors.Surface)
            .border(1.dp, KColors.Line, RoundedCornerShape(17.dp))
    ) {
        Image(
            painter = painterResource(category.image),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0x22070A12),
                    0.34f to Color(0x3D070A12),
                    0.68f to Color(0x99070A12),
                    1f to Color(0xF2070A12)
                )
            )
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier.size(7.dp).clip(CircleShape).background(category.accent)
            )
            Text(
                "COLLECTION",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 7.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }
        Box(
            Modifier.align(Alignment.BottomEnd)
                .padding(10.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0x55070A12))
                .border(1.dp, Color.White.copy(alpha = 0.20f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("↗", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            category.title,
            Modifier.align(Alignment.BottomStart).fillMaxWidth(0.84f)
                .padding(start = 11.dp, end = 4.dp, bottom = 12.dp),
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.55.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
