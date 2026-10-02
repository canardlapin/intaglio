package intaglio

/** A difference extent such as `npc(1) - pt(m)` is admitted at construction with its sign decided
  * when it is resolved against a concrete frame; everything without a difference keeps the
  * construction-time proof.
  */
class ExtentDifferenceSuite extends munit.FunSuite:
  private def ok[A](result: Either[GraphicsError, A]): A =
    result.fold(error => fail(error.message), identity)

  private val fullMinusMargin: ExtentExpr =
    ok(ExtentExpr.fromExpr(LengthExpr.npcUnsafe(1.0) - LengthExpr(Length.pointsUnsafe(12.0))))

  private def resolver(width: Double, height: Double): LengthResolver =
    val device = DeviceContext.unsafe(width, height)
    LengthResolver(device, DeviceFrame.root(device))

  test("the full extent minus a fixed margin resolves across resizes") {
    // 12 pt at 96 ppi is 16 px.
    Vector(400.0, 800.0, 1234.0).foreach { width =>
      assertEqualsDouble(ok(resolver(width, 300.0).width(fullMinusMargin)), width - 16.0, 1e-9)
      assertEqualsDouble(ok(resolver(300.0, width).height(fullMinusMargin)), width - 16.0, 1e-9)
    }
  }

  test("a negative resolution is a typed error naming the expression and value, not a clamp") {
    assertEquals(
      resolver(10.0, 300.0).width(fullMinusMargin).left.toOption,
      Some(GraphicsError.InvalidExtent("npc(1) - pt(12) resolved to -6 px"))
    )
    // The axis-neutral extent fails on whichever axis is too small.
    assert(resolver(300.0, 10.0).extent(fullMinusMargin).isLeft)
  }

  test("a viewport sized by a difference insets its child frame, and refuses when too small") {
    val inset = ok(
      Viewport.checked(size = Size.fromExtents(fullMinusMargin, fullMinusMargin))
    )
    val frame = ok(resolver(640.0, 480.0).childFrame(inset))
    assertEqualsDouble(frame.width, 624.0, 1e-9)
    assertEqualsDouble(frame.height, 464.0, 1e-9)
    assert(resolver(12.0, 480.0).childFrame(inset).left.toOption.exists {
      case GraphicsError.InvalidExtent(description) => description.startsWith("npc(1) - pt(12)")
      case _                                        => false
    })
  }

  test("a location offset by a negative difference is refused at resolution") {
    val location = LengthExpr.npcUnsafe(0.5) + fullMinusMargin
    assertEqualsDouble(ok(resolver(400.0, 300.0).x(location)), 200.0 + 384.0, 1e-9)
    assert(resolver(10.0, 300.0).x(location).isLeft)
  }

  test("rounding noise in an exactly cancelling difference resolves to zero") {
    val empty = ok(
      ExtentExpr.fromExpr(
        LengthExpr.npcUnsafe(0.3) - LengthExpr.npcUnsafe(0.1) - LengthExpr.npcUnsafe(0.2)
      )
    )
    assertEquals(ok(resolver(3.0, 3.0).width(empty)), 0.0)
  }

  test("expressions without a difference keep the construction-time proof") {
    assertEquals(ExtentExpr.npc(-0.5).left.toOption, Some(GraphicsError.InvalidExtent("-0.5 Npc")))
    assert(ExtentExpr.fromExpr(LengthExpr.Mul(-1.0, LengthExpr.npcUnsafe(1.0))).isLeft)
    assert(
      ExtentExpr
        .fromExpr(LengthExpr.npcUnsafe(0.0) + ExtentExpr.npcUnsafe(0.5))
        .isLeft,
      "a location offset is not an extent"
    )
    // A difference whose operand is itself refused stays refused.
    assert(
      ExtentExpr
        .fromExpr(LengthExpr.npcUnsafe(1.0) - LengthExpr(Length.npcUnsafe(-0.5)))
        .isLeft
    )
    // A proved extent never needs the resolution check, even where it resolves to zero.
    assertEquals(ok(resolver(1.0, 1.0).width(ExtentExpr.zero)), 0.0)
  }
