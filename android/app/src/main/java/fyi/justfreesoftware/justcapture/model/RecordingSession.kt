package fyi.justfreesoftware.justcapture.model

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Serializable
enum class UploadStatus {
    PENDING,
    UPLOADING,
    UPLOADED,
    FAILED
}

@Serializable
data class VideoChunk(
    val id: String,
    val index: Int,
    val localPath: String,
    val createdAtEpochMs: Long,
    val durationSec: Double = 0.0,
    val fileSizeBytes: Long = 0,
    val status: UploadStatus = UploadStatus.PENDING,
    val uploadProgress: Double = 0.0,
    val driveFileId: String? = null
) {
    val fileName: String
        get() = localPath.substringAfterLast('/')
}

/** A location reading and when it was taken (wall clock). */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val timeMs: Long,
    /** Radius in meters that the true position is likely within, if known. */
    val accuracyMeters: Double? = null
)

/**
 * One line in a recording's location trail: where the recording started, or
 * where the phone was when a clip ended. A clip with no recent position still
 * gets a line, with null coordinates, so the gap is visible.
 */
@Serializable
data class LocationPoint(
    val epochMs: Long,
    /** The finished clip this belongs to; null for the recording's start. */
    val clipFileName: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Radius in meters that the true position is likely within. */
    val accuracyMeters: Double? = null,
    /** The phone had no current position, so this is the last one it knew, from [epochMs]. */
    val isLastKnown: Boolean? = null
) {
    companion object {
        /** A fix older than this isn't where the phone is now. */
        const val CURRENT_FIX_MAX_AGE_MS = 30_000L

        /** Above this radius, a street address would claim more than the fix knows. */
        const val STREET_ADDRESS_MAX_ACCURACY_M = 100.0

        fun from(fix: Fix, clipFileName: String?, nowMs: Long) = LocationPoint(
            epochMs = fix.timeMs,
            clipFileName = clipFileName,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyMeters = fix.accuracyMeters,
            isLastKnown = nowMs - fix.timeMs > CURRENT_FIX_MAX_AGE_MS
        )

        /**
         * Text for a fix too rough for a street address, e.g. "Near Boston, MA
         * (within about 12 km)". States the accuracy because emails and the
         * trail header show this without the accuracy next to it.
         */
        fun approximateAddress(area: String, accuracyMeters: Double): String {
            val radius = if (accuracyMeters >= 1000) {
                "${(accuracyMeters / 1000).roundToInt()} km"
            } else {
                "${accuracyMeters.roundToInt()} m"
            }
            return "Near $area (within about $radius)"
        }

        /** Fixed format so a coordinate never prints in scientific notation. */
        fun coordinates(latitude: Double, longitude: Double): String =
            String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

        fun mapUrl(latitude: Double, longitude: Double): String =
            String.format(Locale.US, "https://www.google.com/maps?q=%.6f,%.6f", latitude, longitude)
    }
}

@Serializable
data class RecordingSession(
    val id: String,
    val startEpochMs: Long,
    val chunks: List<VideoChunk> = emptyList(),
    val driveFolderId: String? = null,
    val driveFolderLink: String? = null,
    /**
     * Whether the Drive folder has been shared with anyone who has the link.
     * `false` means sharing is still owed and will be retried; `null` means
     * the folder is left private (no one has been notified about this session,
     * or the account's policy forbids public links).
     */
    val driveFolderShared: Boolean? = null,
    val locationAddress: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** When the start location fix was taken, which can be slightly before [startEpochMs]. */
    val locationEpochMs: Long? = null,
    /** The start of the recording plus one entry per finished clip, oldest first. */
    val locationTrail: List<LocationPoint>? = null,
    val locationFileId: String? = null,
    /** What's in the Drive copy of [locationText], to tell if it's out of date. */
    val uploadedLocationText: String? = null,
    val chunkDurationSec: Double = 30.0,
    val isActive: Boolean = true,
    val playlistFileId: String? = null
) {
    val folderName: String
        get() = "Session $id"

    /** Records where the recording started. Does nothing once the trail has begun. */
    fun addingStartToTrail(fix: Fix, nowMs: Long): RecordingSession =
        if (locationTrail != null) this
        else copy(locationTrail = listOf(LocationPoint.from(fix, clipFileName = null, nowMs = nowMs)))

    /** Records where the phone was when a clip ended; a null fix means it isn't known. */
    fun addingClipToTrail(fix: Fix?, clipFileName: String, nowMs: Long): RecordingSession {
        val point = fix?.let { LocationPoint.from(it, clipFileName, nowMs) }
            ?: LocationPoint(epochMs = nowMs, clipFileName = clipFileName)
        return copy(locationTrail = locationTrail.orEmpty() + point)
    }

    /**
     * Contents of the session's `location.txt` on Drive, once a location is
     * known. Each upload replaces the file with the whole trail so far.
     */
    val locationText: String?
        get() {
            val trail = locationTrail.orEmpty()
            if (trail.none { it.latitude != null } && latitude == null) return null
            val formatter = SimpleDateFormat("MMM d, yyyy 'at' h:mm:ss a zzz", Locale.US)
            val lines = mutableListOf("Recording started: ${formatter.format(Date(startEpochMs))}")
            locationAddress?.let { lines += "Starting address: $it" }
            lines += ""
            lines += "Location trail (oldest first):"
            if (trail.isEmpty() && latitude != null && longitude != null) {
                val time = locationEpochMs?.let { formatter.format(Date(it)) } ?: "Unknown time"
                lines += "$time | recording start | ${LocationPoint.coordinates(latitude, longitude)} | " +
                    LocationPoint.mapUrl(latitude, longitude)
            }
            for (point in trail) {
                val fields = mutableListOf(formatter.format(Date(point.epochMs)), point.clipFileName ?: "recording start")
                if (point.latitude != null && point.longitude != null) {
                    if (point.isLastKnown == true) fields += "last known position"
                    fields += LocationPoint.coordinates(point.latitude, point.longitude)
                    point.accuracyMeters?.let { fields += "within ${it.roundToInt()} m" }
                    fields += LocationPoint.mapUrl(point.latitude, point.longitude)
                } else {
                    fields += "no location available"
                }
                lines += fields.joinToString(" | ")
            }
            return lines.joinToString("\n")
        }

    val uploadedCount: Int
        get() = chunks.count { it.status == UploadStatus.UPLOADED }

    val failedCount: Int
        get() = chunks.count { it.status == UploadStatus.FAILED }

    val queuedCount: Int
        get() = chunks.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.UPLOADING }

    val totalCount: Int
        get() = chunks.size

    val totalDurationSec: Double
        get() = chunks.sumOf { it.durationSec }

    val totalBytes: Long
        get() = chunks.sumOf { it.fileSizeBytes }

    val uploadFraction: Float
        get() = if (totalCount == 0) 0f else uploadedCount.toFloat() / totalCount

    companion object {
        private var lastIssuedBase: String? = null
        private var collisionCount = 0

        fun new(chunkDurationSec: Double): RecordingSession {
            val now = System.currentTimeMillis()
            // Ids have one-second resolution; a stop-and-restart within the
            // same second would otherwise reuse the previous session's id and
            // folder.
            var id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
            synchronized(this) {
                if (id == lastIssuedBase) {
                    collisionCount += 1
                    id += "_${collisionCount + 1}"
                } else {
                    lastIssuedBase = id
                    collisionCount = 0
                }
            }
            return RecordingSession(
                id = id,
                startEpochMs = now,
                chunkDurationSec = chunkDurationSec
            )
        }
    }
}
