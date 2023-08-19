package com.lagradost.cloudstream3.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.bumptech.glide.load.model.GlideUrl
import com.fasterxml.jackson.annotation.JsonProperty
import com.hippo.unifile.UniFile
import com.lagradost.cloudstream3.APIHolder.getApiFromNameNull
import com.lagradost.cloudstream3.AcraApplication.Companion.removeKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.services.VideoDownloadService
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import com.lagradost.cloudstream3.utils.DataStore.getKey
import com.lagradost.cloudstream3.utils.DataStore.removeKey
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.internal.closeQuietly
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URL
import java.util.*

const val DOWNLOAD_CHANNEL_ID = "cloudstream3.general"
const val DOWNLOAD_CHANNEL_NAME = "Downloads"
const val DOWNLOAD_CHANNEL_DESCRIPT = "The download notification channel"

object VideoDownloadManager {
    var maxConcurrentDownloads = 3
    private var currentDownloads = mutableListOf<Int>()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

    @get:DrawableRes
    val imgDone get() = R.drawable.rddone

    @get:DrawableRes
    val imgDownloading get() = R.drawable.rdload

    @get:DrawableRes
    val imgPaused get() = R.drawable.rdpause

    @get:DrawableRes
    val imgStopped get() = R.drawable.rderror

    @get:DrawableRes
    val imgError get() = R.drawable.rderror

    @get:DrawableRes
    val pressToPauseIcon get() = R.drawable.ic_baseline_pause_24

    @get:DrawableRes
    val pressToResumeIcon get() = R.drawable.ic_baseline_play_arrow_24

    @get:DrawableRes
    val pressToStopIcon get() = R.drawable.baseline_stop_24

    enum class DownloadType {
        IsPaused,
        IsDownloading,
        IsDone,
        IsFailed,
        IsStopped,
        IsPending
    }

    enum class DownloadActionType {
        Pause,
        Resume,
        Stop,
    }

    interface IDownloadableMinimum {
        val url: String
        val referer: String
        val headers: Map<String, String>
    }

    fun IDownloadableMinimum.getId(): Int {
        return url.hashCode()
    }

    data class DownloadEpisodeMetadata(
        @JsonProperty("id") val id: Int,
        @JsonProperty("mainName") val mainName: String,
        @JsonProperty("sourceApiName") val sourceApiName: String?,
        @JsonProperty("poster") val poster: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("season") val season: Int?,
        @JsonProperty("episode") val episode: Int?,
        @JsonProperty("type") val type: TvType?,
    )

    data class DownloadItem(
        @JsonProperty("source") val source: String?,
        @JsonProperty("folder") val folder: String?,
        @JsonProperty("ep") val ep: DownloadEpisodeMetadata,
        @JsonProperty("links") val links: List<ExtractorLink>,
    )

    data class DownloadResumePackage(
        @JsonProperty("item") val item: DownloadItem,
        @JsonProperty("linkIndex") val linkIndex: Int?,
    )

    data class DownloadedFileInfo(
        @JsonProperty("totalBytes") val totalBytes: Long,
        @JsonProperty("relativePath") val relativePath: String,
        @JsonProperty("displayName") val displayName: String,
        @JsonProperty("extraInfo") val extraInfo: String? = null,
        @JsonProperty("basePath") val basePath: String? = null // null is for legacy downloads. See getDefaultPath()
    )

    data class DownloadedFileInfoResult(
        @JsonProperty("fileLength") val fileLength: Long,
        @JsonProperty("totalBytes") val totalBytes: Long,
        @JsonProperty("path") val path: Uri,
    )

    data class DownloadQueueResumePackage(
        @JsonProperty("index") val index: Int,
        @JsonProperty("pkg") val pkg: DownloadResumePackage,
    )

    private const val SUCCESS_DOWNLOAD_DONE = 1
    private const val SUCCESS_STREAM = 3
    private const val SUCCESS_STOPPED = 2

    // will not download the next one, but is still classified as an error
    private const val ERROR_DELETING_FILE = 3
    private const val ERROR_CREATE_FILE = -2
    private const val ERROR_UNKNOWN = -10

    //private const val ERROR_OPEN_FILE = -3
    private const val ERROR_TOO_SMALL_CONNECTION = -4

    //private const val ERROR_WRONG_CONTENT = -5
    private const val ERROR_CONNECTION_ERROR = -6

    //private const val ERROR_MEDIA_STORE_URI_CANT_BE_CREATED = -7
    //private const val ERROR_CONTENT_RESOLVER_CANT_OPEN_STREAM = -8
    private const val ERROR_CONTENT_RESOLVER_NOT_FOUND = -9

    private const val KEY_RESUME_PACKAGES = "download_resume"
    const val KEY_DOWNLOAD_INFO = "download_info"
    private const val KEY_RESUME_QUEUE_PACKAGES = "download_q_resume"

    val downloadStatus = HashMap<Int, DownloadType>()
    val downloadStatusEvent = Event<Pair<Int, DownloadType>>()
    val downloadDeleteEvent = Event<Int>()
    val downloadEvent = Event<Pair<Int, DownloadActionType>>()
    val downloadProgressEvent = Event<Triple<Int, Long, Long>>()
    val downloadQueue = LinkedList<DownloadResumePackage>()

    private var hasCreatedNotChanel = false
    private fun Context.createNotificationChannel() {
        hasCreatedNotChanel = true
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = DOWNLOAD_CHANNEL_NAME //getString(R.string.channel_name)
            val descriptionText = DOWNLOAD_CHANNEL_DESCRIPT//getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(DOWNLOAD_CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            // Register the channel with the system
            val notificationManager: NotificationManager =
                this.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /** Will return IsDone if not found or error */
    fun getDownloadState(id: Int): DownloadType {
        return try {
            downloadStatus[id] ?: DownloadType.IsDone
        } catch (e: Exception) {
            logError(e)
            DownloadType.IsDone
        }
    }

    private val cachedBitmaps = hashMapOf<String, Bitmap>()
    fun Context.getImageBitmapFromUrl(url: String, headers: Map<String, String>? = null): Bitmap? {
        try {
            if (cachedBitmaps.containsKey(url)) {
                return cachedBitmaps[url]
            }

            val bitmap = GlideApp.with(this)
                .asBitmap()
                .load(GlideUrl(url) { headers ?: emptyMap() })
                .into(720, 720)
                .get()

            if (bitmap != null) {
                cachedBitmaps[url] = bitmap
            }
            return bitmap
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    /**
     * @param hlsProgress will together with hlsTotal display another notification if used, to lessen the confusion about estimated size.
     * */
    private suspend fun createNotification(
        context: Context,
        source: String?,
        linkName: String?,
        ep: DownloadEpisodeMetadata,
        state: DownloadType,
        progress: Long,
        total: Long,
        notificationCallback: (Int, Notification) -> Unit,
        hlsProgress: Long? = null,
        hlsTotal: Long? = null
    ): Notification? {
        try {
            if (total <= 0) return null// crash, invalid data

//        main { // DON'T WANT TO SLOW IT DOWN
            val builder = NotificationCompat.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setAutoCancel(true)
                .setColorized(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setColor(context.colorFromAttribute(R.attr.colorPrimary))
                .setContentTitle(ep.mainName)
                .setSmallIcon(
                    when (state) {
                        DownloadType.IsDone -> imgDone
                        DownloadType.IsDownloading -> imgDownloading
                        DownloadType.IsPaused -> imgPaused
                        DownloadType.IsFailed -> imgError
                        DownloadType.IsStopped -> imgStopped
                        DownloadType.IsPending -> imgDownloading
                    }
                )

            if (ep.sourceApiName != null) {
                builder.setSubText(ep.sourceApiName)
            }

            if (source != null) {
                val intent = Intent(context, MainActivity::class.java).apply {
                    data = source.toUri()
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent: PendingIntent =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                    } else {
                        PendingIntent.getActivity(context, 0, intent, 0)
                    }
                builder.setContentIntent(pendingIntent)
            }

            if (state == DownloadType.IsDownloading || state == DownloadType.IsPaused) {
                builder.setProgress((total / 1000).toInt(), (progress / 1000).toInt(), false)
            }

            val rowTwoExtra = if (ep.name != null) " - ${ep.name}\n" else ""
            val rowTwo = if (ep.season != null && ep.episode != null) {
                "${context.getString(R.string.season_short)}${ep.season}:${context.getString(R.string.episode_short)}${ep.episode}" + rowTwoExtra
            } else if (ep.episode != null) {
                "${context.getString(R.string.episode)} ${ep.episode}" + rowTwoExtra
            } else {
                (ep.name ?: "") + ""
            }
            val downloadFormat = context.getString(R.string.download_format)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ep.poster != null) {
                    val poster = withContext(Dispatchers.IO) {
                        context.getImageBitmapFromUrl(ep.poster)
                    }
                    if (poster != null)
                        builder.setLargeIcon(poster)
                }

                val progressPercentage: Long
                val progressMbString: String
                val totalMbString: String
                val suffix: String

                if (hlsProgress != null && hlsTotal != null) {
                    progressPercentage = hlsProgress.toLong() * 100 / hlsTotal
                    progressMbString = hlsProgress.toString()
                    totalMbString = hlsTotal.toString()
                    suffix = " - %.1f MB".format(progress / 1000000f)
                } else {
                    progressPercentage = progress * 100 / total
                    progressMbString = "%.1f MB".format(progress / 1000000f)
                    totalMbString = "%.1f MB".format(total / 1000000f)
                    suffix = ""
                }

                val bigText =
                    when (state) {
                        DownloadType.IsDownloading, DownloadType.IsPaused -> {
                            (if (linkName == null) "" else "$linkName\n") + "$rowTwo\n$progressPercentage % ($progressMbString/$totalMbString)$suffix"
                        }

                        DownloadType.IsFailed -> {
                            downloadFormat.format(
                                context.getString(R.string.download_failed),
                                rowTwo
                            )
                        }

                        DownloadType.IsDone -> {
                            downloadFormat.format(context.getString(R.string.download_done), rowTwo)
                        }

                        else -> {
                            downloadFormat.format(
                                context.getString(R.string.download_canceled),
                                rowTwo
                            )
                        }
                    }

                val bodyStyle = NotificationCompat.BigTextStyle()
                bodyStyle.bigText(bigText)
                builder.setStyle(bodyStyle)
            } else {
                val txt =
                    when (state) {
                        DownloadType.IsDownloading, DownloadType.IsPaused -> {
                            rowTwo
                        }

                        DownloadType.IsFailed -> {
                            downloadFormat.format(
                                context.getString(R.string.download_failed),
                                rowTwo
                            )
                        }

                        DownloadType.IsDone -> {
                            downloadFormat.format(context.getString(R.string.download_done), rowTwo)
                        }

                        else -> {
                            downloadFormat.format(
                                context.getString(R.string.download_canceled),
                                rowTwo
                            )
                        }
                    }

                builder.setContentText(txt)
            }

            if ((state == DownloadType.IsDownloading || state == DownloadType.IsPaused) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val actionTypes: MutableList<DownloadActionType> = ArrayList()
                // INIT
                if (state == DownloadType.IsDownloading) {
                    actionTypes.add(DownloadActionType.Pause)
                    actionTypes.add(DownloadActionType.Stop)
                }

                if (state == DownloadType.IsPaused) {
                    actionTypes.add(DownloadActionType.Resume)
                    actionTypes.add(DownloadActionType.Stop)
                }

                // ADD ACTIONS
                for ((index, i) in actionTypes.withIndex()) {
                    val actionResultIntent = Intent(context, VideoDownloadService::class.java)

                    actionResultIntent.putExtra(
                        "type", when (i) {
                            DownloadActionType.Resume -> "resume"
                            DownloadActionType.Pause -> "pause"
                            DownloadActionType.Stop -> "stop"
                        }
                    )

                    actionResultIntent.putExtra("id", ep.id)

                    val pending: PendingIntent = PendingIntent.getService(
                        // BECAUSE episodes lying near will have the same id +1, index will give the same requested as the previous episode, *100000 fixes this
                        context, (4337 + index * 1000000 + ep.id),
                        actionResultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    builder.addAction(
                        NotificationCompat.Action(
                            when (i) {
                                DownloadActionType.Resume -> pressToResumeIcon
                                DownloadActionType.Pause -> pressToPauseIcon
                                DownloadActionType.Stop -> pressToStopIcon
                            }, when (i) {
                                DownloadActionType.Resume -> context.getString(R.string.resume)
                                DownloadActionType.Pause -> context.getString(R.string.pause)
                                DownloadActionType.Stop -> context.getString(R.string.cancel)
                            }, pending
                        )
                    )
                }
            }

            if (!hasCreatedNotChanel) {
                context.createNotificationChannel()
            }

            val notification = builder.build()
            notificationCallback(ep.id, notification)
            with(NotificationManagerCompat.from(context)) {
                // notificationId is a unique int for each notification that you must define
                notify(ep.id, notification)
            }
            return notification
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    private const val reservedChars = "|\\?*<\":>+[]/\'"
    fun sanitizeFilename(name: String, removeSpaces: Boolean = false): String {
        var tempName = name
        for (c in reservedChars) {
            tempName = tempName.replace(c, ' ')
        }
        if (removeSpaces) tempName = tempName.replace(" ", "")
        return tempName.replace("  ", " ").trim(' ')
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ContentResolver.getExistingFolderStartName(relativePath: String): List<Pair<String, Uri>>? {
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,   // unused (for verification use only)
                //MediaStore.MediaColumns.RELATIVE_PATH,  // unused (for verification use only)
            )

            val selection =
                "${MediaStore.MediaColumns.RELATIVE_PATH}='$relativePath'"

            val result = this.query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                projection, selection, null, null
            )
            val list = ArrayList<Pair<String, Uri>>()

            result.use { c ->
                if (c != null && c.count >= 1) {
                    c.moveToFirst()
                    while (true) {
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        val name =
                            c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                        val uri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                        )
                        list.add(Pair(name, uri))
                        if (c.isLast) {
                            break
                        }
                        c.moveToNext()
                    }

                    /*
                    val cDisplayName = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                    val cRelativePath = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))*/

                }
            }
            return list
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    /**
     * Used for getting video player subs.
     * @return List of pairs for the files in this format: <Name, Uri>
     * */
    fun getFolder(
        context: Context,
        relativePath: String,
        basePath: String?
    ): List<Pair<String, Uri>>? {
        val base = basePathToFile(context, basePath)
        val folder = base?.gotoDir(relativePath, false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && base.isDownloadDir()) {
            return context.contentResolver?.getExistingFolderStartName(relativePath)
        } else {
//            val normalPath =
//                "${Environment.getExternalStorageDirectory()}${File.separatorChar}${relativePath}".replace(
//                    '/',
//                    File.separatorChar
//                )
//            val folder = File(normalPath)
            if (folder?.isDirectory == true) {
                return folder.listFiles()?.map { Pair(it.name ?: "", it.uri) }
            }
        }
        return null
//        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun ContentResolver.getExistingDownloadUriOrNullQ(
        relativePath: String,
        displayName: String
    ): Uri? {
        try {
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                //MediaStore.MediaColumns.DISPLAY_NAME,   // unused (for verification use only)
                //MediaStore.MediaColumns.RELATIVE_PATH,  // unused (for verification use only)
            )

            val selection =
                "${MediaStore.MediaColumns.RELATIVE_PATH}='$relativePath' AND " + "${MediaStore.MediaColumns.DISPLAY_NAME}='$displayName'"

            val result = this.query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                projection, selection, null, null
            )

            result.use { c ->
                if (c != null && c.count >= 1) {
                    c.moveToFirst().let {
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                        /*
                        val cDisplayName = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                        val cRelativePath = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH))*/

                        return ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                        )
                    }
                }
            }
            return null
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    fun ContentResolver.getFileLength(fileUri: Uri): Long? {
        return try {
            this.openFileDescriptor(fileUri, "r")
                .use { it?.statSize ?: 0 }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    data class CreateNotificationMetadata(
        val type: DownloadType,
        val bytesDownloaded: Long,
        val bytesTotal: Long,
        val hlsProgress: Long? = null,
        val hlsTotal: Long? = null,
    )

    data class StreamData(
        val errorCode: Int,
        val resume: Boolean? = null,
        val fileLength: Long? = null,
        val fileStream: OutputStream? = null,
    )

    /**
     * Sets up the appropriate file and creates a data stream from the file.
     * Used for initializing downloads.
     * */
    fun setupStream(
        context: Context,
        name: String,
        folder: String?,
        extension: String,
        tryResume: Boolean,
    ): StreamData {
        val displayName = getDisplayName(name, extension)
        val fileStream: OutputStream
        val fileLength: Long
        var resume = tryResume
        val baseFile = context.getBasePath()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && baseFile.first?.isDownloadDir() == true) {
            val cr = context.contentResolver ?: return StreamData(ERROR_CONTENT_RESOLVER_NOT_FOUND)

            val currentExistingFile =
                cr.getExistingDownloadUriOrNullQ(
                    folder ?: "",
                    displayName
                ) // CURRENT FILE WITH THE SAME PATH

            fileLength =
                if (currentExistingFile == null || !resume) 0 else (cr.getFileLength(
                    currentExistingFile
                )
                    ?: 0)// IF NOT RESUME THEN 0, OTHERWISE THE CURRENT FILE SIZE

            if (!resume && currentExistingFile != null) { // DELETE FILE IF FILE EXITS AND NOT RESUME
                val rowsDeleted = context.contentResolver.delete(currentExistingFile, null, null)
                if (rowsDeleted < 1) {
                    println("ERROR DELETING FILE!!!")
                }
            }

            var appendFile = false
            val newFileUri = if (resume && currentExistingFile != null) {
                appendFile = true
                currentExistingFile
            } else {
                val contentUri =
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) // USE INSTEAD OF MediaStore.Downloads.EXTERNAL_CONTENT_URI
                //val currentMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                val currentMimeType = when (extension) {

                    // Absolutely ridiculous, if text/vtt is used as mimetype scoped storage prevents
                    // downloading to /Downloads yet it works with null

                    "vtt" -> null // "text/vtt"
                    "mp4" -> "video/mp4"
                    "srt" -> null // "application/x-subrip"//"text/plain"
                    else -> null
                }
                val newFile = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.TITLE, name)
                    if (currentMimeType != null)
                        put(MediaStore.MediaColumns.MIME_TYPE, currentMimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                }

                cr.insert(
                    contentUri,
                    newFile
                ) ?: return StreamData(ERROR_CONTENT_RESOLVER_NOT_FOUND)
            }

            fileStream = cr.openOutputStream(newFileUri, "w" + (if (appendFile) "a" else ""))
                ?: return StreamData(ERROR_CONTENT_RESOLVER_NOT_FOUND)
        } else {
            val subDir = baseFile.first?.gotoDir(folder)
            val rFile = subDir?.findFile(displayName)
            if (rFile?.exists() != true) {
                fileLength = 0
                if (subDir?.createFile(displayName) == null) return StreamData(ERROR_CREATE_FILE)
            } else {
                if (resume) {
                    fileLength = rFile.size()
                } else {
                    fileLength = 0
                    if (!rFile.delete()) return StreamData(ERROR_DELETING_FILE)
                    if (subDir.createFile(displayName) == null) return StreamData(ERROR_CREATE_FILE)
                }
            }
            fileStream = (subDir.findFile(displayName)
                ?: subDir.createFile(displayName))!!.openOutputStream()
//          fileStream = FileOutputStream(rFile, false)
            if (fileLength == 0L) resume = false
        }
        return StreamData(SUCCESS_STREAM, resume, fileLength, fileStream)
    }

    /** This class handles the notifications, as well as the relevant key */
    data class DownloadMetaData(
        private val id: Int?,
        var bytesDownloaded: Long = 0,
        var totalBytes: Long? = null,

        // notification metadata
        private var lastUpdatedMs: Long = 0,
        private val createNotificationCallback: (CreateNotificationMetadata) -> Unit,

        private var internalType: DownloadType = DownloadType.IsPending,

        // how many segments that we have downloaded
        var hlsProgress: Int = 0,
        // how many segments that exist
        var hlsTotal: Int? = null,
        // this is how many segments that has been written to the file
        // will always be <= hlsProgress as we may keep some in a buffer
        var hlsWrittenProgress: Int = 0,

        // this is used for copy with metadata on how much we have downloaded for setKey
        private var downloadFileInfoTemplate: DownloadedFileInfo? = null
    ) : Closeable {
        val approxTotalBytes: Long
            get() = totalBytes ?: hlsTotal?.let { total ->
                (bytesDownloaded * (total / hlsProgress.toFloat())).toLong()
            } ?: 0L

        private val isHLS get() = hlsTotal != null

        private val downloadEventListener = { event: Pair<Int, DownloadActionType> ->
            if (event.first == id) {
                when (event.second) {
                    DownloadActionType.Pause -> {
                        type = DownloadType.IsPaused
                    }

                    DownloadActionType.Stop -> {
                        type = DownloadType.IsStopped
                        removeKey(KEY_RESUME_PACKAGES, event.first.toString())
                        saveQueue()
                    }

                    DownloadActionType.Resume -> {
                        type = DownloadType.IsDownloading
                    }
                }
            }
        }

        private fun updateFileInfo() {
            if (id == null) return
            downloadFileInfoTemplate?.let { template ->
                setKey(
                    KEY_DOWNLOAD_INFO,
                    id.toString(),
                    template.copy(
                        totalBytes = approxTotalBytes,
                        extraInfo = if (isHLS) hlsWrittenProgress.toString() else null
                    )
                )
            }
        }

        fun setDownloadFileInfoTemplate(template: DownloadedFileInfo) {
            downloadFileInfoTemplate = template
            updateFileInfo()
        }

        init {
            if (id != null) {
                downloadEvent += downloadEventListener
            }
        }

        override fun close() {
            // as we may need to resume hls downloads, we save the current written index
            if (isHLS) {
                updateFileInfo()
            }
            if (id != null) {
                downloadEvent -= downloadEventListener
                downloadStatus -= id
            }
        }

        var type
            get() = internalType
            set(value) {
                internalType = value
                notify()
            }

        fun onDelete() {
            bytesDownloaded = 0
            hlsWrittenProgress = 0
            hlsProgress = 0

            //internalType = DownloadType.IsStopped
            notify()
        }

        companion object {
            const val UPDATE_RATE_MS: Long = 1000L
        }

        @JvmName("DownloadMetaDataNotify")
        private fun notify() {
            lastUpdatedMs = System.currentTimeMillis()
            try {
                val bytes = approxTotalBytes

                // notification creation
                if (isHLS) {
                    createNotificationCallback(
                        CreateNotificationMetadata(
                            internalType,
                            bytesDownloaded,
                            bytes,
                            hlsTotal = hlsTotal?.toLong(),
                            hlsProgress = hlsProgress.toLong()
                        )
                    )
                } else {
                    createNotificationCallback(
                        CreateNotificationMetadata(
                            internalType,
                            bytesDownloaded,
                            bytes,
                        )
                    )
                }

                // as hls has an approx file size we want to update this metadata
                if (isHLS) {
                    updateFileInfo()
                }

                // push all events, this *should* not crash, TODO MUTEX?
                if (id != null) {
                    downloadStatus[id] = type
                    downloadProgressEvent(Triple(id, bytesDownloaded, bytes))
                    downloadStatusEvent(id to type)
                }
            } catch (t: Throwable) {
                logError(t)
                if (BuildConfig.DEBUG) {
                    throw t
                }
            }
        }

        private fun checkNotification() {
            if (lastUpdatedMs + UPDATE_RATE_MS > System.currentTimeMillis()) return
            notify()
        }


        /** adds the length and pushes a notification if necessary */
        fun addBytes(length: Long) {
            bytesDownloaded += length
            // we don't want to update the notification after it is paused,
            // download progress may not stop directly when we "pause" it
            if (type == DownloadType.IsDownloading) checkNotification()
        }

        /** adds the length + hsl progress and pushes a notification if necessary */
        fun addSegment(length: Long) {
            hlsProgress += 1
            addBytes(length)
        }

        fun setWrittenSegment(segmentIndex: Int) {
            hlsWrittenProgress = segmentIndex + 1
        }
    }

    @Throws
    suspend fun downloadThing(
        context: Context,
        link: IDownloadableMinimum,
        name: String,
        folder: String?,
        extension: String,
        tryResume: Boolean,
        parentId: Int?,
        createNotificationCallback: (CreateNotificationMetadata) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        // we cant download torrents with this implementation, aria2c might be used in the future
        if (link.url.startsWith("magnet") || link.url.endsWith(".torrent")) {
            return@withContext ERROR_UNKNOWN
        }

        var fileStream: OutputStream? = null
        var requestStream: InputStream? = null
        val metadata = DownloadMetaData(
            totalBytes = 0,
            bytesDownloaded = 0,
            createNotificationCallback = createNotificationCallback,
            id = parentId,
        )
        try {
            // get the file path
            val (baseFile, basePath) = context.getBasePath()
            val displayName = getDisplayName(name, extension)
            val relativePath =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && baseFile.isDownloadDir()) getRelativePath(
                    folder
                ) else folder

            // set up the download file
            val stream = setupStream(context, name, relativePath, extension, tryResume)
            if (stream.errorCode != SUCCESS_STREAM) return@withContext stream.errorCode
            fileStream = stream.fileStream ?: return@withContext ERROR_UNKNOWN
            val resume = stream.resume ?: return@withContext ERROR_UNKNOWN
            val fileLength = stream.fileLength ?: return@withContext ERROR_UNKNOWN
            val resumeAt = (if (resume) fileLength else 0)
            metadata.bytesDownloaded = resumeAt
            metadata.type = DownloadType.IsPending

            // set up a connection
            val request = app.get(
                link.url.replace(" ", "%20"),
                headers = link.headers.appendAndDontOverride(
                    mapOf(
                        "Accept-Encoding" to "identity",
                        "accept" to "*/*",
                        "user-agent" to USER_AGENT,
                        "sec-ch-ua" to "\"Chromium\";v=\"91\", \" Not;A Brand\";v=\"99\"",
                        "sec-fetch-mode" to "navigate",
                        "sec-fetch-dest" to "video",
                        "sec-fetch-user" to "?1",
                        "sec-ch-ua-mobile" to "?0",
                    ) + if (resumeAt > 0) mapOf("Range" to "bytes=${resumeAt}-") else emptyMap()
                ),
                referer = link.referer,
                verify = false
            )

            // init variables
            val contentLength = request.size ?: 0
            metadata.totalBytes = contentLength + resumeAt

            // save
            metadata.setDownloadFileInfoTemplate(
                DownloadedFileInfo(
                    totalBytes = metadata.approxTotalBytes,
                    relativePath = relativePath ?: "",
                    displayName = displayName,
                    basePath = basePath
                )
            )

            // total length is less than 5mb, that is too short and something has gone wrong
            if (extension == "mp4" && metadata.approxTotalBytes < 5000000) return@withContext ERROR_TOO_SMALL_CONNECTION

            // read the buffer into the filestream, this is equivalent of transferTo
            requestStream = request.body.byteStream()
            metadata.type = DownloadType.IsDownloading

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var read: Int
            while (requestStream.read(buffer, 0, DEFAULT_BUFFER_SIZE).also { read = it } >= 0) {
                fileStream.write(buffer, 0, read)

                // wait until not paused
                while (metadata.type == DownloadType.IsPaused) delay(100)
                // if stopped then break to delete
                if (metadata.type == DownloadType.IsStopped) break
                metadata.addBytes(read.toLong())
            }

            if (metadata.type == DownloadType.IsStopped) {
                // we need to close before delete
                fileStream.closeQuietly()
                metadata.onDelete()
                if (deleteFile(context, baseFile, relativePath ?: "", displayName)) {
                    return@withContext SUCCESS_STOPPED
                } else {
                    return@withContext ERROR_DELETING_FILE
                }
            }

            metadata.type = DownloadType.IsDone
            return@withContext SUCCESS_DOWNLOAD_DONE
        } catch (e: IOException) {
            // some sort of IO error, this should not happened
            // we just rethrow it
            logError(e)
            throw e
        } catch (t: Throwable) {
            // some sort of network error, will error

            // note that when failing we don't want to delete the file,
            // only user interaction has that power
            metadata.type = DownloadType.IsFailed
            return@withContext ERROR_CONNECTION_ERROR
        } finally {
            fileStream?.closeQuietly()
            requestStream?.closeQuietly()
            metadata.close()
        }
    }

    /** Helper function to make sure duplicate attributes don't get overriden or inserted without lowercase cmp
     * example: map("a" to 1) appendAndDontOverride map("A" to 2, "a" to 3, "c" to 4) = map("a" to 1, "c" to 4)
     * */
    private fun <V> Map<String, V>.appendAndDontOverride(rhs: Map<String, V>): Map<String, V> {
        val out = this.toMutableMap()
        val current = this.keys.map { it.lowercase() }
        for ((key, value) in rhs) {
            if (current.contains(key.lowercase())) continue
            out[key] = value
        }
        return out
    }

    @Throws
    private suspend fun downloadHLS(
        context: Context,
        link: ExtractorLink,
        name: String,
        folder: String?,
        parentId: Int?,
        startIndex: Int?,
        createNotificationCallback: (CreateNotificationMetadata) -> Unit,
        parallelConnections: Int = 3
    ): Int = withContext(Dispatchers.IO) {
        require(parallelConnections >= 1)

        val metadata = DownloadMetaData(
            createNotificationCallback = createNotificationCallback,
            id = parentId
        )
        val extension = "mp4"

        var fileStream: OutputStream? = null
        try {
            // the start .ts index
            var startAt = startIndex ?: 0

            // set up the file data
            val (baseFile, basePath) = context.getBasePath()
            val relativePath =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && baseFile.isDownloadDir()) getRelativePath(
                    folder
                ) else folder
            val displayName = getDisplayName(name, extension)
            val stream = setupStream(context, name, relativePath, extension, startAt > 0)
            if (stream.errorCode != SUCCESS_STREAM) return@withContext stream.errorCode
            if (stream.resume != true) startAt = 0
            fileStream = stream.fileStream ?: return@withContext ERROR_UNKNOWN

            // push the metadata
            metadata.bytesDownloaded = stream.fileLength ?: 0
            metadata.hlsProgress = startAt
            metadata.type = DownloadType.IsPending
            metadata.setDownloadFileInfoTemplate(
                DownloadedFileInfo(
                    totalBytes = 0,
                    relativePath = relativePath ?: "",
                    displayName = displayName,
                    basePath = basePath
                )
            )

            // do the initial get request to fetch the segments
            val m3u8 = M3u8Helper.M3u8Stream(
                link.url, link.quality, link.headers.appendAndDontOverride(
                    mapOf(
                        "Accept-Encoding" to "identity",
                        "accept" to "*/*",
                        "user-agent" to USER_AGENT,
                    ) + if (link.referer.isNotBlank()) mapOf("referer" to link.referer) else emptyMap()
                )
            )
            val items = M3u8Helper2.hslLazy(listOf(m3u8))

            metadata.hlsTotal = items.size
            metadata.type = DownloadType.IsDownloading

            // does several connections in parallel instead of a regular for loop to improve
            // download speed
            (startAt until items.size).chunked(parallelConnections).forEach { subset ->
                // wait until not paused
                while (metadata.type == DownloadType.IsPaused) delay(100)
                // if stopped then break to delete
                if (metadata.type == DownloadType.IsStopped) return@forEach

                subset.amap { idx ->
                    idx to items.resolveLinkSafe(idx)?.also { bytes ->
                        metadata.addSegment(bytes.size.toLong())
                    }
                }.forEach { (idx, bytes) ->
                    if (bytes == null) {
                        metadata.type = DownloadType.IsFailed
                        return@withContext ERROR_CONNECTION_ERROR
                    }
                    fileStream.write(bytes)
                    metadata.setWrittenSegment(idx)
                }
            }

            if (metadata.type == DownloadType.IsStopped) {
                // we need to close before delete
                fileStream.closeQuietly()
                metadata.onDelete()
                if (deleteFile(context, baseFile, relativePath ?: "", displayName)) {
                    return@withContext SUCCESS_STOPPED
                } else {
                    return@withContext ERROR_DELETING_FILE
                }
            }

            metadata.type = DownloadType.IsDone
            return@withContext SUCCESS_DOWNLOAD_DONE
        } catch (t: Throwable) {
            logError(t)
            metadata.type = DownloadType.IsFailed
            return@withContext ERROR_UNKNOWN
        } finally {
            fileStream?.closeQuietly()
            metadata.close()
        }
    }


    /**
     * Guarantees a directory is present with the dir name (if createMissingDirectories is true).
     * Works recursively when '/' is present.
     * Will remove any file with the dir name if present and add directory.
     * Will not work if the parent directory does not exist.
     *
     * @param directoryName if null will use the current path.
     * @return UniFile / null if createMissingDirectories = false and folder is not found.
     * */
    private fun UniFile.gotoDir(
        directoryName: String?,
        createMissingDirectories: Boolean = true
    ): UniFile? {

        // May give this error on scoped storage.
        // W/DocumentsContract: Failed to create document
        // java.lang.IllegalArgumentException: Parent document isn't a directory

        // Not present in latest testing.

//        println("Going to dir $directoryName from ${this.uri} ---- ${this.filePath}")

        try {
            // Creates itself from parent if doesn't exist.
            if (!this.exists() && createMissingDirectories && !this.name.isNullOrBlank()) {
                if (this.parentFile != null) {
                    this.parentFile?.createDirectory(this.name)
                } else if (this.filePath != null) {
                    UniFile.fromFile(File(this.filePath!!).parentFile)?.createDirectory(this.name)
                }
            }

            val allDirectories = directoryName?.split("/")
            return if (allDirectories?.size == 1 || allDirectories == null) {
                val found = this.findFile(directoryName)
                when {
                    directoryName.isNullOrBlank() -> this
                    found?.isDirectory == true -> found

                    !createMissingDirectories -> null
                    // Below creates directories
                    found?.isFile == true -> {
                        found.delete()
                        this.createDirectory(directoryName)
                    }

                    this.isDirectory -> this.createDirectory(directoryName)
                    else -> this.parentFile?.createDirectory(directoryName)
                }
            } else {
                var currentDirectory = this
                allDirectories.forEach {
                    // If the next directory is not found it returns the deepest directory possible.
                    val nextDir = currentDirectory.gotoDir(it, createMissingDirectories)
                    currentDirectory = nextDir ?: return null
                }
                currentDirectory
            }
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    private fun getDisplayName(name: String, extension: String): String {
        return "$name.$extension"
    }

    /**
     * Gets the default download path as an UniFile.
     * Vital for legacy downloads, be careful about changing anything here.
     *
     * As of writing UniFile is used for everything but download directory on scoped storage.
     * Special ContentResolver fuckery is needed for that as UniFile doesn't work.
     * */
    fun getDownloadDir(): UniFile? {
        // See https://www.py4u.net/discuss/614761
        return UniFile.fromFile(
            File(
                Environment.getExternalStorageDirectory().absolutePath + File.separatorChar +
                        Environment.DIRECTORY_DOWNLOADS
            )
        )
    }

    @Deprecated("TODO fix UniFile to work with download directory.")
    private fun getRelativePath(folder: String?): String {
        return (Environment.DIRECTORY_DOWNLOADS + '/' + folder + '/').replace(
            '/',
            File.separatorChar
        ).replace("${File.separatorChar}${File.separatorChar}", File.separatorChar.toString())
    }

    /**
     * Turns a string to an UniFile. Used for stored string paths such as settings.
     * Should only be used to get a download path.
     * */
    private fun basePathToFile(context: Context, path: String?): UniFile? {
        return when {
            path.isNullOrBlank() -> getDownloadDir()
            path.startsWith("content://") -> UniFile.fromUri(context, path.toUri())
            else -> UniFile.fromFile(File(path))
        }
    }

    /**
     * Base path where downloaded things should be stored, changes depending on settings.
     * Returns the file and a string to be stored for future file retrieval.
     * UniFile.filePath is not sufficient for storage.
     * */
    fun Context.getBasePath(): Pair<UniFile?, String?> {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        val basePathSetting = settingsManager.getString(getString(R.string.download_path_key), null)
        return basePathToFile(this, basePathSetting) to basePathSetting
    }

    fun UniFile?.isDownloadDir(): Boolean {
        return this != null && this.filePath == getDownloadDir()?.filePath
    }

    /*private fun delete(
        context: Context,
        name: String,
        folder: String?,
        extension: String,
        parentId: Int?,
        basePath: UniFile?
    ): Int {
        val displayName = getDisplayName(name, extension)

        // delete all subtitle files
        if (extension != "vtt" && extension != "srt") {
            try {
                delete(context, name, folder, "vtt", parentId, basePath)
                delete(context, name, folder, "srt", parentId, basePath)
            } catch (e: Exception) {
                logError(e)
            }
        }

        // If scoped storage and using download dir (not accessible with UniFile)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && basePath.isDownloadDir()) {
            val relativePath = getRelativePath(folder)
            val lastContent =
                context.contentResolver.getExistingDownloadUriOrNullQ(relativePath, displayName) ?: return ERROR_DELETING_FILE
            if(context.contentResolver.delete(lastContent, null, null) <= 0) {
                return ERROR_DELETING_FILE
            }
        } else {
            val dir = basePath?.gotoDir(folder)
            val file = dir?.findFile(displayName)
            val success = file?.delete()
            if (success != true) return ERROR_DELETING_FILE else {
                // Cleans up empty directory
                if (dir.listFiles()?.isEmpty() == true) dir.delete()
            }
            parentId?.let {
                downloadDeleteEvent.invoke(parentId)
            }
        }
        return SUCCESS_STOPPED
    }*/


    fun getFileName(context: Context, metadata: DownloadEpisodeMetadata): String {
        return getFileName(context, metadata.name, metadata.episode, metadata.season)
    }

    private fun getFileName(
        context: Context,
        epName: String?,
        episode: Int?,
        season: Int?
    ): String {
        // kinda ugly ik
        return sanitizeFilename(
            if (epName == null) {
                if (season != null) {
                    "${context.getString(R.string.season)} $season ${context.getString(R.string.episode)} $episode"
                } else {
                    "${context.getString(R.string.episode)} $episode"
                }
            } else {
                if (episode != null) {
                    if (season != null) {
                        "${context.getString(R.string.season)} $season ${context.getString(R.string.episode)} $episode - $epName"
                    } else {
                        "${context.getString(R.string.episode)} $episode - $epName"
                    }
                } else {
                    epName
                }
            }
        )
    }

    private suspend fun downloadSingleEpisode(
        context: Context,
        source: String?,
        folder: String?,
        ep: DownloadEpisodeMetadata,
        link: ExtractorLink,
        notificationCallback: (Int, Notification) -> Unit,
        tryResume: Boolean = false,
    ): Int {
        val name = getFileName(context, ep)

        // Make sure this is cancelled when download is done or cancelled.
        val extractorJob = ioSafe {
            if (link.extractorData != null) {
                getApiFromNameNull(link.source)?.extractorVerifierJob(link.extractorData)
            }
        }

        if (link.isM3u8 || URL(link.url).path.endsWith(".m3u8")) {
            val startIndex = if (tryResume) {
                context.getKey<DownloadedFileInfo>(
                    KEY_DOWNLOAD_INFO,
                    ep.id.toString(),
                    null
                )?.extraInfo?.toIntOrNull()
            } else null
            return suspendSafeApiCall {
                downloadHLS(
                    context,
                    link,
                    name,
                    folder,
                    ep.id,
                    startIndex,
                    createNotificationCallback = { meta ->
                        main {
                            createNotification(
                                context,
                                source,
                                link.name,
                                ep,
                                meta.type,
                                meta.bytesDownloaded,
                                meta.bytesTotal,
                                notificationCallback,
                                meta.hlsProgress,
                                meta.hlsTotal
                            )
                        }
                    }
                )
            }.also {
                extractorJob.cancel()
            } ?: ERROR_UNKNOWN
        }

        return suspendSafeApiCall {
            downloadThing(context, link, name, folder, "mp4", tryResume, ep.id) { meta ->
                main {
                    createNotification(
                        context,
                        source,
                        link.name,
                        ep,
                        meta.type,
                        meta.bytesDownloaded,
                        meta.bytesTotal,
                        notificationCallback
                    )
                }
            }
        }.also { extractorJob.cancel() } ?: ERROR_UNKNOWN
    }

    suspend fun downloadCheck(
        context: Context, notificationCallback: (Int, Notification) -> Unit,
    ) {
        if (!(currentDownloads.size < maxConcurrentDownloads && downloadQueue.size > 0)) return

        val pkg = downloadQueue.removeFirst()
        val item = pkg.item
        val id = item.ep.id
        if (currentDownloads.contains(id)) { // IF IT IS ALREADY DOWNLOADING, RESUME IT
            downloadEvent.invoke(Pair(id, DownloadActionType.Resume))
            /** ID needs to be returned to the work-manager to properly await notification */
            // return id
        }

        currentDownloads.add(id)
        try {
            for (index in (pkg.linkIndex ?: 0) until item.links.size) {
                val link = item.links[index]
                val resume = pkg.linkIndex == index

                setKey(
                    KEY_RESUME_PACKAGES,
                    id.toString(),
                    DownloadResumePackage(item, index)
                )

                var connectionResult =
                    downloadSingleEpisode(
                        context,
                        item.source,
                        item.folder,
                        item.ep,
                        link,
                        notificationCallback,
                        resume
                    )
                //.also { println("Single episode finished with return code: $it") }

                // retry every link at least once
                if (connectionResult <= 0) {
                    connectionResult = downloadSingleEpisode(
                        context,
                        item.source,
                        item.folder,
                        item.ep,
                        link,
                        notificationCallback,
                        true
                    )
                }

                if (connectionResult > 0) { // SUCCESS
                    removeKey(KEY_RESUME_PACKAGES, id.toString())
                    break
                } else if (index == item.links.lastIndex) {
                    downloadStatusEvent.invoke(Pair(id, DownloadType.IsFailed))
                }
            }
        } catch (e: Exception) {
            logError(e)
        } finally {
            currentDownloads.remove(id)
            // Because otherwise notifications will not get caught by the work manager
            downloadCheckUsingWorker(context)
        }

        // return id
    }

    fun getDownloadFileInfoAndUpdateSettings(context: Context, id: Int): DownloadedFileInfoResult? {
        val res = getDownloadFileInfo(context, id)
        if (res == null) context.removeKey(KEY_DOWNLOAD_INFO, id.toString())
        return res
    }

    private fun getDownloadFileInfo(context: Context, id: Int): DownloadedFileInfoResult? {
        try {
            val info =
                context.getKey<DownloadedFileInfo>(KEY_DOWNLOAD_INFO, id.toString()) ?: return null
            val base = basePathToFile(context, info.basePath)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && base.isDownloadDir()) {
                val cr = context.contentResolver ?: return null
                val fileUri =
                    cr.getExistingDownloadUriOrNullQ(info.relativePath, info.displayName)
                        ?: return null
                val fileLength = cr.getFileLength(fileUri) ?: return null
                if (fileLength == 0L) return null
                return DownloadedFileInfoResult(fileLength, info.totalBytes, fileUri)
            } else {

                val file = base?.gotoDir(info.relativePath, false)?.findFile(info.displayName)

//            val normalPath = context.getNormalPath(getFile(info.relativePath), info.displayName)
//            val dFile = File(normalPath)

                if (file?.exists() != true) return null

                return DownloadedFileInfoResult(file.size(), info.totalBytes, file.uri)
            }
        } catch (e: Exception) {
            logError(e)
            return null
        }
    }

    /**
     * Gets the true download size as Scoped Storage sometimes wrongly returns 0.
     * */
    fun UniFile.size(): Long {
        val len = length()
        return if (len <= 1) {
            val inputStream = this.openInputStream()
            return inputStream.available().toLong().also { inputStream.closeQuietly() }
        } else {
            len
        }
    }

    fun deleteFileAndUpdateSettings(context: Context, id: Int): Boolean {
        val success = deleteFile(context, id)
        if (success) context.removeKey(KEY_DOWNLOAD_INFO, id.toString())
        return success
    }

    private fun deleteFile(
        context: Context,
        folder: UniFile?,
        relativePath: String,
        displayName: String
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && folder.isDownloadDir()) {
            val cr = context.contentResolver ?: return false
            val fileUri =
                cr.getExistingDownloadUriOrNullQ(relativePath, displayName)
                    ?: return true // FILE NOT FOUND, ALREADY DELETED

            return cr.delete(fileUri, null, null) > 0 // IF DELETED ROWS IS OVER 0
        } else {
            val file = folder?.gotoDir(relativePath)?.findFile(displayName)
//            val normalPath = context.getNormalPath(getFile(info.relativePath), info.displayName)
//            val dFile = File(normalPath)
            if (file?.exists() != true) return true
            return try {
                file.delete()
            } catch (e: Exception) {
                logError(e)
                val cr = context.contentResolver
                cr.delete(file.uri, null, null) > 0
            }
        }
    }

    private fun deleteFile(context: Context, id: Int): Boolean {
        val info =
            context.getKey<DownloadedFileInfo>(KEY_DOWNLOAD_INFO, id.toString()) ?: return false
        downloadEvent.invoke(Pair(id, DownloadActionType.Stop))
        downloadProgressEvent.invoke(Triple(id, 0, 0))
        downloadStatusEvent.invoke(id to DownloadType.IsStopped)
        downloadDeleteEvent.invoke(id)
        val base = basePathToFile(context, info.basePath)
        return deleteFile(context, base, info.relativePath, info.displayName)
    }

    fun getDownloadResumePackage(context: Context, id: Int): DownloadResumePackage? {
        return context.getKey(KEY_RESUME_PACKAGES, id.toString())
    }

    suspend fun downloadFromResume(
        context: Context,
        pkg: DownloadResumePackage,
        notificationCallback: (Int, Notification) -> Unit,
        setKey: Boolean = true
    ) {
        if (!currentDownloads.any { it == pkg.item.ep.id }) {
            downloadQueue.addLast(pkg)
            downloadCheck(context, notificationCallback)
            if (setKey) saveQueue()
            //ret
        } else {
            downloadEvent(
                Pair(pkg.item.ep.id, DownloadActionType.Resume)
            )
            //null
        }
    }

    private fun saveQueue() {
        try {
            val dQueue =
                downloadQueue.toList()
                    .mapIndexed { index, any -> DownloadQueueResumePackage(index, any) }
                    .toTypedArray()
            setKey(KEY_RESUME_QUEUE_PACKAGES, dQueue)
        } catch (t: Throwable) {
            logError(t)
        }
    }

    /*fun isMyServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager?
        for (service in manager!!.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }*/

    suspend fun downloadEpisode(
        context: Context?,
        source: String?,
        folder: String?,
        ep: DownloadEpisodeMetadata,
        links: List<ExtractorLink>,
        notificationCallback: (Int, Notification) -> Unit,
    ) {
        if (context == null) return
        if (links.isEmpty()) return
        downloadFromResume(
            context,
            DownloadResumePackage(DownloadItem(source, folder, ep, links), null),
            notificationCallback
        )
    }

    /** Worker stuff */
    private fun startWork(context: Context, key: String) {
        val req = OneTimeWorkRequest.Builder(DownloadFileWorkManager::class.java)
            .setInputData(
                Data.Builder()
                    .putString("key", key)
                    .build()
            )
            .build()
        (WorkManager.getInstance(context)).enqueueUniqueWork(
            key,
            ExistingWorkPolicy.KEEP,
            req
        )
    }

    fun downloadCheckUsingWorker(
        context: Context,
    ) {
        startWork(context, DOWNLOAD_CHECK)
    }

    fun downloadFromResumeUsingWorker(
        context: Context,
        pkg: DownloadResumePackage,
    ) {
        val key = pkg.item.ep.id.toString()
        setKey(WORK_KEY_PACKAGE, key, pkg)
        startWork(context, key)
    }

    // Keys are needed to transfer the data to the worker reliably and without exceeding the data limit
    const val WORK_KEY_PACKAGE = "work_key_package"
    const val WORK_KEY_INFO = "work_key_info"

    fun downloadEpisodeUsingWorker(
        context: Context,
        source: String?,
        folder: String?,
        ep: DownloadEpisodeMetadata,
        links: List<ExtractorLink>,
    ) {
        val info = DownloadInfo(
            source, folder, ep, links
        )

        val key = info.ep.id.toString()
        setKey(WORK_KEY_INFO, key, info)
        startWork(context, key)
    }

    data class DownloadInfo(
        @JsonProperty("source") val source: String?,
        @JsonProperty("folder") val folder: String?,
        @JsonProperty("ep") val ep: DownloadEpisodeMetadata,
        @JsonProperty("links") val links: List<ExtractorLink>
    )
}
