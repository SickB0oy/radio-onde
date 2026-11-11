package app.onde.radio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.onde.radio.R
import app.onde.radio.ui.theme.Onde

private const val REPO_URL = "https://github.com/SickB0oy/radio-onde"

@Composable
fun AboutScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("À propos", modifier = Modifier.weight(1f), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            CircleIconButton(Icons.Rounded.Close, "Fermer", onClose)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Onde.Ink)
                    .border(1.dp, Onde.Line, RoundedCornerShape(22.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Radio Onde", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                if (version.isNotBlank()) Text("Version $version", fontSize = 14.sp, color = Onde.Muted)
            }
        }

        Text(
            "Une appli radio libre et gratuite : des dizaines de milliers de stations du monde entier, " +
                "la pause du direct, l'historique des titres et Android Auto.",
            fontSize = 15.sp,
            color = Onde.TextSoft,
        )

        AboutCard(
            title = "Ta vie privée",
            body = "Pas de pub, pas de pistage, pas de compte. Tes favoris, ton historique et tes goûts " +
                "restent sur ton téléphone. Seules les recherches de stations partent vers l'annuaire Radio-Browser, " +
                "et le son vient directement de chaque radio.",
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Liens")
            LinkRow("Code source sur GitHub", REPO_URL)
            LinkRow("Mises à jour", "$REPO_URL/releases/latest")
            LinkRow("Signaler un problème ou proposer une idée", "$REPO_URL/issues")
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Remerciements")
            AboutCard(
                title = "Radio-Browser",
                body = "L'annuaire libre et collaboratif qui fournit toutes les stations, leurs genres et leurs tendances.",
                url = "https://www.radio-browser.info",
            )
            AboutCard(
                title = "Bibliothèques libres",
                body = "AndroidX Media3 (ExoPlayer), Jetpack Compose, OkHttp, Coil, Kotlin et kotlinx.",
            )
        }

        Text(
            "Les logos et les flux appartiennent à leurs stations respectives.",
            fontSize = 12.sp,
            color = Onde.Muted,
        )
    }
}

@Composable
private fun AboutCard(title: String, body: String, url: String? = null) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Onde.Surface2)
            .then(if (url != null) Modifier.clickable(role = Role.Button) { context.openUrl(url) } else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (url != null) Onde.Accent else Onde.Text)
        Text(body, fontSize = 13.sp, color = Onde.TextSoft)
    }
}

@Composable
private fun LinkRow(label: String, url: String) {
    val context = LocalContext.current
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Onde.Line, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button) { context.openUrl(url) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = Onde.Accent,
    )
}

private fun Context.openUrl(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
