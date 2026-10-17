package io.github.iacoposb.adodash.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import java.time.Instant

/** The reference names of the fields the work item details read and change. */
object WiFields {
    const val STATE = "System.State"
    const val ASSIGNED_TO = "System.AssignedTo"
    const val PRIORITY = "Microsoft.VSTS.Common.Priority"
    const val ITERATION = "System.IterationPath"
    const val AREA = "System.AreaPath"
    const val REMAINING_WORK = "Microsoft.VSTS.Scheduling.RemainingWork"
    const val DESCRIPTION = "System.Description"
    const val REPRO_STEPS = "Microsoft.VSTS.TCM.ReproSteps"
    const val CHANGED_BY = "System.ChangedBy"
    const val COMMENT_COUNT = "System.CommentCount"
}

/** Someone work can be assigned to; [uniqueName] (e-mail or domain\user) is what a change sends. */
data class Person(val id: String, val name: String, val uniqueName: String)

/** How another work item or a pull request is linked to the one open. */
enum class LinkKind { PARENT, CHILD, RELATED, PULL_REQUEST }

/** A link of a work item: [targetId] is the work item id, or the pull request's. */
data class WorkItemLink(val kind: LinkKind, val targetId: Int, val url: String)

/** A pull request linked to a work item; [status] is "active", "completed" or "abandoned". */
data class LinkedPullRequest(
    val id: Int,
    val title: String,
    val status: String,
    val draft: Boolean,
    val url: String,
)

/** An iteration of a project; [path] as in [WorkItemDetail.iterationPath] ("Project\\Sprint 42"). */
data class Iteration(val name: String, val path: String, val start: Instant? = null, val finish: Instant? = null)

/**
 * A work item with what its details show and change. [rev] is the revision the values are from:
 * a change is sent only against it, so that it never overwrites someone else's.
 */
data class WorkItemDetail(
    val item: WorkItem,
    val rev: Int,
    /** HTML as Azure DevOps stores it: the description, or a bug's repro steps. */
    val descriptionHtml: String = "",
    val assignee: Person? = null,
    val iterationPath: String = item.project,
    val remainingWork: Double? = null,
    val changedBy: String? = null,
    val commentCount: Int = 0,
    val links: List<WorkItemLink> = emptyList(),
) {
    val id get() = item.id

    /** [change] applied, as it will read once saved. */
    fun with(change: FieldChange): WorkItemDetail = when (change) {
        is FieldChange.State -> copy(item = item.copy(state = change.state))
        is FieldChange.AssignedTo -> copy(assignee = change.person, item = item.copy(assignedTo = change.person?.name))
        is FieldChange.Priority -> copy(item = item.copy(priority = change.priority))
        is FieldChange.IterationPath -> copy(iterationPath = change.path)
        is FieldChange.AreaPath -> copy(item = item.copy(areaPath = change.path))
        is FieldChange.RemainingWork -> copy(remainingWork = change.hours)
    }
}

/** What a field of the details can be changed to. */
sealed interface FieldChange {
    val field: String

    data class State(val state: String) : FieldChange { override val field get() = WiFields.STATE }
    /** Null unassigns. */
    data class AssignedTo(val person: Person?) : FieldChange { override val field get() = WiFields.ASSIGNED_TO }
    data class Priority(val priority: Int) : FieldChange { override val field get() = WiFields.PRIORITY }
    data class IterationPath(val path: String) : FieldChange { override val field get() = WiFields.ITERATION }
    data class AreaPath(val path: String) : FieldChange { override val field get() = WiFields.AREA }
    /** Null clears it. */
    data class RemainingWork(val hours: Double?) : FieldChange { override val field get() = WiFields.REMAINING_WORK }
}

/** The JSON patch documents that change a work item. */
object WorkItemPatch {
    /**
     * Tests that the work item is still at [rev], then sets [change]: when someone else saved in
     * between the test fails and nothing is changed. Clearing a field sets it empty, which works
     * also when it was never set (a remove would fail then).
     */
    fun build(rev: Int, change: FieldChange): JsonArray = buildJsonArray {
        addJsonObject {
            put("op", "test")
            put("path", "/rev")
            put("value", rev)
        }
        addJsonObject {
            put("op", "add")
            put("path", "/fields/${change.field}")
            when (change) {
                is FieldChange.State -> put("value", change.state)
                is FieldChange.AssignedTo -> put("value", change.person?.uniqueName?.ifBlank { change.person.name } ?: "")
                is FieldChange.Priority -> put("value", change.priority)
                is FieldChange.IterationPath -> put("value", change.path)
                is FieldChange.AreaPath -> put("value", change.path)
                is FieldChange.RemainingWork -> put("value", change.hours?.let { JsonPrimitive(it) } ?: JsonPrimitive(""))
            }
        }
    }
}

/**
 * Someone else saved the work item since it was read: the `test` on its revision failed. Azure
 * DevOps answers 412 or 409, older servers a 400 with TF26071.
 */
val AdoException.isConflict: Boolean
    get() = code == 409 || code == 412 || (code == 400 && message.orEmpty().contains("TF26071"))

/** The credentials may read but not change. */
val AdoException.isDenied: Boolean get() = code == 401 || code == 403
