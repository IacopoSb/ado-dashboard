package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.Fakes.anna
import io.github.iacoposb.adodash.Fakes.marco
import io.github.iacoposb.adodash.api.PolicyEvaluation
import io.github.iacoposb.adodash.api.PrStatus
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

    private fun required(ids: List<String>, minimum: Int? = null, creatorVoteCounts: Boolean = false) = PolicyEvaluation(
        PolicyEvaluation.REQUIRED_REVIEWERS_POLICY, "Required reviewers", blocking = true, enabled = true, status = "queued",
        minApprovers = minimum, requiredReviewerIds = ids, creatorVoteCounts = creatorVoteCounts,
    )

    @Test fun `required reviewers without a minimum, each one named must approve`() {
        val pr = Fakes.pr(
            311, anna,
            reviewers = listOf(
                Reviewer(marco.id, marco.name, Vote.APPROVED_WITH_SUGGESTIONS, true),
                Reviewer("luca", "Luca", Vote.WAITING, true),
                Reviewer("other", "Other", Vote.APPROVED, false),
            ),
        )
        val row = PrChecks.of(pr, listOf(required(listOf(marco.id.uppercase(), "luca"))), 0, emptyList()).single()
        assertEquals(CheckKind.RequiredReviewers(approvals = 1, required = 2), row.kind)
        assertEquals(CheckState.WAITING, row.state)
    }

    @Test fun `a group named is approved by one of its members`() {
        val pr = Fakes.pr(
            311, anna,
            reviewers = listOf(
                Reviewer("team", "[Contoso]\\Backend", Vote.APPROVED, true, group = true),
                Reviewer(marco.id, marco.name, Vote.APPROVED, false, votedFor = listOf("team")),
            ),
        )
        assertEquals(CheckKind.RequiredReviewers(1, 1), PrChecks.requiredReviewers(pr, required(listOf("team"))))
        // The group's vote alone, with nobody voting for it, is not an approval of a person
        val alone = pr.copy(reviewers = pr.reviewers.take(1))
        assertEquals(CheckKind.RequiredReviewers(0, 1), PrChecks.requiredReviewers(alone, required(listOf("team"))))
    }

    @Test fun `with a minimum, approvals of the people named or voting for a group named`() {
        val pr = Fakes.pr(
            311, anna,
            reviewers = listOf(
                Reviewer("team", "Team", Vote.APPROVED, true, group = true),
                Reviewer(marco.id, marco.name, Vote.APPROVED, false, votedFor = listOf("team")),
                Reviewer("luca", "Luca", Vote.APPROVED, false, votedFor = listOf("team")),
                Reviewer("sara", "Sara", Vote.REJECTED, false, votedFor = listOf("team")),
                Reviewer("other", "Other", Vote.APPROVED, false),
            ),
        )
        assertEquals(CheckKind.RequiredReviewers(2, 3), PrChecks.requiredReviewers(pr, required(listOf("team"), minimum = 3)))
        // Never more than it asks for
        assertEquals(CheckKind.RequiredReviewers(1, 1), PrChecks.requiredReviewers(pr, required(listOf("team"), minimum = 1)))
    }

    @Test fun `the author's approval counts only if the policy says so`() {
        val pr = Fakes.pr(
            311, anna,
            reviewers = listOf(
                Reviewer("team", "Team", Vote.APPROVED, true, group = true),
                Reviewer(anna.id, anna.name, Vote.APPROVED, false, votedFor = listOf("team")),
            ),
        )
        assertEquals(CheckKind.RequiredReviewers(0, 1), PrChecks.requiredReviewers(pr, required(listOf("team"))))
        assertEquals(CheckKind.RequiredReviewers(1, 1), PrChecks.requiredReviewers(pr, required(listOf("team"), creatorVoteCounts = true)))
        assertEquals(
            CheckKind.Reviewers(0, 2),
            PrChecks.of(pr, listOf(policy(PolicyEvaluation.MIN_REVIEWERS_POLICY, "queued")), 0, emptyList()).single().kind,
        )
    }

    @Test fun `without names, the reviewers marked required`() {
        val pr = Fakes.pr(311, anna, reviewers = listOf(Reviewer(marco.id, marco.name, Vote.APPROVED, true), Reviewer("luca", "Luca", Vote.NONE, true)))
        assertEquals(CheckKind.RequiredReviewers(1, 2), PrChecks.requiredReviewers(pr, required(emptyList())))
    }

    private fun statusPolicy(status: String, name: String? = null) = PolicyEvaluation(
        PolicyEvaluation.STATUS_POLICY, "Status", blocking = true, enabled = true, status = status,
        displayName = name, statusGenre = "SonarQube", statusName = "quality-gate",
    )

    @Test fun `statuses join the policies, once each`() {
        val sonar = PrStatus("sonarqube", "Quality-Gate", "failed", "Quality Gate failed", "https://sonar/x", at = Fakes.now)
        val lint = PrStatus(null, "lint", "failed")
        val rows = PrChecks.of(
            pr.copy(mergeStatus = null),
            listOf(statusPolicy("rejected"), policy(PolicyEvaluation.BUILD_POLICY, "approved", buildId = 1), policy("other-type", "approved")),
            0, emptyList(), listOf(lint, sonar),
        )
        assertEquals(
            listOf(
                CheckKind.Build("ci", 1, "approved", false),
                // The policy's row carries its status; the status is not shown again
                CheckKind.Status("SonarQube/quality-gate", "Quality Gate failed", "https://sonar/x"),
                CheckKind.Status("lint"),
                CheckKind.Other("ci"),
            ),
            rows.map { it.kind },
        )
        assertEquals(listOf(CheckState.OK, CheckState.FAILED, CheckState.FAILED, CheckState.OK), rows.map { it.state })
        // No policy waits for it: it does not block
        assertEquals(listOf(true, true, false, true), rows.map { it.blocking })
        assertEquals(Fakes.now, rows[1].at)
    }

    @Test fun `status checks show only when they failed`() {
        fun rows(status: String, posted: PrStatus?, name: String? = null) =
            PrChecks.of(pr.copy(mergeStatus = null), listOf(statusPolicy(status, name)), 0, emptyList(), listOfNotNull(posted))
        assertEquals(CheckKind.Status("Sonar gate"), rows("rejected", null, name = "Sonar gate").single().kind)
        assertEquals(emptyList<CheckRow>(), rows("queued", null))
        assertEquals(emptyList<CheckRow>(), rows("queued", PrStatus("SonarQube", "quality-gate", "pending")))
        assertEquals(emptyList<CheckRow>(), rows("approved", PrStatus("SonarQube", "quality-gate", "succeeded")))
        // Without a policy too
        val unbacked = PrChecks.of(pr.copy(mergeStatus = null), emptyList(), 0, emptyList(),
            listOf(PrStatus(null, "lint", "succeeded"), PrStatus(null, "cov", "pending"), PrStatus(null, "sec", "error")))
        assertEquals(listOf(CheckKind.Status("sec")), unbacked.map { it.kind })
    }

    @Test fun `status states`() {
        fun state(s: String) = PrChecks.stateOf(PrStatus(null, "x", s))
        assertEquals(listOf(CheckState.OK, CheckState.FAILED, CheckState.FAILED, CheckState.RUNNING),
            listOf("succeeded", "failed", "error", "pending").map(::state))
    }
}
