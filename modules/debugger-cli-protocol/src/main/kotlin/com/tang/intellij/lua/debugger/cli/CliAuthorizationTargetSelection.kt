package com.tang.intellij.lua.debugger.cli

/**
 * Label offered in the CLI authorization dialog. Only the project name is
 * appended here: a target id may already contain parentheses of its own.
 */
fun authorizationChoiceLabel(target: CliTargetSummary): String =
    "${target.targetId} (${target.projectName})"

/**
 * Resolves the target picked in the CLI authorization dialog.
 *
 * Attach sessions build their target id from the run configuration name, and the
 * attach runner sets that name to the process title including "(PID: n)".
 * Stripping a " (...)" suffix from the display string therefore truncates the id
 * to something that was never registered: the grant lands on a phantom target and
 * every later CLI read keeps failing with NOT_AUTHORIZED. Match the offered label
 * exactly instead, and only parse when the user typed a custom value into the
 * editable dialog.
 */
fun resolveAuthorizationTargetId(
    targets: List<CliTargetSummary>,
    selected: String?
): String? {
    if (targets.isEmpty()) return null
    val value = selected?.trim().orEmpty()
    if (value.isEmpty()) return null
    targets.firstOrNull { authorizationChoiceLabel(it) == value }?.let { return it.targetId }
    targets.firstOrNull { it.targetId == value }?.let { return it.targetId }
    val prefixed = targets.filter { it.targetId.startsWith(value) }
    if (prefixed.size == 1) return prefixed.single().targetId
    // Ambiguous or free-form input: keep the typed value so a wrong id fails
    // loudly instead of silently granting a guessed target.
    return value
}
