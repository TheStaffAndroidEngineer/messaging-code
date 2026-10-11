# Third-party materials

Original companion code is MIT licensed; see [LICENSE](LICENSE). The materials below retain their own terms.

## Files included in this repository

- **Gradle wrapper** (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`): copied from the book's pinned Gradle 9.8.0 checkpoint. The distribution's [Apache 2.0 licence](gradle/GRADLE-LICENSE.txt) and [notice inventory](gradle/GRADLE-NOTICE.txt) are retained. The wrapper downloads the pinned distribution when needed.
- **Google Sans** (`app/src/main/res/font/google_sans_*.ttf`): the same static font assets used by the Common Goods companion. Copyright 2025 The Google Sans Project Authors; [SIL Open Font License 1.1](app/src/main/assets/licenses/google-sans-OFL.txt).
- **Sora** (`app/src/main/res/font/sora_bold.ttf`): the same static font asset used by the Common Goods companion. Copyright 2019 The Sora Project Authors; [SIL Open Font License 1.1](app/src/main/assets/licenses/sora-OFL.txt).

The product drawings, fixture text and generated geometric media are original companion material. No retailer branding or campaign assets are included.

## Dependencies fetched during a build

Gradle resolves Android/AndroidX/Compose, Kotlin/coroutines, OkHttp/Okio and test/build dependencies from the declared repositories. Versions and direct selections are recorded in `gradle/libs.versions.toml`, `build.gradle.kts` and `app/build.gradle.kts`; unused catalog aliases do not mean those libraries are included in the app. Their published packages carry their own licences and notices. Preserve the notices applicable to resolved dependencies when packaging a binary for public distribution. This local source checkpoint is not a completed public binary distribution package.
