# CameraAccess_AI

An Android application that connects to **Meta Ray-Ban smart glasses** and uses the live camera feed to perform real-time AI-powered scene analysis via OpenAI GPT-4o. Built on top of Meta's Device Access Toolkit (DAT) SDK.

---

## What it does

The app has two modes:

### Camera Mode
Standard camera access features using the Meta DAT SDK:
- Live video stream from the glasses displayed on your phone
- Photo capture (Bitmap + HEIC with EXIF orientation handling), saved to gallery
- Video recording with microphone audio (H.264 + AAC MP4), saved to gallery
- Toggleable face blur using Google ML Kit face detection
- Import media from the glasses
- Mock Device Kit support for testing without physical hardware

### AI Analyst Mode ✨
A live AI analysis mode powered by OpenAI GPT-4o Vision:
- Streams the live camera feed from the glasses
- Samples one frame every 3 seconds and sends it to GPT-4o for analysis
- Displays AI responses as a text overlay on the live video
- Supports an optional **session context** — type a focus prompt before starting (e.g. *"I'm tracking my calories today"*) and the AI tailors every response to that goal
- Example use case: point the glasses at food and receive a real-time nutritional estimate — calories, macros, portion size

---

## Screenshots / Flow

```
HomeScreen (register with Meta AI)
    ↓
NonStreamScreen (glasses connected)
    ├── Start Stream     → live camera feed
    └── AI Analyst       → type optional context → live AI analysis overlay
```

---

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 11 or newer
- Android SDK 31+ (Android 12.0+)
- Meta Ray-Ban glasses with Developer Mode enabled
- OpenAI API key (for AI Analyst mode)
- Meta Wearables Device Access Toolkit (included as a dependency)

---

## Setup

### 1. Clone the repo

```bash
git clone https://github.com/HkaruM4x/CameraAccess.git
cd CameraAccess
```

### 2. Configure `local.properties`

Create or edit `local.properties` in the project root (this file is gitignored — never commit it):

```properties
sdk.dir=/path/to/your/Android/sdk
username=YOUR_META_DAT_USERNAME
github_token=YOUR_META_DAT_GITHUB_TOKEN
openai_api_key=YOUR_OPENAI_API_KEY
```

- `username` and `github_token` — from the [Meta Wearables DAT setup guide](https://wearables.developer.meta.com/docs/getting-started-toolkit/#sdk-for-android-setup)
- `openai_api_key` — from [platform.openai.com](https://platform.openai.com)

### 3. Sync and build

- Open the project in Android Studio
- Click **File → Sync Project with Gradle Files**
- Click **Run → Run app** with your Android phone connected via USB

### 4. On the phone

1. Enable Developer Options on your Android phone (tap Build Number 7 times in Settings → About Phone)
2. Enable USB Debugging (Settings → Developer Options → USB Debugging)
3. Connect via USB cable — accept the "Allow USB debugging?" prompt

### 5. On the glasses

1. Open the Meta AI app on your phone
2. Enable Developer Mode for your Ray-Ban glasses
3. Enable SDK access in the developer settings

---

## Project Structure

```
app/src/main/java/.../cameraaccess/
│
├── stream/                    # Camera mode (DAT SDK streaming, recording, face blur)
│   ├── StreamViewModel.kt
│   ├── StreamUiState.kt
│   ├── StreamScreen.kt        (in ui/)
│   ├── VideoRecorder.kt
│   ├── FaceBlurProcessor.kt
│   ├── PresentationQueue.kt
│   └── YuvToBitmapConverter.kt
│
├── aistream/                  # AI Analyst mode (fully isolated)
│   ├── AIStreamViewModel.kt
│   ├── AIStreamUiState.kt
│   ├── AIAnalystProcessor.kt  # OpenAI GPT-4o caller
│   └── AIStreamScreen.kt      (in aistream/)
│
├── wearables/                 # Shared device/navigation state
│   ├── WearablesViewModel.kt
│   └── WearablesUiState.kt
│
└── ui/                        # Shared UI components and screens
    ├── CameraAccessScaffold.kt
    ├── HomeScreen.kt
    ├── NonStreamScreen.kt
    └── ...
```

---

## How the AI Feature Works

```
Ray-Ban glasses (camera)
    ↓  Bluetooth / DAT SDK
Android app receives VideoFrame (I420 YUV)
    ↓  YuvToBitmapConverter
Bitmap
    ↓  AIAnalystProcessor (throttled to 1 frame per 3 seconds)
Compressed JPEG (60% quality, ~40-80KB)
    ↓  Base64 encoded → HTTP POST via OkHttp
OpenAI GPT-4o Vision API (detail: low, max_tokens: 120)
    ↓  JSON response
Text displayed as overlay card on AIStreamScreen
```

**Key design decisions:**
- **3-second throttle** — balances responsiveness with API cost (~$0.06/min)
- **60% JPEG quality** — sufficient for scene understanding, 40–80KB per frame
- **`detail: low`** — OpenAI analyzes at 512×512 internally, ~3× cheaper than high detail
- **Feature isolation** — AI mode lives in its own package and never touches stream/recording code

---

## Dependencies

| Library | Purpose |
|---|---|
| Meta Wearables DAT SDK | Connect to Ray-Ban glasses, receive video |
| OpenAI GPT-4o (REST API) | AI scene analysis |
| OkHttp 4.12 | HTTP client for OpenAI calls |
| Jetpack Compose | UI framework |
| Google ML Kit Face Detection | Face blur feature |
| AndroidX ExifInterface | HEIC photo orientation correction |
| Coil | Image loading for media import |

---

## Branches

| Branch | Description |
|---|---|
| `main` | Stable base — original Meta DAT camera sample app |
| `dev` | Active development — includes AI Analyst feature |

---

## License

This source code is licensed under the license found in the [LICENSE](LICENSE) file in the root directory of this source tree.
