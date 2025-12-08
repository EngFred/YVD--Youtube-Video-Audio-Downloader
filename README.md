# YVD - Modern Android YouTube Downloader ( Donwnload link below )

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4285F4?style=for-the-badge&logo=android&logoColor=white)
![Hilt](https://img.shields.io/badge/Hilt-Dependency%20Injection-orange?style=for-the-badge)
![WorkManager](https://img.shields.io/badge/WorkManager-Background%20Sync-red?style=for-the-badge&logo=android&logoColor=white)

[![Download APK](https://img.shields.io/github/v/release/EngFred/YVD?style=for-the-badge&label=Download%20APK&color=success&logo=android)](https://github.com/EngFred/YVD/releases/download/v1.2.4/yvd_v1.2.4.apk)

**YVD** is a lightweight, high-performance native Android application built with Kotlin and Jetpack Compose. It allows users to download YouTube videos and audio only instantly without the bloat of external binarie and at very fast download speeds.
YVD uses the NewPipe Extractor engine to parse metadata and OkHttp for raw stream downloading. This results in instant startup times and a significantly smaller APK size.

---

### App Interface
<img src="https://github.com/user-attachments/assets/daacf372-1b8a-411e-bc60-bc5053cd84ef" width="250">
<img src="https://github.com/user-attachments/assets/14d92a4a-4791-448c-a09e-db1df5cb83ce" width="250">
<img src="https://github.com/user-attachments/assets/be771b28-d628-4dc2-9505-e71a7019c7d2" width="250">
<img src="https://github.com/user-attachments/assets/fcfdcb9b-8a40-45b7-aaa8-3e47288b6592" width="250">
<img src="https://github.com/user-attachments/assets/248bf8b9-6cd7-420a-8e78-3c975748442f" width="250">
<img src="https://github.com/user-attachments/assets/569bcda9-ce6d-465b-985d-da8c5e277d55" width="250">

---

## Tech Stack & Architecture

This project was built to demonstrate modern Android development best practices.

### Architecture
The app follows **Clean Architecture** with the **MVVM (Model-View-ViewModel)** pattern:

* **Presentation Layer:** Jetpack Compose (UI) + ViewModels (State Management).
* **Domain Layer:** Data models and Repository interfaces (Pure Kotlin).
* **Data Layer:** Repository implementations, Native library wrappers, and File handling.
* **Worker Layer:** Android WorkManager for guaranteed background execution.

### Libraries & Tools
* **Language:** [Kotlin](https://kotlinlang.org/) (100%)
* **UI:** [Jetpack Compose](https://developer.android.com/jetpack/compose) (Material 3 Design)
* **Dependency Injection:** [Hilt](https://dagger.dev/hilt/)
* **Asynchronous Processing:** [Coroutines & Flow](https://kotlinlang.org/docs/coroutines-overview.html)
* **Background Tasks:** [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager)
* **Image Loading:** [Coil](https://coil-kt.github.io/coil/)
* **Navigation:** Compose Navigation

---

## Key Features

* **Smart Clipboard Detection:** Automatically detects copied YouTube links and loads metadata immediately.
* **Background Downloading:** Downloads continue seamlessly even when the app is closed or the screen is off using **WorkManager**.
* **Smart Notifications:** Persistent progress bars in the notification tray and actionable "Success" alerts that play the video instantly when tapped.
* **Format Selection:** Parses available video/audio streams and presents user-friendly resolution options (1080p, 720p, 480p, etc.).
* **Progressive Stream Support:** Automatically filters for "Progressive" streams (Video + Audio combined) to ensure playable files without needing CPU-intensive merging.
* **Audio/Video Merging:** Automatically merges high-quality video streams (which usually lack audio).
* **Built-in Preview:** Leverages Android `FileProvider` to securely open and play downloaded files immediately within the app.

---

## Technical Highlights (Under the Hood)

### 1. Robust Background Execution
Implemented **WorkManager** with **Foreground Services** to ensure downloads survive app termination.
* **Challenge:** Android 14+ restricts background work and requires strict Foreground Service Types.
* **Solution:** Configured `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` and managed Notification lifecycles manually within the Worker to prevent system kills during large file downloads.


### 2. State Management
The UI is driven by a `Sealed Class` state machine to ensure the UI is always in a valid state.
```kotlin
data class HomeState(
    val urlInput: String = "",
    val isFormatDialogVisible: Boolean = false,
    val isCancelDialogVisible: Boolean = false,
    val isThemeDialogVisible: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val videoMetadata: VideoMetadata? = null,
    val downloadProgress: Float = 0f,
    val downloadStatusText: String = "",
    val isDownloading: Boolean = false,
    val downloadComplete: Boolean = false,
    val downloadedFile: File? = null,
    val isAudio: Boolean = false
)
```
### 3. Granular Flow Updates
The Repository uses callbackFlow to emit a sealed DownloadStatus class. This allows the UI to reactively differentiate between continuous numeric progress updates, distinct success events (returning a File object), and error states without callback hell.

### 4. Secure File Access
The app targets Android 10+ (Scoped Storage) but utilizes FileProvider to securely share the downloaded file Uri with external video player apps. It grants temporary read permissions via Intent.FLAG_GRANT_READ_URI_PERMISSION, ensuring the app remains secure while interacting with the Android ecosystem.
