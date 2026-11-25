@file:OptIn(ExperimentalLayoutApi::class)

package com.sky.siplink.ui.call

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sky.siplink.R
import com.sky.siplink.core.CallManager
import com.sky.siplink.core.logic.Formatters
import com.sky.siplink.core.logic.ListKeys
import com.sky.siplink.core.logic.RoomRules
import com.sky.siplink.core.model.RoomInfo
import com.sky.siplink.core.model.ShortcutAction
import com.sky.siplink.engine
import com.sky.siplink.ui.components.AppBottomSheet
import com.sky.siplink.ui.components.Avatar
import com.sky.siplink.ui.components.AwayWhileTyping
import com.sky.siplink.ui.components.ChoiceRow
import com.sky.siplink.ui.components.FadingText
import com.sky.siplink.ui.components.FieldText
import com.sky.siplink.ui.components.isLowWindow
import com.sky.siplink.ui.components.machineValueStyle
import com.sky.siplink.ui.components.stringOf
import kotlinx.coroutines.delay

/** Someone who can be called into the room: by [name], at [target] (what is dialled). */
private data class Candidate(val name: String, val target: String) {
    val number: String get() = Formatters.userPart(target)
}

private enum class Source { RECENT, DIRECTORY, SHORTCUTS }

/**
 * Calls members into the room: numbers typed in, recent calls, the LDAP directory and the speed
 * dial keys. Several are picked and called at once; the sheet stays open for more.
 */
@Composable
internal fun RoomAddSheet(room: RoomInfo, calls: CallManager, onDismiss: () -> Unit) {
    val engine = LocalContext.current.engine
    val history by engine.history.entries.collectAsStateWithLifecycle()
    val shortcuts by engine.shortcuts.shortcuts.collectAsStateWithLifecycle()
    val directories by engine.directory.directories.collectAsStateWithLifecycle()
    val found by engine.directory.state.collectAsStateWithLifecycle()
    var typed by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(Source.RECENT) }
    var message by remember { mutableStateOf<String?>(null) }
    val picked = remember { mutableStateListOf<Candidate>() }

    val directory = directories.firstOrNull { it.enabled }?.takeIf { engine.directory.isAvailable }
    val speedDials = shortcuts.filter { it.action == ShortcutAction.SPEED_DIAL && it.number.isNotBlank() }
    val recentLabel = stringResource(R.string.room_source_recent)
    val directoryLabel = stringResource(R.string.room_source_directory)
    val shortcutsLabel = stringResource(R.string.room_source_shortcuts)
    val sources = buildList {
        add(Source.RECENT to recentLabel)
        if (directory != null) add(Source.DIRECTORY to directoryLabel)
        if (speedDials.isNotEmpty()) add(Source.SHORTCUTS to shortcutsLabel)
    }
    val shownSource = source.takeIf { chosen -> sources.any { it.first == chosen } } ?: Source.RECENT
    val query = typed.trim()

    LaunchedEffect(query, shownSource, directory) {
        if (shownSource != Source.DIRECTORY || directory == null) return@LaunchedEffect
        delay(SEARCH_DEBOUNCE_MS)
        engine.directory.search(directory.id, query)
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(MESSAGE_MS)
            message = null
        }
    }

    val inRoom = room.members.filter { it.taken }.mapTo(HashSet()) { Formatters.userPart(it.address) }
    val candidates = when (shownSource) {
        Source.RECENT -> history.distinctBy { Formatters.userPart(it.address) }
            .filter { it.name.contains(query, true) || it.address.contains(query, true) }
            .map { Candidate(it.name, it.address) }
        Source.DIRECTORY -> found.contacts.takeIf { found.directory == directory?.id }.orEmpty()
            .map { Candidate(it.name, it.callTarget) }
        Source.SHORTCUTS -> speedDials
            .filter { it.label.contains(query, true) || it.number.contains(query, true) }
            .map { Candidate(it.label, it.number) }
    }

    fun toggle(candidate: Candidate) {
        val index = picked.indexOfFirst { it.number == candidate.number }
        if (index >= 0) picked.removeAt(index) else picked += candidate
    }

    fun addTyped() {
        if (query.isEmpty()) return
        if (picked.none { it.number == Formatters.userPart(query) }) picked += Candidate(query, query)
        typed = ""
    }

    // The phone on its side: too low for the list under the field, so it stands beside it.
    val twoPanes = isLowWindow()

    val title: @Composable () -> Unit = {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.room_add_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                RoomRules.count(room.present, room.limit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    val field: @Composable () -> Unit = {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            label = { FieldText(stringResource(R.string.room_add_field)) },
            singleLine = true,
            textStyle = machineValueStyle(),
            shape = MaterialTheme.shapes.medium,
            // Names are typed here as well as numbers, in any script.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { addTyped() }),
            trailingIcon = {
                IconButton(onClick = ::addTyped, enabled = query.isNotEmpty()) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.room_add_number))
                }
            },
        )
    }
    // The members picked so far, and where the list is taken from.
    val choices: @Composable () -> Unit = {
        AnimatedVisibility(picked.isNotEmpty()) {
            // Many picks scroll in two rows of their own, the newest in sight: the list and the
            // call button keep their room, also above the keyboard.
            val picks = rememberScrollState()
            // Its end is known once the rows are laid out anew.
            LaunchedEffect(picked.size, picks.maxValue) { picks.animateScrollTo(picks.maxValue) }
            FlowRow(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = PICKS_MAX_HEIGHT)
                    .verticalScroll(picks)
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                picked.forEach { candidate ->
                    InputChip(
                        selected = true,
                        onClick = { picked.remove(candidate) },
                        label = { FadingText(candidate.name, modifier = Modifier.widthIn(max = 160.dp)) },
                        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = stringOf(R.string.room_pick_remove, candidate.name), modifier = Modifier.size(18.dp)) },
                    )
                }
            }
        }
        if (sources.size > 1) {
            ChoiceRow(
                options = sources,
                selected = shownSource,
                onSelect = { source = it },
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
    // Two entries of a directory, or two speed dial keys, may be alike. Made again only when the
    // candidates are other ones, not each time the sheet is composed again (the room changed, a
    // letter was typed that the directory has not answered yet).
    val keys = remember(candidates) { ListKeys.distinct(candidates.map { "${it.target}|${it.name}" }) }
    val list: @Composable (Modifier) -> Unit = { modifier ->
        LazyColumn(modifier, contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(candidates, key = { index, _ -> keys[index] }, contentType = { _, _ -> "candidate" }) { _, candidate ->
                CandidateRow(
                    candidate = candidate,
                    inRoom = candidate.number in inRoom,
                    picked = picked.any { it.number == candidate.number },
                    onToggle = { toggle(candidate) },
                )
            }
        }
    }
    val foot: @Composable () -> Unit = {
        AnimatedVisibility(message != null) {
            Text(
                message.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        Button(
            onClick = {
                val error = calls.inviteToRoom(picked.map { it.target })
                if (error == null) picked.clear() else message = error
            },
            enabled = picked.isNotEmpty(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            Text(if (picked.isEmpty()) stringResource(R.string.room_call) else stringOf(R.string.room_call_count, picked.size.toString()))
        }
    }

    AppBottomSheet(onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(if (twoPanes) 1f else SHEET_HEIGHT),
        ) {
            if (twoPanes) {
                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        // The keyboard leaves hardly more than the field here. The title gives way
                        // to it and the field stays where it is, at the top and whole: it is not
                        // part of what scrolls. What stands below it the keyboard covers.
                        AwayWhileTyping(title)
                        field()
                        Column(
                            Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            choices()
                            foot()
                        }
                    }
                    list(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    title()
                    field()
                    choices()
                    list(Modifier.weight(1f))
                    foot()
                }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: Candidate, inRoom: Boolean, picked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(enabled = !inRoom, onClick = onToggle)
            .padding(start = 24.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(candidate.name, size = 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            FadingText(candidate.name, style = MaterialTheme.typography.titleMedium)
            FadingText(
                if (inRoom) stringOf(R.string.room_candidate_in_room, candidate.number) else candidate.number,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = picked || inRoom, onCheckedChange = { onToggle() }, enabled = !inRoom)
    }
}

/** Gathers the letters of a quick typist into one question to the directory. */
private const val SEARCH_DEBOUNCE_MS = 250L
private const val MESSAGE_MS = 3_500L

/** Two rows of picked members, each as high as a finger needs, and the gap around them. */
private val PICKS_MAX_HEIGHT = 104.dp

/** Upright: tall enough for a list, and the room stays in sight above it. On its side the sheet takes all the height there is. */
private const val SHEET_HEIGHT = 0.86f

