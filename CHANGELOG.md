# Changelog

All notable changes to the Term Management plugin are documented here.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 1.0.11 - 2026-07-08

### Fixed
- Icon cache no longer misuses the global `UIManager` defaults table as a cache store; switched to a private, isolated cache map, consistent with the plugin's other caches.
- Fallback resource bundle (`messages.properties`) was out of sync with the other language files (63 vs 150 keys); now kept in sync.
- Removed a duplicate, unused `i18n/` folder left over at the repository root from an earlier packaging attempt.

## [1.0.10] - 2026-07-07

### Added
- Localization coverage extended to ~30 core UI strings across menus, dialogs, and buttons (right-click context menu, Terminology panel, Recognition panel, Preferences page).
- New language support: French, German, Japanese (in addition to existing English/Chinese), matching Oxygen XML Editor's own set of built-in UI languages.
- Plugin UI language now follows Oxygen's configured interface language (via `getUserInterfaceLanguage()`) instead of the OS/JVM default locale.
- `TermbaseRegistry` change-listener mechanism — panels now automatically refresh when terms are added/edited/deleted from any entry point (e.g. right-click Quick Add updates an already-open Terminology panel).
- Compound edit support for "Insert Translation" — the delete-selection + insert-translation sequence is now a single undoable step in Author mode.

## [1.0.9] - 2026-07-06

### Added
- Right-click context menu integration in Author and Text mode: Quick Add, Insert Translation, Edit Term, Search in Termbase.
- Multi-term selection guard — selecting a continuous span of text containing more than one known term now shows a disabled hint instead of allowing an accidental compound "term" to be added.
- Unit test suite for the three termbase format handlers (CSV/XLSX/TBX), 17 tests covering round-trip save/load, encoding edge cases, and malformed/missing-file handling.

### Improved
- Termbase configuration is now serialized as proper JSON via Gson, replacing a hand-written parser.
- Long-running operations (termbase reload, term save/delete, document scanning) now run on a background thread via `SwingWorker`, keeping the UI responsive on large termbases or documents.
- Right-click menu term lookup uses the existing source-term index and caches compiled matching patterns, instead of a full re-scan on every invocation.
- Consistent, user-facing error dialogs for load/save/reload failures, replacing silent console-only logging.

### Fixed
- Crash risk when loading XLSX termbases with a non-text (e.g. numeric) header cell.
- A threading/error-handling conflict where a reload or scan failure could show a background-thread error dialog immediately followed by a contradictory "success" message.
- The "Search in Termbase" menu item could appear enabled but silently do nothing if the Term Management view had never been opened.
- Term Entry dialog allowed confirming with an empty target term; both source and target are now required.

## [1.0.8] and earlier

Right-click context menu (initial version), core Term Recognition / Terminology Management / Termbase Search functionality, TBX/XLSX/CSV format support, Preferences-based termbase configuration. Detailed changelog entries were not tracked prior to 1.0.9 — see git history for full detail.