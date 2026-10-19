# Changelog

All notable changes to this project are documented in this file.
Format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Refactor — page extraction (incremental monolith reduction)

- **ModelsPage extracted** (`fcd86be`)
  - Moved the entire `buildModelsPage()` body out of `MainActivity` into a new
    `ModelsPage.kt` (584 lines) owned by a narrow `ModelsHost` contract
    (`ModelsHost.kt`, 145 lines). `MainActivity` now only hosts the page.
  - `MainActivity` lost ~570 lines (3,891 remaining, was 4,427 pre-extraction).
  - The page is the single owner of its 43 views; `MainActivity` exposes them
    via read-only getter delegations — no double initialization.
  - Removed the orphan closing brace left by the off-by-one function deletion
    that had prematurely closed `MainActivity`.
  - Replaced the recursive ambiguous host callbacks
    `onDrawToggleClick()` / `onTaggerToggleClick()` with explicit
    `onDrawToggleClickRequested()` / `onTaggerToggleClickRequested()`, wired
    through `this@MainActivity`.
  - Fixed `ColorStateList` import (`android.content.res`, not `android.graphics`).
  - Fixed `themeMode` (`val`, not `var`) to satisfy the `SettingsHost` contract.

### Added
- **`ChatSendLogic` + characterization tests** (`ChatSendLogic.kt`, `ChatSendLogicTest.kt`)
  - Factored the pure, stateless logic out of `doSend()`: `composeSendText`
    (text/file merge), `computeSeedTitle` (18-char title fallback),
    `countImages` (`userImage` "|" split) and `buildTagHint` (tagger notice).
  - **15 JVM characterization tests** pin the current `doSend()` behavior
    *before* the refactor, so any drift fails the gate.
  - `doSend()` and `tagHint()` now delegate to `ChatSendLogic`; behavior
    preserved. `runRound()` is intentionally untouched (shared-state
    orchestration requires characterization coverage first).

### Changed
- **About-section portrait updated** — `res/drawable-nodpi/about_portrait.png`
  replaced with the new artwork at its **full 1408×768** aspect (no crop); the
  ImageView uses `matchWrap()` so the whole image is shown. No code change:
  `SettingsPage.kt` still uses `R.drawable.about_portrait`; renders landscape
  (950×543 px on device).

### Fixed
- **Startup crash** (`savedContainer` `lateinit` unassigned): the delegated
  getters resolved it; app now launches without
  `UninitializedPropertyAccessException` (verified on-device, pid 21195,
  crash buffer clean).
- **ETA formatter** (`Formatters.kt`): corrected the invalid `%h` format specifier
  (which prints the argument's `hashCode`) to `%d:%02d`, so hours ≥ 1 render as
  `1:16` instead of garbage. (Shared by UI and downloader via `Formatters.eta`.)

### Verified (device: Motorola Edge 50 Fusion, ADB over Wi-Fi)
- `compileDebugKotlin` → BUILD SUCCESSFUL
- `testDebugUnitTest` → 15/15 `ChatSendLogicTest` pass (no other JVM tests in tree)
- `assembleDebug` → app-debug.apk (73 MB)
- App starts; Models page renders: sub-tabs `Backend`/`CPU`/`Chat model`/`Draw model`/`Tagger`,
  `loadButton`, `savedContainer`, and navigation tabs all present.
- `doSend()` post-refactor: send tap does not crash (pid 23727 alive, crash buffer 0).
