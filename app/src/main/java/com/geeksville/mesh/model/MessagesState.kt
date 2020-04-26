package com.geeksville.mesh.model

import android.os.RemoteException
import androidx.lifecycle.MutableLiveData
import com.geeksville.android.BuildUtils.isEmulator
import com.geeksville.android.Logging
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.utf8
import java.util.*

/**
 * the model object for a text message
 *
 * if errorMessage is set then we had a problem sending this message
 */
data class TextMessage(
    val from: String,
    val text: String,
    val date: Date = Date(),
    val errorMessage: String? = null
)


class MessagesState(private val ui: UIViewModel) : Logging {
    private val testTexts = listOf(
        TextMessage(
            "+16508765310",
            "I found the cache"
        ),
        TextMessage(
            "+16508765311",
            "Help! I've fallen and I can't get up."
        )
    )

    // If the following (unused otherwise) line is commented out, the IDE preview window works.
    // if left in the preview always renders as empty.
    val messages =
        object : MutableLiveData<List<TextMessage>>(if (isEmulator) testTexts else listOf()) {

        }

    /// add a message our GUI list of past msgs
    fun addMessage(m: TextMessage) {
        messages.value = messages.value!! + m
    }

    /// Send a message and added it to our GUI log
    fun sendMessage(str: String, dest: String? = null) {
        var error: String? = null
        val service = ui.meshService
        if (service != null)
            try {
                service.sendData(
                    dest,
                    str.toByteArray(utf8),
                    MeshProtos.Data.Type.CLEAR_TEXT_VALUE
                )
            } catch (ex: RemoteException) {
                error = "Error: ${ex.message}"
            }
        else
            error = "Error: No Mesh service"

        addMessage(
            TextMessage(
                ui.nodeDB.myId.value!!,
                str,
                errorMessage = error
            )
        )
    }
}
