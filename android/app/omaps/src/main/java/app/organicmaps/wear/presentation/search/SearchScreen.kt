package app.organicmaps.wear.presentation.search

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import androidx.wear.tooling.preview.devices.WearDevices
import app.organicmaps.wear.NavigationStateHolder
import app.organicmaps.wear.SearchResultItem
import app.organicmaps.wear.WearCommandService
import kotlinx.coroutines.launch

/**
 * The main search screen for the WearOS app.
 *
 * This screen provides:
 * 1. A text field for manual query entry.
 * 2. A voice input button for speech-to-text search.
 * 3. Recent search history when the query is empty.
 * 4. Real-time search results as the user types (sent to the phone app).
 * 5. A selection screen to choose the transportation mode (Walk/Bike) after selecting a result.
 *
 * @param onSearchClick Callback when a search action is performed (currently unused by caller).
 */
@Composable
fun SearchScreen(onSearchClick: () -> Unit) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    
    var searchText by remember { mutableStateOf("") }
    var selectedResult by remember { mutableStateOf<SearchResultItem?>(null) }
    val focusRequester = remember { FocusRequester() }
    val navState by NavigationStateHolder.state.collectAsState()
    val listState = rememberScalingLazyListState()

    // Request history on launch
    LaunchedEffect(Unit) {
        WearCommandService.requestSearchHistory(context)
    }

    /**
     * Handles query changes. Triggers a search on the phone if the query is long enough.
     */
    val onQueryChanged: (String) -> Unit = { newQuery ->
        val oldText = searchText
        searchText = newQuery
        if (newQuery.length > 2 && newQuery != oldText) {
            WearCommandService.search(context, newQuery)
        }
    }

    // Voice input launcher
    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val query = data?.get(0)
        if (!query.isNullOrEmpty()) {
            searchText = query
            WearCommandService.search(context, query)
            coroutineScope.launch {
                listState.animateScrollToItem(1)
            }
        }
    }

    Scaffold(timeText = { TimeText() }) {
        if (selectedResult != null) {
            ModeSelectionScreen(
                result = selectedResult!!,
                onModeSelected = { routerType ->
                    WearCommandService.selectSearchResult(context, selectedResult!!, routerType)
                    selectedResult = null
                    searchText = ""
                },
                onCancel = { selectedResult = null }
            )
        } else {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = 32.dp, start = 8.dp, end = 8.dp, bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                // Unified Search Input
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .focusRequester(focusRequester),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            BasicTextField(
                                value = searchText,
                                onValueChange = onQueryChanged,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(color = MaterialTheme.colors.onSurface, fontSize = 16.sp),
                                cursorBrush = SolidColor(MaterialTheme.colors.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = {
                                    if (searchText.isNotEmpty()) {
                                        WearCommandService.search(context, searchText)
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        coroutineScope.launch {
                                            listState.animateScrollToItem(1)
                                        }
                                    }
                                }),
                                decorationBox = { innerTextField ->
                                    if (searchText.isEmpty()) {
                                        Text("Search...", style = TextStyle(color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)))
                                    }
                                    innerTextField()
                                }
                            )
                        }
                        
                        Button(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                }
                                voiceLauncher.launch(intent)
                            },
                            modifier = Modifier.size(ButtonDefaults.SmallButtonSize),
                            colors = ButtonDefaults.secondaryButtonColors()
                        ) {
                            Icon(Icons.Default.Mic, modifier = Modifier.size(18.dp), contentDescription = "Voice")
                        }
                    }
                }

                if (searchText.isEmpty()) {
                    // Show History
                    if (navState.searchHistory.isNotEmpty()) {
                        item {
                            Text(
                                "Recent Searches",
                                style = MaterialTheme.typography.caption2,
                                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                            )
                        }
                        items(navState.searchHistory) { query ->
                            Chip(
                                onClick = { 
                                    searchText = query
                                    WearCommandService.search(context, query)
                                    focusManager.clearFocus()
                                    keyboardController?.hide()
                                },
                                label = { Text(query, maxLines = 1) },
                                icon = { Icon(Icons.Default.History, modifier = Modifier.size(16.dp), contentDescription = null) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ChipDefaults.secondaryChipColors()
                            )
                        }
                    } else {
                        item {
                            Text(
                                "Type or use voice to search",
                                modifier = Modifier.padding(top = 20.dp),
                                style = MaterialTheme.typography.caption2,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                } else {
                    // Show Results
                    if (navState.searchResults.isEmpty() && navState.isSearching) {
                        item { CircularProgressIndicator(modifier = Modifier.padding(top = 20.dp)) }
                    } else if (navState.searchResults.isEmpty()) {
                        item {
                            Text(
                                "No results found",
                                modifier = Modifier.padding(top = 20.dp),
                                style = MaterialTheme.typography.caption2
                            )
                        }
                    } else {
                        items(navState.searchResults) { result ->
                            SearchResultChip(result) {
                                selectedResult = result
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A screen to select the transportation mode for the selected search result.
 * 
 * @param result The search result selected by the user.
 * @param onModeSelected Callback with the selected router type (1 for Walk, 2 for Bike).
 * @param onCancel Callback to cancel selection and return to the search list.
 */
@Composable
fun ModeSelectionScreen(
    result: SearchResultItem,
    onModeSelected: (Int) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val displayName = result.name.ifEmpty { result.description }
        Text(displayName, style = MaterialTheme.typography.title3, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { onModeSelected(1) }, modifier = Modifier.size(ButtonDefaults.DefaultButtonSize)) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = "Walk")
            }
            Button(onClick = { onModeSelected(2) }, modifier = Modifier.size(ButtonDefaults.DefaultButtonSize)) {
                Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = "Bike")
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.secondaryButtonColors(),
            modifier = Modifier.height(32.dp).fillMaxWidth()
        ) {
            Text("Cancel", style = MaterialTheme.typography.caption2)
        }
    }
}

/**
 * A chip representing a single search result.
 * 
 * @param result The search result item.
 * @param onClick Callback when the chip is clicked.
 */
@Composable
fun SearchResultChip(result: SearchResultItem, onClick: () -> Unit) {
    val title = result.name.ifEmpty { result.description }
    val subTitle = if (result.name.isNotEmpty()) result.description else ""
    
    Chip(
        onClick = onClick,
        label = { Text(title, maxLines = 1) },
        secondaryLabel = if (subTitle.isNotEmpty()) { { Text(subTitle, maxLines = 1) } } else null,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.secondaryChipColors()
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun SearchScreenPreview() {
    SearchScreen(onSearchClick = {})
}
