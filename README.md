# CameraAccess_AI

> **See the world through AI-powered smart glasses.**

An Android application that connects to **Meta Ray-Ban smart glasses** and uses the live camera feed to perform **real-time AI scene analysis** using OpenAI GPT-4o Vision. Point your glasses at anything — food, objects, environments — and receive instant AI insights directly on your phone screen.

---

## What makes this AI-powered

Every frame captured by the glasses is analyzed by **OpenAI GPT-4o**, one of the most capable vision AI models available. The AI doesn't just describe what it sees — it reasons about it:

- 🍔 **Food** → estimates calories, macronutrients, portion size
- 🏪 **Environments** → describes the scene and provides relevant context
- 🧪 **Objects** → identifies items and shares useful information about them
- 💬 **Your questions** → set a focus before you start (e.g. *"I'm tracking my macros today"*) and the AI tailors every response to your goal

The AI updates its analysis every 3 seconds, creating a continuous live commentary of what your glasses see — hands-free.

---

## How it works

```
You wear Meta Ray-Ban glasses
        ↓
Glasses camera captures what you see
        ↓
Android app receives the live video feed
        ↓  (one frame every 3 seconds)
Frame compressed and sent to OpenAI GPT-4o Vision API
        ↓
AI analyzes the image and responds in 2–3 sentences
        ↓
Response appears as a live text overlay on your phone screen
```

---

## Modes

### AI Analyst Mode ✨
The core feature of this app. Live AI analysis of everything your glasses see:
- Stream the glasses camera feed to your phone
- AI analyses one frame every 3 seconds using GPT-4o Vision
- Results appear as a text card overlay on the live video
- Optional **session context** — type what you want the AI to focus on before you start, and it remembers that goal for the entire session

**Example session contexts:**
- *"I have a nut allergy, flag anything that might contain nuts"*
- *"I'm a student studying architecture, describe what you see in detail"*
- *"I'm counting calories, estimate the nutritional value of any food"*

### Camera Mode
Standard camera access features — stream, capture photos, record video, and import media from the glasses. Built on Meta's Device Access Toolkit (DAT) SDK.

---

## Requirements

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 11 or newer
- Android SDK 31+ (Android 12.0+)
- Meta Ray-Ban glasses with Developer Mode enabled
- An OpenAI API key (for AI Analyst mode)
- Meta Wearables Device Access Toolkit (included as a dependency)

---

## Setup

### 1. Clone the repo

```bash
git clone https://github.com/HkaruM4x/CameraAccess_AI.git
cd CameraAccess_AI
```

### 2. Configure `local.properties`

Create or edit `local.properties` in the project root. **This file is gitignored — never commit it as it contains your API keys.**

```properties
sdk.dir=/path/to/your/Android/sdk
username=YOUR_META_DAT_USERNAME
github_token=YOUR_META_DAT_GITHUB_TOKEN
openai_api_key=YOUR_OPENAI_API_KEY
```

- `username` + `github_token` — from the [Meta Wearables DAT setup guide](https://wearables.developer.meta.com/docs/getting-started-toolkit/#sdk-for-android-setup)
- `openai_api_key` — from [platform.openai.com](https://platform.openai.com)

### 3. Sync and build

- Open the project in Android Studio
- Click **File → Sync Project with Gradle Files**
- Connect your Android phone via USB with USB Debugging enabled
- Click **Run → Run app**

### 4. Enable Developer Mode on your glasses

1. Open the **Meta AI app** on your phone
2. Go to your Ray-Ban glasses settings
3. Enable **Developer Mode**
4. Enable **SDK access**

---

## User Flow

```
1. Launch app on your Android phone
2. Register the app with Meta AI (one-time setup)
3. Put on your Ray-Ban glasses
4. On the NonStream screen — glasses connect automatically via Bluetooth
5. Optional: type a session context (e.g. "Identify food and estimate calories")
6. Tap "AI Analyst"
7. App requests camera permission from your glasses
8. Live video feed appears on your phone with AI analysis overlay
   ┌─────────────────────────────────┐
   │      [live glasses feed]        │
   │                    ● Analyzing… │
   │ ┌─────────────────────────────┐ │
   │ │ "I can see a Caesar salad   │ │
   │ │  with grilled chicken.      │ │
   │ │  ~380 cal, 32g protein."    │ │
   │ └─────────────────────────────┘ │
   │                                 │
   │       [ Stop AI Stream ]        │
   └─────────────────────────────────┘
9. Tap "Stop AI Stream" when done
```

---

## Project Structure

```
app/src/main/java/.../cameraaccess/
│
├── aistream/                  # AI Analyst mode (isolated feature)
│   ├── AIStreamViewModel.kt   # Manages stream + sends frames to AI
│   ├── AIStreamUiState.kt     # Screen state (video, AI text, loading)
│   ├── AIAnalystProcessor.kt  # OpenAI GPT-4o API caller (3s throttle)
│   └── AIStreamScreen.kt      # UI: live video + AI text overlay
│
├── stream/                    # Camera mode (DAT SDK streaming)
│   ├── StreamViewModel.kt
│   ├── VideoRecorder.kt       # H.264 + AAC MP4 recording
│   ├── FaceBlurProcessor.kt   # ML Kit face detection + blur
│   └── ...
│
├── wearables/                 # Shared device/navigation state
└── ui/                        # Shared screens and UI components
```

---

## Tech Stack

| Technology | Role |
|---|---|
| **OpenAI GPT-4o Vision** | AI scene analysis from camera frames |
| **Meta Wearables DAT SDK** | Connect to Ray-Ban glasses, receive live video |
| **OkHttp** | HTTP client for OpenAI API calls |
| **Jetpack Compose** | Android UI framework |
| **Kotlin Coroutines** | Async stream processing and network calls |
| **Google ML Kit** | Face detection for blur feature |
| **Android MediaCodec** | Hardware H.264/AAC video encoding |

---

## Branches

| Branch | Description |
|---|---|
| `main` | Stable base — original Meta DAT camera sample |
| `dev` | Active development — includes AI Analyst feature |

---

## License

Licensed under the license found in the [LICENSE](LICENSE) file in the root directory of this source tree.
