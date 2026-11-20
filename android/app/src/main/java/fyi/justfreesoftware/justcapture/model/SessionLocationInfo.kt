package fyi.justfreesoftware.justcapture.model

/**
 * A session's `location.txt`, read back into something a screen can show.
 * The file is written by [RecordingSession.locationText], and the two must
 * stay in step. Anything this doesn't recognize is skipped rather than
 * treated as an error, since the file may come from another version of the
 * app on someone else's phone.
 */
data class SessionLocationInfo(
    /** As written by the recording phone, in its time zone. */
    val started: String?,
    val startingAddress: String?,
    /** Oldest first. */
    val points: List<Point>
) {
    data class Point(
        /** As written by the recording phone, in its time zone. */
        val time: String,
        /** "Recording start" or "Clip 3". */
        val label: String,
        val latitude: Double?,
        val longitude: Double?,
        /** e.g. "within 12 m". */
        val accuracy: String?,
        /** The phone had no current position, so this is an older one. */
        val isLastKnown: Boolean
    ) {
        val mapUrl: String?
            get() = if (latitude != null && longitude != null) LocationPoint.mapUrl(latitude, longitude) else null
    }

    companion object {
        private const val STARTED_PREFIX = "Recording started:"
        private const val ADDRESS_PREFIX = "Starting address:"
        private const val CLIP_PREFIX = "chunk_"
        private const val MIN_POINT_FIELDS = 3
        private val LATITUDES = -90.0..90.0
        private val LONGITUDES = -180.0..180.0

        /** Null when the text holds nothing recognizable. */
        fun parse(text: String): SessionLocationInfo? {
            var started: String? = null
            var address: String? = null
            val points = mutableListOf<Point>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                when {
                    line.startsWith(STARTED_PREFIX) -> started = value(line, STARTED_PREFIX)
                    line.startsWith(ADDRESS_PREFIX) -> address = value(line, ADDRESS_PREFIX)
                    else -> point(line)?.let { points += it }
                }
            }
            if (started == null && address == null && points.isEmpty()) return null
            return SessionLocationInfo(started, address, points)
        }

        private fun value(line: String, prefix: String): String? =
            line.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }

        /**
         * A trail line: time | clip or "recording start" | then some of
         * "last known position", "lat, lon", "within N m", a map link, or
         * "no location available".
         */
        private fun point(line: String): Point? {
            val fields = line.split(" | ").map { it.trim() }
            if (fields.size < MIN_POINT_FIELDS) return null

            var latitude: Double? = null
            var longitude: Double? = null
            var accuracy: String? = null
            var isLastKnown = false
            for (field in fields.drop(2)) {
                when {
                    field == "last known position" -> isLastKnown = true

                    field.startsWith("within ") -> accuracy = field

                    !field.startsWith("http") -> coordinates(field)?.let { (lat, lon) ->
                        latitude = lat
                        longitude = lon
                    }
                }
            }
            return Point(fields[0], label(fields[1]), latitude, longitude, accuracy, isLastKnown)
        }

        /** Reads "lat, lon", or null if the field is anything else. */
        private fun coordinates(field: String): Pair<Double, Double>? {
            val parts = field.split(",").map { it.trim().toDoubleOrNull() }
            val lat = parts.getOrNull(0)?.takeIf { it in LATITUDES }
            val lon = parts.getOrNull(1)?.takeIf { it in LONGITUDES }
            return if (parts.size == 2 && lat != null && lon != null) lat to lon else null
        }

        /** "chunk_2.mp4" is the third clip, which the rest of the app calls Clip 3. */
        private fun label(name: String): String {
            if (name == "recording start") return "Recording start"
            val index = name.takeIf { it.startsWith(CLIP_PREFIX) }
                ?.removePrefix(CLIP_PREFIX)?.takeWhile { it.isDigit() }?.toIntOrNull()
            return if (index != null) "Clip ${index + 1}" else name
        }
    }
}
