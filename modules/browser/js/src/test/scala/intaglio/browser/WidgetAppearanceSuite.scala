package intaglio.browser

class WidgetAppearanceSuite extends munit.FunSuite:
  test("invalid opacity and transition inputs are rejected before mounting") {
    Vector(Double.NaN, Double.PositiveInfinity, -0.1, 1.1).foreach { value =>
      assert(WidgetAppearance(inactiveOpacity = value).validate.isLeft)
    }
    Vector(-1, 10001).foreach(value =>
      assert(WidgetAppearance(transitionMs = value).validate.isLeft)
    )
    assert(WidgetAppearance(inactiveOpacity = 0, transitionMs = 0).validate.isRight)
    assert(WidgetAppearance(inactiveOpacity = 1, transitionMs = 10000).validate.isRight)
  }
