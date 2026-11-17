package com.sky.siplink.ui.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.sky.siplink.R
import com.sky.siplink.core.logic.UpdateRules
import com.sky.siplink.engine
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen

/**
 * About the app: its icon and name, the version with its updates ([UpdateRows]), the version of
 * the SIP library, the licences, and the GitHub mark that opens the app's repository.
 */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val messenger = LocalMessenger.current
    SubScreen(title = "关于", onBack = onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The launcher icon is adaptive, which a painter does not draw: its two layers, in its shape.
            Box(
                Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.large),
            ) {
                Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null)
                Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null)
            }
            Spacer(Modifier.height(12.dp))
            Text("SipLink", style = MaterialTheme.typography.titleLarge)
        }

        SectionCard(title = null) {
            UpdateRows()
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Linphone SDK", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(context.engine.sdkVersion, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf("SipLink：GNU AGPL-3.0", "SIP 协议栈：Belledonne Communications Linphone SDK，GNU AGPLv3").forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(
                onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, UpdateRules.REPOSITORY_PAGE.toUri())) }
                        .onFailure { messenger("无法打开浏览器") }
                },
            ) {
                Icon(
                    painterResource(R.drawable.ic_github),
                    contentDescription = "GitHub 仓库",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
