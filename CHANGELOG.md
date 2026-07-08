# Changelog

## 1.0.11 — 2026-07-08

### Fixed
- **Duplicate `i18n/` folder**: removed root `i18n/` (unreferenced at runtime); cleaned up pom.xml ant copy step
- **Stale fallback translations**: `messages.properties` synced to full 150-key set matching `messages_en.properties`
- **Icon cache via `UIManager`**: replaced with private `ConcurrentHashMap` to avoid LAF collision risk

## 1.0.10 — 2026-07-07

### New
- Full i18n: all hardcoded strings extracted, language follows Oxygen's UI setting
- French, German, Japanese translations added

### Fixed
- Non-English Preferences page blank (tree node mismatch with plugin `name`)
- UI panels not refreshing after term edits (added change listener to `TermbaseRegistry`)
- Insert Translation required two Ctrl+Z to undo (compound edit wrapping)
- Context menu submenu 1-2s delay (SVG icon caching, token-based multi-term detection)

## 1.0.9

### Fixed
- Silent failures now visible via error dialogs
- UI freezes during scan/save moved to SwingWorker
- XLSX header cell type mismatch
- Multi-term context menu hidden by Oxygen popup framework

### New
- Unit test coverage for CSV, XLSX, TBX handlers
