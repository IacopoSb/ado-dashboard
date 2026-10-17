package io.github.iacoposb.adodash.sync

import io.github.iacoposb.adodash.Fakes
import io.github.iacoposb.adodash.api.TeamArea
import io.github.iacoposb.adodash.data.Condition
import io.github.iacoposb.adodash.data.Filters
import io.github.iacoposb.adodash.data.WiField
import io.github.iacoposb.adodash.data.WiOrder
import io.github.iacoposb.adodash.data.WorkItemsPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewQueryTest {

    @Test fun `conditions, board and global filters all reach the query`() {
        val panel = WorkItemsPanel(
            conditions = listOf(
                Condition(WiField.TYPE, values = listOf("Bug", "Task")),
                Condition.assignedTo("Anna O'Neil"),
                Condition.notClosed,
                Condition(WiField.STATE, negate = true, values = listOf("Blocked")),
            ),
            order = WiOrder.CHANGED,
        )
        val scope = BoardScope("Contoso", listOf(TeamArea("Contoso\\Backend", true), TeamArea("Contoso", false)))
        val wiql = ViewQuery.wiql(panel, Filters(areas = setOf("Contoso")), scope)
        assertEquals(
            """
            SELECT [System.Id] FROM WorkItems
            WHERE [System.Id] > 0 AND [System.WorkItemType] IN ('Bug','Task') AND [System.AssignedTo] = 'Anna O''Neil' AND [System.State] NOT IN ('Closed','Done','Removed','Completed','Cut') AND [System.State] NOT IN ('Blocked') AND [System.TeamProject] = 'Contoso' AND ([System.AreaPath] UNDER 'Contoso\Backend' OR [System.AreaPath] = 'Contoso') AND ([System.AreaPath] UNDER 'Contoso')
            ORDER BY [System.ChangedDate] DESC
            """.trimIndent(),
            wiql,
        )
    }

    @Test fun `me, anyone and closed states`() {
        val wiql = ViewQuery.wiql(
            WorkItemsPanel(
                conditions = listOf(
                    Condition.assignedTo(Condition.ME),
                    Condition.assignedTo(Condition.ANYONE),
                    Condition(WiField.STATE, values = listOf("Active", Condition.CLOSED)),
                    Condition(WiField.TYPE),
                ),
            ),
            Filters(), scope = null,
        )
        assertTrue(wiql, wiql.contains("AND [System.AssignedTo] = @Me AND [System.State] IN ('Active','Closed','Done','Removed','Completed','Cut','Resolved')\n"))
        assertTrue(wiql.endsWith("ORDER BY [Microsoft.VSTS.Common.Priority] ASC, [System.ChangedDate] DESC"))
    }

    @Test fun `a board scope takes the team's areas, with or without the areas below`() {
        val scope = BoardScope("Contoso", listOf(TeamArea("Contoso\\Backend", true), TeamArea("Contoso\\Mobile", false)))
        assertTrue(scope.accepts(Fakes.workItem(1, area = "Contoso\\Backend\\Gateway")))
        assertTrue(scope.accepts(Fakes.workItem(2, area = "Contoso\\Mobile")))
        assertFalse(scope.accepts(Fakes.workItem(3, area = "Contoso\\Mobile\\iOS")))
        assertFalse(scope.accepts(Fakes.workItem(4, area = "Contoso\\Frontend")))
        assertFalse(scope.accepts(Fakes.workItem(5, area = "Interno\\Backend")))
        assertTrue("no areas known: the whole project", BoardScope("contoso").accepts(Fakes.workItem(6, area = "Contoso\\Frontend")))
        assertTrue(scope.accepts(Fakes.pr(7, Fakes.anna)))
        assertFalse(scope.accepts(Fakes.pr(8, Fakes.anna, project = "Interno")))
    }
}
