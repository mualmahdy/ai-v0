# AI-V0 Ultimate — Third-Party Licenses

Copyright 2026 Almujtaba Almahdy

AI-V0 Ultimate is distributed under the Apache License, Version 2.0.

This document records third-party software used by the project.
Third-party components are NOT relicensed under Apache 2.0 merely
because they are distributed with AI-V0 Ultimate.

The license applicable to each component is determined by its own
upstream license and distribution terms.

## 1. Runtime / Application Dependencies

| Component | Coordinate / Family | License | Notes |
|---|---|---|---|
| AndroidX Core | androidx.core | Apache-2.0 | Third-party dependency |
| AndroidX Activity | androidx.activity | Apache-2.0 | Third-party dependency |
| AndroidX Lifecycle | androidx.lifecycle | Apache-2.0 | Third-party dependency |
| AndroidX Navigation | androidx.navigation | Apache-2.0 | Third-party dependency |
| AndroidX Room | androidx.room | Apache-2.0 | Third-party dependency |
| Jetpack Compose | androidx.compose | Apache-2.0 | Third-party dependency |
| Kotlin | org.jetbrains.kotlin | Apache-2.0 | Third-party dependency |
| Kotlin Coroutines | org.jetbrains.kotlinx | Apache-2.0 | Third-party dependency |
| Retrofit | com.squareup.retrofit2 | Apache-2.0 | Third-party dependency |
| OkHttp | com.squareup.okhttp3 | Apache-2.0 | Third-party dependency |
| Moshi | com.squareup.moshi | Apache-2.0 | Third-party dependency |
| Firebase Android SDK | com.google.firebase | Apache-2.0 | Verify exact modules per release |
| ONNX Runtime Android | com.microsoft.onnxruntime | MIT | Third-party dependency |

## 2. Core Library Desugaring

Component:

    com.android.tools:desugar_jdk_libs

License:

    GPLv2 with Classpath Exception

This dependency is used for Java API desugaring and compatibility with
the project's minimum Android API level.

Its license is independent of the AI-V0 Ultimate project license.

The applicable upstream LICENSE file must be retained and made available
where required by the distribution of the component.

## 3. Build and Development Dependencies

The project also uses build/test tooling such as:

- Android Gradle Plugin
- Gradle
- Kotlin compiler/plugin
- KSP
- Google Services Gradle Plugin
- Secrets Gradle Plugin
- Roborazzi
- Robolectric
- AndroidX testing libraries
- JUnit
- Espresso

These components are build-time or test-time dependencies unless a
specific component is packaged into the distributed application.

Their licenses must be evaluated against the resolved dependency graph
for each release.

## 4. Gradle Wrapper

The repository includes the Gradle Wrapper.

The Gradle Wrapper contains third-party material distributed under
the Apache License, Version 2.0.

Existing upstream copyright and license notices must not be removed
or altered.

## 5. Dependency Resolution

The project resolves dependencies from:

- Google Maven
- Maven Central
- Gradle Plugin Portal for plugins

The exact dependency versions used by a release must be determined from
the resolved Gradle dependency graph.

Where dependency locking is enabled, the lock files associated with
the release provide the authoritative resolved versions.

## 6. No Relicensing of Third-Party Software

Nothing in the AI-V0 Ultimate LICENSE grants permission to relicense,
remove, or disregard the licenses of third-party components.

Users and redistributors must comply with the applicable licenses of
all third-party components included in their particular distribution.

## 7. Release Compliance

Before publishing a release:

1. Resolve the complete runtime dependency graph.
2. Resolve the complete packaged dependency graph.
3. Identify all transitive dependencies.
4. Record each component's applicable license.
5. Preserve required copyright and attribution notices.
6. Preserve required NOTICE files.
7. Verify compatibility between the dependency licenses and the
   intended distribution model.
8. Update this document when the dependency graph materially changes.

## 8. Important Scope Limitation

This file is not a substitute for the license text supplied by the
upstream authors of third-party components.

Where there is a conflict between this summary and an upstream license,
the applicable upstream license controls the third-party component,
subject to applicable law.

## 9. AI Models, APIs, Data, and External Services

AI-V0 Ultimate may interact with external AI providers, models,
repositories, APIs, datasets, images, fonts, or other external
resources.

Those resources are NOT automatically covered by the Apache License
for AI-V0 Ultimate.

Their respective terms, licenses, usage restrictions, attribution
requirements, and commercial-use conditions must be evaluated
separately before redistribution or commercial deployment.