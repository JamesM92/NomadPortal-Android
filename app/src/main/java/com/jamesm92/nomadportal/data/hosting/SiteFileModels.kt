package com.jamesm92.nomadportal.data.hosting

/**
 * One entry in the hosted node's pages directory — either a `.mu` page
 * or a folder (folders have no extension concept; every non-directory
 * entry that can exist here is a `.mu` page, enforced at creation/
 * rename time by `orchestrator.py`'s own doc comment on why "no Python/
 * executables" is enforced at the point content is created, not only
 * at the serving layer).
 */
data class SiteFileEntry(
    val name: String,
    /** Relative to the pages root, forward-slash separated regardless
     * of host OS — this app's own join convention (see
     * `list_site_pages_json`'s doc comment), not the device's path
     * separator. */
    val path: String,
    val isDirectory: Boolean,
)
