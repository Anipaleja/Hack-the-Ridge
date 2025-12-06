# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project layout

- The Git repo root contains a single Android project in `SensorySafe/`.
- The Gradle root project is `SensorySafe` (`SensorySafe/settings.gradle.kts`) and defines one Android application module: `:app`.

## Build and run

All Gradle commands should be run from the `SensorySafe/` directory (where `gradlew` lives).

- Full build (compile app and run unit tests):
  - `cd SensorySafe && ./gradlew build`
- Assemble a debug APK for the app module:
  - `cd SensorySafe && ./gradlew :app:assembleDebug`
- Clean build outputs:
  - `cd SensorySafe && ./gradlew clean`

The app is typically run via Android Studio using the `app` run configuration, which builds and deploys the `debug` variant to an emulator or device.

## Linting

Android Lint is provided by the Android Gradle Plugin.

- Lint all variants for the app module:
  - `cd SensorySafe && ./gradlew :app:lint`
- Lint the debug variant only (faster during development):
  - `cd SensorySafe && ./gradlew :app:lintDebug`

## Testing

There are two kinds of tests: local unit tests and instrumented Android tests.

### Local unit tests (JVM)

- Run all unit tests for all modules:
  - `cd SensorySafe && ./gradlew test`
- Run unit tests for the `app` module only:
  - `cd SensorySafe && ./gradlew :app:test`
- Run a single unit test class or method (example):
  - Entire class:
    - `cd SensorySafe && ./gradlew :app:testDebugUnitTest --tests "com.example.sensorysafe.ExampleUnitTest"`
  - Single method:
    - `cd SensorySafe && ./gradlew :app:testDebugUnitTest --tests "com.example.sensorysafe.ExampleUnitTest.addition_isCorrect"`

### Instrumented Android tests (on device/emulator)

Instrumented tests live under `app/src/androidTest/java/` and run on an emulator or physical device.

- Run all instrumented tests for the app module (requires an attached/emulated device):
  - `cd SensorySafe && ./gradlew :app:connectedAndroidTest`

## Code architecture

### Gradle and dependency management

- Root build script: `SensorySafe/build.gradle.kts` applies the Android application plugin via a version catalog alias (`libs.plugins.android.application`).
- Repositories and module inclusion are configured in `SensorySafe/settings.gradle.kts`, which:
  - Sets up `google()`, `mavenCentral()`, and `gradlePluginPortal()` as plugin and dependency repositories.
  - Names the root project `"SensorySafe"` and includes the single module `":app"`.
- Common Gradle settings are defined in `SensorySafe/gradle.properties`, including:
  - `android.useAndroidX=true` and `android.nonTransitiveRClass=true`.
- Dependencies and plugin versions are centralized via the Gradle version catalog in `SensorySafe/gradle/libs.versions.toml`:
  - Defines versions for the Android Gradle Plugin, JUnit, AndroidX test libraries, AppCompat, Material Components, ConstraintLayout, and Navigation.
  - Exposes library coordinates (e.g., `libs.appcompat`, `libs.material`, `libs.navigation.fragment`, `libs.navigation.ui`) and the `android-application` plugin alias.

### Android application module (`app`)

- Module build script: `SensorySafe/app/build.gradle.kts`.
  - `namespace = "com.example.sensorysafe"` and `applicationId = "com.example.sensorysafe"`.
  - `minSdk = 24`, `targetSdk = 36`, `compileSdk` set via the `release(36)` API.
  - `buildTypes` defines a `release` type with ProGuard configuration and `isMinifyEnabled = false`.
  - `compileOptions` target Java 11 (`JavaVersion.VERSION_11`).
  - `buildFeatures` enables `viewBinding = true`.
  - Dependencies:
    - UI and platform: `androidx.appcompat`, Material Components, ConstraintLayout.
    - Navigation: `androidx.navigation` fragment and UI artifacts.
    - Testing: JUnit 4 for local tests, AndroidX JUnit and Espresso for instrumented tests.

### UI and navigation flow

- Entry activity: `MainActivity` (`SensorySafe/app/src/main/java/com/example/sensorysafe/MainActivity.java`).
  - Extends `AppCompatActivity` and uses `ActivityMainBinding` for view binding.
  - Sets the content view from the bound layout and configures the toolbar via `setSupportActionBar(binding.toolbar)`.
  - Obtains a `NavController` for `R.id.nav_host_fragment_content_main` and wires it to the `AppBarConfiguration` using `NavigationUI.setupActionBarWithNavController`.
  - Implements `onSupportNavigateUp` to delegate up-navigation to the `NavController`.
  - Configures the floating action button (`binding.fab`) to show a `Snackbar` placeholder action.

- Navigation is structured around the AndroidX Navigation component:
  - A `NavHostFragment` (referenced by `R.id.nav_host_fragment_content_main`) hosts the navigation graph.
  - Navigation actions are declared in the navigation XML (e.g., `action_FirstFragment_to_SecondFragment`, `action_SecondFragment_to_FirstFragment`) and are triggered from the fragments.

### Fragments

- `FirstFragment` (`SensorySafe/app/src/main/java/com/example/sensorysafe/FirstFragment.java`):
  - Uses `FragmentFirstBinding` for view binding.
  - Inflates its layout in `onCreateView` and retains the binding until `onDestroyView`.
  - In `onViewCreated`, sets up a click listener on `binding.buttonFirst` that navigates to `SecondFragment` via `NavHostFragment.findNavController(...).navigate(R.id.action_FirstFragment_to_SecondFragment)`.

- `SecondFragment` (`SensorySafe/app/src/main/java/com/example/sensorysafe/SecondFragment.java`):
  - Mirrors the pattern of `FirstFragment` using `FragmentSecondBinding`.
  - Configures `binding.buttonSecond` to navigate back to `FirstFragment` using `R.id.action_SecondFragment_to_FirstFragment`.

Both fragments follow the standard pattern of:
- Holding a nullable binding property.
- Initializing it in `onCreateView` via the generated binding class.
- Clearing it in `onDestroyView` to avoid leaks.

### Testing structure

- Local JVM unit tests:
  - Located in `SensorySafe/app/src/test/java/com/example/sensorysafe/`.
  - Example: `ExampleUnitTest` verifies simple logic with JUnit 4 (`assertEquals`).
- Instrumented Android tests:
  - Located in `SensorySafe/app/src/androidTest/java/com/example/sensorysafe/`.
  - Example: `ExampleInstrumentedTest` runs under `AndroidJUnit4`, accesses `InstrumentationRegistry`, and asserts the app package name.

This project currently follows the default single-module Android app structure generated by the Android Studio template, with navigation and view binding enabled and all dependencies managed through the Gradle version catalog.