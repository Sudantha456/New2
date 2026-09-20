package com.app.youtube.lite.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.app.youtube.lite.BuildConfig
import com.app.youtube.lite.auth.AuthState
import com.app.youtube.lite.core.di.AppGraph
import com.app.youtube.lite.core.perf.MemoryTrimmer
import com.app.youtube.lite.data.model.AppSettings
import com.app.youtube.lite.data.model.QualityCap
import kotlinx.coroutines.launch

/**
 * Settings.
 *
 * Every switch here maps to a runtime behaviour that is actually enforced (see [AppSettings]):
 * the quality cap feeds the extractor's ladder *and* the player's track selection, the buffer
 * size feeds `LowRamLoadControl`, background audio decides whether the video track is released
 * when the app leaves the foreground. Nothing in this list is decorative, which is the reason
 * there are ten entries and not fifty.
 *
 * The screen is a `LazyColumn` rather than a `Column` inside a `verticalScroll`: rows are cheap
 * but there are enough of them (plus dialogs) that laziness costs nothing and avoids measuring
 * off-screen switches.
 */
@Composable
fun SettingsScreen(
    graph: AppGraph,
    onSignIn: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val authState by graph.auth.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Read once: the heap snapshot touches the ActivityManager and ART, so it must not run on
    // every recomposition of a settings screen.
    val context = LocalContext.current
    val heap = remember { MemoryTrimmer.heapSnapshot(context) }
    // Dialog results are set from click handlers, which are not composable scopes: the strings are
    // resolved here, once, and the handlers just store them.
    val signedOutLabel = stringResource(R.string.settings_signed_out)
    val clearedLabel = stringResource(R.string.settings_cleared)

    var showQualityDialog by remember { mutableStateOf(false) }
    var confirmClearResume by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun update(transform: (AppSettings) -> AppSettings) {
        scope.launch { graph.settings.update(transform) }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(key = "playback-header") { SectionHeader(stringResource(R.string.settings_playback)) }

        item(key = "quality") {
            SettingRow(
                title = stringResource(R.string.settings_quality),
                description = stringResource(R.string.settings_quality_desc),
                trailing = settings.qualityCap.label,
                onClick = { showQualityDialog = true },
            )
        }
        item(key = "data-saver") {
            SwitchRow(
                title = stringResource(R.string.settings_data_saver),
                description = stringResource(R.string.settings_data_saver_desc),
                checked = settings.dataSaver,
                onCheckedChange = { value -> update { it.copy(dataSaver = value) } },
            )
        }
        item(key = "background-audio") {
            SwitchRow(
                title = stringResource(R.string.settings_background_audio),
                description = stringResource(R.string.settings_background_audio_desc),
                checked = settings.backgroundAudio,
                onCheckedChange = { value -> update { it.copy(backgroundAudio = value) } },
            )
        }
        item(key = "autoplay") {
            SwitchRow(
                title = stringResource(R.string.settings_autoplay),
                description = stringResource(R.string.settings_autoplay_desc),
                checked = settings.autoplayNext,
                onCheckedChange = { value -> update { it.copy(autoplayNext = value) } },
            )
        }
        item(key = "seek-step") {
            SliderRow(
                title = stringResource(R.string.settings_seek_step),
                description = stringResource(
                    R.string.settings_seek_step_desc,
                    settings.doubleTapSeekSeconds,
                ),
                value = settings.doubleTapSeekSeconds.toFloat(),
                range = 5f..30f,
                steps = 4,
                onValueChange = { value ->
                    update { it.copy(doubleTapSeekSeconds = value.toInt()) }
                },
            )
        }

        item(key = "performance-header") { SectionHeader(stringResource(R.string.settings_performance)) }

        item(key = "prefer-h264") {
            SwitchRow(
                title = stringResource(R.string.settings_prefer_h264),
                description = stringResource(R.string.settings_prefer_h264_desc),
                checked = settings.preferH264,
                onCheckedChange = { value -> update { it.copy(preferH264 = value) } },
            )
        }
        item(key = "allow-vp9") {
            SwitchRow(
                title = stringResource(R.string.settings_allow_vp9),
                description = stringResource(R.string.settings_allow_vp9_desc),
                checked = settings.allowVp9,
                onCheckedChange = { value -> update { it.copy(allowVp9 = value) } },
            )
        }
        item(key = "allow-av1") {
            SwitchRow(
                title = stringResource(R.string.settings_allow_av1),
                description = stringResource(R.string.settings_allow_av1_desc),
                checked = settings.allowAv1,
                onCheckedChange = { value -> update { it.copy(allowAv1 = value) } },
            )
        }
        item(key = "refresh-rate") {
            SwitchRow(
                title = stringResource(R.string.settings_high_refresh),
                description = stringResource(R.string.settings_high_refresh_desc),
                checked = settings.highRefreshRate,
                onCheckedChange = { value -> update { it.copy(highRefreshRate = value) } },
            )
        }
        item(key = "buffer") {
            SliderRow(
                title = stringResource(R.string.settings_buffer),
                description = stringResource(R.string.settings_buffer_desc, settings.maxBufferMegabytes),
                value = settings.maxBufferMegabytes.toFloat(),
                range = 8f..32f,
                steps = 5,
                onValueChange = { value -> update { it.copy(maxBufferMegabytes = value.toInt()) } },
            )
        }
        item(key = "heap") {
            SettingRow(
                title = stringResource(R.string.settings_memory_title),
                description = stringResource(
                    R.string.settings_memory_desc,
                    heap.summary,
                    heap.javaHeapBytes / (1024 * 1024),
                ),
                trailing = "",
                onClick = null,
            )
        }

        item(key = "account-header") { SectionHeader(stringResource(R.string.settings_account)) }

        item(key = "account") {
            val signedIn = authState is AuthState.LoggedIn
            SettingRow(
                title = stringResource(
                    if (signedIn) R.string.settings_signed_in else R.string.settings_signed_out,
                ),
                description = stringResource(
                    if (signedIn) R.string.settings_signed_in_desc else R.string.settings_signed_out_desc,
                ),
                trailing = stringResource(
                    if (signedIn) R.string.nav_sign_out else R.string.nav_sign_in,
                ),
                onClick = {
                    if (signedIn) {
                        graph.auth.logout()
                        graph.feeds.invalidateAll()
                        statusMessage = signedOutLabel
                    } else {
                        onSignIn()
                    }
                },
                icon = true,
            )
        }

        item(key = "clear-progress") {
            SettingRow(
                title = stringResource(R.string.settings_clear_positions),
                description = stringResource(R.string.settings_clear_positions_desc),
                trailing = stringResource(R.string.action_clear),
                onClick = { confirmClearResume = true },
                icon = true,
            )
        }

        item(key = "about-header") { SectionHeader(stringResource(R.string.settings_about)) }

        item(key = "about") {
            SettingRow(
                title = stringResource(R.string.app_name),
                description = stringResource(R.string.settings_about_desc),
                trailing = "",
                onClick = null,
            )
        }
        item(key = "version") {
            SettingRow(
                title = stringResource(R.string.settings_version),
                description = "${stringResource(R.string.settings_extractor)} " +
                    BuildConfig.NEWPIPE_EXTRACTOR_VERSION,
                trailing = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                onClick = null,
            )
        }
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text(stringResource(R.string.settings_quality)) },
            text = {
                Column {
                    QualityCap.entries.forEach { cap ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    update { it.copy(qualityCap = cap) }
                                    showQualityDialog = false
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.qualityCap == cap,
                                onClick = {
                                    update { it.copy(qualityCap = cap) }
                                    showQualityDialog = false
                                },
                            )
                            Text(cap.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            },
        )
    }

    if (confirmClearResume) {
        AlertDialog(
            onDismissRequest = { confirmClearResume = false },
            title = { Text(stringResource(R.string.settings_clear_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_confirm_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        graph.positions.clear()
                        confirmClearResume = false
                        statusMessage = clearedLabel
                    },
                ) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearResume = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    statusMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { statusMessage = null },
            confirmButton = {
                Button(onClick = { statusMessage = null }) { Text(stringResource(R.string.settings_ok)) }
            },
            text = { Text(message) },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    trailing: String,
    onClick: (() -> Unit)?,
    icon: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon) {
            Icon(
                imageVector = Icons.Filled.AccountCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing.isNotEmpty()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun SliderRow(
    title: String,
    description: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(2.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
