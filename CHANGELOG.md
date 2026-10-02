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
- **TBX saves no longer lose data (P0).** Three save gaps are closed: (1) a `termEntry` without an `id` is no longer treated as new — it keeps its document position and its `descrip`/`note`/other children survive a no-op save; (2) entries the loader skips (e.g. a `termEntry` with only a `descrip` and no readable term) are no longer deleted on save — only nodes the loader would actually produce are eligible for removal; (3) clearing a translation and saving, then reloading, keeps the right language pair — `selectLangSets` now matches langSets structurally (has `xml:lang` and a `tig/term` or `ntig/termGrp/term`) instead of skipping empty terms, so a third language is no longer mistaken for the target.
- **XLSX refuses to overwrite an unopenable file (P0).** When the termbase file exists and is non-empty but the workbook cannot be parsed, the save now throws with the underlying reason instead of logging to stderr and replacing the file with a fresh two-column workbook.
- **Term status can be cleared (P1).** `setStatus(null)` is now distinguishable from “never edited” (empty-string sentinel): a cleared status writes an empty CSV/XLSX cell and removes the TBX `termNote type="administrativeStatus"`, rather than silently reverting to the value read at load time.
- **TBX `ntig`/`termGrp` status now works (P1).** Administrative status is read and written for both `tig/term` and `ntig/termGrp/term` structures; it previously failed silently on `ntig` entries. Note the semantics differ by format: in TBX the status is bound to the source-language term, while in CSV/XLSX it is a per-row column.
- **Edit dialog no longer writes on no-op (P1).** Confirming an entry whose status dropdown was left on the default no longer stamps `preferred` or adds a `status` column to a file that had none.
- **External-change detection is background and correct (P1).** Checking for external changes on tab activation no longer reloads on the UI thread (large termbases froze the interface); the reload tooltip is cleared when leaving the tab; the on-disk stamp is now read *before* the load so a change landing during the load window is not missed; and editing after an external reload now merges the user's edits onto the freshly loaded entry instead of overwriting the external change with a stale copy.
- **Text-mode scans no longer match inside entity names (P1).** `amp` and `lt` no longer hit inside `&amp;` / `&lt;`; a term that legitimately spans or equals an escaped sequence (e.g. `R&D` matching `R&amp;D`) still matches.
- **Deprecated terms are always reported (P1).** A deprecated term that lies inside a longer match is no longer swallowed by longest-match filtering, so the “deprecated” warning still surfaces (it does not, however, affect the containment check for other matches).
- **TBX claiming info is refreshed after every successful save (P0).** Each entry remembered the `termEntry` position and `id` it had when the file was *loaded*. The registry returns the same cached list after a save, so a later edit that reused that list (the panel's undo snapshot, or a cached termbase edited without a reload) claimed the wrong node — deleting one no-id entry shifted every following definition onto the wrong term, and a newly created entry was assigned a fresh `id` on every save. `saveTerms` now writes back each entry's final document ordinal and `id` (including the ids it mints for new nodes), but only after the file has been written, so a failed save leaves the in-memory values untouched; the two fields are `volatile` because the writer thread updates them while the UI thread reads the same objects.
- **A change landing during a load marks the cache stale (P1).** If the file was modified while it was being read, the loader had recorded the post-read stamp, making the half-read content look fresh; the next edit then skipped reloading and clobbered that change. It now records the pre-read stamp in that case, so the following write reloads first and preserves the external edit.

### Improved
- Recognition scans are faster, not slower: the 20 000-term × 1 MB benchmark runs in about 140 s versus 261–272 s before the matching fixes (an intermediate per-position boundary regex had cost 966 s). The match pattern is now a plain literal and the word-boundary rule is checked in code per hit; compiled patterns are also reused across scans instead of being rebuilt for every panel refresh.
- **TBX saves are now O(N) instead of O(N²).** Node claiming during save uses an `id → queue` map rather than a linear scan of the remaining nodes; a 20 000-entry save dropped from about 3.8 s to well under a second.
- **Atomic saves preserve file permissions and report move failures.** On POSIX file systems the target's permissions are copied onto the temporary file before the move (the default `0600` temp file no longer silently changes them); when the atomic-move fallback's plain replace fails, it is reported as a file-in-use/read-only error like the primary path.
- **The static scan-pattern cache is bounded.** Once it exceeds 50 000 entries it is cleared wholesale, so it can no longer grow without limit across a long session; results are unaffected (the next scan just recompiles).
- Removed dead matching code that no longer had callers: the single-argument `TermMatchUtils.buildMatchPattern(String)`, `TermbaseRegistry.getMatchPattern` and its separate `patternCache` (the surviving `buildMatchPattern(String, boolean)` javadoc now states it builds a plain literal and that boundaries live in `acceptAtBoundary`).

### Added
- New "Check" button on the Terminology tab runs a quality inspection across all enabled termbases (empty source/target, multi-target conflicts, case-only duplicates, consecutive whitespace, cross-termbase conflicts). Results are shown in a non-modal dialog table and can be exported as UTF-8 BOM CSV for Excel. The checker (`TermbaseChecker`) is pure logic with 13 unit tests.
- External-change notification on tab activation (step 4.2): when the user switches to the Recognition or Terminology tab, the view lazily compares each cached termbase’s on-disk stamp; if a file was modified externally, it is reloaded and a tooltip appears on the tab header. Own saves never trigger this (stamp is updated post-write).
- **TBX saves no longer attach an entry to the wrong node after a delete-and-undo (P0).** An entry without an `id` is matched to its node by document position, and that position goes stale when neighbouring entries are removed; the panel's undo restores a full pre-delete list in which the deleted entry still carries its old position, so it could claim a neighbour's node (moving that neighbour's `descrip`/`note` under the wrong term). A position claim is now accepted only if the node still holds the same source and target text the entry last had on disk (`TermEntry.persistedFingerprint`, set on load and after every successful save); otherwise the entry is written as a new node. Two id-less nodes whose source and target text are both identical cannot be told apart this way (known limitation: they may swap their unmodelled content).

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