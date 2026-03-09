package io.musicassistant.companion.ui.search

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.musicassistant.companion.data.model.MediaItemImage
import io.musicassistant.companion.data.model.SearchResults
import io.musicassistant.companion.service.ServiceLocator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SearchViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SearchViewModel"
        private const val DEBOUNCE_MS = 400L
    }

    private val api = ServiceLocator.api

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow(SearchResults())
    val results: StateFlow<SearchResults> = _results.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        if (newQuery.length < 2) {
            _results.value = SearchResults()
            _isSearching.value = false
            return
        }
        _isSearching.value = true
        searchJob =
                viewModelScope.launch {
                    delay(DEBOUNCE_MS)
                    try {
                        _results.value = api.search(newQuery, limit = 20)
                    } catch (e: Exception) {
                        Log.e(TAG, "Search failed: ${e.message}")
                    } finally {
                        _isSearching.value = false
                    }
                }
    }

    fun getImageUrl(imagePath: String): String {
        val baseUrl =
                ServiceLocator.apiClient.connectionUrl.ifEmpty {
                    ServiceLocator.apiClient.serverInfo.value?.baseUrl ?: ""
                }
        return api.getImageUrl(imagePath, baseUrl)
    }

    fun getImageUrl(image: MediaItemImage): String {
        val baseUrl =
                ServiceLocator.apiClient.connectionUrl.ifEmpty {
                    ServiceLocator.apiClient.serverInfo.value?.baseUrl ?: ""
                }
        return api.getImageUrl(image, baseUrl)
    }
}
