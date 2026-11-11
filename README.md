# Radio Onde – radio libre pour Android

Appli radio gratuite, sans pub ni pistage, qui va plus loin que les autres :

- **Pause du direct** : en pause, la radio est mise de côté en mémoire pour reprendre là où tu t'étais arrêté.
  Pendant l'écoute, rien n'est gardé.
- **Option « Retour en arrière (30 min) »**, désactivée par défaut (Réglages, sur l'accueil) :
  recul/avance de 30 s, réécoute d'un titre, **enregistrement** d'une émission dans `Musique/Radio Onde`.
  Le fichier temporaire est effacé dès qu'on change de station.
- **Historique des titres** de toutes les stations (seulement les noms des morceaux, jamais l'audio).
- **Découverte intelligente** : suggestions calculées sur le téléphone selon ton temps d'écoute par genre, tendances par pays, ambiances.
- Notification, écran verrouillé et Bluetooth via Media3.

Les stations viennent de l'annuaire libre [Radio-Browser](https://www.radio-browser.info).

## Compiler et installer

1. Installe [Android Studio](https://developer.android.com/studio) (gratuit).
2. *File › Open* et choisis ce dossier. Laisse la synchronisation Gradle se terminer
   (la première fois, Android Studio télécharge le SDK et les bibliothèques).
3. Branche ton téléphone (débogage USB activé) et appuie sur ▶ *Run*.

Pour obtenir un APK : *Build › Build App Bundle(s) / APK(s) › Build APK(s)*.
Le fichier arrive dans `app/build/outputs/apk/`.

En ligne de commande : `./gradlew assembleRelease` (il faut le SDK Android et `ANDROID_HOME`).

> La version *release* est signée avec la clé de debug pour pouvoir l'installer tout de suite.
> Crée ta propre clé avant de publier l'appli (F-Droid, IzzyOnDroid, Play Store).

## Comment marche le retour en arrière

```
Station (HTTP/ICY) ──► StreamRecorder ──► RingBuffer (fichier de 80 Mo)
                         │ repère les titres            ▲
                         ▼                              │ lit depuis n'importe quelle position
                    Historique                TimeShiftDataSource ──► ExoPlayer ──► MediaSession
```

- `StreamRecorder` télécharge le flux en continu, retire les métadonnées ICY et note la
  position exacte de chaque changement de titre.
- `RingBuffer` : par défaut en mémoire vive, vidé au fil de l'écoute (il ne grossit que pendant
  une pause, jusqu'à 24 Mo). Avec l'option activée : 30 min dans un fichier du cache, supprimé à l'arrêt.
- `PlaybackEngine` relance ExoPlayer à la position voulue : reculer, avancer, revenir au
  direct ou réécouter un titre revient à changer d'octet de départ.

Limites connues :
- Flux **HLS** (`.m3u8`) et flux qui refusent la connexion directe : lecture normale, sans
  retour en arrière ni enregistrement.
- Flux **Ogg/Opus** : pause possible, mais pas de saut en arrière.
- Les titres dépendent de la station : certaines n'envoient aucune métadonnée.

## Structure

```
app/src/main/java/app/onde/radio/
├── OndeApp.kt                  initialisation
├── data/                       Radio-Browser, favoris/historique (JSON), suggestions
├── player/                     tampon, enregistreur, source Media3, moteur, service
└── ui/                         écrans Compose (Accueil, Lecteur, Historique, Favoris, Recherche)
```

## Idées pour la suite

- Android Auto, widget, réveil radio, minuteur de veille.
- Polices Space Grotesk / Inter de la maquette (à placer dans `res/font`).
- Licence libre (GPL-3.0 conseillée) avant publication sur F-Droid.
