package app.organicmaps.wear.presentation.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.*
import app.organicmaps.wear.BookmarkCategoryItem
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.WearCommandService
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Sync

@Composable
fun BookmarksScreen(isVisible: Boolean) {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val categories = navState.bookmarkCategories
    
    var selectedCategoryName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            WearCommandService.requestBookmarks(context)
        } else {
            selectedCategoryName = null
        }
    }

    if (selectedCategoryName != null) {
        val category = categories.find { it.name == selectedCategoryName }
        if (category != null) {
            BookmarkListScreen(category = category, onBack = { selectedCategoryName = null })
        } else {
            selectedCategoryName = null
        }
        return
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        autoCentering = AutoCenteringParams(itemIndex = 0)
    ) {
        item {
            ListHeader {
                Text("Bookmarks", textAlign = TextAlign.Center)
            }
        }

        item {
            val isAnySyncing = categories.any { it.isSyncing }
            Chip(
                onClick = {
                    app.organicmaps.wear.WatchBookmarkSyncManager.requestSync(context)
                },
                label = { Text(if (isAnySyncing) "Syncing..." else "Sync with Phone") },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                },
                colors = if (isAnySyncing) {
                    ChipDefaults.chipColors(
                        backgroundColor = MaterialTheme.colors.secondary,
                        contentColor = Color.Black
                    )
                } else {
                    ChipDefaults.primaryChipColors()
                },
                enabled = !isAnySyncing && navState.isPhoneConnected && !navState.standaloneMode,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        if (categories.isEmpty()) {
            item {
                Text(
                    text = "No lists found on phone",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            item {
                CompactChip(
                    onClick = { WearCommandService.requestBookmarks(context) },
                    label = { Text("Refresh") }
                )
            }
        } else {
            items(categories) { category ->
                BookmarkCategoryChip(
                    category = category,
                    onClick = {
                        selectedCategoryName = category.name
                    },
                    onToggleVisibility = {
                        WearCommandService.toggleBookmarkCategory(context, category.name)
                    }
                )
            }
        }
    }
}

@Composable
fun BookmarkCategoryChip(category: BookmarkCategoryItem, onClick: () -> Unit, onToggleVisibility: () -> Unit) {
    Chip(
        onClick = onClick,
        label = { Text(category.name, maxLines = 1) },
        secondaryLabel = { 
            Text("${category.bookmarksCount} bmk, ${category.tracksCount} trk", style = MaterialTheme.typography.caption3)
        },
        icon = {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleVisibility),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (category.isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = if (category.isVisible) "Visible" else "Hidden",
                    tint = if (category.isVisible) Color(0xFF4CAF50) else Color.Gray,
                    modifier = Modifier.size(18.dp)
                )
            }
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun BookmarkListScreen(category: BookmarkCategoryItem, onBack: () -> Unit) {
    val context = LocalContext.current
    
    var bookmarks by remember { mutableStateOf<List<app.organicmaps.sdk.bookmarks.data.BookmarkInfo>>(emptyList()) }

    fun refreshBookmarks() {
        val manager = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
        val cat = manager.getCategories().find { it.name.equals(category.name, ignoreCase = true) }
        if (cat != null) {
            val list = mutableListOf<app.organicmaps.sdk.bookmarks.data.BookmarkInfo>()
            for (i in 0 until cat.bookmarksCount) {
                val bmkId = cat.getBookmarkIdByPosition(i)
                manager.getBookmarkInfo(bmkId)?.let { list.add(it) }
            }
            bookmarks = list
        } else {
            bookmarks = emptyList()
        }
    }

    DisposableEffect(category.name) {
        refreshBookmarks()
        val listener = app.organicmaps.sdk.bookmarks.data.DataChangedListener {
            refreshBookmarks()
        }
        app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.addCategoriesUpdatesListener(listener)
        onDispose {
            app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE.removeCategoriesUpdatesListener(listener)
        }
    }

    var editingBookmark by remember { mutableStateOf<app.organicmaps.sdk.bookmarks.data.BookmarkInfo?>(null) }

    if (editingBookmark != null) {
        BookmarkEditScreen(bookmark = editingBookmark!!, onBack = { editingBookmark = null })
        return
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        autoCentering = AutoCenteringParams(itemIndex = 0)
    ) {
        item {
            ListHeader {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(category.name, textAlign = TextAlign.Center, maxLines = 1)
                    Text("${bookmarks.size} bookmarks", style = MaterialTheme.typography.caption3)
                }
            }
        }

        if (bookmarks.isEmpty()) {
            item {
                Text(
                    text = "No bookmarks found locally",
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        } else {
            items(bookmarks) { bookmark ->
                Chip(
                    onClick = {
                        WearCommandService.showBookmark(context, bookmark.bookmarkId)
                    },
                    label = { Text(bookmark.name, maxLines = 1) },
                    secondaryLabel = {
                        Text(bookmark.address, maxLines = 1, style = MaterialTheme.typography.caption3)
                    },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .background(
                                    Color(app.organicmaps.sdk.bookmarks.data.PredefinedColors.getColor(bookmark.icon.color)),
                                    CircleShape
                                )
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        item {
            CompactChip(
                onClick = onBack,
                label = { Text("Back") },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun BookmarkEditScreen(bookmark: app.organicmaps.sdk.bookmarks.data.BookmarkInfo, onBack: () -> Unit) {
    // Basic editing UI
    val context = LocalContext.current
    var name by remember { mutableStateOf(bookmark.name) }
    
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        autoCentering = AutoCenteringParams(itemIndex = 0)
    ) {
        item {
            ListHeader {
                Text("Edit Bookmark", textAlign = TextAlign.Center)
            }
        }
        
        item {
            Text(
                text = "Name: $name",
                style = MaterialTheme.typography.caption2,
                modifier = Modifier.padding(8.dp)
            )
        }
        
        item {
            Chip(
                onClick = {
                    name += " (Edited)"
                },
                label = { Text("Rename") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        item {
            Chip(
                onClick = {
                    // Cycle through predefined colors (skip index 0 which is 'no color')
                    var newColor = bookmark.icon.color + 1
                    if (newColor >= 16) newColor = 1 
                    WearCommandService.updateBookmark(context, bookmark.bookmarkId, name, newColor)
                    onBack()
                },
                label = { Text("Change Color & Save") },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        item {
            CompactChip(
                onClick = onBack,
                label = { Text("Cancel") },
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
