package org.hogel.pocketssh.shortcuts

/**
 * A user-configurable shortcut button. [label] is the visible text; [payload]
 * is the action specification parsed by [parseShortcutActions].
 */
data class Shortcut(val label: String, val payload: String)

/**
 * A group of shortcuts that activates when the active tmux pane's foreground
 * command (delivered via the OSC window title) matches one of [contexts]
 * (lowercased exact match).
 */
data class ContextGroup(val contexts: List<String>, val shortcuts: List<Shortcut>)

/**
 * Payloads bound to a horizontal swipe on the terminal. Empty string disables
 * that direction. The same payload syntax as keyboard shortcuts applies, plus
 * `{TMUX-PREFIX}` which expands to the user's configured tmux prefix byte.
 */
data class SwipeShortcuts(val left: String, val right: String)
