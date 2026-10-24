# Third-party notices

Ember is licensed under the [MIT License](LICENSE). It includes or depends on the following software. Each keeps its own license.

## Bundled assets

### Inter

`app/src/main/res/font/inter_variable.ttf` is Inter 4.1 by Rasmus Andersson and the Inter Project Authors, from <https://github.com/rsms/inter>.

Licensed under the [SIL Open Font License, Version 1.1](https://openfontlicense.org/open-font-license-official-text/). Copyright (c) 2016 The Inter Project Authors (https://github.com/rsms/inter).

### Lucide

`app/src/main/kotlin/dev/fl1nt/ember/ui/icons/Lucide.kt` and the generated drawables in `app/src/main/res/drawable/ic_tile_*` and `ic_shortcut_*` contain path data from Lucide 1.61.0, <https://lucide.dev>.

```text
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

Some Lucide icons are derived from the Feather project, Copyright (c) 2013-2023 Cole Bemis, under the MIT License.

### Flare design tokens

Ember's color palettes and visual conventions follow [Flare](https://github.com/FlintSH/Flare), MIT licensed, Copyright (c) Zachary Lowery. The Ember mark is original artwork for this project and is not Flare's icon.

## Libraries

These are compiled into the APK. All are licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

| Library | Project |
| --- | --- |
| AndroidX (Activity, Compose, Core, DataStore, Lifecycle, Navigation, Room, SplashScreen, WorkManager) | <https://developer.android.com/jetpack/androidx> |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | <https://kotlinlang.org> |
| OkHttp and Okio | <https://square.github.io/okhttp/> |
| Coil | <https://coil-kt.github.io/coil/> |

Build and test tools such as Gradle, the Android Gradle plugin, Robolectric, Roborazzi, JUnit, and Truth are not distributed with Ember.
