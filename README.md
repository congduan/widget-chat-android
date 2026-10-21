# Widget Chat Android

English | [简体中文](README.zh-CN.md)

Build Android home screen widgets with natural language. Currently a **P0 technical prototype**: configuration-driven preview and home screen widgets are working, and an OpenAI-compatible chat model (DeepSeek et al.) can draft the spec.

## Screenshots

| Chat to build | Widgets and preview | Templates | Settings |
| :---: | :---: | :---: | :---: |
| <img src="docs/screenshots/chat.png" width="220"> | <img src="docs/screenshots/widgets.png" width="220"> | <img src="docs/screenshots/templates.png" width="220"> | <img src="docs/screenshots/settings.png" width="220"> |

## What works today

- Four tabs — Widgets / Templates / Chat / Settings: widget library and preview, template gallery, chat generation, model/refresh/cache management.
- Chat generation: pick DeepSeek, OpenAI or any OpenAI-compatible endpoint in Settings (model, base URL, API key). The returned WidgetSpec is validated and previewed before it is loaded into the editor; the API key is encrypted with the Android Keystore.
- Three templates: daily quote, battery, countdown.
- WidgetSpec JSON validation and editing; row/column layout, text, spacing, progress bars.
- Preview rendered with real RemoteViews inside the app, at small-card and standard height, optionally following the actual size of a bound home screen widget.
- Widget library, local atomic storage, save-as-copy, last ten versions and rollback.
- JSON import/export; system add-to-home-screen, configuration screen, manual refresh and multi-instance binding.
- Tapping a widget returns to its record; system updates are combined with periodic WorkManager refreshes (15 minutes minimum in background, actual timing decided by Android).
- Node-level `click`: any node can carry a `scheme://…`; tapping opens that app or page directly. Without it, tapping the card returns to the editor.
- WidgetSpec v2 data sources: `STATIC`, `HTTP` (https only), `SCRIPT` (QuickJS) and `SYSTEM` (permission-free device info); executed in `dependsOn` order with TTL, `fallback` and editor-only `sample`.

Not yet supported: calendar, arbitrary images, minute-accurate refresh and runtime-delivered scripts. The script engine is a prebuilt 16 KB page-aligned QuickJS library (Apache-2.0); when it fails to load, data sources degrade to cache or `fallback`.

## Build and run

Requires JDK 17, Android SDK 35 and an Android 12+ device. Dependency versions are pinned to a known-compatible set; the first build downloads them.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb -s <device-serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <device-serial> shell am start -n app.widgetchat/.ui.MainActivity
```

You can also open the project root directly in Android Studio. Set the SDK path via `ANDROID_HOME` or an untracked `local.properties`.

## Usage

1. Pick a template and check the real date and battery level in the preview.
2. Expand the JSON editor and change `name`, `background`, `text`, `textSize`, `targetDate`, `click`, or configure data sources under `data.sources`.
3. Tap "Validate and preview", then save; "Save as copy" creates an independent widget.
4. Tap "Add to home screen" and confirm with the system; you can also pick Widget Chat from the home screen widget list.
5. Multiple home screen instances may bind to different records; edits to one record update every instance bound to it. Save a copy first if you need independent edits.
6. Long-press the widget to reconfigure, or tap its content to return to the editor; tap "Refresh" to update local data.
7. The "Settings" tab can disable background refresh, refresh bound widgets immediately, clear the data cache and show storage usage.

New widgets default to 4×3; existing small cards shrink text and prioritize the primary content. If space is tight, long-press the widget on the home screen and increase its height. The preview reads the size assigned by the launcher; complex layouts still need real-device testing.

## Data sources and refresh

Multiple home screen instances of the same record share a cache; rendering only reads the cache, and fetching is triggered solely by periodic WorkManager jobs, manual refresh or system `onUpdate`.

```json
{
  "schemaVersion": 2,
  "name": "Weather",
  "root": {
    "type": "COLUMN",
    "children": [
      { "type": "TEXT", "textSize": 28, "binding": "DATA", "source": "shaped", "field": "temp", "fallback": "--" }
    ]
  },
  "data": {
    "sources": {
      "weather": {
        "type": "HTTP",
        "url": "https://api.example.com/weather",
        "pick": "data.now",
        "ttlSeconds": 900,
        "fallback": { "temp": "--" }
      },
      "shaped": {
        "type": "SCRIPT",
        "dependsOn": ["weather"],
        "timeoutMs": 2000,
        "script": "function refresh(ctx) { return { temp: ctx.sources.weather.temp + '°' }; }",
        "sample": { "temp": "23°" }
      }
    }
  }
}
```

Text and progress nodes use `"binding": "DATA"` together with `source` and `field`; the optional `fallback` is shown when that field is missing. `sample` is only used for the editor preview.

`SYSTEM` sources are collected by Kotlin on the permission-free side; results are cached like any other source, and scripts can only read them through `ctx.sources`:

```json
{
  "clock": { "type": "SYSTEM", "system": "CLOCK", "ttlSeconds": 60 },
  "net": { "type": "SYSTEM", "system": "NETWORK" }
}
```

| `system` | Fields |
| --- | --- |
| `BATTERY` | `percent` (missing when unknown), `charging`, `status` (`charging`/`full`/`discharging`/`not_charging`/`unknown`) |
| `CLOCK` | `now`, `date`, `time`, `weekday` (follows system language), `timezone`, `epochSeconds` |
| `NETWORK` | `connected`, `type` (`wifi`/`cellular`/`ethernet`/`vpn`/`other`/`none`), `metered` |
| `LOCALE` | `language`, `region`, `timezone`, `hour24` |
| `STORAGE` | `freeGb`, `totalGb`, `usedPercent` |

`SYSTEM` sources do not accept `value`/`url`/`pick`/`script`/`dependsOn`, and an unknown `system` name fails validation. All collection is synchronous and needs no runtime permission (only `ACCESS_NETWORK_STATE` is declared); no location, sensors, clipboard or other gated or background-restricted capabilities. Because rendering only reads the cache, `CLOCK`/`BATTERY` values stay at the last refresh; for a live clock or battery indicator keep using the `DATE`/`BATTERY` node bindings.

## Tap actions (click)

Any node may add `"click": "scheme://…"`, which is attached to that node's RemoteViews. The most specific node handles the tap, so a child wins over its container. Once the root node also sets `click`, tapping the whole card no longer returns to the editor by default.

```json
{
  "type": "COLUMN",
  "click": "widgetchat://edit",
  "children": [
    { "type": "TEXT", "text": "Weather", "click": "https://weather.example.com" },
    { "type": "TEXT", "text": "Promo", "click": "exampleapp://promo/detail?id=123" }
  ]
}
```

- `widgetchat://` is handled by this app; `widgetchat://edit/<recordId>` jumps straight to that record, and any other path opens the editor.
- Other schemes are launched as `ACTION_VIEW` into the system or a third-party app; nothing happens if the app is not installed.
- Validation only allows `scheme:` URIs up to 2048 characters; `file`, `content`, `javascript`, `data`, `intent` and similar schemes are rejected.
- Chinese OEM ROMs restrict launching background apps from widgets and may fail silently on some models — verify on a real device. Widgets in the preview do not respond to taps, so editing does not jump out of the app.

Scripts run in QuickJS on a dedicated thread and must synchronously return a JSON object (strings, numbers or booleans, at most 32 fields), receiving `ctx.now`, `ctx.battery`, `ctx.sources` and `ctx.cache`. `ttlSeconds` decides whether a background job actually fetches; WorkManager's minimum period is 15 minutes and is not exact, and may slip further under Doze.

## Layout

- app/src/main/java/app/widgetchat/model: config schema, validation, data binding and templates.
- data: local storage and versions, instance mapping, data cache, HTTP/QuickJS/SYSTEM fetching and WorkManager scheduling.
- render: shared RemoteViews renderer.
- widget: home screen lifecycle and add callbacks.
- ui: widget library, config editor and system configuration screens.
- app/src/test: schema and binding tests.
- app/src/androidTest: Android rendering, storage, data refresh and script execution tests.

This repository has no public license yet. No competing implementation was copied directly.
