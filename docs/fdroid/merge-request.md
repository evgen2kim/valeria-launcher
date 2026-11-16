## Add Valeria Launcher

Valeria Launcher is a maintained GPL-3.0-or-later fork of Victoria Launcher with its own Android application ID, `dev.valerialauncher`, and its own name and icon.

Favorites, including shortcuts and folders, can appear or disappear according to Wi-Fi, time windows, connected headphones, or daily session counts. The rules run locally while the launcher is visible. Valeria also has a dedicated swipe-up search screen with the keyboard ready. Wi-Fi and session conditions require the corresponding optional Android permissions.

Release and reference APK: https://github.com/evgen2kim/valeria-launcher/releases/tag/v0.77.1-valeria.2

The APK has no internet permission. The source repository contains the English and Russian listing and release notes. The recipe uses the APK published by the developer as the reference for reproducible verification and checks only Valeria release tags.

<!-- Submit on or after January 4, 2027. -->
Independent development began on October 4, 2026. The maintainer uses Valeria daily.

Validation: the recipe passed `fdroid lint`. The official F-Droid Linux build image built version 95 from commit `0f55f06c551054f2e2460d39fd1f87fab3610bac`. The result accepted the developer APK signature and matched the reference APK byte for byte.
