package net.frju.flym.utils

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor.moveToFirst
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.rometools.opml.feed.opml.Attribute
import com.rometools.opml.feed.opml.Opml
import com.rometools.opml.feed.opml.Outline
import com.rometools.opml.io.impl.OPML20Generator
import com.rometools.rome.io.WireFeedInput
import com.rometools.rome.io.WireFeedOutput
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.dialog_edit_feed.view.*
import kotlinx.android.synthetic.main.fragment_entries.*
import kotlinx.android.synthetic.main.view_main_containers.*
import kotlinx.android.synthetic.main.view_main_drawer_header.*
import net.fred.feedex.R
import net.frju.flym.App
import net.frju.flym.data.entities.Feed
import net.frju.flym.data.entities.FeedWithCount
import net.frju.flym.data.utils.PrefConstants
import net.frju.flym.service.AutoRefreshJobService
import net.frju.flym.service.FetcherService
import net.frju.flym.ui.about.AboutActivity
import net.frju.flym.ui.entries.EntriesFragment
import net.frju.flym.ui.entrydetails.EntryDetailsActivity
import net.frju.flym.ui.entrydetails.EntryDetailsFragment
import net.frju.flym.ui.feeds.FeedAdapter
import net.frju.flym.ui.feeds.FeedGroup
import net.frju.flym.ui.feeds.FeedListEditActivity
import net.frju.flym.ui.settings.SettingsActivity
import net.frju.flym.utils.closeKeyboard
import net.frju.flym.utils.getPrefBoolean
import net.frju.flym.utils.putPrefBoolean
import net.frju.flym.utils.setupNoActionBarTheme
import org.jetbrains.anko.*
import org.jetbrains.anko.sdk21.listeners.onClick
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.EasyPermissions
import java.io.*
import java.net.URL
import java.util.*

object OpmlUtils {

    private const val AUTO_IMPORT_OPML_REQUEST_CODE = 1
    private const val WRITE_OPML_REQUEST_CODE = 2
    private const val READ_OPML_REQUEST_CODE = 3
    private val NEEDED_PERMS = arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    private val BACKUP_OPML = File(Environment.getExternalStorageDirectory(), "/Flym_auto_backup.opml")
    private const val RETRIEVE_FULLTEXT_OPML_ATTR = "retrieveFullText"

    fun pickOpml() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*" // https://github.com/FredJul/Flym/issues/407
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(intent, READ_OPML_REQUEST_CODE)
    }

    fun exportOpml() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = "text/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_TITLE, "Flym_" + System.currentTimeMillis() + ".opml")
        }
        startActivityForResult(intent, WRITE_OPML_REQUEST_CODE)
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (requestCode == READ_OPML_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            resultData?.data?.also { uri -> importOpml(uri) }
        } else if (requestCode == WRITE_OPML_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            resultData?.data?.also { uri -> exportOpml(uri) }
        }
    }

    @AfterPermissionGranted(AUTO_IMPORT_OPML_REQUEST_CODE)
    private fun autoImportOpml(context: Context) {
        if (!EasyPermissions.hasPermissions(context, *NEEDED_PERMS)) {
            EasyPermissions.requestPermissions(context, getString(R.string.welcome_title_with_opml_import), AUTO_IMPORT_OPML_REQUEST_CODE, *NEEDED_PERMS)
        } else {
            if (BACKUP_OPML.exists()) {
                importOpml(Uri.fromFile(BACKUP_OPML))
            } else {
                toast(R.string.cannot_find_feeds)
            }
        }
    }

    private fun importOpml(uri: Uri) {
        doAsync {
            try {
                InputStreamReader(contentResolver.openInputStream(uri)).use { reader -> parseOpml(reader) }
            } catch (e: Exception) {
                try {
                    // We try to remove the opml version number, it may work better in some cases
                    val content = BufferedInputStream(contentResolver.openInputStream(uri)).bufferedReader().use { it.readText() }
                    val fixedReader = StringReader(content.replace("<opml version=['\"][0-9]\\.[0-9]['\"]>".toRegex(), "<opml>"))
                    parseOpml(fixedReader)
                } catch (e: Exception) {
                    uiThread { toast(R.string.cannot_find_feeds) }
                }
            }
        }
    }

    private fun exportOpml(uri: Uri) {
        doAsync {
            try {
                OutputStreamWriter(contentResolver.openOutputStream(uri), Charsets.UTF_8).use { writer -> exportOpml(writer) }
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val fileName = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                        uiThread { toast(String.format(getString(R.string.message_exported_to), fileName)) }
                    }
                }
            } catch (e: Exception) {
                uiThread { toast(R.string.error_feed_export) }
            }
        }
    }

    private fun parseOpml(opmlReader: Reader) {
        var genId = 1L
        val feedList = mutableListOf<Feed>()
        val opml = WireFeedInput().build(opmlReader) as Opml
        opml.outlines.forEach { outline ->
            if (outline.xmlUrl != null || outline.children.isNotEmpty()) {
                val topLevelFeed = Feed().apply {
                    id = genId++
                    title = outline.title
                }

                if (outline.xmlUrl != null) {
                    if (!outline.xmlUrl.startsWith(OLD_GNEWS_TO_IGNORE)) {
                        topLevelFeed.link = outline.xmlUrl
                        topLevelFeed.retrieveFullText = outline.getAttributeValue(RETRIEVE_FULLTEXT_OPML_ATTR) == "true"
                        feedList.add(topLevelFeed)
                    }
                } else {
                    topLevelFeed.isGroup = true
                    feedList.add(topLevelFeed)

                    outline.children.filter { it.xmlUrl != null && !it.xmlUrl.startsWith(OLD_GNEWS_TO_IGNORE) }.forEach {
                        val subLevelFeed = Feed().apply {
                            id = genId++
                            title = it.title
                            link = it.xmlUrl
                            retrieveFullText = it.getAttributeValue(RETRIEVE_FULLTEXT_OPML_ATTR) == "true"
                            groupId = topLevelFeed.id
                        }

                        feedList.add(subLevelFeed)
                    }
                }
            }
        }

        if (feedList.isNotEmpty()) {
            App.db.feedDao().insert(*feedList.toTypedArray())
        }
    }

    private fun exportOpml(opmlWriter: Writer) {
        val feeds = App.db.feedDao().all.groupBy { it.groupId }

        val opml = Opml().apply {
            feedType = OPML20Generator().type
            encoding = "utf-8"
            created = Date()
            outlines = feeds[null]?.map { feed ->
                Outline(feed.title, if (feed.link.isNotBlank()) URL(feed.link) else null, null).apply {
                    children = feeds[feed.id]?.map {
                        Outline(it.title, if (it.link.isNotBlank()) URL(it.link) else null, null).apply {
                            if (it.retrieveFullText) {
                                attributes.add(Attribute(RETRIEVE_FULLTEXT_OPML_ATTR, "true"))
                            }
                        }
                    }
                    if (feed.retrieveFullText) {
                        attributes.add(Attribute(RETRIEVE_FULLTEXT_OPML_ATTR, "true"))
                    }
                }
            }
        }

        WireFeedOutput().output(opml, opmlWriter)
    }
}