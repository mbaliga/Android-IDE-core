package dev.fonebrew.domain.workspace.fuzzy

import java.util.PriorityQueue

/**
 * Bounded selection of the best [k] items under [order] (smallest by [order] is "best") using a
 * size-k heap: memory O(k) however many candidates are offered, time O(n log k).
 */
class TopK<T>(private val k: Int, private val order: Comparator<T>) {
    // head = the WORST kept item, so it is the one evicted
    private val heap = PriorityQueue<T>(maxOf(k, 1), order.reversed())

    fun offer(item: T) {
        if (k <= 0) return
        if (heap.size < k) { heap.add(item); return }
        if (order.compare(item, heap.peek()) < 0) { heap.poll(); heap.add(item) }
    }

    /** Best first. */
    fun toSortedList(): List<T> = heap.toMutableList().also { it.sortWith(order) }
}
