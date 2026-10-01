# Changelog

All notable changes to the Term Management plugin are documented here.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## Unreleased

### Added
- `TermEntry` now carries unmapped data through edits: `extraFields` for columns beyond source/target (CSV/XLSX) and unmapped TBX fields, and `entryId` for the TBX `termEntry` id. `TermEntry.copy()` deep-copies both, and the panel and context-menu edit paths use it instead of rebuilding an entry from its two visible columns.
- `TermbaseConfig.extraColumns` remembers the header names from the third column on (runtime state, re-detected on load, never persisted).
- New `AtomicFileWriter`: saves fill a temporary file in the target's directory and move it over the target when complete, so a failure half-way leaves the previous file untouched and no temporary file is left behind; a move blocked by another application is reported as "file in use".
- CSV saves are lossless: columns from the third on are read into entries, written back in the original header order on save, and saves now go through `AtomicFileWriter` (UTF-8 BOM and blank-row skipping unchanged). Two-column files gain no new column.
- XLSX saves are lossless: the original workbook is reopened and only the first sheet's data rows are rewritten, so other sheets, header-row styles and column widths survive; extra columns are read into entries and written back like CSV. Known limitation: per-cell formatting on data rows is not preserved. If the original file cannot be opened, a new workbook is written instead (logged to the plugin console).
- TBX saves are lossless: the file on disk is re-parsed and matched by `termEntry` id, so `descrip`, `note`, `termNote`, additional languages and the original ids survive an edit or a no-op save; only the term text of the same two langSets the loader reads is replaced. New entries get collision-free ids, deleted entries are removed, and whitespace normalisation makes repeated saves byte-identical (no more blank-line growth). The DOCTYPE's external DTD declaration is preserved; a DOCTYPE internal subset is not (known limitation).
- Editing a term no longer overwrites changes made to the termbase file outside the plugin: the registry remembers when each cached file was last written (timestamp + size) and re-reads the file first if it changed on disk before applying the edit. Editing after the file was deleted now fails with an explicit error instead of silently recreating it.
- New "Case sensitive matching" checkbox on Preferences > Term Management (off by default, persisted with Oxygen's options storage): when enabled, document scans no longer match a term against text that differs only in letter case. The setting applies to the recognition panel and is honoured across restarts even if Preferences is never opened.
- Term maturity status (`preferred`, `admitted`, `deprecated`) is now modelled end-to-end in the storage layer. CSV and XLSX files use a column named `status` (case-insensitive header match); TBX files use `termNote type="administrativeStatus"` with TBX-Basic domain values (`preferredTerm-admn-sts`, `admittedTerm-admn-sts`, `deprecatedTerm-admn-sts`). Unknown values survive round-trips verbatim; files without a status column are never altered; the column/termNote appears only when at least one entry actually carries a status. `TermEntry.getStatus()` returns the parsed enum (or null), `setStatus()` and `getStoredStatusValue()` support the UI and handlers, and `copy()` preserves the status.
- Status is visible and editable in the UI: the Terminology panel now shows a read-only "Status" column beside Source and Target; the Add/Edit dialog includes a status dropdown (Preferred / Admitted / Deprecated) and, when the entry already has a note, an editable Note field. The Recognition panel adds a "Status" column to its results table, and deprecated terms are highlighted in red in the author-mode overlay instead of the normal yellow.

### Fixed
- A Latin term written right next to Chinese, Japanese or Korean characters is now recognized again (e.g. `FEA` in `使用FEA。`). The word-boundary check used to treat CJK characters as letters and rejected every such occurrence; letters from other languages still block a match, and a term next to digits behaves as before.
- Scans no longer bury a long term under its own fragments: when a shorter hit lies fully inside a longer one (e.g. 弯曲 and 刚度 within 弯曲刚度), only the longer hit is reported. Equal spans and partial overlaps are still reported separately.
- A source term with several translations now lists every translation for the same occurrence; previously only the first one survived deduplication. Identical (source, translation, position) hits coming from two loaded termbases are still reported once.
- Text-mode scans no longer report hits inside XML markup. Tags, attributes, comments, processing instructions and the DOCTYPE are blanked out before matching (at identical offsets), so a term that only occurs as an element name or attribute value is no longer flagged; text content, including CDATA content, is matched as before.
- The word-boundary check now also rejects letters written outside the Basic Multilingual Plane (e.g. mathematical bold 𝐀 before `FEA`); a regex look-around only saw one UTF-16 code unit of such a letter and let the occurrence through.

### Improved
- Recognition scans are faster, not slower: the 20 000-term × 1 MB benchmark runs in about 140 s versus 261–272 s before the matching fixes (an intermediate per-position boundary regex had cost 966 s). The match pattern is now a plain literal and the word-boundary rule is checked in code per hit; compiled patterns are also reused across scans instead of being rebuilt for every panel refresh.

### Added
- New "Check" button on the Terminology tab runs a quality inspection across all enabled termbases (empty source/target, multi-target conflicts, case-only duplicates, consecutive whitespace, cross-termbase conflicts). Results are shown in a non-modal dialog table and can be exported as UTF-8 BOM CSV for Excel. The checker (`TermbaseChecker`) is pure logic with 13 unit tests.
- External-change notification on tab activation (step 4.2): when the user switches to the Recognition or Terminology tab, the view lazily compares each cached termbase’s on-disk stamp; if a file was modified externally, it is reloaded and a tooltip appears on the tab header. Own saves never trigger this (stamp is updated post-write).

## 1.0.12 - 2026-07-08

### Refactored
- `DocumentScanner` extracted from `TermRecognitionPanel` (13 new unit tests)
- `checkDuplicateInCurrentTerms()` uses source index instead of linear scan

### Maintenance
- CI profile includes `DocumentScanner.java` and `TermMatchUtils.java`

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