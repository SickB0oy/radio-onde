package app.onde.radio.ui

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onde.radio.data.Station
import app.onde.radio.ui.theme.Onde
import coil.compose.AsyncImage

/** Pochette d'une station : son logo s'il existe, sinon ses initiales sur une couleur. */
@Composable
fun StationArt(
    station: Station?,
    size: Dp,
    corner: Dp,
    modifier: Modifier = Modifier,
    initialsSize: TextUnit = (size.value * 0.2f).sp,
) {
    val name = station?.displayName ?: "?"
    val favicon = station?.favicon?.takeIf { it.startsWith("http") }
    var logoLoaded by remember(favicon) { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(Onde.artColor(name)),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (!logoLoaded) {
            Text(
                initials(name),
                modifier = Modifier.padding(size * 0.1f),
                color = Onde.Ink,
                fontWeight = FontWeight.Bold,
                fontSize = initialsSize,
            )
        }
        if (favicon != null) {
            AsyncImage(
                model = favicon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { logoLoaded = true },
                modifier = Modifier
                    .size(size)
                    .padding(size * 0.12f)
                    .clip(RoundedCornerShape(corner / 2)),
            )
        }
    }
}

fun initials(name: String): String =
    name.split(' ', '-', '_', '.')
        .filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifBlank { "?" }

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Onde.Accent else Onde.Surface2)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Onde.Ink else Color(0xFFE5E7EB),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    background: Color = Onde.Surface,
    tint: Color = Onde.Text,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (enabled) tint else tint.copy(alpha = 0.35f), modifier = Modifier.size(iconSize))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Onde.Text)
}

@Composable
fun StationRow(
    station: Station,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        StationArt(station, 48.dp, 12.dp)
        Column(Modifier.weight(1f)) {
            Text(station.displayName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (station.subtitle.isNotBlank()) {
                Text(station.subtitle, fontSize = 12.sp, color = Onde.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun EmptyMessage(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(body, fontSize = 13.sp, color = Onde.Muted)
        Spacer(Modifier.width(1.dp))
    }
}

/** « 2:10 » ou « 1:02:10 ». */
fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Ouvre une recherche web pour retrouver un morceau entendu. */
fun Context.findTrack(artist: String, title: String) {
    val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
    if (query.isBlank()) return
    val intents = listOf(
        Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://duckduckgo.com/?q=" + Uri.encode(query))),
    )
    for (intent in intents) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}
