package com.example.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.AudioExporter
import com.example.audio.EngineStatus
import com.example.audio.RecordingItem
import com.example.ui.theme.DarkGray
import com.example.ui.theme.GrayBorder
import com.example.ui.theme.LightGray
import com.example.ui.theme.MartianMonoFontFamily
import com.example.ui.theme.MidGray
import com.example.ui.theme.OffWhite
import com.example.ui.theme.PureWhite
import com.example.ui.theme.RamsRed
import com.example.ui.theme.SolidBlack
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryTab(
    recordings: List<RecordingItem>,
    engineStatus: EngineStatus,
    onPlayRecording: (RecordingItem) -> Unit,
    onRenameRecording: (RecordingItem, String) -> Unit,
    onShareRecording: (RecordingItem) -> Unit,
    onDeleteRecording: (RecordingItem) -> Unit,
    onExportRecording: (RecordingItem, AudioExporter.ExportFormat, AudioExporter.QualityPreset) -> Unit,
    onNavigateToRecorder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var itemToExport by remember { mutableStateOf<RecordingItem?>(null) }
    var itemToRename by remember { mutableStateOf<RecordingItem?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var selectedFormat by remember { mutableStateOf(AudioExporter.ExportFormat.MP3) }
    var selectedPreset by remember { mutableStateOf(AudioExporter.QualityPreset.NORMAL_AUDIO) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OffWhite)
            .padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Content section: No inner "gallery" title, just the list of files
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (recordings.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(PureWhite, RectangleShape)
                        .border(1.dp, GrayBorder, RectangleShape)
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "no recordings yet",
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 12.sp,
                            color = DarkGray
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "tap rec to capture instrument audio",
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 10.sp,
                            color = MidGray
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("gallery_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(recordings, key = { it.id }) { item ->
                        val isCurrentActive = engineStatus.activePlaybackFile?.id == item.id

                        GalleryItemRow(
                            item = item,
                            isActive = isCurrentActive,
                            isPlaying = isCurrentActive && engineStatus.isPlayingFile,
                            onPlay = { onPlayRecording(item) },
                            onTriggerRename = {
                                itemToRename = item
                                renameInput = item.file.nameWithoutExtension
                            },
                            onExport = { itemToExport = item },
                            onShare = { onShareRecording(item) },
                            onDelete = { onDeleteRecording(item) }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Large CTA on bottom for recording
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .background(SolidBlack, RectangleShape)
                .clickable(interactionSource = interactionSource, indication = null) {
                    onNavigateToRecorder()
                }
                .testTag("gallery_rec_cta"),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(RamsRed, RectangleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "new rec",
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite
                )
            }
        }
    }

    // Rename dialog: triggered by pressing rename or double-pressing the white/text part
    itemToRename?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToRename = null },
            title = {
                Text(
                    text = "rename file",
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = SolidBlack
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "enter new file name",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 10.sp,
                        color = DarkGray
                    )
                    OutlinedTextField(
                        value = renameInput,
                        onValueChange = { renameInput = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            fontFamily = MartianMonoFontFamily,
                            fontSize = 12.sp,
                            color = SolidBlack
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SolidBlack,
                            unfocusedBorderColor = GrayBorder,
                            focusedContainerColor = PureWhite,
                            unfocusedContainerColor = PureWhite
                        ),
                        shape = RectangleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("rename_input")
                    )
                }
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .background(SolidBlack, RectangleShape)
                        .clickable {
                            val target = itemToRename
                            val newName = renameInput.trim()
                            itemToRename = null
                            if (target != null && newName.isNotEmpty()) {
                                onRenameRecording(target, newName)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "rename",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToRename = null }) {
                    Text(
                        text = "cancel",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 11.sp,
                        color = DarkGray
                    )
                }
            },
            containerColor = PureWhite,
            shape = RectangleShape
        )
    }

    // Minimalist Dieter Rams Export Modal
    itemToExport?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToExport = null },
            title = {
                Text(
                    text = "export ${item.name}",
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = SolidBlack
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "format",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 10.sp,
                        color = DarkGray
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(AudioExporter.ExportFormat.MP3 to "mp3", AudioExporter.ExportFormat.WAV to "wav").forEach { (fmt, label) ->
                            val isSel = selectedFormat == fmt
                            Box(
                                modifier = Modifier
                                    .background(if (isSel) SolidBlack else LightGray, RectangleShape)
                                    .clickable { selectedFormat = fmt }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontFamily = MartianMonoFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) PureWhite else SolidBlack
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "quality preset",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 10.sp,
                        color = DarkGray
                    )

                    AudioExporter.QualityPreset.values().forEach { preset ->
                        val isSel = selectedPreset == preset
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSel) LightGray else PureWhite, RectangleShape)
                                .border(1.dp, if (isSel) SolidBlack else GrayBorder, RectangleShape)
                            .clickable { selectedPreset = preset }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = preset.label,
                                    fontFamily = MartianMonoFontFamily,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = SolidBlack
                                )
                                Text(
                                    text = preset.description,
                                    fontFamily = MartianMonoFontFamily,
                                    fontSize = 8.sp,
                                    color = DarkGray
                                )
                            }
                            if (isSel) {
                                Box(modifier = Modifier.size(6.dp).background(SolidBlack))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .background(SolidBlack, RectangleShape)
                        .clickable {
                            val target = itemToExport
                            itemToExport = null
                            if (target != null) {
                                onExportRecording(target, selectedFormat, selectedPreset)
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "export & share",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = PureWhite
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToExport = null }) {
                    Text(
                        text = "cancel",
                        fontFamily = MartianMonoFontFamily,
                        fontSize = 11.sp,
                        color = DarkGray
                    )
                }
            },
            containerColor = PureWhite,
            shape = RectangleShape
        )
    }
}

/**
 * Each recording file card: a white section box with two main lines:
 * Line 1: name, date, length, and size in MB (not kb).
 * Line 2: play, rename, export, share, del.
 * Double-tapping the white/text section triggers rename.
 */
@Composable
private fun GalleryItemRow(
    item: RecordingItem,
    isActive: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onTriggerRename: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = SimpleDateFormat("yy-MM-dd HH:mm", Locale.US).format(Date(item.timestamp))
    val totalSec = item.durationMs / 1000
    val durationStr = String.format(Locale.US, "%02d:%02d", totalSec / 60, totalSec % 60)
    val sizeMb = item.sizeBytes / (1024.0 * 1024.0)
    val sizeMbStr = String.format(Locale.US, "%.1f mb", sizeMb)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PureWhite, RectangleShape)
            .border(1.dp, if (isActive) SolidBlack else GrayBorder, RectangleShape)
            .pointerInput(item.id) {
                detectTapGestures(
                    onDoubleTap = { onTriggerRename() }
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // Line 1: name, date and length and size in MB (not kb)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.name.lowercase(),
                fontFamily = MartianMonoFontFamily,
                fontSize = 11.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                color = SolidBlack,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "$dateStr • $durationStr • $sizeMbStr",
                fontFamily = MartianMonoFontFamily,
                fontSize = 9.sp,
                color = DarkGray
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Line 2: main play, rename, export, share, and del options
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Play / Pause button
            Box(
                modifier = Modifier
                    .background(if (isPlaying) RamsRed else SolidBlack, RectangleShape)
                    .clickable { onPlay() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("item_play_${item.name}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isPlaying) "pause" else "play",
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = PureWhite
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Rename button
            Box(
                modifier = Modifier
                    .background(LightGray, RectangleShape)
                    .border(1.dp, GrayBorder, RectangleShape)
                    .clickable { onTriggerRename() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("item_rename_${item.name}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "rename",
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 9.sp,
                    color = SolidBlack
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Export button
            Box(
                modifier = Modifier
                    .background(LightGray, RectangleShape)
                    .border(1.dp, GrayBorder, RectangleShape)
                    .clickable { onExport() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("item_export_${item.name}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "export",
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 9.sp,
                    color = SolidBlack
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Share button
            Box(
                modifier = Modifier
                    .background(LightGray, RectangleShape)
                    .border(1.dp, GrayBorder, RectangleShape)
                    .clickable { onShare() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("item_share_${item.name}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "share",
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 9.sp,
                    color = DarkGray
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // Delete button
            Box(
                modifier = Modifier
                    .background(LightGray, RectangleShape)
                    .border(1.dp, GrayBorder, RectangleShape)
                    .clickable { onDelete() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("item_delete_${item.name}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "del",
                    fontFamily = MartianMonoFontFamily,
                    fontSize = 9.sp,
                    color = DarkGray
                )
            }
        }
    }
}
