package dev.fl1nt.ember.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.fl1nt.ember.AppContainer
import dev.fl1nt.ember.R
import dev.fl1nt.ember.api.FlareException
import kotlinx.coroutines.CancellationException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

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

/** Explains a failure in plain words, with the next step when there is one. */
fun describeError(context: Context, error: Throwable, host: String? = null): String {
    val place = host ?: context.getString(R.string.error_your_server)
    return when (error) {
        is FlareException.Unauthorized -> context.getString(R.string.error_unauthorized)
        is FlareException.Forbidden -> error.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_forbidden)
        is FlareException.TooLarge -> error.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_too_large)
        is FlareException.RateLimited -> error.retryAfterSeconds.toInt().let { context.resources.getQuantityString(R.plurals.error_rate_limited, it, it) }
        is FlareException.UnexpectedResponse -> context.getString(R.string.error_not_flare, place)
        is FlareException.Server -> context.getString(R.string.error_server, error.status)
        is FlareException.Network -> networkMessage(context, error.cause, place)
        is FlareException -> error.message ?: context.getString(R.string.error_generic)
        else -> networkMessage(context, error, place)
    }
}

private fun networkMessage(context: Context, cause: Throwable?, place: String): String {
    val root = generateSequence(cause) { it.cause }.toList()
    return when {
        root.any { it is SSLException || it is CertificateException } -> context.getString(R.string.error_certificate, place)
        root.any { it is UnknownHostException } -> context.getString(R.string.error_unknown_host, place)
        root.any { it is SocketTimeoutException } -> context.getString(R.string.error_timeout, place)
        root.any { it is ConnectException } -> context.getString(R.string.error_refused, place)
        else -> context.getString(R.string.error_network, place)
    }
}
