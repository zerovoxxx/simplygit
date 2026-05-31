package com.example.simplygit.domain.model

/**
 * File-tree node kind (SPEC §4.1.1 Iteration 3).
 *
 * Persisted as enum `.name` in the `file_tree_cache.type` TEXT column.
 */
enum class FileType {
    FILE,
    DIR,
}
