package org.hogel.pocketssh.shortcuts

/**
 * A user-configurable shortcut button. [label] is the visible text; [payload]
 * is the action specification parsed by [parseShortcutActions].
 */
data class Shortcut(val label: String, val payload: String)

/**
 * A bundle of UI surfaces that activate together when an active match condition
 * is met. Matching is by [contexts] (lowercased exact match against the active
 * tmux pane's foreground command from the OSC window title) and [useTmux]
 * (true → only when the connection uses tmux). See [matches] / [specifity] for
 * the resolution logic and [List.resolve] for the per-feature cascade rules.
 *
 * @property name Display name shown in the settings list. Not used for matching.
 * @property contexts Foreground commands the group binds to. Empty list = match
 * any context.
 * @property useTmux `true` means the group only activates when tmux is in use;
 * `null` (the default) means tmux state is irrelevant. `false` is intentionally
 * excluded from the data model — "only outside tmux" was judged not worth the
 * extra ambiguity in matching rules.
 * @property shortcuts Buttons rendered on the keyboard shortcut bar. All
 * matching groups' lists are concatenated (specifity high → low) into a single
 * bar.
 * @property swipeLeft Optional payload bound to a left swipe on the terminal.
 * Only the highest-specifity matching group's value is used.
 * @property swipeRight Optional payload bound to a right swipe on the terminal.
 * @property fabItems Buttons rendered as one row inside the floating action
 * button menu. Each matching group with a non-empty list contributes its own
 * row; rows are stacked specifity high → low.
 */
data class ContextGroup(
    val name: String,
    val contexts: List<String> = emptyList(),
    val useTmux: Boolean? = null,
    val shortcuts: List<Shortcut> = emptyList(),
    val swipeLeft: Shortcut? = null,
    val swipeRight: Shortcut? = null,
    val fabItems: List<Shortcut> = emptyList(),
) {
    /**
     * Higher = more specific. `contexts.size` is weighted twice so that an
     * explicit foreground match (e.g. `claude`) outranks a tmux-only group of
     * equal context-count, mirroring the user's intuition that the program
     * they're staring at owns the screen real estate over the surrounding
     * environment.
     */
    val specifity: Int
        get() = contexts.size * 2 + if (useTmux == true) 1 else 0

    fun matches(inTmux: Boolean, currentContext: String?): Boolean {
        if (useTmux == true && !inTmux) return false
        if (contexts.isEmpty()) return true
        val key = currentContext?.trim()?.lowercase().orEmpty()
        if (key.isEmpty()) return false
        return contexts.any { it.equals(key, ignoreCase = true) }
    }
}

/**
 * Outcome of resolving the active groups against the current state. Each
 * surface follows its own rule (see [List.resolve]) so that, for instance, a
 * `tmux` group with swipe gestures and a `claude` group with shortcuts can
 * coexist without one blanket-overwriting the other.
 */
data class ResolvedContext(
    val shortcuts: List<Shortcut>,
    val swipeLeft: Shortcut?,
    val swipeRight: Shortcut?,
    /** Each inner list renders as one row in the FAB menu. */
    val fabRows: List<List<Shortcut>>,
)

/**
 * Run the per-feature cascade. Single-value features (swipes) take the
 * specifity-max non-null entry; the shortcut bar concatenates every group's
 * list; the FAB stacks each contributing group as its own row.
 */
fun List<ContextGroup>.resolve(inTmux: Boolean, currentContext: String?): ResolvedContext {
    val matches = filter { it.matches(inTmux, currentContext) }
        .sortedByDescending { it.specifity }
    val shortcuts = matches.flatMap { it.shortcuts }
    val swipeLeft = matches.firstOrNull { it.swipeLeft != null }?.swipeLeft
    val swipeRight = matches.firstOrNull { it.swipeRight != null }?.swipeRight
    val fabRows = matches.filter { it.fabItems.isNotEmpty() }.map { it.fabItems }
    return ResolvedContext(shortcuts, swipeLeft, swipeRight, fabRows)
}
