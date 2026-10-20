package com.ecooctano.telemetry.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class UiMessage(
    val id: Long,
    val text: String,
    val shareFiles: List<File> = emptyList(),
)

/**
 * Mensagens (snackbar) e pedidos de compartilhamento do app inteiro.
 * Vive no Application para que o gravador e as duas versões da UI usem o mesmo canal.
 */
class UiEvents {
    private val _message = MutableStateFlow<UiMessage?>(null)
    val message: StateFlow<UiMessage?> = _message.asStateFlow()

    private val _shareRequests = MutableSharedFlow<List<File>>(extraBufferCapacity = 4)
    val shareRequests: SharedFlow<List<File>> = _shareRequests.asSharedFlow()

    private var counter = 0L

    @Synchronized
    fun post(text: String, shareFiles: List<File> = emptyList()) {
        counter++
        _message.value = UiMessage(counter, text, shareFiles)
    }

    fun consume(id: Long) {
        _message.update { if (it?.id == id) null else it }
    }

    fun requestShare(files: List<File>) {
        if (files.isNotEmpty()) _shareRequests.tryEmit(files)
    }
}
