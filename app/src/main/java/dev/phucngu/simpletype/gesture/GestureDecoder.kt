package dev.phucngu.simpletype.gesture

/**
 * SHARK²-style gesture-typing decoder.
 *
 * A swiped path is matched against the "ideal" path of each candidate word (the polyline
 * through the key centers of its key sequence — for diacritic languages this is the
 * folded base-letter form, see [GestureDictionary.Entry.keys]) using two channels:
 *  - location: mean point-to-point distance in key-width units, with extra weight on the
 *    endpoints (users are most precise at the start and end of a swipe);
 *  - shape: mean distance after both paths are translated to their centroid and scaled to
 *    a unit box, which is robust to sloppy-but-well-shaped gestures.
 * A Zipf-frequency prior breaks ties toward common words.
 */
class GestureDecoder(private val dictionary: GestureDictionary) {

    data class Candidate(val word: String, val score: Float)

    fun decode(
        path: List<GesturePoint>,
        geometry: KeyGeometry,
        maxResults: Int = MAX_RESULTS,
    ): List<Candidate> {
        if (path.size < 2 || geometry.isEmpty) return emptyList()

        val gestureLength = pathLength(path)
        if (gestureLength < geometry.keyWidth * MIN_GESTURE_LENGTH_KEY_WIDTHS) return emptyList()

        val sampled = resample(path, SAMPLE_POINTS)
        val sampledShape = normalizeShape(sampled)
        val start = path.first()
        val end = path.last()
        val anchorRadius = geometry.keyWidth * ANCHOR_RADIUS_KEY_WIDTHS

        val startLetters = lettersNear(start, geometry, anchorRadius)
        val endLetters = lettersNear(end, geometry, anchorRadius)
        if (startLetters.isEmpty() || endLetters.isEmpty()) return emptyList()

        val results = ArrayList<Candidate>()
        for (first in startLetters) {
            for (entry in dictionary.wordsStartingWith(first)) {
                val keys = entry.keys
                if (keys.length < 2) continue
                if (keys[keys.length - 1] !in endLetters) continue

                val ideal = idealPath(keys, geometry) ?: continue
                val idealLength = pathLength(ideal)
                if (idealLength < gestureLength * MIN_LENGTH_RATIO ||
                    idealLength > gestureLength * MAX_LENGTH_RATIO
                ) continue

                val idealSampled = resample(ideal, SAMPLE_POINTS)
                val location = weightedMeanDistance(sampled, idealSampled) / geometry.keyWidth
                if (location > LOCATION_CUTOFF) continue
                val shape = weightedMeanDistance(sampledShape, normalizeShape(idealSampled))

                val score = location * LOCATION_WEIGHT +
                    shape * SHAPE_WEIGHT -
                    entry.zipf100 / 100f * FREQUENCY_WEIGHT
                results.add(Candidate(entry.word, score))
            }
        }

        results.sortBy { it.score }
        return results.take(maxResults)
    }

    /** Key centers for the key sequence's letters with consecutive duplicates collapsed. */
    private fun idealPath(keys: String, geometry: KeyGeometry): List<GesturePoint>? {
        val points = ArrayList<GesturePoint>(keys.length)
        var previous: Char = 0.toChar()
        for (c in keys) {
            if (c == previous) continue
            points.add(geometry.centerOf(c) ?: return null)
            previous = c
        }
        return if (points.size >= 2) points else null
    }

    private fun lettersNear(point: GesturePoint, geometry: KeyGeometry, radius: Float): Set<Char> {
        val letters = HashSet<Char>()
        for ((c, center) in geometry.centers) {
            if (center.distanceTo(point) <= radius) letters.add(c)
        }
        return letters
    }

    private fun weightedMeanDistance(a: List<GesturePoint>, b: List<GesturePoint>): Float {
        var sum = 0f
        var weights = 0f
        val last = a.size - 1
        for (i in a.indices) {
            val w = if (i == 0 || i == last) ENDPOINT_WEIGHT else 1f
            sum += a[i].distanceTo(b[i]) * w
            weights += w
        }
        return sum / weights
    }

    companion object {
        const val MAX_RESULTS = 10
        private const val SAMPLE_POINTS = 32
        private const val ANCHOR_RADIUS_KEY_WIDTHS = 1.6f
        private const val MIN_GESTURE_LENGTH_KEY_WIDTHS = 0.8f
        private const val MIN_LENGTH_RATIO = 0.35f
        private const val MAX_LENGTH_RATIO = 2.8f
        private const val LOCATION_CUTOFF = 1.2f
        private const val LOCATION_WEIGHT = 1f
        private const val SHAPE_WEIGHT = 1f
        private const val FREQUENCY_WEIGHT = 0.08f
        private const val ENDPOINT_WEIGHT = 3f
    }
}
