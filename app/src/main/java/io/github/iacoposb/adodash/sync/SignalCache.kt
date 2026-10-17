package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrThread
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * What a sync keeps for the next ones, so that unchanged pull requests and finished builds are
 * not read again. Kept by the caller across syncs; safe for the parallel reads of one sync.
 */
class SignalCache {
    /** Policies read for [key] (commit, votes and active threads) at [at]. */
    data class Policies(val key: String, val evaluations: List<PolicyEvaluation>, val at: Instant)

    /** Threads of a pull request that is neither mine nor to review, read for [key] at [at]. */
    data class Threads(val key: String, val threads: List<PrThread>, val at: Instant)

    val policies = ConcurrentHashMap<Int, Policies>()
    val threads = ConcurrentHashMap<Int, Threads>()
    /** Project to its repositories' default branches. */
    val defaultBranches = ConcurrentHashMap<String, Map<String, String>>()
    /** Failed tests of a finished build: it never changes. */
    val failedTests = ConcurrentHashMap<Int, Int>()

    /** When builds were last refused (a PAT without Build (read)); null while they are readable. */
    @Volatile var buildsDeniedAt: Instant? = null

    fun clear() {
        policies.clear()
        threads.clear()
        defaultBranches.clear()
        failedTests.clear()
        buildsDeniedAt = null
    }
}
