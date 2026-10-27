# Concept Companion MVP

Concept Companion is a Kotlin/Jetpack Compose Android app plus a Python FastAPI backend. The tablet user explicitly shares a PDF or image from Samsung Notes (or picks one with the system document picker). Android copies that content URI to a short-lived cache file, previews it, and uploads it only after **Analyze** is tapped. The backend extracts selectable PDF text when possible and otherwise sends page images to a pluggable vision analyzer. Explanations and user corrections are saved in Room on the tablet; raw imports live only in Android's cache and are deleted when the app process ends or the cache is cleared.

## Project map

- `android/app/src/main/AndroidManifest.xml`: share targets, permissions and launcher activity.
- `android/app/src/main/java/com/conceptcompanion/app/MainActivity.kt`: receive shares, picker, responsive Compose screen and preview.
- `.../AnalysisClient.kt`: multipart API client and local Room session storage.
- `backend/app/main.py`: upload validation, deduplication, processing and API errors.
- `backend/app/analyzers.py`: demo and OpenAI-compatible vision provider implementations.

The API key is read only by the backend from `OPENAI_API_KEY`. Android stores session titles, dates, imported filenames, explanations and edits in Room. Files are uploaded to the configured backend only upon user action; the backend forwards page content to the configured vision provider when enabled. Demo mode performs no external AI transmission. The backend keeps an in-memory SHA-256 keyed result cache for one hour; it does not persist uploaded bytes. Android cache copies remain available for preview until removed/replaced or Android clears the app cache.

## Run the backend (Windows PowerShell)

Requires Python 3.11+.

```powershell
cd outputs\backend
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
Copy-Item .env.example .env
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Leave `ANALYZER=demo` for mock answers. To use the OpenAI-compatible provider, set `ANALYZER=openai`, `OPENAI_API_KEY`, and optionally `OPENAI_MODEL` in `.env`. Configure your tablet's reachable computer IP as the Android API URL at build time using `-PAPI_BASE_URL=http://192.168.x.x:8000/`. Android emulator users can use `http://10.0.2.2:8000/`.

Run backend tests:

```powershell
python -m pip install -r requirements-dev.txt
python -m pytest
```

## Open/build Android app

Requires current Android Studio and a JDK 17 runtime. This source bundle does not include Gradle's generated wrapper binary. For command-line builds, install Gradle 8.13 once (or use an existing Gradle install) and generate the standard wrapper from PowerShell:

```powershell
Invoke-WebRequest https://services.gradle.org/distributions/gradle-8.13-bin.zip -OutFile "$env:TEMP\gradle-8.13-bin.zip"
Expand-Archive "$env:TEMP\gradle-8.13-bin.zip" "$env:TEMP\gradle-8.13" -Force
cd outputs\android
& "$env:TEMP\gradle-8.13\gradle-8.13\bin\gradle.bat" wrapper --gradle-version 8.13
```

Then open `outputs\android` in Android Studio and allow Gradle sync. From PowerShell in that directory:

```powershell
cd outputs\android
.\gradlew.bat testDebugUnitTest assembleDebug
```

The app targets SDK 36 and has minimum SDK 26 (Android 8.0), a practical baseline for the likely Galaxy Tab models. Build for the Android emulator using the default `10.0.2.2` backend address. For a physical tablet, replace the address with your computer's LAN IPv4 address:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug -PAPI_BASE_URL=http://192.168.1.20:8000/
```

To install by USB, enable Developer options (Settings > About tablet > Software information > tap **Build number** seven times), then enable **USB debugging** in Developer options. Connect by a data-capable USB cable, accept the tablet's authorization prompt, then run `adb install -r app\build\outputs\apk\debug\app-debug.apk`, or press Run in Android Studio and choose the tablet.

From Samsung Notes, use Share/Export and choose Concept Companion for a PDF/image. Or launch the app and tap **Choose PDF or image**. Select one item and tap **Analyze**. Multiple incoming shared items are accepted; the first supported item is used in this MVP. Demo mode works with no credentials. For a physical tablet, use its Wi-Fi LAN address in `API_BASE_URL`, and allow inbound TCP port 8000 through Windows Firewall for the private network profile.

## Next milestone

After validating import and explanations, the next milestone can explore a user-controlled floating assistant that coexists with Samsung Notes. That work requires an explicit user action, Android overlay permission and careful testing against Android's background and privacy restrictions; it should not monitor handwriting or capture the screen continuously.
