package info.soslive.stream.core.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Text that can be produced in a ViewModel (no Context) and resolved in the UI. */
sealed interface UiText {
    data class Raw(val value: String) : UiText
    class Res(@StringRes val id: Int, vararg val args: Any) : UiText

    @Composable
    fun asString(): String = when (this) {
        is Raw -> value
        is Res -> stringResource(id, *args)
    }

    fun asString(context: android.content.Context): String = when (this) {
        is Raw -> value
        is Res -> context.getString(id, *args)
    }
}
