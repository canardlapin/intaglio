package intaglio

class TemporalScaleSuite extends munit.FunSuite:
  private final case class Dated(day: CalendarDate, value: Double)

  test("native calendar and UTC values match independent Unix epoch and leap-day fixtures") {
    val epoch = CalendarDate.parseUnsafe("1970-01-01")
    val leapDay = CalendarDate.parseUnsafe("2000-02-29")
    val beforeEpoch = CalendarDate.parseUnsafe("1969-12-31")

    assertEquals(epoch.toEpochDay, 0L)
    assertEquals(beforeEpoch.toEpochDay, -1L)
    assertEquals(CalendarDate.parseUnsafe("0000-01-01").toEpochDay, -719528L)
    assertEquals(CalendarDate.parseUnsafe("2000-02-29").toEpochDay, 11016L)
    assertEquals(CalendarDate.parseUnsafe("9999-12-31").toEpochDay, 2932896L)
    assertEquals(CalendarDate.fromEpochDay(0L), Right(epoch))
    Vector("-0001-12-31", "0000-01-01", "1900-03-01", "2000-02-29", "9999-12-31")
      .map(CalendarDate.parseUnsafe)
      .foreach(date => assertEquals(CalendarDate.fromEpochDay(date.toEpochDay), Right(date)))
    assertEquals(leapDay.addYears(1), CalendarDate(2001, 2, 28))
    assertEquals(UtcDateTime.parseUnsafe("1970-01-01T00:00:00.000Z").epochMillis, 0L)
    assertEquals(
      UtcDateTime.parseUnsafe("1969-12-31T23:59:59.999Z").epochMillis,
      -1L
    )
  }

  test("calendar dates train, break, label, and round-trip exactly") {
    val values = Vector(
      CalendarDate.parseUnsafe("2024-01-15"),
      CalendarDate.parseUnsafe("2024-02-15"),
      CalendarDate.parseUnsafe("2024-03-15"),
      CalendarDate.parseUnsafe("2024-04-15")
    )
    val scale = DateScale
      .train(
        "visit date",
        values,
        breaks = TemporalBreaks.everyUnsafe(1, TemporalUnit.Month)
      )
      .fold(error => fail(error.message), identity)

    assertEquals(scale.domain.lower, values.head)
    assertEquals(scale.domain.upper, values.last)
    assertEquals(
      scale.breaks,
      Vector(
        CalendarDate.parseUnsafe("2024-02-01"),
        CalendarDate.parseUnsafe("2024-03-01"),
        CalendarDate.parseUnsafe("2024-04-01")
      )
    )
    assertEquals(scale.labels, Vector("2024-02-01", "2024-03-01", "2024-04-01"))
    assert(values.forall(scale.roundTrips))
    assertEquals(scale.mapValue(values.head), Some(0.0))
    assertEquals(scale.mapValue(values.last), Some(1.0))
    assertEquals(
      scale.descriptor.domain,
      ScaleDomain.Temporal(
        TemporalKind.Date,
        Interval.unsafe(values.head.toEpochDay.toDouble, values.last.toEpochDay.toDouble),
        "2024-01-15",
        "2024-04-15"
      )
    )
  }

  test("UTC instants use deterministic aligned breaks and exact millisecond round-trips") {
    val values = Vector(
      UtcDateTime.parseUnsafe("2024-01-01T00:00:00.000Z"),
      UtcDateTime.parseUnsafe("2024-01-01T00:30:00.000Z"),
      UtcDateTime.parseUnsafe("2024-01-01T01:00:00.000Z"),
      UtcDateTime.parseUnsafe("2024-01-01T02:00:00.000Z")
    )
    val scale = DateTimeScale
      .train(
        "acquisition time",
        values,
        breaks = TemporalBreaks.everyUnsafe(30, TemporalUnit.Minute)
      )
      .fold(error => fail(error.message), identity)

    assertEquals(
      scale.breaks,
      Vector(
        UtcDateTime.parseUnsafe("2024-01-01T00:00:00.000Z"),
        UtcDateTime.parseUnsafe("2024-01-01T00:30:00.000Z"),
        UtcDateTime.parseUnsafe("2024-01-01T01:00:00.000Z"),
        UtcDateTime.parseUnsafe("2024-01-01T01:30:00.000Z"),
        UtcDateTime.parseUnsafe("2024-01-01T02:00:00.000Z")
      )
    )
    assertEquals(
      scale.labels,
      Vector(
        "2024-01-01T00:00:00.000Z",
        "2024-01-01T00:30:00.000Z",
        "2024-01-01T01:00:00.000Z",
        "2024-01-01T01:30:00.000Z",
        "2024-01-01T02:00:00.000Z"
      )
    )
    assert(values.forall(scale.roundTrips))
  }

  test("invalid temporal values and break policies fail instead of rounding") {
    assertEquals(
      UtcDateTime.parse("2024-01-01T00:00:00.000001Z").left.toOption,
      Some(GraphicsError.InvalidTemporalText("date-time", "2024-01-01T00:00:00.000001Z"))
    )
    assertEquals(
      UtcDateTime
        .of(CalendarDate.parseUnsafe("2024-01-01"), 0, 0, 0, 1000)
        .left
        .toOption,
      Some(GraphicsError.InvalidUtcDateTime("2024-01-01", 0, 0, 0, 1000))
    )
    assertEquals(
      CalendarDate(2023, 2, 29).left.toOption,
      Some(GraphicsError.InvalidCalendarDate(2023, 2, 29))
    )
    Vector("+2024-01-01", "02024-01-01", "-0000-01-01", "2024-+1-01")
      .foreach(value =>
        assertEquals(
          CalendarDate.parse(value).left.toOption,
          Some(GraphicsError.InvalidTemporalText("date", value))
        )
      )
    assertEquals(
      UtcDateTime.parse("2024-01-01T+0:00:00.000Z").left.toOption,
      Some(GraphicsError.InvalidTemporalText("date-time", "2024-01-01T+0:00:00.000Z"))
    )
    assertEquals(
      TemporalBreaks.every(0, TemporalUnit.Day).left.toOption,
      Some(GraphicsError.InvalidTemporalBreakStep(0))
    )
    val dates = DateScale
      .train(
        "date",
        Vector(CalendarDate.parseUnsafe("2024-01-01"), CalendarDate.parseUnsafe("2024-01-02")),
        breaks = TemporalBreaks.everyUnsafe(1, TemporalUnit.Hour)
      )
      .fold(error => fail(error.message), identity)
    assertEquals(
      dates.breaksResult.left.toOption,
      Some(GraphicsError.UnsupportedTemporalBreakUnit("date", "hour"))
    )
  }

  test("typed date declarations train through the plotting DSL and derive ISO axes") {
    val values = Vector(
      Dated(CalendarDate.parseUnsafe("2024-01-01"), 1.0),
      Dated(CalendarDate.parseUnsafe("2024-02-01"), 2.0),
      Dated(CalendarDate.parseUnsafe("2024-03-01"), 3.0)
    )
    val yScale = ContinuousScaleSpec.numeric("signal").fold(error => fail(error.message), identity)
    val trained = plot(values)
      .scaleXDate(
        _.day,
        name = "visit",
        breaks = TemporalBreaks.everyUnsafe(1, TemporalUnit.Month)
      )
      .encode(Aesthetic.Y, _.value, yScale)
      .geomLine()
      .resolve
      .fold(error => fail(error.message), identity)

    assertEquals(trained.layers.head.rows.length, 3)
    assertEquals(trained.layers.head.rows.map(_.x), Vector(0.0, 0.5166666666666667, 1.0))
    assertEquals(
      trained.scaleRegistry.forAesthetic(Aesthetic.X).map(_.descriptor.kind),
      Some(ScaleKind.Temporal)
    )
    val xAxis = trained.guides
      .collectFirst {
        case ResolvedGuide(axis: GuideSpec.Axis, _) if axis.side == AxisSide.Bottom => axis
      }
      .getOrElse(fail("missing date axis"))
    assertEquals(
      xAxis.ticks.toVector.flatten.map(_.label),
      Vector("2024-01-01", "2024-02-01", "2024-03-01")
    )
    assert(
      trained.semantics.scales.exists(scale =>
        scale.kind == ScaleKind.Temporal && scale.domain.contains("date [2024-01-01, 2024-03-01]")
      )
    )
  }

  test("fixed temporal limits remain a scale-domain contract") {
    val lower = CalendarDate.parseUnsafe("2024-01-01")
    val upper = CalendarDate.parseUnsafe("2024-01-31")
    val scale = DateScale
      .fixed("date", Vector(lower, upper))
      .fold(error => fail(error.message), identity)

    assertEquals(scale.training, ScaleTraining.Fixed)
    assertEquals(scale.mapValue(lower.addDaysUnsafe(-1)), None)
    assertEquals(scale.mapValue(lower), Some(0.0))
    assertEquals(scale.mapValue(upper), Some(1.0))
  }

  test("temporal breaks remain defined at the maximum supported calendar boundary") {
    val first = CalendarDate.parseUnsafe("9999-11-15")
    val last = CalendarDate.parseUnsafe("9999-12-31")
    val monthly = DateScale
      .train(
        "terminal date",
        Vector(first, last),
        breaks = TemporalBreaks.everyUnsafe(1, TemporalUnit.Month)
      )
      .fold(error => fail(error.message), identity)
    assertEquals(monthly.breaksResult, Right(Vector(CalendarDate.parseUnsafe("9999-12-01"))))

    val unaligned = DateScale
      .train(
        "unaligned terminal date",
        Vector(CalendarDate.parseUnsafe("9999-12-15"), last),
        breaks = TemporalBreaks.everyUnsafe(1, TemporalUnit.Month)
      )
      .fold(error => fail(error.message), identity)
    assertEquals(unaligned.breaksResult, Right(Vector(CalendarDate.parseUnsafe("9999-12-15"))))

    val lastInstant = UtcDateTime.parseUnsafe("9999-12-31T23:59:59.999Z")
    val hourly = DateTimeScale
      .train(
        "terminal instant",
        Vector(UtcDateTime.parseUnsafe("9999-12-31T22:30:00.000Z"), lastInstant),
        breaks = TemporalBreaks.everyUnsafe(1, TemporalUnit.Hour)
      )
      .fold(error => fail(error.message), identity)
    assertEquals(
      hourly.breaksResult,
      Right(Vector(UtcDateTime.parseUnsafe("9999-12-31T23:00:00.000Z")))
    )
  }
