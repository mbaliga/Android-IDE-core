package dev.aarso.domain.tasks

/** Where a task came from. MANUAL is the only source the free floor writes; AUDIT/
 *  INCIDENT/TEMPLATE are paid-layer provenance, carried here so the row is the same
 *  table either way (§5.1 — unlock migrates nothing). */
enum class TaskSource { MANUAL, AUDIT, INCIDENT, TEMPLATE }
