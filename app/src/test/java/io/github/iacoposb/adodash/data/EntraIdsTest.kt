package io.github.iacoposb.adodash.data

import io.github.iacoposb.adodash.data.EntraIds.ClientIdProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EntraIdsTest {
    @Test fun `a client ID is a GUID, in either case and with spaces around`() {
        assertNull(EntraIds.clientIdProblem("3f2a9c1e-7b4d-4e8a-9c21-5d6e7f809a1b"))
        assertNull(EntraIds.clientIdProblem(" 3F2A9C1E-7B4D-4E8A-9C21-5D6E7F809A1B "))
        assertTrue(EntraIds.isGuid("3f2a9c1e-7b4d-4e8a-9c21-5d6e7f809a1b"))
    }

    @Test fun `an empty client ID is not an error to show, but no sign-in`() {
        assertEquals(ClientIdProblem.Empty, EntraIds.clientIdProblem(""))
        assertEquals(ClientIdProblem.Empty, EntraIds.clientIdProblem("   "))
    }

    @Test fun `digits missing or too many are counted when the groups are there`() {
        assertEquals(ClientIdProblem.NotGuid(1), EntraIds.clientIdProblem("3f2a9c1e-7b4d-4e8a-9c21-5d6e7f809a1"))
        assertEquals(ClientIdProblem.NotGuid(4), EntraIds.clientIdProblem("3f2a9c1e-7b4-4e8a-9c21-5d6e7f809"))
        assertEquals(ClientIdProblem.NotGuid(-1), EntraIds.clientIdProblem("3f2a9c1e-7b4d-4e8a-9c21-5d6e7f809a1bc"))
        // The right number of digits in the wrong groups
        assertEquals(ClientIdProblem.NotGuid(0), EntraIds.clientIdProblem("3f2a9c1-e7b4d-4e8a-9c21-5d6e7f809a1b"))
    }

    @Test fun `anything else is not a GUID, without a count`() {
        assertEquals(ClientIdProblem.NotGuid(null), EntraIds.clientIdProblem("contoso"))
        assertEquals(ClientIdProblem.NotGuid(null), EntraIds.clientIdProblem("3f2a9c1e7b4d4e8a9c215d6e7f809a1b"))
        assertEquals(ClientIdProblem.NotGuid(null), EntraIds.clientIdProblem("3f2a9c1g-7b4d-4e8a-9c21-5d6e7f809a1b"))
        assertEquals(ClientIdProblem.NotGuid(null), EntraIds.clientIdProblem("{3f2a9c1e-7b4d-4e8a-9c21-5d6e7f809a1b}"))
    }

    @Test fun `a tenant is empty, a GUID or a domain`() {
        assertTrue(EntraIds.isValidTenant(""))
        assertTrue(EntraIds.isValidTenant("  "))
        assertTrue(EntraIds.isValidTenant("72f988bf-86f1-41af-91ab-2d7cd011db47"))
        assertTrue(EntraIds.isValidTenant("contoso.onmicrosoft.com"))
        assertTrue(EntraIds.isValidTenant("login.contoso-group.co.uk"))
    }

    @Test fun `a tenant that is neither is refused`() {
        assertFalse(EntraIds.isValidTenant("contoso"))
        assertFalse(EntraIds.isValidTenant("72f988bf-86f1-41af-91ab-2d7cd011db4"))
        assertFalse(EntraIds.isValidTenant("https://contoso.onmicrosoft.com"))
        assertFalse(EntraIds.isValidTenant("contoso..com"))
        assertFalse(EntraIds.isValidTenant("-contoso.com"))
        assertFalse(EntraIds.isValidTenant("contoso.c0m"))
    }
}
