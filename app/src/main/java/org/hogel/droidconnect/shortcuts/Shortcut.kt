package org.hogel.droidconnect.shortcuts

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
