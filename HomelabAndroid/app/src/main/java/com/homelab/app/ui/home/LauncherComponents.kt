package com.homelab.app.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.homelab.app.R

/** Title bar shared by the Home and Servarr tabs. */
@Composable
fun LauncherHeader(title: String, count: Int, onReorder: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = CircleShape) {
                Text(
                    text = "$count",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
            }
            FilledTonalIconButton(onClick = onReorder) {
                Icon(Icons.Default.SwapVert, contentDescription = stringResource(R.string.home_reorder_services))
            }
        }
    }
}

/** Drawn instead of downloaded, so it follows light and dark mode. */
@Composable
fun TailscaleLogo(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val side = minOf(size.width, size.height)
        val diameter = side * 0.26f
        val step = (side - diameter) / 2
        for (index in 0 until 9) {
            drawCircle(
                color = color.copy(alpha = if (index in setOf(3, 4, 5, 7)) 1f else 0.3f),
                radius = diameter / 2,
                center = Offset((index % 3) * step + diameter / 2, (index / 3) * step + diameter / 2)
            )
        }
    }
}
