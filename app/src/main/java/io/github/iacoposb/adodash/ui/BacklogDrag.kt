package io.github.iacoposb.adodash.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A row of the sprint backlog dragged by its handle among its siblings: the row follows the
 * finger, [order] is the siblings' order while it moves, and the others make room. [group] is
 * the parent whose children are dragged, null for the top level.
 */
@Stable
internal class BacklogDrag(private val list: LazyListState) {
    var id by mutableStateOf<Int?>(null)
        private set
    var group by mutableStateOf<Int?>(null)
        private set
    var order by mutableStateOf(emptyList<Int>())
        private set
    private var start = emptyList<Int>()

    /** The dragged row's top in the list's viewport: where the finger holds it. */
    private var top by mutableFloatStateOf(0f)

    /** The key the row has in the list: a top-level row its id, a child "parent/id". */
    fun keyOf(id: Int, group: Int? = this.group): Any = if (group == null) id else "$group/$id"

    fun isDragging(id: Int, group: Int?) = this.id == id && this.group == group

    fun start(id: Int, group: Int?, siblings: List<Int>) {
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == keyOf(id, group) } ?: return
        this.group = group
        this.id = id
        order = siblings
        start = siblings
        top = info.offset.toFloat()
    }

    fun drag(dy: Float) {
        if (id == null) return
        top += dy
        settle()
    }

    /** How far the dragged row is from where the list lays it out. */
    fun translation(): Float {
        val id = id ?: return 0f
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == keyOf(id) } ?: return 0f
        return top - info.offset
    }

    /**
     * The order for where the row is now: after every sibling whose middle it passed. Siblings
     * scrolled out of sight above stay above, those below stay below.
     */
    fun settle() {
        val id = id ?: return
        val visible = list.layoutInfo.visibleItemsInfo
        val me = visible.firstOrNull { it.key == keyOf(id) } ?: return
        val middle = top + me.size / 2f
        val others = order - id
        val shown = others.mapNotNull { k -> visible.firstOrNull { it.key == keyOf(k) }?.let { k to it.offset + it.size / 2f } }
        if (shown.isEmpty()) return
        var target = others.indexOf(shown.first().first)
        shown.forEach { (k, c) -> if (c < middle) target = others.indexOf(k) + 1 }
        val next = others.toMutableList().apply { add(target, id) }
        if (next != order) {
            // The list keeps its first row in place by key: by index instead, or moving the first
            // row would scroll the list along with it
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            order = next
        }
    }

    /**
     * How fast to scroll while the row is held near an edge, in pixels per frame: negative
     * upwards, 0 away from the edges.
     */
    fun edgeSpeed(edge: Float, max: Float): Float {
        val id = id ?: return 0f
        val info = list.layoutInfo
        val me = info.visibleItemsInfo.firstOrNull { it.key == keyOf(id) } ?: return 0f
        val above = top - info.viewportStartOffset
        val below = info.viewportEndOffset - (top + me.size)
        return when {
            above < edge && list.canScrollBackward -> -max * (1f - (above / edge).coerceIn(0f, 1f))
            below < edge && list.canScrollForward -> max * (1f - (below / edge).coerceIn(0f, 1f))
            else -> 0f
        }
    }

    /** Dropped: the new order when it changed, null otherwise. */
    fun end(): List<Int>? {
        val result = order.takeIf { id != null && it != start }
        id = null
        group = null
        order = emptyList()
        return result
    }
}
