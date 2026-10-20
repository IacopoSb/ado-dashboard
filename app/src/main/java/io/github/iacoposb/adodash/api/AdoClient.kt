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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
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
        return list.comments.filterNot { it.isDeleted }.map { it.toComment() }
    }

    /**
     * The newest page first ("desc"), shown oldest first: the continuation then reads the
     * comments before it, for "Load earlier comments".
     */
    override suspend fun workItemCommentPage(project: String, id: Int, continuation: String?): CommentPage {
        val (list, header) = page<CommentList>(
            url(project, "_apis", "wit", "workItems", "$id", "comments", apiVersion = COMMENTS_API) {
                addQueryParameter("\$top", "${CommentPage.PAGE_SIZE}")
                addQueryParameter("order", "desc")
                continuation?.let { addQueryParameter("continuationToken", it) }
            },
        )
        val next = (list.continuationToken ?: header)?.takeIf { it.isNotBlank() }
        val comments = list.comments.filterNot { it.isDeleted }.map { it.toComment() }.reversed()
        return CommentPage(comments, next, list.totalCount ?: comments.size)
    }

    override suspend fun addWorkItemComment(project: String, id: Int, html: String): Comment {
        val body = buildJsonObject { put("text", html) }
        val dto: CommentDto = post(url(project, "_apis", "wit", "workItems", "$id", "comments", apiVersion = COMMENTS_API), body)
        return dto.toComment()
    }

    override suspend fun activePullRequests(project: String, filter: PrFilter): List<PullRequest> {
        val list: ValueList<PullRequestDto> = get(
            url(project, "_apis", "git", "pullrequests") {
                addQueryParameter("searchCriteria.status", "active")
                when (filter) {
                    is PrFilter.Reviewer -> addQueryParameter("searchCriteria.reviewerId", filter.id)
                    is PrFilter.Creator -> addQueryParameter("searchCriteria.creatorId", filter.id)
                    PrFilter.All -> {}
                }
                addQueryParameter("\$top", "100")
            },
        )
        return list.value.map { it.toPullRequest() }
    }

    override suspend fun pullRequestComments(pr: PullRequest): List<PrComment> =
        pullRequestThreads(pr).flatMap { it.comments }

    override suspend fun pullRequestThreads(pr: PullRequest): List<PrThread> = threads(pr, iteration = null)

    override suspend fun pullRequestThreads(pr: PullRequest, iteration: Int): List<PrThread> = threads(pr, iteration)

    /** With [iteration], each thread's context holds its position tracked to that push. */
    private suspend fun threads(pr: PullRequest, iteration: Int?): List<PrThread> {
        val list: ValueList<ThreadDto> = get(
            url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "threads") {
                if (iteration != null) {
                    addQueryParameter("\$iteration", "$iteration")
                    addQueryParameter("\$baseIteration", "0")
                }
            },
        )
        return list.value.filterNot { it.isDeleted }.map { it.toThread() }
    }

    override suspend fun pullRequest(id: Int): PullRequest =
        get<PullRequestDto>(url("_apis", "git", "pullrequests", "$id")).toPullRequest()

    override suspend fun pullRequestDetail(pr: PullRequest): PullRequestDetail {
        val dto: PullRequestDto = get(
            url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}") {
                addQueryParameter("includeWorkItemRefs", "true")
            },
        )
        return PullRequestDetail(
            pr = dto.toPullRequest(),
            description = dto.description.orEmpty(),
            labels = dto.labels.filter { it.active }.map { it.name },
            workItemIds = dto.workItemRefs.mapNotNull { it.id.toIntOrNull() }.distinct(),
            status = dto.status,
        )
    }

    override suspend fun votePullRequest(pr: PullRequest, reviewerId: String, vote: Int): Reviewer {
        val body = buildJsonObject { put("vote", vote) }
        val dto: ReviewerDto = call(
            Request.Builder()
                .url(url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "reviewers", reviewerId))
                .put(body.toString().toRequestBody(JSON)),
        )
        return dto.toReviewer()
    }

    override suspend fun addPullRequestComment(pr: PullRequest, threadId: Int, parentId: Int, content: String): PrComment {
        val body = buildJsonObject {
            put("content", content)
            put("parentCommentId", parentId)
            put("commentType", 1)
        }
        val dto: ThreadCommentDto = post(
            url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "threads", "$threadId", "comments"),
            body,
        )
        return dto.toComment(threadId)
    }

    override suspend fun createPullRequestThread(pr: PullRequest, content: String): PrThread {
        val body = buildJsonObject {
            putJsonArray("comments") {
                add(
                    buildJsonObject {
                        put("parentCommentId", 0)
                        put("content", content)
                        put("commentType", 1)
                    },
                )
            }
            put("status", ThreadStatus.ACTIVE)
        }
        val dto: ThreadDto = post(url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "threads"), body)
        return dto.toThread()
    }

    override suspend fun createPullRequestFileThread(
        pr: PullRequest,
        content: String,
        anchor: FileAnchor,
        iteration: Int,
        changeTrackingId: Int,
    ): PrThread {
        val side = if (anchor.left) "left" else "right"
        val body = buildJsonObject {
            putJsonArray("comments") {
                add(
                    buildJsonObject {
                        put("parentCommentId", 0)
                        put("content", content)
                        put("commentType", 1)
                    },
                )
            }
            put("status", ThreadStatus.ACTIVE)
            putJsonObject("threadContext") {
                put("filePath", anchor.path)
                putJsonObject("${side}FileStart") {
                    put("line", anchor.line)
                    put("offset", 1)
                }
                putJsonObject("${side}FileEnd") {
                    put("line", anchor.line)
                    put("offset", 1)
                }
            }
            if (iteration > 0 && changeTrackingId > 0) {
                putJsonObject("pullRequestThreadContext") {
                    put("changeTrackingId", changeTrackingId)
                    putJsonObject("iterationContext") {
                        put("firstComparingIteration", 1)
                        put("secondComparingIteration", iteration)
                    }
                }
            }
        }
        val dto: ThreadDto = post(url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "threads"), body)
        return dto.toThread()
    }

    override suspend fun pullRequestIterations(pr: PullRequest): List<PrIteration> {
        val list: ValueList<IterationDto> = get(
            url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "iterations"),
        )
        return list.value.map {
            PrIteration(it.id, it.sourceRefCommit?.commitId.orEmpty(), (it.commonRefCommit ?: it.targetRefCommit)?.commitId.orEmpty())
        }.sortedBy { it.id }
    }

    override suspend fun iterationChanges(pr: PullRequest, iteration: Int): List<PrFileChange> {
        val dto: IterationChangesDto = get(
            url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "iterations", "$iteration", "changes") {
                addQueryParameter("\$top", "2000")
            },
        )
        return dto.changeEntries.mapNotNull { e ->
            val item = e.item ?: return@mapNotNull null
            val path = item.path.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (item.isFolder || item.gitObjectType == "tree") return@mapNotNull null
            val type = PrFileChange.typeOf(e.changeType)
            PrFileChange(
                path, type,
                originalPath = (e.originalPath ?: e.sourceServerItem)?.takeIf { type == ChangeType.RENAME && it.isNotBlank() && it != path },
                changeTrackingId = e.changeTrackingId,
            )
        }
    }

    override suspend fun fileContent(pr: PullRequest, path: String, commit: String): FileContent {
        val target = url(pr.project, "_apis", "git", "repositories", pr.repoId, "items") {
            addQueryParameter("path", path)
            addQueryParameter("versionDescriptor.version", commit)
            addQueryParameter("versionDescriptor.versionType", "commit")
            addQueryParameter("includeContent", "true")
            // Without it the metadata may be missing: the NUL check below is then all there is
            addQueryParameter("includeContentMetadata", "true")
        }
        val text = try {
            fetch(Request.Builder().url(target).get(), maxBytes = FileContent.MAX_BYTES).first
        } catch (e: AdoException) {
            when (e.code) {
                404 -> return FileContent.Missing
                TOO_LARGE -> return FileContent.TooLarge
                else -> throw e
            }
        }
        val item = json.decodeFromString<ItemDto>(text)
        val content = item.content ?: return FileContent.Missing
        return when {
            item.contentMetadata?.isBinary == true || content.contains('\u0000') -> FileContent.Binary
            content.length > FileContent.MAX_BYTES -> FileContent.TooLarge
            else -> FileContent.Text(content)
        }
    }

    override suspend fun pullRequestCommits(pr: PullRequest): List<PrCommit> {
        val all = mutableListOf<CommitDto>()
        var token: String? = null
        var pages = 0
        do {
            val (page, next) = page<ValueList<CommitDto>>(
                url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "commits") {
                    token?.let { addQueryParameter("continuationToken", it) }
                },
            )
            all += page.value
            token = next?.takeIf { it.isNotBlank() }
        } while (token != null && ++pages < MAX_COMMIT_PAGES)
        return all.distinctBy { it.commitId }.map { c ->
            PrCommit(
                id = c.commitId,
                message = c.comment.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim(),
                author = c.author?.name.orEmpty(),
                date = c.author?.date?.let(::instantOrNull) ?: Instant.EPOCH,
                url = baseUrl.newBuilder().addPathSegment(pr.project).addPathSegment("_git").addPathSegment(pr.repoName)
                    .addPathSegment("commit").addPathSegment(c.commitId).build().toString(),
            )
        }
    }

    override suspend fun setPullRequestThreadStatus(pr: PullRequest, threadId: Int, status: String): PrThread {
        val body = buildJsonObject { put("status", status) }
        val dto: ThreadDto = call(
            Request.Builder()
                .url(url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "threads", "$threadId"))
                .patch(body.toString().toRequestBody(JSON)),
        )
        return dto.toThread()
    }

    /**
     * Identities live in the organization's identity service: vssps.dev.azure.com on Azure DevOps
     * Services, the collection itself on Azure DevOps Server.
     */
    override suspend fun identityNames(ids: Collection<String>): Map<String, String> {
        if (ids.isEmpty()) return emptyMap()
        val host = baseUrl.host
        val base = when {
            host.equals("dev.azure.com", ignoreCase = true) -> baseUrl.newBuilder().host("vssps.dev.azure.com").build()
            host.endsWith(".visualstudio.com", ignoreCase = true) && !host.contains(".vssps.") ->
                baseUrl.newBuilder().host(host.substringBefore('.') + ".vssps.visualstudio.com").build()
            else -> baseUrl
        }
        val target = base.newBuilder().addPathSegment("_apis").addPathSegment("identities")
            .addQueryParameter("identityIds", ids.joinToString(","))
            .addQueryParameter("api-version", API_VERSION).build()
        val list: ValueList<IdentityRecordDto> = get(target)
        return list.value.mapNotNull { i ->
            val name = i.customDisplayName?.takeIf { it.isNotBlank() } ?: i.providerDisplayName?.takeIf { it.isNotBlank() }
            name?.let { i.id to it }
        }.toMap()
    }

    override suspend fun policyEvaluations(pr: PullRequest): List<PolicyEvaluation> {
        require(pr.projectId.isNotEmpty()) { "Missing project id" }
        val list: ValueList<PolicyEvaluationDto> = get(
            url(pr.projectId, "_apis", "policy", "evaluations", apiVersion = "7.1-preview.1") {
                addQueryParameter("artifactId", "vstfs:///CodeReview/CodeReviewId/${pr.projectId}/${pr.id}")
            },
        )
        return list.value.map {
            val context = it.context
            val settings = it.configuration.settings
            PolicyEvaluation(
                typeId = it.configuration.type.id,
                typeName = it.configuration.type.displayName,
                blocking = it.configuration.isBlocking,
                enabled = it.configuration.isEnabled,
                status = it.status,
                buildId = (context?.get("buildId") as? JsonPrimitive)?.intOrNull,
                expired = (context?.get("isExpired") as? JsonPrimitive)?.booleanOrNull == true,
                // A status check's name, when it was given one, is its "default display name"
                displayName = settings.text("displayName") ?: settings.text("defaultDisplayName")
                    ?: context.text("buildDefinitionName"),
                minApprovers = (settings?.get("minimumApproverCount") as? JsonPrimitive)?.intOrNull,
                requiredReviewerIds = (settings?.get("requiredReviewerIds") as? JsonArray)
                    ?.mapNotNull { id -> (id as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                creatorVoteCounts = (settings?.get("creatorVoteCounts") as? JsonPrimitive)?.booleanOrNull == true,
                completedAt = it.completedDate?.let(::instantOrNull),
                statusGenre = settings.text("statusGenre"),
                statusName = settings.text("statusName"),
            )
        }
    }

    private fun JsonObject?.text(key: String) = (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    override suspend fun pullRequestStatuses(pr: PullRequest): List<PrStatus> {
        val list: ValueList<PrStatusDto> = get(
            url(pr.project, "_apis", "git", "repositories", pr.repoId, "pullRequests", "${pr.id}", "statuses", apiVersion = "7.1-preview.1"),
        )
        return PrStatus.latest(
            list.value.filter { it.context.name.isNotBlank() }.map {
                PrStatus(
                    genre = it.context.genre?.takeIf { g -> g.isNotBlank() },
                    name = it.context.name,
                    state = it.state,
                    description = it.description?.takeIf { d -> d.isNotBlank() },
                    url = it.targetUrl?.takeIf { u -> u.startsWith("https://") || u.startsWith("http://") },
                    at = (it.updatedDate ?: it.creationDate)?.let(::instantOrNull),
                    id = it.id,
                )
            },
        )
    }

    override suspend fun builds(project: String, top: Int): List<Build> {
        val list: ValueList<BuildDto> = get(
            url(project, "_apis", "build", "builds") {
                addQueryParameter("queryOrder", "queueTimeDescending")
                addQueryParameter("\$top", "$top")
            },
        )
        return list.value.map { it.toBuild(project) }
    }

    override suspend fun pipelines(project: String): List<Pipeline> {
        val out = mutableListOf<Pipeline>()
        var continuation: String? = null
        // Pages of up to 1000: a project's pipelines all come, in a few calls at most
        repeat(MAX_DEFINITION_PAGES) {
            val (list, next) = page<ValueList<DefinitionDto>>(
                url(project, "_apis", "build", "definitions") {
                    addQueryParameter("includeLatestBuilds", "true")
                    addQueryParameter("queryOrder", "definitionNameAscending")
                    addQueryParameter("\$top", "$DEFINITIONS_PAGE")
                    continuation?.let { addQueryParameter("continuationToken", it) }
                },
            )
            out += list.value.map { it.toPipeline(project) }
            continuation = next?.takeIf { it.isNotBlank() } ?: return out
        }
        return out
    }

    override suspend fun pipelineRuns(project: String, definitionId: Int, top: Int): List<Build> {
        val list: ValueList<BuildDto> = get(
            url(project, "_apis", "build", "builds") {
                addQueryParameter("definitions", "$definitionId")
                addQueryParameter("\$top", "$top")
                addQueryParameter("queryOrder", "queueTimeDescending")
            },
        )
        return list.value.map { it.toBuild(project) }
    }

    override suspend fun build(project: String, id: Int): Build =
        get<BuildDto>(url(project, "_apis", "build", "builds", "$id")).toBuild(project)

    override suspend fun buildTimeline(project: String, id: Int): List<TimelineRecord> {
        val timeline: TimelineDto = get(url(project, "_apis", "build", "builds", "$id", "timeline"))
        return timeline.records.map {
            TimelineRecord(
                id = it.id, parentId = it.parentId, type = it.type, name = it.name, order = it.order ?: 0,
                state = stateOf(it.state, it.result), skipped = it.result == "skipped",
                startedAt = it.startTime?.let(::instantOrNull), finishedAt = it.finishTime?.let(::instantOrNull),
            )
        }
    }

    override suspend fun retryBuild(project: String, id: Int) {
        call<JsonElement>(
            Request.Builder().url(url(project, "_apis", "build", "builds", "$id") { addQueryParameter("retry", "true") })
                .patch("{}".toRequestBody(JSON)),
        )
    }

    override suspend fun testRuns(project: String, buildId: Int): List<TestRun> {
        val list: ValueList<TestRunDto> = get(
            url(project, "_apis", "test", "runs") { addQueryParameter("buildUri", "vstfs:///Build/Build/$buildId") },
        )
        return list.value.map { TestRun(it.id, it.name, it.unanalyzedTests) }
    }

    override suspend fun failedTests(project: String, runId: Int, top: Int): List<FailedTest> {
        val list: ValueList<TestResultDto> = get(
            url(project, "_apis", "test", "Runs", "$runId", "results") {
                addQueryParameter("outcomes", "Failed")
                addQueryParameter("\$top", "$top")
            },
        )
        return list.value.map { r ->
            // "Package.Class.method" reads as "Class.method", as in the test reports
            val name = r.automatedTestName?.split('.')?.takeLast(2)?.joinToString(".")?.takeIf { it.isNotBlank() }
                ?: r.testCaseTitle.orEmpty()
            FailedTest(name, r.errorMessage.orEmpty().trim())
        }
    }

    override suspend fun defaultBranches(project: String): Map<String, String> {
        val list: ValueList<RepoNameDto> = get(url(project, "_apis", "git", "repositories"))
        return list.value.mapNotNull { r -> r.defaultBranch?.let { r.id to it.removePrefix("refs/heads/") } }.toMap()
    }

    override suspend fun teams(project: String): List<String> {
        val list: ValueList<ProjectDto> = get(
            url("_apis", "projects", project, "teams") { addQueryParameter("\$top", "500") },
        )
        return list.value.map { it.name }.sortedBy { it.lowercase() }
    }

    override suspend fun teamAreas(project: String, team: String): List<TeamArea>? {
        val values: TeamFieldValuesDto = get(url(project, team, "_apis", "work", "teamsettings", "teamfieldvalues"))
        if (!values.field.referenceName.equals("System.AreaPath", ignoreCase = true)) return null
        return values.values.map { TeamArea(it.value, it.includeChildren) }
    }

    override suspend fun currentSprint(project: String, team: String): String? = currentIteration(project, team)?.name

    override suspend fun currentIteration(project: String, team: String): TeamIteration? {
        val list: ValueList<TeamIterationDto> = get(
            url(project, team, "_apis", "work", "teamsettings", "iterations") { addQueryParameter("\$timeframe", "current") },
        )
        return list.value.firstOrNull()?.toIteration()
    }

    override suspend fun teamIterations(project: String, team: String): List<TeamIteration> {
        val list: ValueList<TeamIterationDto> = get(url(project, team, "_apis", "work", "teamsettings", "iterations"))
        return list.value.map { it.toIteration() }
    }

    private fun TeamIterationDto.toIteration() = TeamIteration(
        id, name, path.ifEmpty { name },
        attributes?.startDate?.let(::instantOrNull), attributes?.finishDate?.let(::instantOrNull),
    )

    override suspend fun iterationWorkItems(project: String, team: String, iterationId: String): List<IterationLink> {
        val result: IterationWorkItemsDto = get(
            url(project, team, "_apis", "work", "teamsettings", "iterations", iterationId, "workitems"),
        )
        // A top-level item comes without a source; a child with its parent as the source. An
        // item listed both ways keeps its parent.
        return result.workItemRelations.mapNotNull { r ->
            val id = r.target?.id ?: return@mapNotNull null
            IterationLink(id, r.source?.id?.takeIf { r.rel == HIERARCHY_FORWARD })
        }.groupBy { it.id }.map { (_, links) -> links.firstOrNull { it.parentId != null } ?: links.first() }
    }

    override suspend fun reorderSprintItems(project: String, team: String, iterationId: String, move: BacklogReorder): Map<Int, Double> {
        // The Work API's "Reorder Iteration Work Items", only as a preview
        val body = buildJsonObject {
            putJsonArray("ids") { move.ids.forEach { add(JsonPrimitive(it)) } }
            put("iterationPath", move.iterationPath)
            put("parentId", move.parentId)
            put("previousId", move.previousId)
            put("nextId", move.nextId)
        }
        val url = url(project, team, "_apis", "work", "iterations", iterationId, "workitemsorder", apiVersion = REORDER_API)
        val result: ValueList<ReorderResultDto> = call(Request.Builder().url(url).patch(body.toString().toRequestBody(JSON)))
        return result.value.associate { it.id to it.order }
    }

    override suspend fun backlogItems(ids: List<Int>): List<BacklogItem> = ids.chunked(200).flatMap { chunk ->
        // Stack Rank and Backlog Priority exist in every organization; a server without one of
        // them refuses the call: read again with the dashboard's fields only
        val list = try {
            batch(chunk, BACKLOG_FIELDS)
        } catch (e: AdoException) {
            if (e.code != 400) throw e
            batch(chunk, WORK_ITEM_FIELDS)
        }
        list.map { dto ->
            fun number(name: String) = (dto.fields[name] as? JsonPrimitive)?.doubleOrNull
            BacklogItem(
                item = dto.toWorkItem(),
                assigneeId = (dto.fields["System.AssignedTo"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull,
                remainingWork = number(WiFields.REMAINING_WORK),
                rank = number(STACK_RANK) ?: number(BACKLOG_PRIORITY),
                parentId = (dto.fields["System.Parent"] as? JsonPrimitive)?.intOrNull,
            )
        }
    }

    private suspend fun batch(ids: List<Int>, fields: List<String>): List<WorkItemDto> {
        val body = buildJsonObject {
            putJsonArray("ids") { ids.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("fields") { fields.forEach { add(JsonPrimitive(it)) } }
        }
        return post<ValueList<WorkItemDto>>(url("_apis", "wit", "workitemsbatch"), body).value
    }

    override suspend fun teamWorkingDays(project: String, team: String): Set<DayOfWeek>? {
        val settings: TeamSettingsDto = get(url(project, team, "_apis", "work", "teamsettings"))
        return settings.workingDays.mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.name.equals(day, ignoreCase = true) } }
            .toSet().takeIf { it.isNotEmpty() }
    }

    override suspend fun teamDaysOff(project: String, team: String, iterationId: String): List<DayRange> {
        val result: TeamDaysOffDto = get(
            url(project, team, "_apis", "work", "teamsettings", "iterations", iterationId, "teamdaysoff"),
        )
        return result.daysOff.mapNotNull { d ->
            val start = instantOrNull(d.start)?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: return@mapNotNull null
            val end = instantOrNull(d.end)?.atOffset(ZoneOffset.UTC)?.toLocalDate() ?: start
            DayRange(start, end)
        }
    }

    override suspend fun repositories(project: String): List<String> {
        val list: ValueList<RepoNameDto> = get(url(project, "_apis", "git", "repositories"))
        return list.value.filterNot { it.isDisabled }.map { it.name }.sortedBy { it.lowercase() }
    }

    override suspend fun workItemDetail(id: Int): WorkItemDetail =
        get<WorkItemDetailDto>(url("_apis", "wit", "workitems", "$id") { addQueryParameter("\$expand", "relations") })
            .toDetail()

    override suspend fun updateWorkItem(id: Int, rev: Int, change: FieldChange): WorkItemDetail {
        val body = WorkItemPatch.build(rev, change).toString().toRequestBody(JSON_PATCH)
        return call<WorkItemDetailDto>(
            Request.Builder().url(url("_apis", "wit", "workitems", "$id") { addQueryParameter("\$expand", "relations") }).patch(body),
        ).toDetail()
    }

    override suspend fun typeFields(project: String, type: String): Set<String> {
        val list: ValueList<FieldRefDto> = get(url(project, "_apis", "wit", "workitemtypes", type, "fields"))
        return list.value.map { it.referenceName }.toSet()
    }

    override suspend fun iterations(project: String): List<Iteration> {
        val root: ClassificationNodeDto = get(
            url(project, "_apis", "wit", "classificationnodes", "iterations") { addQueryParameter("\$depth", "$AREA_DEPTH") },
        )
        // As for the areas: work items read "Project\\Sprint 1", without the API's "\\Iteration"
        val out = mutableListOf<Iteration>()
        fun ClassificationNodeDto.walk(path: String) {
            out += Iteration(
                name, path,
                attributes?.startDate?.let(::instantOrNull), attributes?.finishDate?.let(::instantOrNull),
            )
            children.forEach { it.walk("$path\\${it.name}") }
        }
        root.copy(name = project).walk(project)
        return out
    }

    override suspend fun searchPeople(query: String): List<Person> {
        val body = buildJsonObject {
            put("query", query)
            putJsonArray("identityTypes") { add(JsonPrimitive("user")) }
            putJsonArray("operationScopes") { add(JsonPrimitive("ims")); add(JsonPrimitive("source")) }
            putJsonObject("options") {
                put("MinResults", 5)
                put("MaxResults", 20)
            }
            putJsonArray("properties") {
                listOf("DisplayName", "Mail", "SignInAddress", "SamAccountName").forEach { add(JsonPrimitive(it)) }
            }
        }
        val result: IdentityPickerResult = post(url("_apis", "IdentityPicker", "Identities", apiVersion = "7.1-preview.1"), body)
        return result.results.flatMap { it.identities }.mapNotNull { i ->
            val name = i.displayName ?: return@mapNotNull null
            Person(
                id = i.localId ?: i.entityId.orEmpty(),
                name = name,
                uniqueName = i.signInAddress ?: i.mail ?: i.samAccountName ?: name,
            )
        }.distinctBy { it.uniqueName.lowercase() }
    }

    override suspend fun teamMembers(project: String, team: String): List<Person> {
        val list: ValueList<TeamMemberDto> = get(url("_apis", "projects", project, "teams", team, "members"))
        return list.value.map { it.identity }.filterNot { it.isContainer }
            .map { Person(it.id, it.displayName, it.uniqueName.ifBlank { it.displayName }) }
    }

    override suspend fun pullRequestById(id: Int): LinkedPullRequest {
        val pr: PullRequestDto = get(url("_apis", "git", "pullrequests", "$id"))
        return pr.toPullRequest().let { LinkedPullRequest(it.id, it.title, pr.status, it.draft, it.url) }
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

    private suspend inline fun <reified T> call(request: Request.Builder): T = json.decodeFromString(fetch(request).first)

    /** A page of a list and the continuation token of the next one, if any. */
    private suspend inline fun <reified T> page(url: HttpUrl): Pair<T, String?> =
        fetch(Request.Builder().url(url).get()).let { (text, next) -> json.decodeFromString<T>(text) to next }

    /**
     * The body of a successful response and its continuation token; one longer than [maxBytes]
     * is not read, but fails with [TOO_LARGE].
     */
    private suspend fun fetch(request: Request.Builder, maxBytes: Long? = null): Pair<String, String?> {
        val auth = authorization()
        return withContext(Dispatchers.IO) {
            http.newCall(request.header("Authorization", auth).header("Accept", "application/json").build())
                .execute().use { response ->
                    // A JSON item holds the file's content escaped: some room over the file's limit
                    val limit = maxBytes?.let { it * 2 }
                    val length = response.body?.contentLength() ?: -1
                    if (limit != null && response.isSuccessful && length > limit) throw AdoException(TOO_LARGE, "Too large")
                    val body = response.body?.string().orEmpty()
                    if (limit != null && response.isSuccessful && body.length > limit) throw AdoException(TOO_LARGE, "Too large")
                    // An expired PAT gets a 203 with the sign-in page instead of a 401; a proxy's
                    // error page (502, 503…) is HTML too, but no reason to doubt the credentials
                    val isHtml = response.header("Content-Type")?.startsWith("text/html") == true
                    when {
                        response.code == 401 || response.code == 203 || (isHtml && response.isSuccessful) ->
                            throw AdoException(401, "Invalid or expired credentials")
                        !response.isSuccessful -> throw AdoException(response.code, errorMessage(response.code, body))
                    }
                    body to response.header("x-ms-continuationtoken")
                }
        }
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
            title = HtmlEntities.decode(text("System.Title").orEmpty()),
            state = text("System.State").orEmpty(),
            priority = (field("Microsoft.VSTS.Common.Priority") as? JsonPrimitive)?.intOrNull,
            assignedTo = (field("System.AssignedTo") as? JsonObject)?.get("displayName")?.jsonPrimitive?.contentOrNull,
            changedAt = text("System.ChangedDate")?.let(Instant::parse) ?: Instant.EPOCH,
            url = baseUrl.newBuilder().addPathSegment(project).addPathSegment("_workitems")
                .addPathSegment("edit").addPathSegment("$id").build().toString(),
            areaPath = text("System.AreaPath") ?: project,
        )
    }

    private fun WorkItemDetailDto.toDetail(): WorkItemDetail {
        val item = WorkItemDto(id, fields).toWorkItem()
        fun text(name: String) = (fields[name] as? JsonPrimitive)?.contentOrNull
        fun person(name: String) = (fields[name] as? JsonObject)?.let { o ->
            val display = o["displayName"]?.jsonPrimitive?.contentOrNull ?: return@let null
            Person(
                o["id"]?.jsonPrimitive?.contentOrNull.orEmpty(), display,
                o["uniqueName"]?.jsonPrimitive?.contentOrNull ?: display,
            )
        }
        val description = text(WiFields.DESCRIPTION)?.takeIf { it.isNotBlank() } ?: text(WiFields.REPRO_STEPS).orEmpty()
        return WorkItemDetail(
            item = item,
            rev = rev,
            descriptionHtml = description,
            assignee = person(WiFields.ASSIGNED_TO),
            iterationPath = text(WiFields.ITERATION) ?: item.project,
            remainingWork = (fields[WiFields.REMAINING_WORK] as? JsonPrimitive)?.doubleOrNull,
            changedBy = person(WiFields.CHANGED_BY)?.name,
            commentCount = (fields[WiFields.COMMENT_COUNT] as? JsonPrimitive)?.intOrNull ?: 0,
            links = relations.mapNotNull { it.toLink() },
        )
    }

    private fun RelationDto.toLink(): WorkItemLink? {
        fun workItemId() = url.substringAfterLast('/').toIntOrNull()
        return when (rel) {
            "System.LinkTypes.Hierarchy-Reverse" -> workItemId()?.let { WorkItemLink(LinkKind.PARENT, it, url) }
            "System.LinkTypes.Hierarchy-Forward" -> workItemId()?.let { WorkItemLink(LinkKind.CHILD, it, url) }
            "System.LinkTypes.Related" -> workItemId()?.let { WorkItemLink(LinkKind.RELATED, it, url) }
            "ArtifactLink" -> {
                // vstfs:///Git/PullRequestId/<project id>%2F<repo id>%2F<pr id>
                if (!url.startsWith(PR_ARTIFACT, ignoreCase = true)) return null
                val parts = url.substring(PR_ARTIFACT.length).replace("%2F", "/", ignoreCase = true).split('/')
                val prId = parts.lastOrNull()?.toIntOrNull() ?: return null
                val page = if (parts.size == 3) {
                    baseUrl.newBuilder().addPathSegment(parts[0]).addPathSegment("_git").addPathSegment(parts[1])
                        .addPathSegment("pullrequest").addPathSegment("$prId").build().toString()
                } else {
                    ""
                }
                WorkItemLink(LinkKind.PULL_REQUEST, prId, page)
            }
            else -> null
        }
    }

    private fun DefinitionDto.toPipeline(project: String): Pipeline {
        val projectName = this.project?.name ?: project
        val run = (latestBuild ?: latestCompletedBuild)?.toBuild(projectName)
            // The runs inside a definition may leave their definition out
            ?.let { if (it.definitionId == 0) it.copy(definitionId = id, definitionName = name) else it }
        return Pipeline(
            id = id,
            name = name,
            folder = path.ifBlank { "\\" },
            project = projectName,
            latest = run,
            url = baseUrl.newBuilder().addPathSegment(projectName).addPathSegment("_build")
                .addQueryParameter("definitionId", "$id").build().toString(),
        )
    }

    private fun BuildDto.toBuild(project: String): Build {
        val pr = triggerInfo["pr.number"]?.toIntOrNull()
            ?: sourceBranch.takeIf { it.startsWith("refs/pull/") }?.removePrefix("refs/pull/")?.substringBefore('/')?.toIntOrNull()
        val projectName = this.project?.name ?: project
        fun buildPage() = baseUrl.newBuilder().addPathSegment(projectName).addPathSegment("_build")
        return Build(
            id = id,
            number = buildNumber,
            project = projectName,
            definitionId = definition.id,
            definitionName = definition.name,
            repoId = repository?.id.orEmpty(),
            repoName = repository?.name.orEmpty(),
            sourceBranch = sourceBranch,
            reason = reason,
            prId = pr,
            prSourceBranch = triggerInfo["pr.sourceBranch"]?.removePrefix("refs/heads/"),
            requestedBy = requestedFor?.displayName ?: requestedBy?.displayName.orEmpty(),
            state = stateOf(status, result),
            queuedAt = queueTime?.let(::instantOrNull),
            startedAt = startTime?.let(::instantOrNull),
            finishedAt = finishTime?.let(::instantOrNull),
            url = links?.web?.href
                ?: buildPage().addPathSegment("results").addQueryParameter("buildId", "$id").build().toString(),
            definitionUrl = buildPage().addQueryParameter("definitionId", "${definition.id}").build().toString(),
        )
    }

    private fun PullRequestDto.toPullRequest() = PullRequest(
        id = pullRequestId,
        project = repository.project.name,
        repoId = repository.id,
        repoName = repository.name,
        title = HtmlEntities.decode(title),
        author = createdBy.toIdentity(),
        sourceBranch = sourceRefName.removePrefix("refs/heads/"),
        targetBranch = targetRefName.removePrefix("refs/heads/"),
        draft = isDraft,
        reviewers = reviewers.map { it.toReviewer() },
        createdAt = Instant.parse(creationDate),
        url = baseUrl.newBuilder().addPathSegment(repository.project.name).addPathSegment("_git")
            .addPathSegment(repository.name).addPathSegment("pullrequest").addPathSegment("$pullRequestId")
            .build().toString(),
        projectId = repository.project.id,
        mergeStatus = mergeStatus,
        lastCommit = lastMergeSourceCommit?.commitId,
    )

    companion object {
        const val API_VERSION = "7.1"
        /** Work item comments exist only as a preview API. */
        const val COMMENTS_API = "7.1-preview.4"
        /** Reordering the backlog exists only as a preview API. */
        const val REORDER_API = "7.1-preview.1"
        private val JSON = "application/json".toMediaType()
        private val JSON_PATCH = "application/json-patch+json".toMediaType()
        private const val PR_ARTIFACT = "vstfs:///Git/PullRequestId/"
        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        private val WORK_ITEM_FIELDS = listOf(
            "System.Id", "System.TeamProject", "System.WorkItemType", "System.Title", "System.State",
            "System.AssignedTo", "System.ChangedDate", "Microsoft.VSTS.Common.Priority", "System.AreaPath",
        )
        private const val STACK_RANK = "Microsoft.VSTS.Common.StackRank"
        private const val BACKLOG_PRIORITY = "Microsoft.VSTS.Common.BacklogPriority"
        private val BACKLOG_FIELDS = WORK_ITEM_FIELDS +
            listOf(WiFields.REMAINING_WORK, STACK_RANK, BACKLOG_PRIORITY, "System.Parent")
        private const val HIERARCHY_FORWARD = "System.LinkTypes.Hierarchy-Forward"
        /** Deep enough for any real area tree. */
        private const val AREA_DEPTH = 10
        /** Older servers: the states of a project's types read a few at a time. */
        private const val PARALLEL_TYPE_STATES = 4

        /** What [fetch] answers for a body over its limit: not an HTTP code. */
        private const val TOO_LARGE = -413

        /** Pull request commits: pages read at most. */
        private const val MAX_COMMIT_PAGES = 5

        /** Pipelines read per call, and calls at most. */
        private const val DEFINITIONS_PAGE = 1000
        private const val MAX_DEFINITION_PAGES = 5

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

        /** A build or a timeline record by its status (or state) and result. */
        internal fun stateOf(status: String?, result: String?): BuildState = when (status) {
            "completed" -> when (result) {
                "succeeded" -> BuildState.SUCCEEDED
                "partiallySucceeded", "succeededWithIssues" -> BuildState.PARTIAL
                "failed", "abandoned" -> BuildState.FAILED
                else -> BuildState.CANCELED
            }
            "inProgress", "cancelling" -> BuildState.RUNNING
            else -> BuildState.QUEUED
        }

        private fun instantOrNull(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()

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
@Serializable private data class RepoNameDto(
    val id: String = "",
    val name: String,
    val isDisabled: Boolean = false,
    val defaultBranch: String? = null,
)
@Serializable private data class TeamFieldDto(val referenceName: String = "")
@Serializable private data class TeamFieldValueDto(val value: String, val includeChildren: Boolean = false)
@Serializable private data class TeamFieldValuesDto(
    val field: TeamFieldDto = TeamFieldDto(),
    val values: List<TeamFieldValueDto> = emptyList(),
)
@Serializable private data class TeamIterationDto(
    val id: String = "",
    val name: String,
    val path: String = "",
    val attributes: NodeAttributesDto? = null,
)
@Serializable private data class WorkItemRefDto(val id: Int)
@Serializable private data class IterationRelationDto(
    val rel: String? = null,
    val source: WorkItemRefDto? = null,
    val target: WorkItemRefDto? = null,
)
@Serializable private data class IterationWorkItemsDto(val workItemRelations: List<IterationRelationDto> = emptyList())
@Serializable private data class ReorderResultDto(val id: Int, val order: Double = 0.0)
@Serializable private data class TeamSettingsDto(val workingDays: List<String> = emptyList())
@Serializable private data class DayOffDto(val start: String = "", val end: String = "")
@Serializable private data class TeamDaysOffDto(val daysOff: List<DayOffDto> = emptyList())
@Serializable private data class StateDto(val name: String, val category: String = "")
@Serializable private data class ClassificationNodeDto(
    val name: String,
    val children: List<ClassificationNodeDto> = emptyList(),
    val attributes: NodeAttributesDto? = null,
)
@Serializable private data class NodeAttributesDto(val startDate: String? = null, val finishDate: String? = null)
@Serializable private data class FieldRefDto(val referenceName: String)
@Serializable private data class RelationDto(val rel: String = "", val url: String = "")
@Serializable private data class WorkItemDetailDto(
    val id: Int,
    val rev: Int = 0,
    val fields: JsonObject = JsonObject(emptyMap()),
    val relations: List<RelationDto> = emptyList(),
)
@Serializable private data class IdentityPickerResult(val results: List<IdentityPickerQuery> = emptyList())
@Serializable private data class IdentityPickerQuery(val identities: List<PickerIdentityDto> = emptyList())
@Serializable private data class PickerIdentityDto(
    val entityId: String? = null,
    val localId: String? = null,
    val displayName: String? = null,
    val mail: String? = null,
    val signInAddress: String? = null,
    val samAccountName: String? = null,
)
@Serializable private data class TeamMemberIdentityDto(
    val id: String = "",
    val displayName: String = "",
    val uniqueName: String = "",
    val isContainer: Boolean = false,
)
@Serializable private data class TeamMemberDto(val identity: TeamMemberIdentityDto = TeamMemberIdentityDto())
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

@Serializable private data class CommentList(
    val comments: List<CommentDto> = emptyList(),
    val totalCount: Int? = null,
    val continuationToken: String? = null,
)
@Serializable private data class CommentDto(
    val id: Int,
    val workItemId: Int,
    val text: String? = null,
    val createdBy: IdentityDto = IdentityDto(),
    val createdDate: String,
    val modifiedDate: String? = null,
    val version: Int = 1,
    val isDeleted: Boolean = false,
)
private fun CommentDto.toComment(): Comment {
    val created = Instant.parse(createdDate)
    // modifiedDate is a few ms after createdDate even for a comment never edited
    val modified = modifiedDate?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val edited = version > 1 || (modified != null && modified.isAfter(created.plusSeconds(1)))
    return Comment(id, workItemId, createdBy.toIdentity(), text.orEmpty(), created, edited)
}

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
    val mergeStatus: String? = null,
    val lastMergeSourceCommit: CommitRefDto? = null,
    val status: String = "active",
    val description: String? = null,
    val labels: List<LabelDto> = emptyList(),
    val workItemRefs: List<ResourceRefDto> = emptyList(),
)
@Serializable private data class LabelDto(val name: String = "", val active: Boolean = true)
@Serializable private data class ResourceRefDto(val id: String = "")
@Serializable private data class IdentityRecordDto(
    val id: String = "",
    val providerDisplayName: String? = null,
    val customDisplayName: String? = null,
)
@Serializable private data class ReviewerDto(
    val id: String,
    val displayName: String = "",
    val vote: Int = 0,
    val isRequired: Boolean = false,
    val isContainer: Boolean = false,
    val votedFor: List<ReviewerRefDto> = emptyList(),
)
@Serializable private data class ReviewerRefDto(val id: String = "")
private fun ReviewerDto.toReviewer() =
    Reviewer(id, displayName, vote, isRequired, group = isContainer, votedFor = votedFor.map { it.id }.filter { it.isNotEmpty() })
@Serializable private data class RepositoryDto(val id: String, val name: String, val project: ProjectRefDto)
@Serializable private data class ProjectRefDto(val id: String = "", val name: String)
@Serializable private data class CommitRefDto(val commitId: String = "")

@Serializable private data class ThreadDto(
    val id: Int,
    val status: String? = null,
    val isDeleted: Boolean = false,
    val comments: List<ThreadCommentDto> = emptyList(),
    val threadContext: ThreadContextDto? = null,
)
@Serializable private data class IterationDto(
    val id: Int,
    val sourceRefCommit: CommitRefDto? = null,
    val targetRefCommit: CommitRefDto? = null,
    val commonRefCommit: CommitRefDto? = null,
)
@Serializable private data class ChangeItemDto(val path: String = "", val isFolder: Boolean = false, val gitObjectType: String? = null)
@Serializable private data class ChangeEntryDto(
    val changeTrackingId: Int = 0,
    val changeType: String = "edit",
    val item: ChangeItemDto? = null,
    val originalPath: String? = null,
    val sourceServerItem: String? = null,
)
@Serializable private data class IterationChangesDto(val changeEntries: List<ChangeEntryDto> = emptyList())
@Serializable private data class ContentMetadataDto(val isBinary: Boolean = false)
@Serializable private data class ItemDto(val content: String? = null, val contentMetadata: ContentMetadataDto? = null)
@Serializable private data class GitUserDateDto(val name: String = "", val date: String? = null)
@Serializable private data class CommitDto(val commitId: String, val comment: String = "", val author: GitUserDateDto? = null)
@Serializable private data class FilePositionDto(val line: Int = 0)
@Serializable private data class ThreadContextDto(
    val filePath: String? = null,
    val rightFileStart: FilePositionDto? = null,
    val leftFileStart: FilePositionDto? = null,
)
@Serializable private data class ThreadCommentDto(
    val id: Int,
    val parentCommentId: Int = 0,
    val author: IdentityDto = IdentityDto(),
    val content: String? = null,
    val commentType: String = "text",
    val publishedDate: String,
    val isDeleted: Boolean = false,
)
private fun ThreadCommentDto.toComment(threadId: Int) =
    PrComment(threadId, id, author.toIdentity(), content.orEmpty(), Instant.parse(publishedDate), parentCommentId)

/** System comments (votes, pushes) left out; a thread on a file at the line it starts on. */
private fun ThreadDto.toThread(): PrThread {
    val comments = comments.filter { !it.isDeleted && it.commentType == "text" }.map { it.toComment(id) }
    val context = threadContext
    val right = context?.rightFileStart?.line?.takeIf { it > 0 }
    val left = context?.leftFileStart?.line?.takeIf { it > 0 }
    return PrThread(
        id, status, comments, filePath = context?.filePath?.takeIf { it.isNotBlank() }, line = right ?: left,
        leftSide = right == null && left != null,
    )
}

@Serializable private data class PolicyTypeDto(val id: String = "", val displayName: String = "")
@Serializable private data class PolicyConfigurationDto(
    val isEnabled: Boolean = true,
    val isBlocking: Boolean = false,
    val type: PolicyTypeDto = PolicyTypeDto(),
    val settings: JsonObject? = null,
)
@Serializable private data class PolicyEvaluationDto(
    val configuration: PolicyConfigurationDto = PolicyConfigurationDto(),
    val status: String = "",
    val context: JsonObject? = null,
    val completedDate: String? = null,
)

@Serializable private data class StatusContextDto(val name: String = "", val genre: String? = null)
@Serializable private data class PrStatusDto(
    val id: Int = 0,
    val state: String = "",
    val description: String? = null,
    val context: StatusContextDto = StatusContextDto(),
    val targetUrl: String? = null,
    val creationDate: String? = null,
    val updatedDate: String? = null,
)

@Serializable private data class NamedRefDto(val id: Int = 0, val name: String = "")
@Serializable private data class BuildRepoDto(val id: String = "", val name: String = "")
@Serializable private data class LinkDto(val href: String? = null)
@Serializable private data class BuildLinksDto(val web: LinkDto? = null)
@Serializable private data class BuildDto(
    val id: Int,
    val buildNumber: String = "",
    val status: String? = null,
    val result: String? = null,
    val queueTime: String? = null,
    val startTime: String? = null,
    val finishTime: String? = null,
    val sourceBranch: String = "",
    val reason: String = "",
    val definition: NamedRefDto = NamedRefDto(),
    val project: ProjectDto? = null,
    val repository: BuildRepoDto? = null,
    val requestedFor: IdentityDto? = null,
    val requestedBy: IdentityDto? = null,
    val triggerInfo: Map<String, String> = emptyMap(),
    @SerialName("_links") val links: BuildLinksDto? = null,
)
@Serializable private data class DefinitionDto(
    val id: Int,
    val name: String = "",
    val path: String = "",
    val project: ProjectDto? = null,
    val latestBuild: BuildDto? = null,
    val latestCompletedBuild: BuildDto? = null,
)
@Serializable private data class TimelineRecordDto(
    val id: String = "",
    val parentId: String? = null,
    val type: String = "",
    val name: String = "",
    val order: Int? = null,
    val state: String? = null,
    val result: String? = null,
    val startTime: String? = null,
    val finishTime: String? = null,
)
@Serializable private data class TimelineDto(val records: List<TimelineRecordDto> = emptyList())
@Serializable private data class TestRunDto(val id: Int, val name: String = "", val unanalyzedTests: Int = 0)
@Serializable private data class TestResultDto(
    val testCaseTitle: String? = null,
    val automatedTestName: String? = null,
    val errorMessage: String? = null,
)
