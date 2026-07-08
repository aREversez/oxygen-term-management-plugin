# Term Management

<p align="right">
  <a href="./README.md">English</a> |
  <a href="./README.zh.md">中文</a>
</p>

Oxygen XML Editor plugin for terminology management and translation assistance.

## Screenshots

![Plugin Overview](./assets/plugin-screenshot.png)

## Features

### Term Recognition
- Scan the current editor document for terms from a selected termbase
- Supports both Author and Text editing modes
- In Text mode, XML entities (`&`, `<`, `>`, `"`, `'`) are escaped before matching
- **Author-mode term highlighting** — toggle highlight on/off with a single button; highlighted terms are visually marked in the document with a yellow background; toggle state is per-document (session-scoped)
- **CJK support** — correctly recognizes Chinese, Japanese, and Korean text without requiring whitespace delimiters; non-CJK terms use word-boundary regex (`(?<![\\p{L}])TERM(?![\\p{L}])`)
- Double-click a matched term to switch to the **Terminology** tab for editing
- Navigate occurrences with **< Prev** / **Next >** buttons and position label (e.g. `3/12`)
- Navigation counts are deduplicated by document position — duplicate entries in the termbase do not inflate occurrence counts
- **Duplicate entries** — if a source term has multiple translations in the same termbase, all translation pairs are shown in the results table
- Statistics label shows `Matched n terms (m unique entries)`
- Auto-scan on tab switch and editor change
- **Background scan** — scanning runs on a background thread; the scan button is disabled and shows "Scanning..." status during the operation
- **Theme-aware SVG icons** — icons adapt to dark/light Oxygen theme automatically

### Right-Click Context Menu
When text is selected in the editor, right-click to access the **Term Management** submenu:

| Menu Item | Condition | Action |
|-----------|-----------|--------|
| **Quick Add** | Text selected, term NOT known in the Recognition tab's active termbase | Opens Quick Add dialog with source term pre-filled, cursor on target field |
| **Insert Translation** | Text selected, matches a known term in the active termbase | Single match inserts directly; multiple matches show a chooser |
| **Edit Term** | Text selected, matches a known term in the active termbase | Opens the Edit Term dialog directly (no Tab navigation needed) |
| **Search in Termbase** | Text selected | Switches to the Search tab, populates search field, and executes search across **all** enabled termbases |

- All termbase operations (Insert, Edit, Quick Add) are scoped to the **termbase currently selected in the Term Recognition tab**
- Discontinuous multi-selection (Ctrl+click) is detected and suppresses the menu
- **Multi-term selection** — if the selected text contains 2+ different known terms, the submenu shows a disabled hint instead of Quick Add / Insert / Edit; if the selection contains multiple occurrences of the same known term, the menu works normally
- **Robust matching** — individual term matching errors are caught gracefully and logged to console without interrupting the menu construction or crashing the right-click event

### Terminology Management
- Add, edit, delete terms in individual termbases (TBX / XLSX / CSV)
- Quick-Add: create a term from the current editor selection, cursor auto-focuses on the target term field
- **Inline editing** — double-click a cell to modify, changes saved immediately
- **Undo Delete** — one-step undo support for the last delete operation
- **Reset Sort** — restore original row order and clear filters
- **Filter terms** — real-time case-insensitive regex filter field
- **Right-click context menu** — Edit Term / Delete Term
- **Chinese-aware sorting** — uses `Collator.getInstance(Locale.CHINESE)` for column sorting
- **Duplicate detection** — warns when adding a term whose source already exists (same or different translation)
- **File lock detection** — checks write access before saving; shows specific messages for locked XLSX files (e.g. open in Excel)
- **User-facing error dialogs** — load, save, and reload failures show a message dialog instead of failing silently
- **Background operations** — termbase file I/O (save, reload) and document scanning run on background threads via SwingWorker, keeping the UI responsive
- Batch delete with confirmation: `Delete X term(s) from Y?`

### Termbase Search
- Fuzzy (case-insensitive substring) search across all enabled termbases
- Searches both source and target terms
- Double-click a result to jump to the **Terminology** tab for editing

### Termbase Configuration (Preferences)
- Table with columns: File Name, Path, Format, **Status** (`Enabled` / `Disabled` / `! Missing`), **Term Count**
- **Add** termbase(s) via native file dialog (multi-select, rubber-band selection)
  - Duplicate path detection (silently skipped)
  - Format validation (unsupported files are skipped)
  - Load error handling — dialog prompt to **Skip** file or **Abort** the whole operation
  - Empty file warning — `"File contains no terms. Add it anyway?"`
  - Translation conflict detection — scans enabled termbases for overlapping source terms before adding
  - Summary message after adding (added / duplicates / skipped counts)
  - Last used directory is remembered across sessions
- **Remove** termbase(s) from the list (does not delete the file)
- **Enable / Disable** termbases without removing them
- **Reload** termbase from disk (multi-select supported)
- **Edit** opens termbase file in system default application
- Configurations are persisted via Oxygen's `WSOptionsStorage` as a JSON string (serialized with Gson)

### Term Entry Dialog
- Source term (required) and target term fields
- **Enter** to confirm, **ESC** to cancel
- Validation: source term cannot be empty

## Requirements

- **Oxygen XML Editor** 27 or 28
- **Java** 17+
- **Maven** 3.6+ (for building)

## Build

1. Copy `oxygen.jar` from your Oxygen XML Editor installation directory (`lib/oxygen.jar`) to `libs/`:
   ```bash
   cp <OXYGEN_HOME>/lib/oxygen.jar libs/
   ```
2. Build the plugin:
   ```bash
   mvn clean package
   ```

The deployable plugin package will be available at `output/term-management/`.

## Installation

1. Build the plugin (see above).
2. Copy the output directory to Oxygen's plugins folder:
   ```bash
   cp -r output/term-management/ <OXYGEN_HOME>/plugins/term-management/
   ```
3. Restart Oxygen XML Editor.
4. Open the **Term Management** view from `Window > Show View > Term Management`.
5. Configure termbases at `Preferences > Plugins > Term Management`.

## Usage

### Configuration (Preferences)
1. Go to `Preferences > Plugins > Term Management`.
2. Click **Add** to select a TBX / XLSX / CSV file.
3. Select a termbase and click **Enable** / **Disable** to control its availability.
4. Click **OK** or **Apply** to save.

### Term Recognition
1. Open an XML document in Author or Text mode.
2. In the **Term Recognition** tab, select a termbase from the dropdown.
3. Click **Scan** (or switch tabs to auto-scan).
4. Matched terms appear in the table, with a statistics label showing hit counts.
5. Use **< Prev** / **Next >** to navigate occurrences in the document.
6. **Double-click** any row to switch to the **Terminology** tab for editing.
7. **Toggle Highlight** — enable/disable Author-mode highlighting to visually mark matched terms in the document.

### Terminology Management
1. Switch to the **Terminology** tab.
2. Select a termbase from the dropdown.
3. Use the toolbar buttons to manage terms:
   - **Reload** — re-read the termbase from disk
   - **Add** — add a new term manually
   - **Quick Add** — add a term using the current editor selection as source, cursor auto-focuses on the target term field
   - **Edit** — modify the selected term (single selection only)
   - **Delete** — remove selected term(s) (supports multi-select)
   - **Undo** — restore the last deleted term(s)
   - **Reset Sort** — restore original row order and clear filters
4. **Filter** terms in real-time using the text field above the table.
5. **Inline editing** — click any cell to edit, changes are saved immediately.
6. **Right-click** a row for a context menu with Edit/Delete options.

### Right-Click Context Menu
1. Select text in the editor (Author or Text mode).
2. Right-click and find the **Term Management** submenu (at the bottom of the popup, after a separator).
3. Choose an action depending on whether the selected text matches a known term:
   - **Quick Add** — add as a new term to the Recognition tab's active termbase
   - **Insert Translation** — replace the selection with the translation
   - **Edit Term** — directly edit the term entry
   - **Search in Termbase** — search across all enabled termbases
4. The submenu is hidden when no relevant action is available.

### Termbase Search
1. Switch to the **Termbase Search** tab.
2. Enter a search term and click **Search** (or press Enter).
3. Results are shown from all enabled termbases.
4. **Double-click** a result to jump to the **Terminology** tab for editing.

## Supported Formats

| Format | Library | Notes |
|--------|---------|-------|
| CSV | OpenCSV | UTF-8 with BOM, first row header, BCP 47 language tags |
| XLSX | Apache POI | First sheet, first row header |
| TBX (ISO 30042) | JDK DOM | `xml:lang` attributes for language detection; supports both `<tig>` and `<ntig>` / `<termGrp>` structures |

### Language Identification

Language tags follow the **BCP 47** standard (e.g., `en-US`, `zh-CN`, `ja-JP`). A reference table is available at [`language-tags-BCP-47.md`](./language-tags-BCP-47.md).

## Project Structure

```
term-management/
├── plugin.xml                 # Oxygen plugin descriptor
├── extension.xml              # Extension registration
├── pom.xml                    # Maven build
├── LICENSE
├── README.md
├── README.zh.md
├── assets/                    # Screenshots for README
├── licenses/                  # Third-party license files
├── libs/                      # Oxygen SDK and other local JARs
├── src/main/
│   ├── java/com/example/termmgmt/
│   │   ├── TermManagementPlugin.java
│   │   ├── TermManagementWorkspaceAccessExtension.java
│   │   ├── TermContextMenuInstaller.java
│   │   ├── model/
│   │   │   ├── TermEntry.java
│   │   │   └── TermbaseConfig.java
│   │   ├── service/
│   │   │   ├── TermbaseLoader.java
│   │   │   ├── CsvTermbaseHandler.java
│   │   │   ├── XlsxTermbaseHandler.java
│   │   │   ├── TbxTermbaseHandler.java
│   │   │   └── TermbaseRegistry.java
│   │   ├── prefs/
│   │   │   └── TermManagementPreferencePage.java
│   │   ├── util/
│   │   │   └── TermMatchUtils.java
│   │   └── ui/
│   │       ├── TermManagementView.java
│   │       ├── TermRecognitionPanel.java
│   │       ├── TermbaseSearchPanel.java
│   │       ├── TerminologyPanel.java
│   │       └── TermEntryDialog.java
│   └── resources/
│       ├── i18n/              # Active i18n resource bundles
│       │   ├── messages_en.properties
│       │   ├── messages_zh.properties
│       │   ├── messages_fr.properties
│       │   ├── messages_de.properties
│       │   └── messages_ja.properties
│       └── icons/             # SVG icons (8 files)
│           ├── logo.svg
│           ├── scan.svg
│           ├── toggle_highlight.svg
│           ├── reload.svg
│           ├── add.svg
│           ├── quick_add.svg
│           ├── edit.svg
│           └── delete.svg
├── src/test/java/com/example/termmgmt/service/   # Unit tests (handlers only)
│   ├── CsvTermbaseHandlerTest.java
│   ├── XlsxTermbaseHandlerTest.java
│   └── TbxTermbaseHandlerTest.java
├── output/                    # Build output (not committed)
│   └── term-management/
└── reference/                 # Reference materials
```

## Development

### Prerequisites
- JDK 17+
- Apache Maven 3.6+
- Oxygen XML Editor 27+ (for SDK JARs and testing)

### Building
1. Copy `oxygen.jar` from your Oxygen XML Editor installation directory (`lib/oxygen.jar`) to `libs/`:
   ```bash
   cp <OXYGEN_HOME>/lib/oxygen.jar libs/
   ```
2. Build the plugin:
   ```bash
   mvn clean package
   ```

### Testing
Unit tests cover the termbase format handlers (`CsvTermbaseHandler`, `XlsxTermbaseHandler`, `TbxTermbaseHandler`), including round-trip save/load, encoding edge cases (UTF-8 BOM), malformed/edge-case input, and missing-file handling.

Run the test suite:
```bash
mvn test
```
Note: `mvn test` runs tests only and does not produce a plugin package — use `mvn package` to build a deployable jar.

### IntelliJ IDEA Setup
1. Open the project directory.
2. Ensure the project SDK is set to JDK 17.
3. Run Maven `package` goal to verify the build.

### Adding Oxygen SDK Dependencies
The Oxygen SDK JARs (`oxygen.jar`, etc.) are included in `libs/` and are referenced from the local Maven repository. Refer to `pom.xml` for the repository configuration.

## License

Apache License 2.0