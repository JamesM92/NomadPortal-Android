package com.jamesm92.nomadportal.data.hosting

/**
 * Manages the hosted node's pages directory — the "file nav" for a
 * multi-page site (see the nomadportal-android-hosted-node memory for
 * the full phased design). Deliberately plain `suspend fun`s, not a
 * `Flow`: nothing outside this app's own actions ever mutates this
 * directory, so there's nothing to poll for — a caller re-lists after
 * each mutating call instead (same reasoning as
 * `list_site_pages_json`'s own doc comment on the Python side).
 *
 * Every path here is relative to the pages root and forward-slash
 * separated (this app's own convention, not the device's path
 * separator) — never an absolute on-device path, unlike
 * [com.jamesm92.nomadportal.data.messaging.Attachment.path] (that one's
 * a real file Kotlin reads directly; this app's own content is always
 * mediated through these calls instead, so a raw path was never
 * needed).
 */
interface SiteFileRepository {
    /** Immediate children of [path] only (not a recursive tree) — "" for the root. */
    suspend fun listEntries(path: String): List<SiteFileEntry>

    /** [path] must end in ".mu". False (with no further detail — see
     * each implementation for how failures surface) if it already
     * exists or the path is otherwise invalid. */
    suspend fun createPage(path: String): Boolean

    suspend fun createFolder(path: String): Boolean

    /** Also how an entry is *moved* — a move is a rename to a path
     * under a different parent, no separate operation. Renaming a page
     * to something not ending in ".mu" fails. */
    suspend fun rename(oldPath: String, newPath: String): Boolean

    /** Deletes a page, or a folder and everything inside it — callers
     * must confirm before calling this, same destructive-action
     * convention as this app's other delete flows (panic wipe,
     * deleting a chat). */
    suspend fun delete(path: String): Boolean

    /** Null (not blank) if [path] doesn't resolve to a real page —
     * blank is a legitimate empty-page's actual content. */
    suspend fun readPage(path: String): String?

    /** [path] must end in ".mu". */
    suspend fun writePage(path: String, content: String): Boolean
}
