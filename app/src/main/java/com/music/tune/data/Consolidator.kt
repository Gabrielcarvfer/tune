package com.music.tune.data

/**
 * Finds songs that belong together on one release but are split across
 * albums in the collection: say, tracks of an anniversary edition that each
 * got matched to a different original soundtrack.
 *
 * Releases are taken greedily, the one covering the most still-unassigned
 * songs first, so each song goes to the biggest release it's on rather than
 * to whichever release its own lookup ranked first.
 */
object Consolidator {

    /** Songs to move onto [release], currently spread over the albums [fromAlbums]. */
    data class Proposal(val release: ReleaseCandidate, val songs: List<Song>, val fromAlbums: List<String>)

    /**
     * @param matches every release each song appears on (AcoustID answers).
     * @param albumOf the album group a song is in now (its id in the collection).
     */
    fun propose(
        songs: List<Song>,
        matches: Map<Long, List<TrackMatch>>,
        albumOf: (Song) -> Long,
        albumTitle: (Long) -> String,
    ): List<Proposal> {
        val byId = songs.associateBy { it.id }
        // release id -> (song id -> best match of that song on the release)
        val byRelease = HashMap<String, HashMap<Long, TrackMatch>>()
        for ((songId, list) in matches) {
            if (songId !in byId) continue
            for (m in list) {
                val slot = byRelease.getOrPut(m.releaseId) { HashMap() }
                val prev = slot[songId]
                if (prev == null || m.score > prev.score) slot[songId] = m
            }
        }

        val unassigned = HashSet(byId.keys)
        val out = ArrayList<Proposal>()
        while (true) {
            var best: Pair<String, Map<Long, TrackMatch>>? = null
            var bestKey: Rank? = null
            for ((rid, tracks) in byRelease) {
                val covered = tracks.filterKeys { it in unassigned }
                if (covered.size < 2) continue
                val albums = covered.keys.map { albumOf(byId.getValue(it)) }.distinct()
                if (albums.size < 2) continue
                val any = covered.values.first()
                val key = Rank(
                    covered.size,
                    if (any.type.equals("album", true) || any.type.equals("soundtrack", true)) 1 else 0,
                    covered.values.map { it.score }.average(),
                    -abs(any.trackCount * maxOf(1, any.discCount) - covered.size),
                    rid,
                )
                if (bestKey == null || key > bestKey) {
                    best = rid to covered
                    bestKey = key
                }
            }
            val (rid, covered) = best ?: break
            val group = covered.keys.map { byId.getValue(it) }.sortedWith(compareBy({ covered.getValue(it.id).disc }, { covered.getValue(it.id).track }))
            val release = AcoustId.groupByRelease(covered.mapValues { listOf(it.value) }, group.size).first { it.releaseId == rid }
            val fromAlbums = group.map { albumOf(it) }.distinct().map(albumTitle)
            out += Proposal(release, group, fromAlbums)
            unassigned.removeAll(covered.keys)
        }
        return out
    }

    /**
     * Most songs first; then albums over other release types, better match
     * quality, a size closer to the release's, and (for a stable result) the
     * smaller release id.
     */
    private data class Rank(val count: Int, val album: Int, val quality: Double, val fit: Int, val id: String) : Comparable<Rank> {
        override fun compareTo(other: Rank): Int =
            compareValuesBy(this, other, { it.count }, { it.album }, { it.quality }, { it.fit })
                .takeIf { it != 0 } ?: other.id.compareTo(id)
    }

    private fun abs(x: Int) = if (x < 0) -x else x
}
