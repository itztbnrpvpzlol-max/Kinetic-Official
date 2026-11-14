package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val effectCategories = listOf(
    "Color & Light" to "CL",
    "Stylize" to "ST",
    "Drawing & Edge" to "DE",
    "Distortion & Warp" to "DW",
    "Procedural" to "PR",
    "Blur" to "BL",
    "3D" to "3D",
    "Text" to "TX",
    "Audio" to "AU",
    "Other / Miscellaneous" to "OT"
)

@Composable
fun EffectsBrowserScreen(onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(KColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Effects", color = KColors.Text, fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text("Browse effects for your layer", color = KColors.Muted, fontSize = 11.sp)
            }
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(KColors.Surface2).clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = KColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Light)
            }
        }

        SectionHeading("Suggested Effects")
        EmptyEffectShelf("Suggested effects will appear here")

        Spacer(Modifier.height(20.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pageWidth = maxWidth * 0.88f
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                EmptySwipeShelf(
                    title = "Recently used",
                    subtitle = "Effects you use will appear here",
                    modifier = Modifier.width(pageWidth)
                )
                EmptySwipeShelf(
                    title = "Favourites",
                    subtitle = "Your favourited effects will appear here",
                    modifier = Modifier.width(pageWidth)
                )
            }
        }
        Text(
            "SWIPE LEFT FOR FAVOURITES",
            Modifier.fillMaxWidth().padding(top = 7.dp),
            color = KColors.Dim,
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(22.dp))
        SectionHeading("Categories")
        effectCategories.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { (name, monogram) ->
                    CategoryTile(name, monogram, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        Modifier.padding(bottom = 9.dp),
        color = KColors.Text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun EmptyEffectShelf(message: String) {
    Box(
        Modifier.fillMaxWidth().height(82.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KColors.Surface)
            .border(1.dp, KColors.Line, RoundedCornerShape(12.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(message, color = KColors.Dim, fontSize = 10.sp)
    }
}

@Composable
private fun EmptySwipeShelf(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionHeading(title)
        Box(
            Modifier.fillMaxWidth().height(82.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(KColors.Surface)
                .border(1.dp, KColors.Line, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(subtitle, color = KColors.Dim, fontSize = 10.sp)
        }
    }
}

@Composable
private fun CategoryTile(name: String, monogram: String, modifier: Modifier = Modifier) {
    Row(
        modifier.height(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(KColors.Surface)
            .border(1.dp, KColors.Line, RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(KColors.Surface2),
            contentAlignment = Alignment.Center
        ) {
            Text(monogram, color = KColors.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            name,
            color = KColors.Text,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
