package dev.screenveil.app.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ProtectionStatus {
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    data class State(
        val active: Boolean = false,
        val modelReady: Boolean = false,
        val message: String = "Protection is stopped",
        val regionsRedacted: Int = 0,
        val lastInferenceMs: Long? = null,
        val error: String? = null
    )

    fun update(value: State) { mutable.value = value }
    fun setMessage(message: String) { mutable.value = mutable.value.copy(message = message) }
}
