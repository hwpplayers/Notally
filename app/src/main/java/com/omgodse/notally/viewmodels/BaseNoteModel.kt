package com.omgodse.notally.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.core.content.edit
import androidx.core.text.HtmlCompat
import androidx.core.text.toHtml
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.omgodse.notally.R
import com.omgodse.notally.helpers.SettingsHelper
import com.omgodse.notally.miscellaneous.applySpans
import com.omgodse.notally.miscellaneous.getBody
import com.omgodse.notally.miscellaneous.getLocale
import com.omgodse.notally.room.*
import com.omgodse.notally.room.livedata.Content
import com.omgodse.notally.room.livedata.SearchResult
import com.omgodse.notally.xml.Backup
import com.omgodse.notally.xml.XMLUtils
import com.omgodse.post.PostPDFGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat

class BaseNoteModel(private val app: Application) : AndroidViewModel(app) {

    private val database = NotallyDatabase.getDatabase(app)
    private val labelDao = database.labelDao
    private val commonDao = database.commonDao
    private val baseNoteDao = database.baseNoteDao

    private val labelCache = HashMap<String, Content<BaseNote>>()

    private val settingsHelper = SettingsHelper(app)
    var currentFile: File? = null

    val labels = Content(labelDao.getAllLabels())
    val baseNotes = Content(baseNoteDao.getAllBaseNotes())
    val deletedNotes = Content(baseNoteDao.getAllDeletedNotes())
    val archivedNotes = Content(baseNoteDao.getAllArchivedNotes())

    var keyword = String()
        set(value) {
            if (field != value) {
                field = value
                searchResults.fetch(value)
            }
        }

    val searchResults = SearchResult(viewModelScope, baseNoteDao)

    init {
        viewModelScope.launch {
            val previousNotes = getPreviousNotes()
            val previousLabels = getPreviousLabels()
            val delete: (file: File) -> Unit = { file: File -> file.delete() }
            if (previousNotes.isNotEmpty() || previousLabels.isNotEmpty()) {
                database.withTransaction {
                    labelDao.insertLabels(previousLabels)
                    baseNoteDao.insertBaseNotes(previousNotes)
                    getNotePath().listFiles()?.forEach(delete)
                    getDeletedPath().listFiles()?.forEach(delete)
                    getArchivedPath().listFiles()?.forEach(delete)
                    getLabelsPreferences().edit(true) { clear() }
                }
            }
        }
    }

    fun getNotesByLabel(label: String): Content<BaseNote> {
        if (labelCache[label] == null) {
            labelCache[label] = Content(commonDao.getBaseNotesByLabel(label))
        }
        return requireNotNull(labelCache[label])
    }


    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val labels = labelDao.getAllLabelsAsList().toHashSet()
                val baseNotes = baseNoteDao.getAllBaseNotesAsList()
                val deletedNotes = baseNoteDao.getAllDeletedNotesAsList()
                val archivedNotes = baseNoteDao.getAllArchivedNotesAsList()

                val backup = Backup(baseNotes, deletedNotes, archivedNotes, labels)

                (app.contentResolver.openOutputStream(uri) as? FileOutputStream)?.use { stream ->
                    stream.channel.truncate(0)
                    backup.writeToStream(stream)
                }
            }
            Toast.makeText(app, R.string.saved_to_device, Toast.LENGTH_LONG).show()
        }
    }

    fun importBackup(uri: Uri) {
        executeAsync {
            app.contentResolver.openInputStream(uri)?.use { stream ->
                val backup = Backup.readFromStream(stream)

                val list = backup.baseNotes.toMutableList()
                list.addAll(backup.deletedNotes)
                list.addAll(backup.archivedNotes)

                val labels = backup.labels.map { label -> Label(label) }

                baseNoteDao.insertBaseNotes(list)
                labelDao.insertLabels(labels)
            }
        }
    }

    fun writeCurrentFileToUri(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                (app.contentResolver.openOutputStream(uri) as? FileOutputStream)?.use { stream ->
                    stream.channel.truncate(0)
                    stream.write(requireNotNull(currentFile).readBytes())
                }
            }
            Toast.makeText(app, R.string.saved_to_device, Toast.LENGTH_LONG).show()
        }
    }


    suspend fun getHTMLFile(baseNote: BaseNote) = withContext(Dispatchers.IO) {
        val fileName = getFileName(baseNote)
        val file = File(getExportedPath(), "$fileName.html")
        val content = getHTML(baseNote)
        file.writeText(content)
        file
    }

    suspend fun getPlainTextFile(baseNote: BaseNote) = withContext(Dispatchers.IO) {
        val fileName = getFileName(baseNote)
        val file = File(getExportedPath(), "$fileName.txt")
        val content = getPlainText(baseNote)
        file.writeText(content)
        file
    }

    fun getPDFFile(baseNote: BaseNote, result: PostPDFGenerator.OnResult) {
        val fileName = getFileName(baseNote)
        val pdfFile = File(getExportedPath(), "$fileName.pdf")

        val html = getHTML(baseNote)

        PostPDFGenerator.Builder()
            .setFile(pdfFile)
            .setContent(html)
            .setContext(app)
            .setOnResult(result)
            .build()
            .create()
    }


    fun deleteLabel(label: Label) = executeAsync { commonDao.deleteLabel(label) }

    fun restoreBaseNote(id: Long) = executeAsync { baseNoteDao.restoreBaseNote(id) }

    fun moveBaseNoteToDeleted(id: Long) = executeAsync { baseNoteDao.moveBaseNoteToDeleted(id) }

    fun moveBaseNoteToArchive(id: Long) = executeAsync { baseNoteDao.moveBaseNoteToArchive(id) }

    fun deleteBaseNoteForever(baseNote: BaseNote) = executeAsync { baseNoteDao.deleteBaseNote(baseNote) }

    fun updateBaseNoteLabels(labels: HashSet<String>, id: Long) = executeAsync { baseNoteDao.updateBaseNoteLabels(labels, id) }


    suspend fun getAllLabelsAsList() = withContext(Dispatchers.IO) { labelDao.getAllLabelsAsList() }

    fun insertLabel(label: Label, onComplete: (success: Boolean) -> Unit) = executeAsyncWithCallback({ labelDao.insertLabel(label) }, onComplete)

    fun updateLabel(oldValue: String, newValue: String, onComplete: (success: Boolean) -> Unit) = executeAsyncWithCallback({ commonDao.updateLabel(oldValue, newValue) }, onComplete)


    private fun getExportedPath(): File {
        val filePath = File(app.cacheDir, "exported")
        if (!filePath.exists()) {
            filePath.mkdir()
        }
        filePath.listFiles()?.forEach { file -> file.delete() }
        return filePath
    }

    private fun getFileName(baseNote: BaseNote): String {
        val title = baseNote.title
        val body = when (baseNote.type) {
            Type.NOTE -> baseNote.body
            Type.LIST -> baseNote.items.getBody()
        }
        val fileName = if (title.isEmpty()) {
            val words = body.split(" ").take(2)
            buildString {
                words.forEach {
                    append(it)
                    append(" ")
                }
            }
        } else title
        return fileName.take(64).replace("/", "")
    }

    private fun getHTML(baseNote: BaseNote) = buildString {
        val formatter = SimpleDateFormat(DateFormat, app.getLocale())
        val date = formatter.format(baseNote.timestamp)

        append("<html><head><meta charset=\"UTF-8\" /></head><body>")
        append("<h2>${baseNote.title}</h2>")

        if (settingsHelper.getShowDateCreated()) {
            append("<p>$date</p>")
        }

        when (baseNote.type) {
            Type.NOTE -> {
                val body = baseNote.body.applySpans(baseNote.spans).toHtml(HtmlCompat.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE)
                append("<p>$body</p>")
            }
            Type.LIST -> {
                append("<ol>")
                baseNote.items.forEach {
                    append("<li>${it.body}</li>")
                }
                append("</ol>")
            }
        }
        append("</body></html")
    }

    private fun getPlainText(baseNote: BaseNote) = buildString {
        val formatter = SimpleDateFormat(DateFormat, app.getLocale())
        val date = formatter.format(baseNote.timestamp)

        val body = when (baseNote.type) {
            Type.NOTE -> baseNote.body
            Type.LIST -> baseNote.items.getBody()
        }

        if (baseNote.title.isNotEmpty()) {
            append("${baseNote.title}\n\n")
        }
        if (settingsHelper.getShowDateCreated()) {
            append("$date\n\n")
        }
        append(body)
    }


    private fun getPreviousNotes(): List<BaseNote> {
        val previousNotes = mutableListOf<BaseNote>()
        getNotePath().listFiles()?.mapTo(previousNotes, { file -> XMLUtils.readBaseNoteFromFile(file, Folder.NOTES) })
        getDeletedPath().listFiles()?.mapTo(previousNotes, { file -> XMLUtils.readBaseNoteFromFile(file, Folder.DELETED) })
        getArchivedPath().listFiles()?.mapTo(previousNotes, { file -> XMLUtils.readBaseNoteFromFile(file, Folder.ARCHIVED) })
        return previousNotes
    }

    private fun getPreviousLabels(): List<Label> {
        val labels = getLabelsPreferences().getStringSet("labelItems", emptySet()) ?: emptySet()
        return labels.map { value -> Label(value) }
    }


    private fun getNotePath() = getFolder("notes")

    private fun getDeletedPath() = getFolder("deleted")

    private fun getArchivedPath() = getFolder("archived")

    private fun getFolder(name: String): File {
        val folder = File(app.filesDir, name)
        if (!folder.exists()) {
            folder.mkdir()
        }
        return folder
    }

    private fun getLabelsPreferences() = app.getSharedPreferences("labelsPreferences", Context.MODE_PRIVATE)


    private fun ViewModel.executeAsync(function: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { function() }
        }
    }

    companion object {
        const val DateFormat = "EEE d MMM yyyy"
    }
}