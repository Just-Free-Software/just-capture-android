package fyi.justfreesoftware.justcapture.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.justfreesoftware.justcapture.model.SessionLocationInfo
import fyi.justfreesoftware.justcapture.ui.theme.AppColors
import fyi.justfreesoftware.justcapture.ui.theme.card

/**
 * Where a session was recorded: when and where it started, and the position
 * saved at the end of each clip. Each position opens in a maps app.
 */
@Composable
fun SessionLocationCard(info: SessionLocationInfo) {
    // Long recordings have hundreds of points; show the first few until asked.
    var showsAllPoints by remember { mutableStateOf(false) }
    val visiblePoints = if (showsAllPoints) info.points else info.points.take(COLLAPSED_POINT_COUNT)

    Column(modifier = Modifier.card(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = AppColors.accent,
                modifier = Modifier.size(18.dp)
            )
            Text("Location", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        if (info.startingAddress != null || info.started != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                info.startingAddress?.let {
                    Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
                info.started?.let { Text("Started $it", color = AppColors.secondaryText, fontSize = 12.sp) }
            }
        }
        visiblePoints.forEach { LocationPointRow(it) }
        if (info.points.size > COLLAPSED_POINT_COUNT) {
            TextButton(onClick = { showsAllPoints = !showsAllPoints }, contentPadding = PaddingValues(0.dp)) {
                Text(
                    if (showsAllPoints) "Show Fewer" else "Show All ${info.points.size} Locations",
                    color = AppColors.accent,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

private const val COLLAPSED_POINT_COUNT = 5

/** One saved position. Opens in a maps app when it has coordinates. */
@Composable
private fun LocationPointRow(point: SessionLocationInfo.Point) {
    val uriHandler = LocalUriHandler.current
    val mapUrl = point.mapUrl
    // The date is in the card's header; a row only needs the time.
    val time = point.time.substringAfterLast(" at ")
    val detail = if (mapUrl == null) {
        "$time · no location available"
    } else {
        listOfNotNull(time, "last known position".takeIf { point.isLastKnown }, point.accuracy).joinToString(" · ")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = mapUrl != null, onClickLabel = "Open in Maps") {
                mapUrl?.let { runCatching { uriHandler.openUri(it) } }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(point.label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(detail, color = if (mapUrl != null) AppColors.secondaryText else AppColors.warning, fontSize = 12.sp)
        }
        if (mapUrl != null) {
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = AppColors.accent,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
