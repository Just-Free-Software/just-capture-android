package fyi.justfreesoftware.justcapture.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.justfreesoftware.justcapture.AppGraph
import fyi.justfreesoftware.justcapture.services.DriveSessionDownloader
import fyi.justfreesoftware.justcapture.services.GoogleDriveService
import fyi.justfreesoftware.justcapture.services.SharedFolder
import fyi.justfreesoftware.justcapture.ui.theme.AppColors
import fyi.justfreesoftware.justcapture.ui.theme.NoticeBanner
import fyi.justfreesoftware.justcapture.ui.theme.NoticeStyle
import fyi.justfreesoftware.justcapture.ui.theme.SectionHeader
import fyi.justfreesoftware.justcapture.ui.theme.StatusBadge
import fyi.justfreesoftware.justcapture.ui.theme.card
import fyi.justfreesoftware.justcapture.util.Formatters
import kotlinx.coroutines.launch

private data class PlaybackTarget(val id: String, val title: String)

/**
 * The Watch tab: Drive folders other people shared by link, saved on this
 * phone for replay, with a badge when the sharer has added clips since.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WatchScreen(graph: AppGraph) {
    val store = graph.sharedFolderStore
    val folders by store.folders.collectAsState()
    val isChecking by store.isChecking.collectAsState()
    val checkError by store.checkError.collectAsState()
    val scope = rememberCoroutineScope()

    var linkText by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<PlaybackTarget?>(null) }
    var folderToDelete by remember { mutableStateOf<SharedFolder?>(null) }
    val focusManager = LocalFocusManager.current
    val clipboard = LocalClipboardManager.current
    val linkFolderId = GoogleDriveService.folderIdFromLink(linkText)

    fun openLink() {
        val folderId = linkFolderId ?: return
        focusManager.clearFocus()
        val existing = folders.firstOrNull { it.id == folderId }
        playing = PlaybackTarget(folderId, existing?.name ?: "Shared Folder")
        linkText = ""
    }

    LaunchedEffect(Unit) { store.checkForNewClips(minimumIntervalMs = 5 * 60_000) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .statusBarsPadding()
    ) {
        ScreenTitle("Watch")
        // Pull to refresh: one listing request per folder, skipping any
        // checked in the last 30 seconds.
        PullToRefreshBox(
            isRefreshing = isChecking,
            onRefresh = { scope.launch { store.checkForNewClips(minimumIntervalMs = 30_000) } },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                item {
                    Column(modifier = Modifier.card(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Open a shared folder", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Paste a Google Drive folder link someone sent you. It must be shared as \"Anyone with the link\".",
                            color = AppColors.secondaryText,
                            fontSize = 12.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = linkText,
                                onValueChange = { linkText = it },
                                placeholder = { Text("drive.google.com/drive/folders/…") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { openLink() }),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { clipboard.getText()?.text?.let { linkText = it } }) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste", tint = AppColors.accent)
                            }
                        }
                        if (linkText.isNotEmpty() && linkFolderId == null) {
                            Text("That doesn't look like a Drive folder link.", color = AppColors.warning, fontSize = 12.sp)
                        }
                        Button(
                            onClick = ::openLink,
                            enabled = linkFolderId != null,
                            colors = ButtonDefaults.buttonColors(containerColor = AppColors.accent),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Download & Play", fontWeight = FontWeight.SemiBold) }
                    }
                }

                checkError?.let { error ->
                    item {
                        NoticeBanner(
                            style = NoticeStyle.WARNING,
                            icon = Icons.Filled.SyncProblem,
                            title = "Couldn't check for new clips",
                            message = error,
                            onDismiss = store::dismissCheckError
                        )
                    }
                }

                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader("Saved", Modifier.weight(1f))
                        if (isChecking) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                if (folders.isEmpty()) {
                    item {
                        Text(
                            "Folders you open appear here and stay on this phone, so you can watch them again without downloading.",
                            color = AppColors.secondaryText,
                            fontSize = 14.sp,
                            modifier = Modifier.card()
                        )
                    }
                } else {
                    items(folders, key = { it.id }) { folder ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .combinedClickable(
                                    onClick = { playing = PlaybackTarget(folder.id, folder.name) },
                                    onLongClick = { folderToDelete = folder },
                                    onLongClickLabel = "Delete folder"
                                )
                        ) {
                            DriveFolderRow(folder.name, subtitle(folder), folder.newClipCount)
                        }
                    }
                }
            }
        }
    }

    playing?.let { target ->
        DrivePlaybackScreen(
            graph = graph,
            folderId = target.id,
            source = DriveSessionDownloader.Source.PUBLIC_LINK,
            title = target.title,
            onDismiss = { playing = null }
        )
    }

    folderToDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderToDelete = null },
            title = { Text("Delete this folder?") },
            text = {
                Text(
                    "Removes the downloaded clips from this phone. The folder in Google Drive isn't affected, " +
                        "and you can add it again with its link."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    graph.appScope.launch { store.remove(folder) }
                    folderToDelete = null
                }) { Text("Delete from This Phone", color = AppColors.record, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { folderToDelete = null }) { Text("Cancel") } }
        )
    }
}

private fun subtitle(folder: SharedFolder): String {
    val count = folder.downloadedClipIds.size
    val parts = mutableListOf("$count clip${if (count == 1) "" else "s"} saved")
    folder.lastCheckedAtMs?.let { parts += "checked ${Formatters.relative(it)}" }
    return parts.joinToString(" · ")
}

/** List row for a Drive folder, with a badge when it has clips the phone doesn't. */
@Composable
private fun DriveFolderRow(title: String, subtitle: String, newClipCount: Int) {
    Row(
        modifier = Modifier.card(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(AppColors.accent.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Folder, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(subtitle, color = AppColors.secondaryText, fontSize = 12.sp, maxLines = 1)
        }
        if (newClipCount > 0) {
            StatusBadge("$newClipCount new", Icons.Filled.Download, AppColors.accent)
        } else {
            Icon(Icons.Filled.PlayCircle, contentDescription = null, tint = AppColors.accent, modifier = Modifier.size(28.dp))
        }
    }
}
