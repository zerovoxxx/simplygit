package com.example.simplygit.data.git

/**
 * Every exception surfaced to UI / logs goes through [JGitExceptionSanitizer] and is
 * re-wrapped as this type (SPEC §4.4). The original class name is preserved in
 * [originalType] for local debugging.
 */
class SanitizedGitException(
    message: String,
    val originalType: String,
) : RuntimeException(message)
