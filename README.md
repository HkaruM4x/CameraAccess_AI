# Camera Access App

A sample Android application demonstrating integration with Meta Wearables Device Access Toolkit. This app showcases streaming video from Meta AI glasses, capturing photos, recording video, and managing connection states.

## Features

- Connect to Meta AI glasses
- Stream camera feed from the device (MEDIUM quality, 24 fps)
- Timestamp-based frame presentation queue for smooth playback
- Background video frame processing via `Dispatchers.Default`
- Capture photos from glasses (supports both Bitmap and HEIC formats with EXIF orientation handling)
- Save captured photos to device gallery (`Pictures/CameraAccess`)
- Share captured photos via Android share sheet
- Record video from the camera stream
- Import media from the device
- Mock device kit support for development without physical hardware

## Prerequisites

- Android Studio Arctic Fox (2021.3.1) or newer
- JDK 11 or newer
- Android SDK 31+ (Android 12.0+)
- Meta Wearables Device Access Toolkit (included as a dependency)
- A Meta AI glasses device for testing (optional — use Mock Device Kit for development)

## Building the app

### Using Android Studio

1. Clone this repository
1. Open the project in Android Studio
1. Add your personal access token (classic) to the `local.properties` file (see [SDK for Android setup](https://wearables.developer.meta.com/docs/getting-started-toolkit/#sdk-for-android-setup))
1. Click **File** > **Sync Project with Gradle Files**
1. Click **Run** > **Run...** > **app**

## Running the app

1. Turn 'Developer Mode' on in the Meta AI app.
1. Launch the app.
1. Press the "Connect" button to complete app registration.
1. Once connected, the camera stream from the device will be displayed.
1. Use the on-screen controls to:
   - Capture photos
   - View and save captured photos
   - Share captured photos
   - Record video from the stream
   - Import media from the device
   - Disconnect from the device

## Mock Device Kit

The app includes a Mock Device Kit screen for testing streaming and photo capture functionality without physical Meta AI glasses hardware. This is useful during development and UI iteration.

## Troubleshooting

For issues related to the Meta Wearables Device Access Toolkit, please refer to the [developer documentation](https://wearables.developer.meta.com/docs/develop/) or visit our [discussions forum](https://github.com/facebook/meta-wearables-dat-android/discussions)

## License

This source code is licensed under the license found in the LICENSE file in the root directory of this source tree.
