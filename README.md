# Featherize

Application Android native (Kotlin + Jetpack Compose) qui compresse en masse photos et vidéos directement sur l'appareil, sans passer par un serveur.

## Fonctionnalités

- Sélection multiple de médias depuis la galerie (photos et vidéos)
- Compression par presets : **Léger**, **Moyen**, **Fort**
- Traitement en file d'attente avec suivi de progression
- Export local via `FileProvider`, sans upload réseau
- Service en foreground pour les compressions longues

## Stack technique

- Kotlin, Jetpack Compose, Material 3
- Coroutines pour le traitement asynchrone
- Coil pour le chargement d'images/vidéos
- Gradle Kotlin DSL

## Prérequis

- Android Studio (dernière version stable)
- JDK 17
- SDK Android : `minSdk 24`, `targetSdk 36`

## Démarrage

```bash
./gradlew assembleDebug
```

Ou ouvrir le projet dans Android Studio et lancer sur un émulateur/appareil.

## Structure du projet

```
app/src/main/java/com/featherize/app/
├── domain/       # Modèles et logique de compression (image, vidéo, presets)
├── data/         # Accès aux médias (MediaRepository)
├── service/      # File de compression et service foreground
├── viewmodel/    # État de l'écran (CompressionViewModel)
└── ui/           # Écrans et composants Compose
```

## Permissions

L'app demande l'accès aux médias (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`) et notifie l'utilisateur pendant la compression via une notification foreground. L'accès complet au stockage (`MANAGE_EXTERNAL_STORAGE`) est optionnel et activable manuellement dans les réglages de l'app.

## Licence

Distribué sous licence [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/) — voir [LICENSE](LICENSE). Usage et modification libres, republication des versions dérivées sous la même licence obligatoire, usage commercial soumis à autorisation.
