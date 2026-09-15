package dev.fonebrew.domain.tasks

/** Where a task came from. MANUAL (typed directly) and CHAT (minted by TurnActionsSheet's
 *  "Convert → Task" row — see [dev.fonebrew.domain.tasks.TaskFromTurn]) are both free-floor,
 *  user-initiated sources; AUDIT/INCIDENT/TEMPLATE are paid-layer provenance, carried here so
 *  the row is the same table either way (§5.1 — unlock migrates nothing). */
enum class TaskSource { MANUAL, CHAT, AUDIT, INCIDENT, TEMPLATE }
