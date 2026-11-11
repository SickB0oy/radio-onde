package app.onde.radio.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

data class Suggestion(val station: Station, val reason: String)

data class Recommendations(val title: String, val items: List<Suggestion>)

/**
 * Suggestions « Pour toi », calculées sur le téléphone à partir du temps d'écoute par genre.
 * Rien n'est envoyé ailleurs : seules des recherches par genre partent vers Radio-Browser.
 */
class Recommender(private val api: RadioBrowserApi) {

    suspend fun forYou(library: LibraryData, countryCode: String): Recommendations = coroutineScope {
        val known = (library.favorites + library.recent).map { it.uuid }.toSet()
        val topTags = library.tagScores.entries
            .filter { it.key !in GENERIC_TAGS && it.key.length > 1 }
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key }

        if (topTags.isEmpty()) {
            val top = api.topVoted(countryCode, 12)
            return@coroutineScope Recommendations(
                title = "Les plus appréciées près de chez toi",
                items = top.map { Suggestion(it, it.subtitle.ifBlank { "Très écoutée" }) },
            )
        }

        val perTag = topTags.map { tag ->
            async {
                val local = runCatching { api.byTag(tag, 12, countryCode) }.getOrDefault(emptyList())
                val world = runCatching { api.byTag(tag, 12) }.getOrDefault(emptyList())
                (local + world)
                    .filter { it.uuid !in known }
                    .distinctBy { it.uuid }
                    .map { Suggestion(it, "Car tu aimes ${tag.replaceFirstChar(Char::uppercase)}") }
            }
        }.awaitAll()

        // On alterne les genres pour varier les suggestions.
        val mixed = mutableListOf<Suggestion>()
        val seen = mutableSetOf<String>()
        var i = 0
        while (mixed.size < 12 && perTag.any { i < it.size }) {
            perTag.forEach { list ->
                list.getOrNull(i)?.let { if (seen.add(it.station.uuid)) mixed += it }
            }
            i++
        }

        val last = library.recent.firstOrNull()?.displayName
        Recommendations(
            title = if (last != null) "Parce que tu écoutes $last" else "Pour toi",
            items = mixed.take(12),
        )
    }

    companion object {
        private val GENERIC_TAGS = setOf(
            "music", "musique", "radio", "fm", "am", "online", "internet", "webradio",
            "local", "various", "misc", "variety", "public radio", "community radio",
        )
    }
}
