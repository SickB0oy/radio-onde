# Radio Onde – radio libre pour Android

Appli radio gratuite, sans pub ni pistage, qui va plus loin que les autres :

- **Pause du direct** : en pause, la suite est mise de côté en mémoire pour reprendre là où tu t'étais arrêté,
  puis « +30 s » ou « Revenir au direct » pour rattraper. Pendant l'écoute, rien n'est gardé, et rien n'est écrit sur le téléphone.
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

## Comment marche la pause du direct

```
Station (HTTP/ICY) ──► StreamRecorder ──► RingBuffer (en mémoire, 24 Mo max)
                         │ repère les titres            │
                         ▼                              ▼
                    Historique            TimeShiftDataSource ──► ExoPlayer ──► MediaSession
```

- `StreamRecorder` reçoit le flux, retire les métadonnées ICY et note chaque changement de titre.
- `RingBuffer` est en mémoire vive : ce qui a été écouté est oublié aussitôt ; il ne grossit
  que pendant une pause, et il est libéré quand on change de station.

Limites connues :
- Flux **HLS** (`.m3u8`) et flux qui refusent la connexion directe : lecture normale, sans pause du direct.
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
