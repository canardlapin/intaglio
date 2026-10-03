# Interaction 12: JavaFX host receipt

The JavaFX interaction host, including its Interaction 10 surface (inspector, saved selections,
undo/redo, snapshots), was qualified at source **`0f6243a9e67b157e09fb927da4c9e082594e08b0`**
(clean tree, on main `5e1b10f`) by three separate courts. None is inferred from another, and the
headless results are not offered as desktop proof.

## Native desktop (this directory)

`sbt "javafxExample/runMain intaglio.javafx.example.NativeEvidence <dir>"`, which wrote
[`evidence.json`](evidence.json):

| Field | Value |
| --- | --- |
| OS | macOS 14.3, aarch64 |
| JDK | OpenJDK 25.0.1 (Homebrew) |
| OpenJFX | 21.0.5+1 (`javafx-base`/`javafx-graphics` `mac-aarch64`) |
| Glass / Prism | `com.sun.glass.ui.mac.MacApplication` / `com.sun.prism.es2.ES2Pipeline` |
| Display | 1728 x 1117 logical; every stage reported output scale 2 |
| Input path | `scene-events`: JavaFX events fired into the live scene graph; physical pointer and keyboard filtered out of the scripted stages; **no OS input injected** |

Results:

- **Shared traces**: the widget (45 steps), linked (21) and members (22) scripts produced traces
  equal, step by step, to the recorded Chromium traces ([`widget-javafx.json`](widget-javafx.json),
  [`linked-javafx.json`](linked-javafx.json), [`members-javafx.json`](members-javafx.json)); no host
  errors.
- **Application-thread ownership**: off-thread calls return `WrongThread`; the same calls succeed on
  the FX thread.
- **Keyboard focus**: the plot node is the scene's focus owner; Home/Right/Enter rove, show the
  tooltip at once, announce the mark and select it, and the selection is projected into the linked
  histogram. The window held OS focus for the first trace stage only in this run (another
  application took it); scene-level focus ownership, which these checks use, does not depend on it.
- **Inspector and history**: the `JavaFxInspector` shows the selected observation ("1: t29"); the
  platform undo shortcut (Cmd+Z) clears it, Shift+Cmd+Z restores it, and a snapshot round-trips
  through JSON.
- **Tooltip**: pointer hover shows the delayed structured tooltip.
- **Navigation**: keyboard zoom twice, a pan drag and `0` reset, through `DataWindowNavigator` and
  `InteractionCompiler.rezoom`.
- **Disposal**: the live hosts dispose; 200 further build/dispose cycles leave 0 hosts reachable
  after GC.

Screenshots are snapshots of the real stages' scene graphs at output scale 2 (not screen grabs):
[initial](screenshots/example-initial.png), [keyboard focus](screenshots/example-keyboard-focus.png),
[inspector](screenshots/example-inspector.png), [hover tooltip](screenshots/example-hover-tooltip.png),
[zoomed](screenshots/example-zoomed.png), [panned](screenshots/example-panned.png), and trace steps
[widget 1](screenshots/widget-step-1.png) (keyboard tooltip),
[widget 14](screenshots/widget-step-14.png) (delayed hover tooltip, inverse emphasis) and
[members 4](screenshots/members-step-4.png) (linked coverage "1 of 7 selected").

**Pending**: OS-injected pointer and keyboard input (`--os-input`, the Glass robot; it moves the
real pointer and needs accessibility permission, so it was not run here), screen readers (VoiceOver),
multi-monitor scale changes, and Windows/Linux desktops.

## Shared traces against the browser

`tools/check-host-trace-browser.cjs` recorded [`tools/trace/browser`](../../tools/trace/browser) in
Playwright Chromium 151.0.7922.34 on SVG and Canvas, from the fixture built at `5e1b10f` (the browser
and fixture sources are unchanged on this branch). Each record carries the script and fixture
SHA-256. `JavaFxTraceParitySuite` requires the SVG and Canvas recordings to agree with each other
and the JavaFX replay to equal them, through both the Glass robot and scene events, and checks that
one changed event, key or announcement is detected.

## Headless toolkit

Monocle with software Prism: `JavaFxInteractionHostSuite`, `JavaFxHostCapabilitySuite` and
`JavaFxHistorySuite` on a simulated 2x screen, and `JavaFxTraceParitySuite` on a 1x screen. This is
toolkit evidence only. Gate results for the final commit are recorded in its commit message.
