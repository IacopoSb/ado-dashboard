package io.github.iacoposb.adodash.ui

import io.github.iacoposb.adodash.data.BoardRef
import io.github.iacoposb.adodash.sync.TeamInfo

/** A project of the boards list: the teams it shows, out of [total], and whether they are visible. */
data class BoardGroup(
    val project: String,
    val teams: List<TeamInfo>,
    val total: Int,
    val expanded: Boolean,
    /** The project's name matches the search: all of its teams are listed. */
    val projectMatch: Boolean = false,
)

/** The boards part of the launch screen and of the view menu: recent boards, then projects. */
data class BoardMenu(val recent: List<BoardRef>, val groups: List<BoardGroup>, val query: String) {
    val searching get() = query.isNotEmpty()
    val isEmpty get() = recent.isEmpty() && groups.isEmpty()
}

/**
 * Without a search: [recent] first, then every project, open only if it is in [expanded].
 * With one (case-insensitive): no recent boards, and the projects whose name matches with all
 * their teams, or the others with only the teams that match; all of them open.
 */
fun boardMenu(boards: List<ProjectBoards>, recent: List<BoardRef>, query: String, expanded: Set<String>): BoardMenu {
    val q = query.trim()
    if (q.isEmpty()) {
        return BoardMenu(recent, boards.map { BoardGroup(it.project, it.teams, it.teams.size, it.project in expanded) }, "")
    }
    val groups = boards.mapNotNull { p ->
        val projectMatch = p.project.contains(q, ignoreCase = true)
        val teams = if (projectMatch) p.teams else p.teams.filter { it.name.contains(q, ignoreCase = true) }
        if (teams.isEmpty()) null else BoardGroup(p.project, teams, p.teams.size, expanded = true, projectMatch = projectMatch)
    }
    return BoardMenu(emptyList(), groups, q)
}

/** At first only the project of the board open (or chosen) is expanded. */
fun initialExpanded(board: BoardRef?): Set<String> = setOfNotNull(board?.project)
