# Implementation Plan - Fix Layout Render Issues

The user is experiencing a layout fidelity warning in the Android Studio Layout Editor (mistakenly referred to as "Compose Preview") because the project is using `compileSdk = 37`, while the current renderer only supports up to API 36. This can cause inaccurate previews or crashes in the layout editor.

## User Review Required

> [!IMPORTANT]
> The project currently uses `compileSdk = 37`, which is an experimental/future SDK version. I will lower it to `35` (Android 15) to match the `targetSdk` and ensure compatibility with the layout renderer.

## Proposed Changes

### Build Configuration

#### [MODIFY] [app/build.gradle.kts](file:///Users/tizan/AndroidStudioProjects/ESPAUTO/app/build.gradle.kts)
- Change `compileSdk` from `37` to `35`.
- Change `kotlinOptions.jvmTarget` and `compileOptions` to `JavaVersion.VERSION_17` (recommended for modern Android development and AGP 8.10.0) if necessary, but I'll stick to 11 first to avoid breaking anything unless needed. Wait, AGP 8.10.0 usually requires Java 17+. I should check if it's already working with 11. The build was successful with 11, so I'll leave it.

### UI Improvements (Optional but recommended for layout fidelity)

#### [MODIFY] [activity_main.xml](file:///Users/tizan/AndroidStudioProjects/ESPAUTO/app/src/main/res/layout/activity_main.xml)
- Remove `app:layout_scrollFlags` from `RelativeLayout` as it is not a child of `AppBarLayout` and this attribute is ignored/invalid there, which can sometimes clutter lint/render logs.

## Verification Plan

### Automated Tests
- Run `./gradlew app:assembleDebug` to ensure the project still builds correctly.

### Manual Verification
- The layout fidelity warning in Android Studio should disappear after the SDK change and a Gradle sync.
- Verify that `activity_main.xml` renders correctly in the Layout Editor.
