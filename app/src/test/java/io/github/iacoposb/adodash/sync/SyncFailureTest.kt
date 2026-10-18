package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.AdoException
import io.github.iacoposb.adodash.auth.SignInRequiredException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class SyncFailureTest {
    @Test fun `an expired session or a refused PAT asks to sign in`() {
        assertEquals(SyncFailure.AUTH, SyncFailure.of(SignInRequiredException()))
        assertEquals(SyncFailure.AUTH, SyncFailure.of(AdoException(401, "Invalid or expired credentials")))
    }

    @Test fun `no connection is offline`() {
        assertEquals(SyncFailure.NETWORK, SyncFailure.of(UnknownHostException("dev.azure.com")))
        assertEquals(SyncFailure.NETWORK, SyncFailure.of(ConnectException("refused")))
    }

    @Test fun `no project readable tells why through its cause`() {
        assertEquals(SyncFailure.NETWORK, SyncFailure.of(SyncException("Contoso: no host", UnknownHostException())))
        assertEquals(SyncFailure.AUTH, SyncFailure.of(SyncException("Contoso: refused", AdoException(401, "x"))))
        assertEquals(SyncFailure.OTHER, SyncFailure.of(SyncException("no readable project", null)))
    }

    @Test fun `server errors, timeouts and forbidden calls are other errors`() {
        assertEquals(SyncFailure.OTHER, SyncFailure.of(AdoException(500, "HTTP 500")))
        assertEquals(SyncFailure.OTHER, SyncFailure.of(AdoException(403, "HTTP 403")))
        assertEquals(SyncFailure.OTHER, SyncFailure.of(SocketTimeoutException("timeout")))
        assertEquals(SyncFailure.OTHER, SyncFailure.of(IllegalStateException("x")))
    }
}
