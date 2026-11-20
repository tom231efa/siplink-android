package com.sky.siplink.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sky.siplink.R
import com.sky.siplink.core.logic.FeatureDial
import com.sky.siplink.core.model.FeatureCodes
import com.sky.siplink.engine
import com.sky.siplink.ui.components.FormField
import com.sky.siplink.ui.components.LocalMessenger
import com.sky.siplink.ui.components.SectionCard
import com.sky.siplink.ui.components.SubScreen

private class CodeField(
    @StringRes val label: Int,
    val read: (FeatureCodes) -> String,
    val write: (FeatureCodes, String) -> FeatureCodes,
)

private val fields = listOf(
    // Dialled with the extension they act on behind them.
    CodeField(R.string.feature_intercom, { it.intercom }) { c, v -> c.copy(intercom = v) },
    CodeField(R.string.feature_listen, { it.listen }) { c, v -> c.copy(listen = v) },
    CodeField(R.string.feature_whisper, { it.whisper }) { c, v -> c.copy(whisper = v) },
    CodeField(R.string.feature_barge, { it.barge }) { c, v -> c.copy(barge = v) },
    CodeField(R.string.feature_force_hang_up, { it.forceHangUp }) { c, v -> c.copy(forceHangUp = v) },
    CodeField(R.string.feature_codes_pickup, { it.pickup }) { c, v -> c.copy(pickup = v) },
    // Dialled as they are.
    CodeField(R.string.feature_group_pickup, { it.groupPickup }) { c, v -> c.copy(groupPickup = v) },
    CodeField(R.string.feature_park, { it.park }) { c, v -> c.copy(park = v) },
    CodeField(R.string.feature_voicemail, { it.voicemail }) { c, v -> c.copy(voicemail = v) },
    CodeField(R.string.feature_dnd_on, { it.dndOn }) { c, v -> c.copy(dndOn = v) },
    CodeField(R.string.feature_dnd_off, { it.dndOff }) { c, v -> c.copy(dndOff = v) },
)

/** Dial codes of the PBX features; they must match the PBX dial plan. */
@Composable
fun FeatureCodesScreen(onBack: () -> Unit) {
    val engine = LocalContext.current.engine
    val messenger = LocalMessenger.current
    // Edited locally: an invalid code is shown with its error but never stored.
    var codes by remember { mutableStateOf(engine.settings.current.features) }
    val restored = stringResource(R.string.feature_codes_restored)

    fun change(next: FeatureCodes) {
        codes = next
        engine.updateSettings { it.copy(features = validOnly(next, it.features)) }
    }

    SubScreen(
        title = stringResource(R.string.settings_feature_codes),
        onBack = onBack,
        actions = {
            TextButton(onClick = {
                codes = FeatureCodes()
                engine.updateSettings { it.copy(features = FeatureCodes()) }
                messenger(restored)
            }) { Text(stringResource(R.string.common_restore_defaults)) }
        },
    ) {
        SectionCard(title = null) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                fields.forEach { field ->
                    val value = field.read(codes)
                    FormField(
                        value = value,
                        onValueChange = { typed -> change(field.write(codes, typed.filterNot(Char::isWhitespace))) },
                        label = stringResource(field.label),
                        error = if (FeatureDial.isValidCode(value)) null else stringResource(R.string.feature_codes_invalid),
                        keyboardType = KeyboardType.Phone,
                    )
                }
            }
        }
    }
}

/** Takes every valid code from [edited] and keeps the stored one where the edit is invalid. */
private fun validOnly(edited: FeatureCodes, stored: FeatureCodes): FeatureCodes {
    fun pick(new: String, old: String) = if (FeatureDial.isValidCode(new)) new else old
    return FeatureCodes(
        park = pick(edited.park, stored.park),
        listen = pick(edited.listen, stored.listen),
        whisper = pick(edited.whisper, stored.whisper),
        barge = pick(edited.barge, stored.barge),
        forceHangUp = pick(edited.forceHangUp, stored.forceHangUp),
        pickup = pick(edited.pickup, stored.pickup),
        groupPickup = pick(edited.groupPickup, stored.groupPickup),
        voicemail = pick(edited.voicemail, stored.voicemail),
        intercom = pick(edited.intercom, stored.intercom),
        dndOn = pick(edited.dndOn, stored.dndOn),
        dndOff = pick(edited.dndOff, stored.dndOff),
    )
}
