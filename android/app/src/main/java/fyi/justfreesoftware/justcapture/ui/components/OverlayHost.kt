package fyi.justfreesoftware.justcapture.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf

/**
 * Where a screen puts something that must cover the whole app, tab bar
 * included, such as a video player. The app's root draws whatever is set
 * here on top of everything else.
 *
 * A dialog isn't used for this: its window is sized from the screen
 * configuration rather than the real window, which leaves it too small or
 * off-center when the app draws behind the system bars or the screen turns.
 */
val LocalOverlayHost = compositionLocalOf<MutableState<(@Composable () -> Unit)?>> {
    error("No overlay host provided")
}
