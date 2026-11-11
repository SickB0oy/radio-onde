package app.onde.radio.data

import java.util.Locale

/**
 * Noms français des villes et régions les plus courantes dans l'annuaire Radio-Browser, où ils
 * sont souvent écrits en anglais. Traduction faite sur le téléphone, sans service en ligne :
 * les noms absents de la table restent tels quels.
 */
object PlaceNames {

    private val FR: Map<String, String> = mapOf(
        // Europe – villes
        "london" to "Londres", "brussels" to "Bruxelles", "vienna" to "Vienne", "lisbon" to "Lisbonne",
        "venice" to "Venise", "genoa" to "Gênes", "seville" to "Séville", "geneva" to "Genève",
        "basel" to "Bâle", "bern" to "Berne", "edinburgh" to "Édimbourg", "moscow" to "Moscou",
        "saint petersburg" to "Saint-Pétersbourg", "st. petersburg" to "Saint-Pétersbourg",
        "warsaw" to "Varsovie", "bucharest" to "Bucarest", "athens" to "Athènes",
        "copenhagen" to "Copenhague", "the hague" to "La Haye", "antwerp" to "Anvers", "ghent" to "Gand",
        "bruges" to "Bruges", "aachen" to "Aix-la-Chapelle", "munich" to "Munich", "cologne" to "Cologne",
        "nuremberg" to "Nuremberg", "hanover" to "Hanovre", "brunswick" to "Brunswick", "mainz" to "Mayence",
        "trier" to "Trèves", "dresden" to "Dresde", "frankfurt" to "Francfort", "frankfurt am main" to "Francfort",
        "hamburg" to "Hambourg", "berlin" to "Berlin", "prague" to "Prague", "cracow" to "Cracovie",
        "krakow" to "Cracovie", "kraków" to "Cracovie", "belgrade" to "Belgrade", "kiev" to "Kiev", "kyiv" to "Kiev",
        "florence" to "Florence", "naples" to "Naples", "rome" to "Rome", "milan" to "Milan", "turin" to "Turin",
        "padua" to "Padoue", "mantua" to "Mantoue", "leghorn" to "Livourne", "bologna" to "Bologne",
        "saragossa" to "Saragosse", "zaragoza" to "Saragosse", "cordoba" to "Cordoue", "córdoba" to "Cordoue",
        "majorca" to "Majorque", "mallorca" to "Majorque", "minorca" to "Minorque", "menorca" to "Minorque",
        "the canary islands" to "Canaries", "canary islands" to "Canaries", "dunkirk" to "Dunkerque",
        "luxembourg" to "Luxembourg", "nicosia" to "Nicosie", "valletta" to "La Valette", "reykjavik" to "Reykjavik",
        // Europe – régions
        "bavaria" to "Bavière", "lower saxony" to "Basse-Saxe", "saxony" to "Saxe", "saxony-anhalt" to "Saxe-Anhalt",
        "north rhine-westphalia" to "Rhénanie-du-Nord-Westphalie", "rhineland-palatinate" to "Rhénanie-Palatinat",
        "thuringia" to "Thuringe", "hesse" to "Hesse", "brandenburg" to "Brandebourg",
        "mecklenburg-vorpommern" to "Mecklembourg-Poméranie-Occidentale",
        "mecklenburg-western pomerania" to "Mecklembourg-Poméranie-Occidentale",
        "baden-wurttemberg" to "Bade-Wurtemberg", "baden-württemberg" to "Bade-Wurtemberg",
        "lower austria" to "Basse-Autriche", "upper austria" to "Haute-Autriche", "styria" to "Styrie",
        "carinthia" to "Carinthie", "tyrol" to "Tyrol", "flanders" to "Flandre", "wallonia" to "Wallonie",
        "ticino" to "Tessin", "grisons" to "Grisons", "scotland" to "Écosse", "wales" to "Pays de Galles",
        "england" to "Angleterre", "northern ireland" to "Irlande du Nord", "cornwall" to "Cornouailles",
        "catalonia" to "Catalogne", "andalusia" to "Andalousie", "basque country" to "Pays basque",
        "galicia" to "Galice", "valencia" to "Valence", "castile and leon" to "Castille-et-León",
        "castile-la mancha" to "Castille-La Manche", "aragon" to "Aragon", "asturias" to "Asturies",
        "balearic islands" to "Baléares", "sicily" to "Sicile", "sardinia" to "Sardaigne", "tuscany" to "Toscane",
        "lombardy" to "Lombardie", "piedmont" to "Piémont", "apulia" to "Pouilles", "calabria" to "Calabre",
        "campania" to "Campanie", "lazio" to "Latium", "latium" to "Latium", "umbria" to "Ombrie",
        "veneto" to "Vénétie", "emilia-romagna" to "Émilie-Romagne", "liguria" to "Ligurie",
        "south tyrol" to "Haut-Adige", "corsica" to "Corse", "brittany" to "Bretagne", "normandy" to "Normandie",
        "burgundy" to "Bourgogne", "crete" to "Crète", "attica" to "Attique", "macedonia" to "Macédoine",
        "north holland" to "Hollande-Septentrionale", "south holland" to "Hollande-Méridionale",
        "zealand" to "Zélande", "bohemia" to "Bohême", "moravia" to "Moravie", "silesia" to "Silésie",
        "transylvania" to "Transylvanie", "dalmatia" to "Dalmatie", "lapland" to "Laponie",
        // Afrique et Moyen-Orient
        "cairo" to "Le Caire", "alexandria" to "Alexandrie", "algiers" to "Alger", "marrakesh" to "Marrakech",
        "fez" to "Fès", "tangier" to "Tanger", "beirut" to "Beyrouth", "jerusalem" to "Jérusalem",
        "mecca" to "La Mecque", "damascus" to "Damas", "baghdad" to "Bagdad", "tehran" to "Téhéran",
        "cape town" to "Le Cap", "addis ababa" to "Addis-Abeba",
        // Asie
        "beijing" to "Pékin", "peking" to "Pékin", "seoul" to "Séoul", "singapore" to "Singapour",
        "new delhi" to "New Delhi", "bombay" to "Bombay", "mumbai" to "Bombay", "calcutta" to "Calcutta",
        "kolkata" to "Calcutta", "bangkok" to "Bangkok", "hanoi" to "Hanoï", "ho chi minh city" to "Hô Chi Minh-Ville",
        "kathmandu" to "Katmandou", "tokyo" to "Tokyo",
        // Amériques
        "new york" to "New York", "new york city" to "New York", "california" to "Californie",
        "florida" to "Floride", "louisiana" to "Louisiane", "pennsylvania" to "Pennsylvanie",
        "virginia" to "Virginie", "west virginia" to "Virginie-Occidentale", "north carolina" to "Caroline du Nord",
        "south carolina" to "Caroline du Sud", "north dakota" to "Dakota du Nord", "south dakota" to "Dakota du Sud",
        "new mexico" to "Nouveau-Mexique", "new jersey" to "New Jersey", "hawaii" to "Hawaï",
        "quebec" to "Québec", "montreal" to "Montréal", "british columbia" to "Colombie-Britannique",
        "new brunswick" to "Nouveau-Brunswick", "nova scotia" to "Nouvelle-Écosse",
        "newfoundland and labrador" to "Terre-Neuve-et-Labrador", "northwest territories" to "Territoires du Nord-Ouest",
        "mexico city" to "Mexico", "havana" to "La Havane", "rio de janeiro" to "Rio de Janeiro",
        "sao paulo" to "São Paulo", "são paulo" to "São Paulo", "patagonia" to "Patagonie",
        // Océanie
        "new south wales" to "Nouvelle-Galles du Sud", "western australia" to "Australie-Occidentale",
        "south australia" to "Australie-Méridionale", "northern territory" to "Territoire du Nord",
        "tasmania" to "Tasmanie",
    )

    /** Nom français si connu, sinon le nom d'origine (nettoyé). */
    fun fr(name: String): String {
        val clean = name.trim()
        return FR[clean.lowercase(Locale.ROOT)] ?: clean
    }
}
