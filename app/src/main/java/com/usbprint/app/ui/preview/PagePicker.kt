package com.usbprint.app.ui.preview

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.usbprint.app.R
import com.usbprint.app.edit.PageEdit
import com.usbprint.app.render.SheetComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Compresses a page set into "1-3, 5"; all pages become "" (meaning all). */
fun rangeOf(pages: Set<Int>, count: Int): String {
    if (pages.size == count) return ""
    val sorted = pages.sorted()
    val parts = ArrayList<String>()
    var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        parts += if (i == j) "${sorted[i] + 1}" else "${sorted[i] + 1}-${sorted[j] + 1}"
        i = j + 1
    }
    // An empty selection is kept as a range that matches nothing.
    return if (parts.isEmpty()) "0" else parts.joinToString(", ")
}

/** Thumbnail strip with a checkbox per page, so pages can be picked without opening Edit. */
@Composable
fun PagePicker(composer: SheetComposer, selected: Set<Int>, onChange: (Set<Int>) -> Unit) {
    val count = composer.pages.size
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.pages_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.size(8.dp))
            Text(
                stringResource(R.string.pages_selected, selected.size, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onChange(if (selected.size == count) emptySet() else (0 until count).toSet()) }) {
                Text(stringResource(if (selected.size == count) R.string.pages_none else R.string.pages_all))
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
            items((0 until count).toList(), key = { it }) { i ->
                val on = i in selected
                PageThumb(composer, i, on) { onChange(if (on) selected - i else selected + i) }
            }
        }
    }
}

@Composable
private fun PageThumb(composer: SheetComposer, index: Int, checked: Boolean, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bmp by produceState<Bitmap?>(null, composer, index) {
        value = withContext(Dispatchers.Default) { runCatching { composer.processed(index, PageEdit.DEFAULT, 200) }.getOrNull() }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(width = 60.dp, height = 80.dp)
            .clip(shape)
            .border(BorderStroke(if (checked) 2.dp else 1.dp, if (checked) scheme.onSurface.copy(alpha = 0.7f) else scheme.onSurface.copy(alpha = 0.10f)), shape)
            .clickable(onClick = onToggle),
    ) {
        Box(Modifier.fillMaxSize().background(Color.White).alpha(if (checked) 1f else 0.45f)) {
            bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(checkedColor = Color(0xFF1C1A17), checkmarkColor = Color.White, uncheckedColor = Color(0xFF8A847A)),
            modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
        )
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF2B2A27),
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp),
        )
    }
}
