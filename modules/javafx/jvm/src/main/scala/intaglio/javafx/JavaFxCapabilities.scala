package intaglio.javafx

import intaglio.IntaglioError
import intaglio.interaction.InteractionError

/** One interactive capability a host may or may not provide, named after the rows of the browser
  * widget's capability matrix (docs/browser-capabilities.md) so the two hosts can be compared row
  * by row.
  */
enum HostCapability(val area: String, val label: String):
  case PlainTooltip extends HostCapability("Inspection", "Plain-text tooltip")
  case StructuredTooltip extends HostCapability("Inspection", "Structured tooltip")
  case DirectHover extends HostCapability("Inspection", "Direct hover")
  case NearestHover extends HostCapability("Inspection", "Nearest hover")
  case TooltipPlacement
      extends HostCapability("Inspection", "Pointer, mark-anchored and fixed tooltip placement")
  case TooltipDelay extends HostCapability("Inspection", "Configurable delay")
  case TooltipAppearance extends HostCapability("Inspection", "Configurable tooltip appearance")
  case HoverAppearance extends HostCapability("Emphasis", "Hovered appearance")
  case SelectedAppearance extends HostCapability("Emphasis", "Selected appearance")
  case FocusedAppearance extends HostCapability("Emphasis", "Focused appearance")
  case InverseEmphasis
      extends HostCapability("Emphasis", "Inactive appearance and inverse emphasis")
  case EmphasisTransitions extends HostCapability("Emphasis", "Configurable transitions")
  case RuntimeTargetStyles extends HostCapability("Emphasis", "Externally assigned styles")
  case LinkedLegendEmphasis
      extends HostCapability("Emphasis", "Linked legend emphasis and recovery")
  case PointerActivation extends HostCapability("Actions", "Pointer activation")
  case KeyboardActivation extends HostCapability("Actions", "Keyboard activation")
  case TargetLinks extends HostCapability("Actions", "Declarative links")
  case HostCallbacks extends HostCapability("Actions", "Host callbacks")
  case SelectionModes extends HostCapability("Selection", "Disabled, single and multiple modes")
  case ToggleSelection extends HostCapability("Selection", "Toggle")
  case RectangleSelection extends HostCapability("Selection", "Rectangle replace, add, subtract")
  case LassoSelection extends HostCapability("Selection", "Lasso")
  case ExternalSelection extends HostCapability("Selection", "Externally supplied selection")
  case InitialSelection extends HostCapability("Selection", "Initial selection")
  case PlotParts
      extends HostCapability("Plot parts", "Legend keys, strips, axes, titles and annotations")
  case Pan extends HostCapability("Navigation", "Pan")
  case WheelZoom extends HostCapability("Navigation", "Wheel zoom")
  case PinchZoom extends HostCapability("Navigation", "Pinch zoom")
  case RectangleZoom extends HostCapability("Navigation", "Rectangle zoom")
  case KeyboardZoom extends HostCapability("Navigation", "Keyboard zoom and reset")
  case NavigationBounds extends HostCapability("Navigation", "Bounds")
  case Reset extends HostCapability("Navigation", "Reset")
  case Toolbar extends HostCapability("Navigation", "Toolbar controls")
  case SharedHover extends HostCapability("Composition", "Shared hover across plots")
  case SharedSelection extends HostCapability("Composition", "Shared selection across plots")
  case ComposedFigures extends HostCapability("Composition", "Across panels of one composed figure")
  case ResponsiveSizing extends HostCapability("Embedding", "Responsive sizing")
  case Fullscreen extends HostCapability("Embedding", "Fullscreen")
  case PngExport extends HostCapability("Embedding", "PNG export")
  case StandaloneHtml extends HostCapability("Embedding", "Standalone HTML")
  case ProgrammaticAccess extends HostCapability("Embedding", "Programmatic event/state access")
  case AggregateMembers extends HostCapability("Analytical", "Aggregate member selection")
  case DeferredMembers extends HostCapability("Analytical", "Deferred member resolution")
  case CoverageEmphasis extends HostCapability("Analytical", "Partial-coverage counts and emphasis")
  case TextCompanion extends HostCapability("Inspection", "Text description of every mark and part")
  case Inspector extends HostCapability("Analytical", "Inspector of what a selection means")
  case NamedSelections
      extends HostCapability("Analytical", "Named selections and selection algebra")
  case UndoRedo extends HostCapability("Analytical", "Undo and redo of durable state")
  case Snapshots extends HostCapability("Analytical", "Versioned snapshots and restore")
  case FilterToSelection extends HostCapability("Analytical", "Filter a plot to a selection")

/** Whether the JavaFX host provides a capability; a refusal says what to do instead. */
enum CapabilitySupport:
  case Supported

  /** Not provided by this host. `instead` names the supported route to the same result. */
  case Unsupported(reason: String, instead: String)

/** The JavaFX host's capability matrix. Every capability the host lacks is refused through
  * [[require]] with a typed `InteractionError.UnsupportedCapability`, never silently ignored; the
  * host's own entry points use the same refusals.
  */
object JavaFxCapabilities:
  import CapabilitySupport.*
  import HostCapability.*

  def support(capability: HostCapability): CapabilitySupport =
    capability match
      case EmphasisTransitions =>
        Unsupported(
          "emphasis changes are drawn at once, with no animated dimming",
          "animate host.node's opacity or a parent effect from the application"
        )
      case RuntimeTargetStyles =>
        Unsupported(
          "the JavaFX host draws a compiled program and cannot repaint single targets",
          "map the style in the plot's aesthetics, recompile and call host.update(view)"
        )
      case Toolbar =>
        Unsupported(
          "the host is one focusable node with no built-in toolbar",
          "drive host.setGestureMode, host.zoomBy and host.resetWindow from application controls"
        )
      case Fullscreen =>
        Unsupported(
          "the host is a node; full-screen belongs to the application's Stage",
          "call Stage.setFullScreen on the stage that holds host.node"
        )
      case PngExport =>
        Unsupported(
          "PNG encoding needs javafx-swing or an image codec the host does not depend on",
          "call host.node.snapshot(...) and encode the WritableImage in the application"
        )
      case StandaloneHtml =>
        Unsupported(
          "a desktop node has no HTML packaging",
          "use intaglio-browser's standalone widget for HTML"
        )
      case _ => Supported

  /** Every capability with its support, in declaration order. */
  def matrix: Vector[(HostCapability, CapabilitySupport)] =
    HostCapability.values.toVector.map(c => c -> support(c))

  /** Right when supported; otherwise the actionable refusal every host entry point returns. */
  def require(capability: HostCapability): Either[IntaglioError, Unit] =
    support(capability) match
      case Supported                    => Right(())
      case Unsupported(reason, instead) => Left(refusal(capability, reason, instead))

  private[javafx] def refusal(
      capability: HostCapability,
      reason: String,
      instead: String
  ): InteractionError =
    InteractionError.UnsupportedCapability(
      s"${capability.label.toLowerCase} on the JavaFX host: $reason; instead, $instead"
    )
