package com.kinetic.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (active) KColors.Clip else KColors.Surface2)
            .border(1.dp, if (active) KColors.Accent else KColors.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = if (active) KColors.ClipText else KColors.Muted, fontSize = 12.sp)
    }
}

@Composable
fun TabLabel(label: String, active: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(
            label, fontSize = 14.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            color = if (active) KColors.Text else KColors.Muted
        )
    }
}

@Composable
fun ActionDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(shape)
                .background(KColors.Surface)
                .border(1.dp, KColors.Line, shape)
                .padding(18.dp)
                .heightIn(max = 480.dp)
        ) {
            Text(title, color = KColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Column(Modifier.verticalScroll(rememberScrollState())) { content() }
        }
    }
}

@Composable
fun DialogItem(label: String, danger: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(KColors.Surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(label, color = if (danger) KColors.Danger else KColors.Text, fontSize = 15.sp)
    }
}

@Composable
fun TextInputDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = KColors.Surface,
        title = { Text(title, color = KColors.Text) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = KColors.Text, unfocusedTextColor = KColors.Text,
                    focusedBorderColor = KColors.Accent, unfocusedBorderColor = KColors.Line,
                    cursorColor = KColors.Accent
                )
            )
        },
        confirmButton = { TextButton(onClick = { onDone(text) }) { Text("OK", color = KColors.Accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = KColors.Muted) } }
    )
}
