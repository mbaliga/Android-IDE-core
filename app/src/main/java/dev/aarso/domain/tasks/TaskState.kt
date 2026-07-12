package dev.aarso.domain.tasks

/** A task's lifecycle stage. The free To-do UI only ever moves a row TODO ↔ DONE by hand;
 *  DOING/BLOCKED exist for the paid Board lens (§5.1, dormant in free). */
enum class TaskState { TODO, DOING, BLOCKED, DONE }
