package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.Reviewer
import io.github.iacoposb.adodash.api.Vote
import org.junit.Assert.assertEquals
import org.junit.Test

class PrChecksTest {
    private val pr = Fakes.pr(
        311, anna,
        reviewers = listOf(Reviewer(marco.id, marco.name, Vote.APPROVED, false), Reviewer("team", "Team", Vote.APPROVED, false, group = true)),
    ).copy(mergeStatus = "succeeded")

    private fun policy(type: String, status: String, blocking: Boolean = true, buildId: Int? = null, expired: Boolean = false) =
        PolicyEvaluation(type, "Type", blocking, enabled = true, status = status, buildId = buildId, expired = expired, displayName = "ci", minApprovers = 2)

    @Test fun `one row per policy, builds first, the merge last`() {
        val rows = PrChecks.of(
            pr,
            listOf(
                policy(PolicyEvaluation.COMMENTS_POLICY, "rejected"),
                policy("other-type", "approved", blocking = false),
                policy(PolicyEvaluation.MIN_REVIEWERS_POLICY, "queued"),
                policy(PolicyEvaluation.BUILD_POLICY, "approved", buildId = 981),
                policy(PolicyEvaluation.WORK_ITEMS_POLICY, "notApplicable"),
            ),
            activeThreads = 2, workItemIds = listOf(4812),
        )
        assertEquals(
            listOf(
                CheckKind.Build("ci", 981, "approved", false),
                // Groups do not count as approvals
                CheckKind.Reviewers(approvals = 1, required = 2),
                CheckKind.Comments(2),
                CheckKind.Other("ci"),
                CheckKind.Merge("succeeded", "main"),
            ),
            rows.map { it.kind },
        )
        assertEquals(listOf(CheckState.OK, CheckState.WAITING, CheckState.WARNING, CheckState.OK, CheckState.OK), rows.map { it.state })
        assertEquals(false, rows[3].blocking)
    }

    @Test fun `build states`() {
        fun state(status: String, buildId: Int? = 1, expired: Boolean = false) =
            PrChecks.of(pr.copy(mergeStatus = null), listOf(policy(PolicyEvaluation.BUILD_POLICY, status, buildId = buildId, expired = expired)), 0, emptyList())
                .single().state
        assertEquals(CheckState.FAILED, state("rejected"))
        assertEquals(CheckState.RUNNING, state("running"))
        assertEquals(CheckState.RUNNING, state("queued"))
        assertEquals(CheckState.WAITING, state("queued", buildId = null))
        assertEquals(CheckState.WAITING, state("approved", expired = true))
    }

    @Test fun `merge conflicts fail, an unknown merge is left out`() {
        assertEquals(CheckState.FAILED, PrChecks.of(pr.copy(mergeStatus = "conflicts"), emptyList(), 0, emptyList()).single().state)
        assertEquals(emptyList<CheckRow>(), PrChecks.of(pr.copy(mergeStatus = null), emptyList(), 0, emptyList()))
    }
}
