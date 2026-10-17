package io.github.iacoposb.adodash.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Azure DevOps REST client. [organization] is either the organization name
 * (dev.azure.com) or the full base URL of a collection; [authorization]
 * gives the Authorization header for each call (see [pat] and [bearer]).
 */
class AdoClient(
    organization: String,
    private val authorization: suspend () -> String,
    private val http: OkHttpClient = defaultHttp,
) : AdoApi {
    val baseUrl: HttpUrl = orgBaseUrl(organization)

    override suspend fun me(): Identity {
        // connectionData exists only as a preview API
        val data: ConnectionData = get(url("_apis", "connectionData", apiVersion = "7.1-preview"))
        return Identity(data.authenticatedUser.id, data.authenticatedUser.providerDisplayName)
    }

    override suspend fun projects(): List<String> {
        val list: ValueList<ProjectDto> = get(url("_apis", "projects") { addQueryParameter("\$top", "500") })
        return list.value.map { it.name }.sorted()
    }

    override suspend fun queryWorkItemIds(wiql: String, top: Int): List<Int> {
        val body = buildJsonObject { put("query", wiql) }
        val result: WiqlResult = post(url("_apis", "wit", "wiql") { addQueryParameter("\$top", "$top") }, body)
        return result.workItems.map { it.id }
    }

    override suspend fun workItems(ids: List<Int>): List<WorkItem> = ids.chunked(200).flatMap { chunk ->
        val body = buildJsonObject {
            putJsonArray("ids") { chunk.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("fields") { WORK_ITEM_FIELDS.forEach { add(JsonPrimitive(it)) } }
        }
        val list: ValueList<WorkItemDto> = post(url("_apis", "wit", "workitemsbatch"), body)
        list.value.map { it.toWorkItem() }
    }

    override suspend fun stateCategories(project: String, type: String): Map<String, String> {
        val list: ValueList<StateDto> = get(url(project, "_apis", "wit", "workitemtypes", type, "states"))
        return list.value.associate { it.name to it.category }
    }

    override suspend fun areas(project: String): AreaNode {
        val root: ClassificationNodeDto = get(
            url(project, "_apis", "wit", "classificationnodes", "areas") { addQueryParameter("\$depth", "$AREA_DEPTH") },
        )
        // The API's own paths read "\Project\Area\Sub"; work items and WIQL use "Project\Sub"
        fun ClassificationNodeDto.toNode(path: String): AreaNode =
            AreaNode(name, path, children.map { it.toNode("$path\\${it.name}") }.sortedBy { it.name.lowercase() })
        return root.toNode(project).copy(name = project)
    }

    override suspend fun workItemTypes(project: String): List<WorkItemTypeInfo> = coroutineScope {
        val hiddenCall = async {
            runCatching {
                get<TypeCategoryDto>(url(project, "_apis", "wit", "workitemtypecategories", "Microsoft.HiddenCategory"))
                    .workItemTypes.map { it.name }.toSet()
            }.getOrDefault(emptySet())
        }
        val list: ValueList<WorkItemTypeDto> = get(url(project, "_apis", "wit", "workitemtypes"))
        val hidden = hiddenCall.await()
        val limit = Semaphore(PARALLEL_TYPE_STATES)
        list.value.filterNot { it.isDisabled }.map { type ->
            async {
                // Recent servers list the states with the type; older ones only through /states
                val states = type.states.ifEmpty {
                    limit.withPermit {
                        get<ValueList<StateDto>>(url(project, "_apis", "wit", "workitemtypes", type.name, "states")).value
                    }
                }
                WorkItemTypeInfo(
                    name = type.name,
                    color = type.color?.takeLast(6),
                    states = states.map { StateInfo(it.name, it.category) },
                    hidden = type.name in hidden,
                )
            }
        }.awaitAll()
    }

    override suspend fun workItemComments(project: String, id: Int): List<Comment> {
        val list: CommentList = get(
            url(project, "_apis", "wit", "workItems", "$id", "comments", apiVersion = "7.1-preview.4") {
                addQueryParameter("\$top", "50")
                addQueryParameter("order", "desc")
            },
        )
        return list.comments.filterNot { it.isDeleted }.map {
            Comment(it.id, it.workItemId, it.createdBy.toIdentity(), it.text.orEmpty(), Instant.parse(it.createdDate))
        }
    }

    override suspend fun activePullRequests(project: String, filter: PrFilter): List<PullRequest> {
        val list: ValueList<PullRequestDto> = get(
            url(project, "_apis", "git", "pullrequests") {
                addQueryParameter("searchCriteria.status", "active")
                when (filter) {
                    is PrFilter.Reviewer -> addQueryParameter("searchCriteria.reviewerId", filter.id)
                    is PrFilter.Creator -> addQueryParameter("searchCriteria.creatorId", filter.id)
                }
                addQueryParameter("\$top", "100")
            },
        )
        return list.value.map { it.toPullRequest() }
    }

    override suspend fun pullRequestComments(pr: PullRequest): List<PrComment> {
        val list: ValueList<ThreadDto> = get(
            url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "threads"),
        )
        return list.value.filterNot { it.isDeleted }.flatMap { thread ->
            thread.comments
                .filter { !it.isDeleted && it.commentType == "text" }
                .map { PrComment(thread.id, it.id, it.author.toIdentity(), it.content.orEmpty(), Instant.parse(it.publishedDate)) }
        }
    }

    private fun url(
        vararg segments: String,
        apiVersion: String = API_VERSION,
        query: HttpUrl.Builder.() -> Unit = {},
    ): HttpUrl = baseUrl.newBuilder().apply {
        segments.forEach { addPathSegment(it) }
        query()
        addQueryParameter("api-version", apiVersion)
    }.build()

    private suspend inline fun <reified T> get(url: HttpUrl): T =
        call(Request.Builder().url(url).get())

    private suspend inline fun <reified T> post(url: HttpUrl, body: JsonObject): T =
        call(Request.Builder().url(url).post(body.toString().toRequestBody(JSON)))

    private suspend inline fun <reified T> call(request: Request.Builder): T {
        val auth = authorization()
        val text = withContext(Dispatchers.IO) {
            http.newCall(request.header("Authorization", auth).header("Accept", "application/json").build())
                .execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    // An expired PAT gets a 203 with the sign-in page instead of a 401
                    val isHtml = response.header("Content-Type")?.startsWith("text/html") == true
                    when {
                        response.code == 401 || response.code == 203 || isHtml ->
                            throw AdoException(401, "Invalid or expired credentials")
                        !response.isSuccessful -> throw AdoException(response.code, errorMessage(response.code, body))
                    }
                    body
                }
        }
        return json.decodeFromString(text)
    }

    private fun errorMessage(code: Int, body: String): String {
        val message = runCatching { json.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()
        return "HTTP $code" + (message?.let { ": $it" } ?: "")
    }

    private fun WorkItemDto.toWorkItem(): WorkItem {
        fun field(name: String): JsonElement? = fields[name]
        fun text(name: String) = (field(name) as? JsonPrimitive)?.contentOrNull
        val project = text("System.TeamProject").orEmpty()
        return WorkItem(
            id = id,
            project = project,
            type = text("System.WorkItemType").orEmpty(),
            title = text("System.Title").orEmpty(),
            state = text("System.State").orEmpty(),
            priority = (field("Microsoft.VSTS.Common.Priority") as? JsonPrimitive)?.intOrNull,
            assignedTo = (field("System.AssignedTo") as? JsonObject)?.get("displayName")?.jsonPrimitive?.contentOrNull,
            changedAt = text("System.ChangedDate")?.let(Instant::parse) ?: Instant.EPOCH,
            url = baseUrl.newBuilder().addPathSegment(project).addPathSegment("_workitems")
                .addPathSegment("edit").addPathSegment("$id").build().toString(),
            areaPath = text("System.AreaPath") ?: project,
        )
    }

    private fun PullRequestDto.toPullRequest() = PullRequest(
        id = pullRequestId,
        project = repository.project.name,
        repoId = repository.id,
        repoName = repository.name,
        title = title,
        author = createdBy.toIdentity(),
        sourceBranch = sourceRefName.removePrefix("refs/heads/"),
        targetBranch = targetRefName.removePrefix("refs/heads/"),
        draft = isDraft,
        reviewers = reviewers.map { Reviewer(it.id, it.displayName, it.vote, it.isRequired) },
        createdAt = Instant.parse(creationDate),
        url = baseUrl.newBuilder().addPathSegment(repository.project.name).addPathSegment("_git")
            .addPathSegment(repository.name).addPathSegment("pullrequest").addPathSegment("$pullRequestId")
            .build().toString(),
    )

    companion object {
        const val API_VERSION = "7.1"
        private val JSON = "application/json".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        private val WORK_ITEM_FIELDS = listOf(
            "System.Id", "System.TeamProject", "System.WorkItemType", "System.Title", "System.State",
            "System.AssignedTo", "System.ChangedDate", "Microsoft.VSTS.Common.Priority", "System.AreaPath",
        )
        /** Deep enough for any real area tree. */
        private const val AREA_DEPTH = 10
        /** Older servers: the states of a project's types read a few at a time. */
        private const val PARALLEL_TYPE_STATES = 4
        private val defaultHttp = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun pat(token: String): suspend () -> String = Credentials.basic("", token).let { { it } }
        fun bearer(token: suspend () -> String): suspend () -> String = { "Bearer ${token()}" }

        /**
         * [authorization] asked once and reused for [ttlMillis]: for a burst of calls, such as
         * reading the filters' catalog, instead of a token lookup per call.
         */
        fun reused(
            authorization: suspend () -> String,
            ttlMillis: Long = 5 * 60_000L,
            now: () -> Long = System::currentTimeMillis,
        ): suspend () -> String {
            val lock = Mutex()
            var value: String? = null
            var at = 0L
            return {
                lock.withLock {
                    value?.takeIf { now() - at < ttlMillis } ?: authorization().also { value = it; at = now() }
                }
            }
        }

        fun orgBaseUrl(organization: String): HttpUrl {
            val trimmed = organization.trim().trimEnd('/')
            require(trimmed.isNotEmpty()) { "Missing organization" }
            return if ("://" in trimmed) trimmed.toHttpUrl() else "https://dev.azure.com/$trimmed".toHttpUrl()
        }
    }
}

@Serializable private data class ValueList<T>(val value: List<T> = emptyList())
@Serializable private data class ConnectionData(val authenticatedUser: UserDto)
@Serializable private data class UserDto(val id: String, val providerDisplayName: String = "")
@Serializable private data class ProjectDto(val name: String)
@Serializable private data class StateDto(val name: String, val category: String = "")
@Serializable private data class ClassificationNodeDto(
    val name: String,
    val children: List<ClassificationNodeDto> = emptyList(),
)
@Serializable private data class WorkItemTypeDto(
    val name: String,
    val color: String? = null,
    val isDisabled: Boolean = false,
    val states: List<StateDto> = emptyList(),
)
@Serializable private data class TypeCategoryDto(val workItemTypes: List<TypeRefDto> = emptyList())
@Serializable private data class TypeRefDto(val name: String)
@Serializable private data class WiqlResult(val workItems: List<WiqlRef> = emptyList())
@Serializable private data class WiqlRef(val id: Int)
@Serializable private data class WorkItemDto(val id: Int, val fields: JsonObject = JsonObject(emptyMap()))

@Serializable private data class IdentityDto(val id: String = "", val displayName: String = "")
private fun IdentityDto.toIdentity() = Identity(id, displayName)

@Serializable private data class CommentList(val comments: List<CommentDto> = emptyList())
@Serializable private data class CommentDto(
    val id: Int,
    val workItemId: Int,
    val text: String? = null,
    val createdBy: IdentityDto = IdentityDto(),
    val createdDate: String,
    val isDeleted: Boolean = false,
)

@Serializable private data class PullRequestDto(
    val pullRequestId: Int,
    val title: String = "",
    val createdBy: IdentityDto = IdentityDto(),
    val creationDate: String,
    val sourceRefName: String = "",
    val targetRefName: String = "",
    val isDraft: Boolean = false,
    val reviewers: List<ReviewerDto> = emptyList(),
    val repository: RepositoryDto,
)
@Serializable private data class ReviewerDto(
    val id: String,
    val displayName: String = "",
    val vote: Int = 0,
    val isRequired: Boolean = false,
)
@Serializable private data class RepositoryDto(val id: String, val name: String, val project: ProjectDto)

@Serializable private data class ThreadDto(
    val id: Int,
    val isDeleted: Boolean = false,
    val comments: List<ThreadCommentDto> = emptyList(),
)
@Serializable private data class ThreadCommentDto(
    val id: Int,
    val author: IdentityDto = IdentityDto(),
    val content: String? = null,
    val commentType: String = "text",
    val publishedDate: String,
    val isDeleted: Boolean = false,
)
