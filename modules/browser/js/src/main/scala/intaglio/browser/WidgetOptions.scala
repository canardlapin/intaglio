package intaglio.browser

/** Optional controls. Omitting a control hides its UI, not the programmatic capability. */
enum WidgetControl:
  case Inspect, Rectangle, Lasso, Pan, ZoomRectangle, Reset, Fullscreen, Download

enum ToolbarPosition:
  case Top, Bottom, FloatingTop, FloatingBottom

enum ToolbarVisibility:
  case Always, OnFocus, Hidden

enum WidgetSizing:
  case Responsive
  case Fixed(width: Int)

/** Explicit export choices: original means the unwindowed current data revision. */
enum ExportViewport:
  case Original, Current

enum ExportSelection:
  case Omit, Include

/** Presentation options do not alter the plot, selection semantics, or event contract. */
final case class WidgetOptions(
    controls: Set[WidgetControl] = WidgetOptions.navigationControls,
    toolbarPosition: ToolbarPosition = ToolbarPosition.Top,
    toolbarVisibility: ToolbarVisibility = ToolbarVisibility.Always,
    sizing: WidgetSizing = WidgetSizing.Responsive
)

object WidgetOptions:
  val navigationControls: Set[WidgetControl] = Set(
    WidgetControl.Inspect,
    WidgetControl.Rectangle,
    WidgetControl.Lasso,
    WidgetControl.Pan,
    WidgetControl.ZoomRectangle,
    WidgetControl.Reset
  )
  val allControls: Set[WidgetControl] = WidgetControl.values.toSet
