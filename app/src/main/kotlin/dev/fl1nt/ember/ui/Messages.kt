package dev.fl1nt.ember.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fl1nt.ember.AppContainer
import kotlinx.coroutines.CancellationException

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer is not provided") }

/** Creates a ViewModel with access to Ember's [AppContainer]. */
@Composable
inline fun <reified VM : ViewModel> emberViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = LocalAppContainer.current
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}

/** Like [runCatching], but never swallows coroutine cancellation. */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    Result.failure(error)
}
