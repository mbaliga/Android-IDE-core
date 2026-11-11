package dev.fonebrew.ui.rooms

import dev.fonebrew.domain.model.ModelSpec
import dev.fonebrew.domain.model.Runtime

/**
 * Whether CodeLens's interpreter is a watched object (binding rule 2): the spec flag, or any
 * cloud runtime — the same rule the watch relay applies. Null (no runnable model, so nothing
 * explains) is not watched. Was hard-coded `false` while the generator could be a cloud model.
 */
internal fun codeLensWatched(spec: ModelSpec?): Boolean =
    spec != null && (spec.watched || spec.runtime == Runtime.CLOUD)
