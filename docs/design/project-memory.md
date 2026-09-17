# Design: Project memory — per-project context protection

> Status: **design only, no code.** Today a "project" is a bare string label
> (`SessionStore.conversationProjects: Map<rootId, String>` —
> `data/SessionStore.kt:110-111`, `:271-286`); there is no `Project` entity, no
> place to hang project-scoped context, and no gate anywhere in the prompt-assembly
> path that would let one conversation's context leak — or fail to leak, on
> purpose — into another. This carves an exception into the append-only tree
> invariant (a context store the tree itself doesn't own) and picks a default that
> restricts what "everything is visible" means today, so it needs the owner
> decisions at the bottom before any code.

## The problem

"Projects" exist today only as a grouping label on the Conversations room (`ui/
rooms` — the "Projects" filter cited in `CLAUDE.md`'s IA section) and as a search
facet (`project_id` in `SearchProjector.Row`, indexed by `SearchIndexer.writeRow`
— `data/search/SearchIndexer.kt:239-255`). Nothing about a project is *content*:
there's no shared instructions, no per-project facts, no "this project's stack is
Kotlin + Compose, don't suggest Java" that would otherwise get retyped into every
new conversation under that label. Every conversation's context is exactly its
own tree path — `MessageTreeRepository.path(leafId)` (`data/
MessageTreeRepository.kt:48-49`) — nothing more, nothing less, and there's no seam
that could pull in anything else even if it wanted to.

The feature: let a project carry its own context (instructions, facts, whatever
the user writes into it), and have every conversation filed under that project see
it — without turning "project" into a second copy of the message tree, and without
silently changing what a user already understands "everything in Fonebrew is
either in this chat's path or it isn't" to mean.

## Proposed shape

### A real `Project` entity

Promote the bare string label into an entity with an id (so renaming a project
doesn't require rewriting every `conversationProjects` value — today's scheme
literally stores the label as the join key, so a rename silently orphans every
conversation that hasn't been re-tagged since):

```kotlin
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val private: Boolean = true,  // see "Private vs. shared" below
)
```

`SessionStore.conversationProjects` becomes `Map<rootId, projectId>` instead of
`Map<rootId, label>` — a mechanical migration (one-time: mint a `Project` per
distinct label already on disk, id = a fresh UUID, name = the label verbatim) that
keeps every existing assignment intact. `DataExport`'s `conversationProjects`
field (`data/DataExport.kt:43`) exports the id map plus the new project table,
same "everything that's yours" posture the file's own KDoc states.

### `ProjectContextStore`

A new SharedPreferences-backed store, same shape as `CouncilStore` (`data/
CouncilStore.kt`) — JSON blob, `StateFlow` cache, write-through setters — holding
one context blob per project id:

```kotlin
data class ProjectContext(val projectId: String, val text: String, val updatedAt: Long)
```

Deliberately *not* Room, and deliberately *not* a new axis on `MessageTree`: the
tree's append-only, git-DAG invariant (`domain/tree/MessageTree.kt`'s own KDoc —
"pure, in-memory view … three axes of the spine") is about turns and branches, not
settings. Project context is authored/edited state (the user rewrites it in
place, same as a council member's `instructions`/`memory` fields in `CouncilStore`
already do without anyone calling that a violation of the tree's append-only
contract) — it was never part of the append-only claim to begin with. Naming this
plainly, rather than quietly reusing the tree, is the "carved exception" the task
brief asks this doc to own: **project context lives beside the tree, not in it.**

### The injection gate

The one real seam in the whole codebase where a chat's outgoing prompt is
assembled from more than its own tree path is `ChatViewModel.effectivePromptPath`
(`ui/ChatViewModel.kt:1955-1971`), called from `runTurn` right before generation
(`ui/ChatViewModel.kt:943`: `val path = effectivePromptPath(fullPath)`). Today it
only applies a compaction-boundary truncation (WP3); it's the natural — and only
— place to also splice in a project's context, because it already sits between
"the tree's honest path" and "what actually gets sent to `engine.generate`"
(`ui/ChatViewModel.kt:947`).

Concretely: `effectivePromptPath` (or a new sibling called right after it) looks
up `session.conversationProjects.value[rootId]`, and if a project is assigned and
its `ProjectContext.text` is non-blank, prepends a synthetic system-role
`MessageNode` (never inserted into the real tree — synthetic exactly the way
`EngineGenerator.node()` (`inference/EngineGenerator.kt:35-42`) already builds
throwaway system/user nodes for the council bridge, never persisted) ahead of the
path handed to `engine.generate`. This is additive and structural, not a change
to what the tree records: `repository.insert` is never called for it, so the
message-tree spine — and everything that reads it verbatim (`GitBackup.backUp`'s
`repository.tree().allNodes()`, `DataExport`'s tree export, `ThreadGraphProjector`)
— never sees project context as if it were a turn someone typed. The user's own
turns and the model's own replies stay exactly what they are.

The same gate must apply to `sendCouncil` (`ui/ChatViewModel.kt:1120-…`, which
builds each voice's own message list around `ui/ChatViewModel.kt:1187-1188`) —
otherwise "project memory" would silently mean "single-model chat only,"
contradicting the council-member-memory section below.

### Private vs. shared: recommend **private by default**

`Project.private` defaults `true`. Argument:

- Every other per-scope memory surface in this codebase defaults closed and
  requires an explicit act to widen: a council member's `memory` field
  (`CouncilStore.Participant.memory`) is per-member and never auto-shared to
  other members; cloud providers are "opt-in per use, never a hidden fallback"
  (`CLAUDE.md` binding rule 2); the free-tier/model-catalog auto-updates in
  `SessionStore` default `false` (`KEY_FT_AUTO`, `KEY_MC_AUTO` — both
  `false`-defaulted at `SessionStore.kt:131,148`) specifically because *any*
  cross-boundary flow needs a deliberate opt-in, not a deliberate opt-out. A
  project defaulting to "shared" would be the first surface in the app where
  data moves across a boundary (project → project, or project → the global
  council roster) without the user having done anything to ask for that.
- The failure mode of private-by-default is "I typed something in Project A that
  I now wish Project B could see" — one extra tap to widen it. The failure mode of
  shared-by-default is "I didn't realize Project A's client notes were visible
  from Project B" — silent, and exactly the kind of invisible routing this app's
  whole design thesis (`CLAUDE.md`: "legibility … keeps the user in the loop")
  exists to avoid.
- Nothing today requires shared-by-default to be useful: a project's context is
  useful the moment its own conversations can see it; cross-project sharing is a
  power-user escalation, not the common case a new feature should default to.

**What "shared freely" would mean, if a project opts in:** *not* "every project
sees every other project's context." The unit of sharing is still the project —
`private = false` should mean "this project's context is visible when explicitly
referenced from elsewhere" (e.g. a future `@project:name` mention, or an explicit
picker when creating a new project — "start from …"), never an implicit global
pool every conversation reads from. A `false` flag with no consumer wired up yet
is fine to ship (same "ships inert until a UI exists" pattern `ThreadObserver`
already uses); an implicit global pool is not something to ship even inert,
because turning it on later can't be done without deciding right then whether
past silent sharing already happened.

## Cross-cutting behaviour

**Council-member memory interaction.** `CouncilStore.systemPromptFor`
(`data/CouncilStore.kt:78-83`) builds a member's system prompt from its own
`instructions` + `memory`, entirely independent of which conversation or project
it's running in — the roster is global, not per-project. Project context and
council memory are today, and should stay, orthogonal: a project's context is
about the *task*, a member's memory is about the *persona*. The injection gate
above should concatenate both (project context, then the member's own
instructions+memory, in that order — general-to-specific) rather than have one
silently override the other. Making council memory per-project as well is a
larger, separate feature (it would mean the roster itself needs project scoping)
and is out of scope here.

**Search-index visibility.** `SearchIndexer.writeRow` already indexes a
conversation's `project_id` as a facet (`data/search/SearchIndexer.kt:243`) — but
that only lets a query filter *by* project, it doesn't touch what's searchable.
Project context text itself, since it's never inserted as a tree node, is never
picked up by `SearchProjector`'s tree walk (`domain/search/
WorkspaceSearchProjector.kt` operates over `MessageTree`) and so is correctly
**not** independently searchable — a search for a phrase that lives only in a
project's context and never in an actual turn will find nothing, which is honest
(there's no turn to find) but worth stating up front so it isn't read as a bug.
If a synthetic system node's *effect* shows up in an assistant reply, that reply
is a normal tree node and gets indexed normally — the injected context isn't
hidden from search that way, and doesn't need to be, since it's the user's own
project's own conversation.

**Export behaviour.** `DataExport.toJson` (`data/DataExport.kt`) already exports
everything unconditionally to the owner running the export — there is no
"private" concept in export today, because export has never crossed a trust
boundary (it hands the user their own data back). Project context should export
the same way regardless of `private`: a project's own privacy flag governs
visibility to *other projects inside the app*, not visibility to the user who
owns the device. Add a `projectContext` array next to `conversationProjects` in
the export JSON, unconditionally.

## Owner decisions required

1. **Default privacy: confirmed private-by-default**, per the argument above — or
   override it. If shared-by-default is chosen instead, the "what does shared
   mean" question above needs a different, harder answer (an implicit visibility
   graph across projects) before any code.
2. What UI surfaces a project's context — a new tab under the existing
   Settings/Project rooms, or a field on the "Projects" filter itself in
   Conversations? Not decided here; this doc only specifies the store and the
   gate, not the screen.
3. Should council-member memory eventually become per-project (making a member's
   persona itself project-scoped), or stay a single global roster forever? Left
   open; the injection order above (project context, then member memory) works
   either way but the roster's shape doesn't change in this doc.
4. Migration UX: does promoting today's bare-string labels into `Project`
   entities happen silently on first launch after the update, or does it prompt
   the user (since it's the first time "project" becomes something with its own
   settable state, not just a label)?
