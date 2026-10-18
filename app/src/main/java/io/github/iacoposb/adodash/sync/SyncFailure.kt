package io.github.iacoposb.adodash.sync

import com.microsoft.identity.client.exception.MsalException
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.auth.SignInRequiredException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.UnknownHostException

/** Why a sync failed, for what the dashboard says and offers. */
enum class SyncFailure {
    /** No connection: the data stays as it was, and the sync runs again when the network is back. */
    NETWORK,

    /** The Microsoft session expired or the PAT is refused: only signing in again helps. */
    AUTH,

    /** Anything else (server errors, timeouts): the reason, and Retry. */
    OTHER,
    ;

    companion object {
        /** The kind of [e], looking through its causes. */
        fun of(e: Throwable): SyncFailure {
            var cause: Throwable? = e
            var depth = 0
            while (cause != null && depth < 10) {
                when {
                    cause is SignInRequiredException -> return AUTH
                    cause is AdoException && cause.code == 401 -> return AUTH
                    cause is UnknownHostException || cause is ConnectException ||
                        cause is NoRouteToHostException || cause is PortUnreachableException -> return NETWORK
                    // MSAL refreshing a token offline
                    cause is MsalException && cause.errorCode in MSAL_NETWORK -> return NETWORK
                }
                cause = cause.cause
                depth++
            }
            return OTHER
        }

        private val MSAL_NETWORK = setOf("device_network_not_available", "io_error")
    }
}

/** No project could be read: the first failure as the cause, so that [SyncFailure.of] can tell why. */
class SyncException(message: String, cause: Throwable?) : Exception(message, cause)
