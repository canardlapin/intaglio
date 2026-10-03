# Save an interactive plot as one HTML file

A browser application compiled by Scala.js can be packaged with its runtime into a single HTML
file. Readers can open the file directly, without a server or network connection. The package
contains the application code and optional stylesheet; the application still owns its typed
plots, widget behavior, event subscriptions, and controlled state.

Build a `NoModule` bundle with a main initializer. For the repository's runnable two-widget
example:

```sh
sbt browserFixture/fastLinkJS
node tools/package-widget.cjs \
  modules/browser-fixture/target/scala-3.3.8/browserfixture-fastopt/main.js \
  /tmp/intaglio-interactive.html --containers left,right --title 'Offline plots'
```

Open `/tmp/intaglio-interactive.html` in a browser. Tab to either plot, use Home or the arrow
keys to focus a mark, and Enter to select. The scatter and histogram maintain separate state.
The [browser widget guide](browser-widget.md) describes the framework-neutral application API,
including `setSelection`, subscriptions, and disposal. Packaging does not change those APIs.

For your application, pass exactly the container IDs its main method mounts into. IDs must be
unique. `--css report.css` embeds a stylesheet; it can set each container's width or font.
The default shell uses 480-pixel containers that shrink to the available width and wrap onto
new rows. The title is escaped as text. JavaScript and CSS are encoded as UTF-8 before
embedding, so literal closing tags and non-ASCII labels retain their meaning.

## Assets and errors

Supply a **trusted, self-contained NoModule bundle**, not an ES module that imports sibling
files. The packager embeds the supplied code; it does not compile Scala or discover assets
referenced dynamically by an application. Embed fonts and images as data URLs. External
stylesheets, scripts, fonts, network calls, and form submissions are blocked by the package's
content security policy. A visible alert reports blocked resources and runtime errors.
User-activated links can still navigate; opening an offline artifact is not a sandbox for
untrusted application code.

The HTML records the source runtime's SHA-256, and the CLI prints the completed file's hash
and size. Keep these with your source revision when distributing an artifact. The HTML is
self-contained; the original `.js` and `.css` files need not accompany it.

## Verification and scope

```sh
node --test tools/package-widget.test.cjs
node tools/check-standalone-browser.cjs \
  modules/browser-fixture/target/scala-3.3.8/browserfixture-fastopt/main.js \
  /tmp/intaglio-standalone-check
```

The browser check opens the saved file offline at desktop and narrow widths, at device scales
1 and 2. It checks keyboard selection, independent widgets, application-controlled selection
without event echo, unique IDs, no horizontal page overflow, and no request beyond the HTML
itself. It also verifies Unicode/closing-tag preservation and a visible failure for an
unbundled resource. Screenshots and a JSON receipt remain in the output directory.

This packaging check does not certify PNG export, fullscreen, configurable controls, Canvas
parity, or native JavaFX behavior. Those capabilities retain their separate interaction-ticket
acceptance gates. Ordinary static SVG, PDF, and raster exports do not use this packager or
include an interaction runtime.

## PNG snapshots

`SvgWidgetExport.png(view, selection, scale)` exports an explicitly supplied `SvgWidgetView`.
Keep the unwindowed view for an original-viewport export; supply a rewindowed view for a current
viewport export. Pass `Selection()` to omit selection, or the intended selection to add its
outline rings. Transient hover and keyboard-focus decorations are omitted. Export does not
change widget state or emit events.

The result is a JavaScript promise of `Either[WidgetExportError, String]`: success is a PNG data
URL. The caller chooses whether to display or download it. Invalid image sizes, absent canvas
support, decoding failures, and encoder failures return error values. Exports are bounded to
16,777,216 pixels and 16,384 pixels per axis. A decoding timeout releases temporary resources.
`SvgWidgetExport.svg(view, selection)` provides the corresponding vector snapshot.

A snapshot uses the SVG's own font and image resources. Embed those resources; a font merely
loaded by a surrounding web page is not automatically embedded in an exported SVG image.

## Configure the widget's controls

Pass `options = WidgetOptions(...)` to `SvgWidget.mount`. `WidgetOptions.allControls` includes
selection/navigation controls, fullscreen, and PNG download. The default retains the navigation
controls. Choose a subset with `Set(WidgetControl.Reset, WidgetControl.Download)`, for example.
Omitting a button changes presentation; it does not disable the corresponding application API.

- `toolbarPosition`: `Top`, `Bottom`, `FloatingTop`, or `FloatingBottom` within the widget.
- `toolbarVisibility`: `Always`, `OnFocus` (shown on hover or focus within the widget), or `Hidden`.
- `sizing`: `WidgetSizing.Responsive` or `WidgetSizing.Fixed(width)` in CSS pixels; nonpositive
  fixed widths are rejected.

The toolbar's buttons form one tab stop with arrow/Home/End navigation. PNG viewport and
selection selectors are separate, labelled native controls. A subset that omits Inspect still
has an accessible first button. The download uses the choices displayed beside it; it does not
silently switch to an original view or omit selection.

`widget.exportPng(ExportViewport.Current, ExportSelection.Include)` offers the same operation
programmatically. `Original` means the unwindowed **current data revision**, including after an
application update, rather than a stale initial dataset. The PNG has a white background and
omits transient hover/focus. `widget.toggleFullscreen()` returns an explicit capability/refusal
result. Call it from a reader activation; browsers may refuse requests without one. Control
failures are also shown as a visible alert in the widget.

Pass caller-owned `SvgFonts` as the optional `fonts` argument to `SvgWidgetView.compile` to
embed configured faces. Navigation and original/current export preserve those resources.
Choose the corresponding font family in the plot theme; font embedding does not change the
plot's typography configuration or license the face for you.

The dedicated `tools/check-export-browser.cjs <fixture main.js> <output-dir> [font-file]` check
verifies decoded PNG dimensions/selection pixels, explicit download choices, real fullscreen,
keyboard access, hidden/floating presentation, resource cleanup and failure paths. With a font
file, it also compares drawn text width against an independently loaded copy before and after
navigation. Browser/runtime details stay in its receipt; this is not a claim about every browser.
