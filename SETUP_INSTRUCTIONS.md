# VisionAid - Android Studio Setup

## Open the project

1. Open Android Studio.
2. Choose File > Open.
3. Select this folder:

   `C:\Users\vishu\OneDrive\Documents\MOORSECODE\VisionAid123456_unzipped\VisionAid_AndroidStudio_Enhanced`

4. Let Gradle sync finish.
5. Connect an Android phone with USB debugging enabled.
6. Press Run.

The project already includes:

- `app/src/main/assets/yolov8n.tflite`
- `app/src/main/assets/coco_labels.txt`
- CameraX preview
- YOLO object detection overlay
- WiFi ESP32 sensor display
- Text-to-speech and vibration alerts

## ESP32 sensor setup

The app polls this URL:

`http://10.56.55.158/sensor`

To change it, edit:

`app/src/main/java/com/visionaid/app/bluetooth/WiFiSensorManager.kt`

Change this value:

```kotlin
const val ESP32_IP = "10.56.55.158"
```

Your ESP32 `/sensor` endpoint should return JSON like this:

```json
{
  "distance": 42.5,
  "tilt": false,
  "slopeDown": false,
  "slopeUp": false,
  "tiltLeft": false,
  "tiltRight": false,
  "shake": false,
  "ax": 0.02,
  "ay": -0.01,
  "az": 1.00
}
```

Only `distance` is required. All other fields have safe defaults.

## What appears in the app

- Camera preview with object detection boxes.
- Top-right ESP32 connection status.
- Sensor card showing distance, tilt/orientation, and MPU X/Y/Z values.
- Red alert banner for close obstacle, tilt, shake, or large MPU movement.
- Start/Stop button for AI detection.

## Build check

This copy was verified with:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-22'
./gradlew.bat :app:assembleDebug
```

The debug APK is created at:

`app/build/outputs/apk/debug/app-debug.apk`
