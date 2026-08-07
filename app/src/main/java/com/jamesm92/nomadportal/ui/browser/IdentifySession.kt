package com.jamesm92.nomadportal.ui.browser

/**
 * Session-scoped state for [BrowserScreen]'s "identify to this node"
 * toggle (porting-notes.md §4: "address bar with a 'fingerprint'
 * (persistent identify-to-this-node) toggle separate from anonymous
 * browsing" — the field name/UI is what actually got misbuilt earlier as
 * a hash-viewer popup instead; this is the real feature).
 *
 * Deliberately **not** DataStore-persisted, per explicit design
 * direction: the toggle should survive navigating between nodes and
 * back within one sitting, but reset to fully anonymous the moment the
 * app is closed (a plain `object` singleton already gives that for
 * free — process death wipes it) or once [IDLE_TIMEOUT_MILLIS] of
 * inactivity has passed, whichever comes first. The idle check happens
 * lazily on read (no background timer/service needed) — any call to
 * [isIdentified] or [setIdentified] first checks whether the session
 * has gone stale and clears everything if so.
 */
object IdentifySession {
    private const val IDLE_TIMEOUT_MILLIS = 15 * 60 * 1000L

    private val identifiedNodes = mutableSetOf<String>()
    private var lastActivityMillis = 0L

    @Synchronized
    fun isIdentified(nodeHash: String): Boolean {
        expireIfIdle()
        return nodeHash in identifiedNodes
    }

    @Synchronized
    fun setIdentified(nodeHash: String, identified: Boolean) {
        expireIfIdle()
        if (identified) identifiedNodes.add(nodeHash) else identifiedNodes.remove(nodeHash)
        lastActivityMillis = System.currentTimeMillis()
    }

    private fun expireIfIdle() {
        val now = System.currentTimeMillis()
        if (lastActivityMillis != 0L && now - lastActivityMillis > IDLE_TIMEOUT_MILLIS) {
            identifiedNodes.clear()
        }
        lastActivityMillis = now
    }
}
