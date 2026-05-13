package app.organicmaps.wear.presentation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.organicmaps.wear.NavigationState
import app.organicmaps.wear.NavigationStateHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    var searchQuery = mutableStateOf(TextFieldValue(""))
    
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating = _isNavigating.asStateFlow()
    
    init {
        viewModelScope.launch {
            NavigationStateHolder.state.collectLatest { state ->
                // Basic stabilization is done in NavigationStateHolder, 
                // but we can add UI-specific stabilization here if needed.
                _isNavigating.value = state.isNavigating
            }
        }
    }
}
