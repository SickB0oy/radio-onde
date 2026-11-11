package app.onde.radio.data

/**
 * Un style musical affiché en onglet. [tags] sont les étiquettes Radio-Browser correspondantes
 * (recherche partielle : « rock » trouve aussi « pop rock », « hard rock »…).
 */
data class Genre(val key: String, val label: String, val tags: List<String>)

object Genres {
    const val FOR_YOU = "pour-toi"
    const val OTHERS = "autres"

    val ALL = listOf(
        Genre("pop", "Pop", listOf("pop")),
        Genre("rock", "Rock", listOf("rock")),
        Genre("hiphop", "Hip-hop & Rap", listOf("hip", "rap")),
        Genre("rnb", "R&B & Soul", listOf("rnb", "soul")),
        Genre("electro", "Électro", listOf("electronic", "electro")),
        Genre("dance", "Dance", listOf("dance")),
        Genre("house", "House", listOf("house")),
        Genre("techno", "Techno", listOf("techno")),
        Genre("trance", "Trance", listOf("trance")),
        Genre("dnb", "Drum & bass", listOf("drum and bass", "dnb")),
        Genre("jazz", "Jazz", listOf("jazz")),
        Genre("blues", "Blues", listOf("blues")),
        Genre("funk", "Funk & Disco", listOf("funk", "disco")),
        Genre("classique", "Classique", listOf("classical", "classique")),
        Genre("opera", "Opéra", listOf("opera")),
        Genre("chanson", "Chanson française", listOf("chanson", "french")),
        Genre("variete", "Variété", listOf("variete", "schlager")),
        Genre("reggae", "Reggae", listOf("reggae")),
        Genre("reggaeton", "Reggaeton", listOf("reggaeton")),
        Genre("latino", "Latino", listOf("latin", "salsa")),
        Genre("afro", "Afro", listOf("afro", "african")),
        Genre("caraibes", "Zouk & Caraïbes", listOf("zouk", "kompa", "caribbean")),
        Genre("metal", "Métal", listOf("metal")),
        Genre("punk", "Punk", listOf("punk")),
        Genre("indie", "Indé", listOf("indie", "alternative")),
        Genre("folk", "Folk", listOf("folk")),
        Genre("country", "Country", listOf("country")),
        Genre("60s70s", "Années 60-70", listOf("60s", "70s", "oldies")),
        Genre("80s", "Années 80", listOf("80s")),
        Genre("90s", "Années 90", listOf("90s")),
        Genre("2000s", "Années 2000", listOf("2000s")),
        Genre("chill", "Chill & Lounge", listOf("chillout", "lounge")),
        Genre("ambient", "Ambient", listOf("ambient")),
        Genre("kpop", "K-pop", listOf("k-pop", "kpop")),
        Genre("arabe", "Musique arabe", listOf("arabic")),
        Genre("bollywood", "Bollywood", listOf("bollywood")),
        Genre("monde", "Musiques du monde", listOf("world")),
        Genre("gospel", "Gospel", listOf("gospel")),
        Genre("bo", "Musiques de films", listOf("soundtrack", "film")),
        Genre("enfants", "Enfants", listOf("children", "kids")),
        Genre("infos", "Infos", listOf("news")),
        Genre("sport", "Sport", listOf("sport")),
        Genre("talk", "Débats & talk", listOf("talk")),
        Genre("humour", "Humour", listOf("comedy")),
    )

    private val byKey = ALL.associateBy { it.key }

    fun find(key: String): Genre? = byKey[key] ?: if (key.startsWith("tag:")) {
        val tag = key.removePrefix("tag:")
        Genre(key, tag.replaceFirstChar(Char::uppercase), listOf(tag))
    } else null

    /** Étiquettes déjà couvertes par un onglet (masquées dans « Autres genres »). */
    val coveredTags: Set<String> = ALL.flatMap { it.tags }.toSet()

    /** Étiquettes trop vagues pour être un genre. */
    val genericTags = setOf(
        "music", "musique", "radio", "fm", "am", "online", "internet", "webradio", "local", "various",
        "misc", "public radio", "community radio", "hits", "top 40", "top40", "adult contemporary",
        "entertainment", "information", "culture", "college radio", "student", "university", "pública",
        "noticias", "musica", "música", "ac", "chr", "commercial", "regional", "community",
    )
}
