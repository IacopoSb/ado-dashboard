package io.github.iacoposb.adodash.data

/** The checks of the Entra app registration's IDs entered in the settings: pure, for the tests. */
object EntraIds {

    /** What is wrong with a client ID. */
    sealed interface ClientIdProblem {
        /** Nothing entered: no error shown, but no sign-in either. */
        data object Empty : ClientIdProblem

        /**
         * Not a GUID. [missing] digits short of 8-4-4-4-12 (negative: too many) when it has the
         * five groups of hexadecimal digits but some are the wrong length; null otherwise.
         */
        data class NotGuid(val missing: Int? = null) : ClientIdProblem
    }

    private val GUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HEX = Regex("^[0-9a-fA-F]+$")
    private val GROUPS = listOf(8, 4, 4, 4, 12)

    /** A domain name such as contoso.onmicrosoft.com: labels of letters, digits and hyphens, a top-level of letters. */
    private val DOMAIN = Regex("^(?=.{1,253}$)([a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,63}$")

    fun isGuid(value: String) = GUID.matches(value.trim())

    /** Null when [value] is a valid application (client) ID: a GUID. */
    fun clientIdProblem(value: String): ClientIdProblem? {
        val v = value.trim()
        if (v.isEmpty()) return ClientIdProblem.Empty
        if (GUID.matches(v)) return null
        val groups = v.split('-')
        val shaped = groups.size == GROUPS.size && groups.all { it.isNotEmpty() && HEX.matches(it) }
        return ClientIdProblem.NotGuid(if (shaped) GROUPS.sum() - groups.sumOf { it.length } else null)
    }

    /** A tenant: empty (any organization), a GUID or a domain such as contoso.onmicrosoft.com. */
    fun isValidTenant(value: String): Boolean {
        val v = value.trim()
        return v.isEmpty() || GUID.matches(v) || DOMAIN.matches(v)
    }
}
