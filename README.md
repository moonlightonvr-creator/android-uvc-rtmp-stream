# UVC Rtmp Stream App for Android

Simple Android app to stream over RTMP using a USB camera, with local replay highlights, private operator notes, and a premium dark studio UI.

## Local install from APK

1. Build the APK locally:
   - ./gradlew assembleDebug
2. Install the APK on your Android device:
   - Open the generated file at app/build/outputs/apk/debug/app-debug.apk
   - Tap it to install
3. If Android blocks installation, enable "Install unknown apps" for your file manager or browser.

## What this build includes

- USB UVC camera preview and RTMP streaming
- Local high-bitrate recording
- 30s/60s/90s/120s replay highlight exports
- Private operator-side chat and audience/subscriber counters
- Vertical 9:16 stream toggle and scene presets

## Libraries

- [UVCCamera](https://github.com/saki4510t/UVCCamera)
- [rtmp-rtsp-stream-client-java](https://github.com/pedroSG94/rtmp-rtsp-stream-client-java)

## License

```
Copyright 2021 Alejandro Rosas

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
