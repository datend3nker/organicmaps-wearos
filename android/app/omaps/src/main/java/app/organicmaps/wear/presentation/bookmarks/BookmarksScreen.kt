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
import app.organicmaps.wear.UiEvent
import app.organicmaps.wear.WearCommandService
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

import androidx.compose.ui.text.input.TextFieldValue
import androidx.wear.compose.material.dialog.Dialog
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp

@Composable
fun BookmarksScreen(isVisible: Boolean) {
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val categories = navState.bookmarkCategories
    
    var selectedCategoryName by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

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

    if (showCreateDialog) {
        TextInputDialog(
            title = "New List",
            onDismiss = { showCreateDialog = false },
            onDone = { name ->
                if (name.isNotEmpty()) {
                    WearCommandService.createBookmarkCategory(context, name)
                }
                showCreateDialog = false
            }
        )
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
                label = { Text(if (isAnySyncing) "Syncing..." else "Push to Phone") },
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

        item {
            Chip(
                onClick = { showCreateDialog = true },
                label = { Text("New List") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
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

    val isSyncingFromPhone = bookmarks.isEmpty() && category.bookmarksCount > 0
    var syncRequested by remember(category.name) { mutableStateOf(false) }
    LaunchedEffect(category.name, isSyncingFromPhone) {
        if (isSyncingFromPhone && !syncRequested) {
            syncRequested = true
            val navState = NavigationStateHolder.state.value
            if (!navState.standaloneMode && navState.isPhoneConnected) {
                // Pull via the per-bookmark upsert path (phone answers PATH_BOOKMARKS_REQUEST with
                // syncBookmarksNow). The old syncCategory → KMZ export → loadBookmarksFile import
                // uniquified colliding category names ("My Places" → "My Places1" …) and was the
                // source of the duplicate-category explosion. Upsert creates into the found-by-name
                // category, so no uniquify, no cascade.
                WearCommandService.requestBookmarks(context)
            }
        }
    }

    var editingBookmark by remember { mutableStateOf<app.organicmaps.sdk.bookmarks.data.BookmarkInfo?>(null) }
    var showingCategorySettings by remember { mutableStateOf(false) }

    if (editingBookmark != null) {
        BookmarkEditScreen(bookmark = editingBookmark!!, onBack = { editingBookmark = null })
        return
    }

    if (showingCategorySettings) {
        CategorySettingsScreen(category = category, onBack = { showingCategorySettings = false }, onDeleted = { 
            showingCategorySettings = false
            onBack()
        })
        return
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        autoCentering = AutoCenteringParams(itemIndex = 0)
    ) {
        item {
            ListHeader {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                        Text(category.name, textAlign = TextAlign.Center, maxLines = 1)
                        Text("${bookmarks.size} bookmarks", style = MaterialTheme.typography.caption3)
                    }
                    Button(onClick = { showingCategorySettings = true }, modifier = Modifier.size(ButtonDefaults.SmallButtonSize), colors = ButtonDefaults.secondaryButtonColors()) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }

        if (bookmarks.isEmpty()) {
            item {
                if (isSyncingFromPhone) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Syncing ${category.bookmarksCount} from phone…",
                            style = MaterialTheme.typography.caption2,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Text(
                        text = "No bookmarks found locally",
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        } else {
            items(bookmarks) { bookmark ->
                Chip(
                    onClick = {
                        editingBookmark = bookmark
                    },
                    label = { Text(bookmark.name, maxLines = 1) },
                    secondaryLabel = {
                        val sub = bookmark.description.ifBlank {
                            "%.5f, %.5f".format(bookmark.lat, bookmark.lon)
                        }
                        Text(sub, maxLines = 1, style = MaterialTheme.typography.caption3)
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
    val context = LocalContext.current
    val navState by NavigationStateHolder.state.collectAsState()
    val categories = navState.bookmarkCategories
    
    var showRenameDialog by remember { mutableStateOf(false) }
    var showMoveDialog by remember { mutableStateOf(false) }

    if (showRenameDialog) {
        TextInputDialog(
            title = "Rename Bookmark",
            initialValue = bookmark.name,
            onDismiss = { showRenameDialog = false },
            onDone = { newName ->
                if (newName.isNotEmpty()) {
                    WearCommandService.updateBookmark(context, bookmark.bookmarkId, newName, bookmark.icon.color)
                }
                showRenameDialog = false
                onBack() 
            }
        )
    }

    if (showMoveDialog) {
        CategorySelectionDialog(
            categories = categories,
            currentCategoryId = bookmark.categoryId,
            onDismiss = { showMoveDialog = false },
            onSelected = { newCatId ->
                WearCommandService.updateBookmark(context, bookmark.bookmarkId, bookmark.name, bookmark.icon.color, newCatId)
                showMoveDialog = false
                onBack()
            }
        )
    }

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
            Chip(
                onClick = { showRenameDialog = true },
                label = { Text(bookmark.name, maxLines = 1) },
                secondaryLabel = { Text("Rename", style = MaterialTheme.typography.caption3) },
                icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            val currentCat = categories.find { it.id == bookmark.categoryId }
            Chip(
                onClick = { showMoveDialog = true },
                label = { Text(currentCat?.name ?: "Unknown Folder", maxLines = 1) },
                secondaryLabel = { Text("Move to Folder", style = MaterialTheme.typography.caption3) },
                icon = { Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(20.dp)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        item {
            Chip(
                onClick = {
                    app.organicmaps.sdk.Framework.nativeZoomToPoint(bookmark.lat, bookmark.lon, 17, true)
                    NavigationStateHolder.emitEvent(UiEvent.OpenMap)
                    NavigationStateHolder.update { it.copy(isMapUnlocked = false) }
                },
                label = { Text("Show on Watch") },
                icon = { Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(20.dp)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = { WearCommandService.showBookmark(context, bookmark.bookmarkId) },
                label = { Text("Show on Phone") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = {
                    var newColor = bookmark.icon.color + 1
                    if (newColor >= 16) newColor = 1
                    WearCommandService.updateBookmark(context, bookmark.bookmarkId, bookmark.name, newColor)
                    onBack()
                },
                label = { Text("Change Color & Save") },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Chip(
                onClick = {
                    val mgr = app.organicmaps.sdk.bookmarks.data.BookmarkManager.INSTANCE
                    try { mgr.deleteBookmark(bookmark.bookmarkId) } catch (e: Exception) {}
                    app.organicmaps.wear.WatchBookmarkSyncManager.onLocalBookmarksChanged(context, isUserAction = true)
                    app.organicmaps.wear.WatchBookmarkSyncManager.requestSync(context)
                    onBack()
                },
                label = { Text("Delete") },
                icon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFFB00020), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            )
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
fun CategorySettingsScreen(category: BookmarkCategoryItem, onBack: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    var showRenameDialog by remember { mutableStateOf(false) }
    
    if (showRenameDialog) {
        TextInputDialog(
            title = "Rename List",
            initialValue = category.name,
            onDismiss = { showRenameDialog = false },
            onDone = { newName ->
                if (newName.isNotEmpty()) {
                    WearCommandService.renameBookmarkCategory(context, category.name, newName)
                }
                showRenameDialog = false
                onBack()
            }
        )
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        autoCentering = AutoCenteringParams(itemIndex = 0)
    ) {
        item {
            ListHeader {
                Text("List Settings", textAlign = TextAlign.Center)
            }
        }
        
        item {
            Chip(
                onClick = { showRenameDialog = true },
                label = { Text(category.name, maxLines = 1) },
                secondaryLabel = { Text("Rename List", style = MaterialTheme.typography.caption3) },
                icon = { Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(20.dp)) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Chip(
                onClick = {
                    WearCommandService.deleteBookmarkCategory(context, category.name)
                    onDeleted()
                },
                label = { Text("Delete List") },
                icon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(20.dp)) },
                colors = ChipDefaults.chipColors(backgroundColor = Color(0xFFB00020), contentColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            )
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
fun CategorySelectionDialog(
    categories: List<BookmarkCategoryItem>,
    currentCategoryId: Long,
    onDismiss: () -> Unit,
    onSelected: (Long) -> Unit
) {
    Dialog(showDialog = true, onDismissRequest = onDismiss) {
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            autoCentering = AutoCenteringParams(itemIndex = 0)
        ) {
            item {
                ListHeader { Text("Choose Folder", textAlign = TextAlign.Center) }
            }
            items(categories) { category ->
                val isSelected = category.id == currentCategoryId
                Chip(
                    onClick = { onSelected(category.id) },
                    label = { Text(category.name, maxLines = 1) },
                    colors = if (isSelected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                CompactChip(onClick = onDismiss, label = { Text("Cancel") })
            }
        }
    }
}

@Composable
fun TextInputDialog(title: String, initialValue: String = "", onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var textValue by remember { mutableStateOf(TextFieldValue(initialValue)) }
    Dialog(
        showDialog = true,
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(title, style = MaterialTheme.typography.caption1)
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(MaterialTheme.colors.surface, CircleShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colors.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                )
                if (textValue.text.isEmpty()) {
                    Text("Type here...", style = TextStyle(color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDismiss, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                    Icon(Icons.Default.Clear, contentDescription = "Cancel")
                }
                Button(onClick = { onDone(textValue.text) }, modifier = Modifier.size(ButtonDefaults.SmallButtonSize)) {
                    Icon(Icons.Default.Check, contentDescription = "Done")
                }
            }
        }
    }
}
