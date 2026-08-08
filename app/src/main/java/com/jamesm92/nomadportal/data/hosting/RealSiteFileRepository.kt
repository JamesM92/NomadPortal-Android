package com.jamesm92.nomadportal.data.hosting

import com.chaquo.python.Python
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real [SiteFileRepository], backed by `nomadportal_core.orchestrator`'s
 * page-management bridge functions — same JSON-string rationale as
 * [com.jamesm92.nomadportal.data.browsing.RealBrowserRepository]
 * (structured results go over as JSON, not a raw Python tuple/dict —
 * this module's own established convention).
 */
class RealSiteFileRepository : SiteFileRepository {
    private val orchestrator by lazy {
        Python.getInstance().getModule("nomadportal_core.orchestrator")
    }

    override suspend fun listEntries(path: String): List<SiteFileEntry> = withContext(Dispatchers.IO) {
        val array = JSONArray(orchestrator.callAttr("list_site_pages_json", path).toString())
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            SiteFileEntry(
                name = obj.getString("name"),
                path = obj.getString("path"),
                isDirectory = obj.getBoolean("is_directory"),
            )
        }
    }

    override suspend fun createPage(path: String): Boolean = fileOp("create_site_page", path)

    override suspend fun rename(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        JSONObject(orchestrator.callAttr("rename_site_entry", oldPath, newPath).toString())
            .optBoolean("success", false)
    }

    override suspend fun delete(path: String): Boolean = fileOp("delete_site_entry", path)

    override suspend fun readPage(path: String): String? = withContext(Dispatchers.IO) {
        val obj = JSONObject(orchestrator.callAttr("read_site_page_json", path).toString())
        if (obj.isNull("content")) null else obj.getString("content")
    }

    override suspend fun writePage(path: String, content: String): Boolean = withContext(Dispatchers.IO) {
        JSONObject(orchestrator.callAttr("write_site_page", path, content).toString())
            .optBoolean("success", false)
    }

    /** Shared shape for the four single-path [FileOpResult]-returning
     * bridge functions (create/delete — rename takes two paths, handled
     * separately above). */
    private suspend fun fileOp(function: String, path: String): Boolean = withContext(Dispatchers.IO) {
        JSONObject(orchestrator.callAttr(function, path).toString()).optBoolean("success", false)
    }
}
