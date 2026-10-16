package io.github.iacoposb.adodash

import android.content.Context
import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.auth.EntraNotConfiguredException
import io.github.iacoposb.adodash.auth.SignInRequiredException
import io.github.iacoposb.adodash.update.UpdateException
import io.github.iacoposb.adodash.update.UpdateException.Reason

/**
 * A failure as the user reads it, in the device language. The app's own
 * exceptions carry English messages for the logs; the rest (network errors
 * and the like) keep their message.
 */
fun Context.errorText(e: Throwable): String = when (e) {
    is AdoException -> if (e.code == 401) getString(R.string.error_credentials) else e.message
    is SignInRequiredException -> getString(R.string.error_sign_in_required)
    is EntraNotConfiguredException -> getString(R.string.error_entra_client_id)
    is UpdateException -> when (e.reason) {
        Reason.RATE_LIMITED -> getString(R.string.update_error_rate_limited)
        Reason.HTTP -> getString(R.string.update_error_http, e.code)
        Reason.INVALID_RESPONSE -> getString(R.string.update_error_invalid_response)
        Reason.NOT_IN_SUMS -> getString(R.string.update_error_not_in_sums, e.detail)
        Reason.CHECKSUM_MISMATCH -> getString(R.string.update_error_checksum)
        Reason.DOWNLOAD_HTTP -> getString(R.string.update_error_download_http, e.code)
        Reason.UNREACHABLE -> getString(R.string.update_error_unreachable)
    }
    else -> e.message
} ?: e.javaClass.simpleName
