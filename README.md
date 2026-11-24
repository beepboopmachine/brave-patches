# Brave Browser Morphe Patches

A modular, high-performance [Morphe](https://github.com/MorpheApp) patch suite for **Brave Browser for Android** (`com.brave.browser`), featuring native multi-threaded HLS & media downloading, true AMOLED black theming, player gestures, aggressive debloating, privacy hardening, and performance optimizations.

---

## Features & Patch Suite

### 1. Media & Downloader
* **Native Media & HLS Downloader**: Injects a custom download action button directly into the top toolbar next to Brave Shields. Automatically detects video, audio, and streaming formats (MP4, WebM, M3U8 HLS playlists, fMP4, TS segments) across web pages, embedded iframes, and YouTube streams.
* **Multi-threaded Stream Downloader & Muxer**: Downloads HLS segments concurrently with background notification progress, supports AES-128 decrypting, and muxes video/audio streams into a clean, seekable MP4 file.
* **Force Picture-in-Picture**: Enforces HTML5 video Picture-in-Picture support on all video players.

### 2. UI & Appearance
* **AMOLED Black Theme**: Replaces dark-gray surfaces (`#1F1F1F`) across dark mode, the omnibox address bar, dropdown menus, and settings with true pitch black (`#000000`) for maximum OLED battery savings and aesthetic contrast.
* **Debloat Menu & Settings**: Strips unwanted promotional entries from the 3-dot overflow menu and main settings page (Brave Wallet, Leo AI, Brave News, Brave Talk, Brave Rewards, and Brave VPN).
* **Fullscreen Player Gestures**: Adds intuitive touch gestures to fullscreen web video players:
  * Left-side vertical swipe: Brightness control
  * Right-side vertical swipe: Volume control
  * Horizontal swipe: Video seek
  * Double tap: Fast forward / rewind
* **Clean New Tab Page**: Streamlines the new tab page by removing sponsored tiles, widgets, and background telemetry.
* **Force Copy/Paste**: Overrides JavaScript handlers that block text selection, copy-pasting, or context menus.
* **Tab Usability**: Preserves the last active tab from accidental closes and closes tabs according to tab group order.

### 3. Privacy & Tracking Protection
* **Strip Tracking Parameters**: Intercepts URL navigations and cleans tracking query parameters (such as `utm_*`, `fbclid`, `gclid`, `mc_eid`, etc.) before loading.
* **Allow Incognito Screenshots**: Re-enables taking screenshots and viewing recents thumbnails inside Incognito tabs (`FLAG_SECURE` removal).
* **Block Motion & Orientation Sensors**: Disables Chromium's `PlatformSensorProvider` to prevent fingerprinting or sensor-based orientation profiling.
* **Block Promo Notifications & Dialogs**: Disables in-app rating requests and onboarding promotion popups.

### 4. Performance & Resource Optimization
* **Force Max Refresh Rate**: Unlocks high display refresh rates (90Hz / 120Hz / 144Hz) consistently across compatible devices.
* **Startup Optimization**: Defers non-critical startup tasks to accelerate initial browser launch.
* **Telemetry & Locale Trim**: Eliminates background metrics dispatching and cleans unused locale data to lower memory usage.
* **Stop Background Wakeups**: Prevents aggressive background wakeups and alarm triggers.
* **Remove Unused AR/XR**: Strips unused Virtual/Augmented Reality modules to shrink memory and binary size.

### 5. Standalone Clone & Branding
* **Clone Package Branding**: Enables side-by-side installation alongside official Brave by altering the package namespace to `com.brave.browser.clone` ("Brave Mod"). Includes custom Android Adaptive Vector Icons with Material You monochrome support.

---

## Compatibility

| Target Application | Target Version | Target Architecture | Minimum Android Version |
|:---|:---|:---|:---|
| **Brave Browser Android** (`com.brave.browser`) | `1.100.58` (`versionCode: 430005804`) | `arm64-v8a`, `armeabi-v7a`, `x86_64` | Android 8.0+ (API 26+) |

---

## Project Structure

```
├── patches/                  # Morphe patches written in Kotlin
│   ├── src/main/kotlin/dev/brave/patches/
│   │   ├── clone/            # Side-by-side package cloning & branding
│   │   ├── core/             # Core tab tracking & lifecycle hooks
│   │   ├── gestures/         # Fullscreen player touch gestures
│   │   ├── media/            # Media sniffer, Hls downloader & PiP
│   │   ├── perf/             # Refresh rate, startup & background optimizations
│   │   ├── privacy/          # Parameter stripping, sensors, incognito screenshots
│   │   ├── shared/           # Constants and shared utilities
│   │   └── ui/               # AMOLED black, debloating, and tab order
│   └── build.gradle.kts      # Patches compilation & bytecode verification
├── extensions/               # Injected Java extensions & Chromium stubs
│   ├── brave/                # Extension code compiled into dex
│   │   ├── src/main/java/dev/brave/extension/
│   │   │   ├── core/         # ActiveTabTracker, JsInjectionHub
│   │   │   ├── gestures/     # Touch overlays & Chromium JS bridge
│   │   │   ├── media/        # M3U8 parser, MediaDownloadService, stream resolver
│   │   │   ├── perf/         # Refresh rate helper, startup helper
│   │   │   ├── privacy/      # UrlStripper
│   │   │   └── ui/           # AmoledColorHelper, MenuHelper
│   │   └── stub/             # Chromium compile-time API stubs
│   └── proguard-rules.pro    # Proguard retention rules
├── branding/                 # Adaptive vector icon assets for cloned build
├── docs/                     # Architectural specs and QA verification checklist
├── patches-list.json         # Generated patch metadata and targets list
├── build.gradle.kts          # Root Gradle configuration
└── settings.gradle.kts       # Gradle plugin & extension configuration
```

---

## Building from Source

### Prerequisites
* **Java Development Kit (JDK)**: JDK 17 or JDK 21
* **Android SDK**: Build-Tools and Platform SDK 34+
* **Gradle**: 8.x+ (Gradle wrapper `9.9.1` included)

### Build the Patches JAR
Run the Gradle wrapper:

```bash
# On Linux / macOS:
./gradlew build

# On Windows:
.\gradlew.bat build
```

This compiles both the extensions and patches, runs API compatibility checks, and outputs:
```
patches/build/libs/patches-<version>.jar
```

To update the `patches-list.json` metadata file:
```bash
./gradlew generatePatchesList
```

---

## Patching Brave Browser

### Using Morphe CLI
1. Download or compile the patches JAR: `patches-<version>.jar`.
2. Obtain the clean target APK (`com.brave.browser` v1.100.58).
3. Run the Morphe CLI:

```bash
java -jar morphe-cli.jar patch \
  --patches patches-1.0.0.jar \
  --package com.brave.browser \
  --out Brave-Patched.apk \
  base.apk
```

### Using Morphe Manager / ReVanced Manager
1. In Manager Settings, configure the custom sources to point to this repository or load the compiled `patches-<version>.jar`.
2. Select **Brave Browser** (`v1.100.58`).
3. Select your desired patches from the patch list.
4. Tap **Patch** and install.

---

## Contributing & Development
1. Fork and clone the repository.
2. Submit code conforming to existing Morphe Kotlin conventions.
3. Test builds with `./gradlew build` prior to submitting a Pull Request.

---

## Disclaimer & License
* This project is an independent open-source patch suite and is not affiliated with, endorsed by, or associated with Brave Software, Inc.
* Distributed under the **GNU General Public License v3.0 (GPLv3)**.
