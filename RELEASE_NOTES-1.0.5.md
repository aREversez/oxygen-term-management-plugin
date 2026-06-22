# Release Notes - Term Management 1.0.5

## High-DPI (HiDPI / Retina) Display Support

### SVG Icon Rewrite: Vector Rendering, Not Bitmap
Toolbar icons are no longer pre-rendered as bitmaps. A new `SvgIcon` class implements `javax.swing.Icon` and renders the SVG directly in `paintIcon()` via SVG Salamander. At render time, Swing's `Graphics2D` already carries the display's DPI scale factor (e.g., 2× on a 4K screen), so the SVG renders at the native physical resolution — exactly like Oxygen's own icons.

- **4K / 200% scaling**: crystal clear, no upscaling artifacts
- **2K / 100% scaling**: identical sharpness to before
- All toolbar icons (scan, toggle highlight, reload, add, quick add, edit, delete) use this new approach

### Other Changes
- Removed unused `BufferedImage` intermediate rendering pipeline from `IconUtils`
- `loadIcon()` return type changed from `ImageIcon` to `Icon`; all callers compatible
- `loadLogo()` remains `ImageIcon` (used for window icon via `setIconImage()`)

## Full Since-1.0.4 Changelog

### Features
- **CJK Term Recognition**: Chinese, Japanese, Korean terms now correctly matched
- **Author-Mode Highlight Toggle**: per-document highlight on/off with yellow background
- **Multi-File Termbase Addition**: select multiple files at once via native Windows dialog
- **Remember Last Directory**: file chooser persists the last-used termbase directory
- **Theme-Aware SVG Icons**: all toolbar icons adapt to Oxygen dark/light theme

### Bug Fixes
- Highlight had off-by-one error (one extra character highlighted)
- Action row layout clipped when Toggle Highlight wrapped to next line
- Chinese/CJK terms not recognized (word-boundary assertion failed on Unicode letters)
- Duplicate termbase files could be added silently
- Unsupported file format gave no error feedback
- "Add Termbase" button non-responsive (FileDialog parent cast to Frame failed)
- FileDialog showed default Java logo instead of plugin icon
- Toolbar icons blurry on high-DPI (4K) displays — **fixed via SvgIcon**

### Improvements
- Icon size reduced: 20px → 16px (toolbar), button 28×28 → 24×24
- Native Windows `FileDialog` replaces Swing `JFileChooser` (rubber-band multi-select)
- File format validation with per-file skip summary
- Version bumped 1.0.0 → 1.0.5
- BCP 47 language identification documented in README
- Build prerequisite (copy `oxygen.jar`) documented
- Cleaned up stale JARs (`curvesapi` duplicates)
- `libs/oxygen.jar` added to `.gitignore`
