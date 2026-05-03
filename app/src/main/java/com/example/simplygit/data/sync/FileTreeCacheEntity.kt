package com.example.simplygit.data.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Row shape for the `file_tree_cache` table (SPEC §4.1.1 / §6.1 Iteration 3).
 *
 * Primary key: (`repo_id`, `path`) — path is the repo-root-relative Unix-style
 * path ("" for the root node). Upsert on `(repoId, path)` is the only legal
 * write path so concurrent rescans converge to the same snapshot.
 *
 * [gitStatus] is the **aggregated** status (parent dir inherits subtree max
 * priority). The on-disk file's own status is not kept separately; when the
 * node type is `FILE`, [gitStatus] IS its own status.
 */
@Entity(
    tableName = "file_tree_cache",
    primaryKeys = ["repo_id", "path"],
    foreignKeys = [
        ForeignKey(
            entity = RepositoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["repo_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("repo_id"),
        Index(value = ["repo_id", "parent_path"]),
    ],
)
data class FileTreeCacheEntity(
    @ColumnInfo(name = "repo_id") val repoId: Long,
    val path: String,
    @ColumnInfo(name = "parent_path") val parentPath: String,
    /** `FileType.name` — `FILE` | `DIR`. */
    val type: String,
    /** `GitFileStatus.name` — aggregated status for directories. */
    @ColumnInfo(name = "git_status") val gitStatus: String,
    val size: Long,
    @ColumnInfo(name = "last_modified") val lastModified: Long,
)
