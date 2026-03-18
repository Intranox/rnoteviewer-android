package com.example.rnoteviewer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class LoadState {
    object Idle    : LoadState()
    object Loading : LoadState()
    data class Success(val doc: RnoteDocument, val tree: RTree) : LoadState()
    data class Error(val message: String) : LoadState()
}

class DocumentViewModel(app: Application) : AndroidViewModel(app) {

    val loadState = MutableLiveData<LoadState>(LoadState.Idle)

    fun loadFile(uri: Uri) {
        if (loadState.value is LoadState.Loading) return
        loadState.value = LoadState.Loading

        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    FontManager.init(getApplication())          // carica .ttf dalla dir font
                    val doc  = RnoteParser.parse(getApplication(), uri)
                    val tree = RTree.build(doc.elements, pageSize = 16)
                    Pair(doc, tree)
                }
                loadState.value = LoadState.Success(result.first, result.second)
            } catch (e: Exception) {
                loadState.value = LoadState.Error(
                    e.message ?: "Errore sconosciuto durante il caricamento"
                )
            }
        }
    }
}
