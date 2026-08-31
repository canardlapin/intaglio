package intaglio

/** Dependency-free proleptic-Gregorian date shared byte-for-byte by the JVM and Scala.js. Years
  * -9999 through 9999 are supported, including ISO year zero.
  */
final case class CalendarDate private (year: Int, month: Int, day: Int):
  require(year >= CalendarDate.MinimumYear && year <= CalendarDate.MaximumYear)
  require(month >= 1 && month <= 12)
  require(day >= 1 && day <= CalendarDate.daysInMonth(year, month))

  def toEpochDay: Long =
    CalendarDate.toEpochDay(year, month, day)

  def compareTo(that: CalendarDate): Int =
    val left = toEpochDay
    val right = that.toEpochDay
    if left < right then -1 else if left > right then 1 else 0

  def addDays(days: Long): Either[GraphicsError, CalendarDate] =
    CalendarDate.checkedAdd(toEpochDay, days).flatMap(CalendarDate.fromEpochDay)

  def addWeeks(weeks: Long): Either[GraphicsError, CalendarDate] =
    CalendarDate.checkedMultiply(weeks, 7L).flatMap(addDays)

  def addMonths(months: Long): Either[GraphicsError, CalendarDate] =
    val current = year.toLong * 12L + month.toLong - 1L
    CalendarDate.checkedAdd(current, months).flatMap { next =>
      val nextYear = CalendarDate.floorDiv(next, 12L)
      val nextMonth = (next - nextYear * 12L + 1L).toInt
      if nextYear < CalendarDate.MinimumYear || nextYear > CalendarDate.MaximumYear then
        Left(
          GraphicsError.TemporalValueOutOfRange(
            TemporalKind.Date.label,
            toString,
            "calendar month addition exceeds the supported year range"
          )
        )
      else
        CalendarDate(
          nextYear.toInt,
          nextMonth,
          math.min(day, CalendarDate.daysInMonth(nextYear.toInt, nextMonth))
        )
    }

  def addYears(years: Long): Either[GraphicsError, CalendarDate] =
    CalendarDate.checkedAdd(year.toLong, years).flatMap { nextYear =>
      if nextYear < CalendarDate.MinimumYear || nextYear > CalendarDate.MaximumYear then
        Left(
          GraphicsError.TemporalValueOutOfRange(
            TemporalKind.Date.label,
            toString,
            "calendar year addition exceeds the supported year range"
          )
        )
      else
        CalendarDate(
          nextYear.toInt,
          month,
          math.min(day, CalendarDate.daysInMonth(nextYear.toInt, month))
        )
    }

  def addDaysUnsafe(days: Long): CalendarDate = addDays(days).orThrow
  def addWeeksUnsafe(weeks: Long): CalendarDate = addWeeks(weeks).orThrow
  def addMonthsUnsafe(months: Long): CalendarDate = addMonths(months).orThrow
  def addYearsUnsafe(years: Long): CalendarDate = addYears(years).orThrow

  override def toString: String =
    s"${CalendarDate.formatYear(year)}-${CalendarDate.twoDigits(month)}-${CalendarDate.twoDigits(day)}"

object CalendarDate:
  val MinimumYear: Int = -9999
  val MaximumYear: Int = 9999

  def apply(year: Int, month: Int, day: Int): Either[GraphicsError, CalendarDate] =
    if year < MinimumYear || year > MaximumYear || month < 1 || month > 12 || day < 1 ||
      day > daysInMonthOption(year, month).getOrElse(0)
    then Left(GraphicsError.InvalidCalendarDate(year, month, day))
    else Right(new CalendarDate(year, month, day))

  def unsafe(year: Int, month: Int, day: Int): CalendarDate =
    apply(year, month, day).orThrow

  def parse(value: String): Either[GraphicsError, CalendarDate] =
    val negative = value.length == 11 && value(0) == '-'
    val positive = value.length == 10
    val yearStart = if negative then 1 else 0
    val monthSeparator = if negative then 5 else 4
    val daySeparator = if negative then 8 else 7
    val validShape =
      (positive || negative) && value(monthSeparator) == '-' && value(daySeparator) == '-' &&
        asciiDigits(value, yearStart, monthSeparator) &&
        asciiDigits(value, monthSeparator + 1, daySeparator) &&
        asciiDigits(value, daySeparator + 1, value.length)
    if !validShape
    then Left(GraphicsError.InvalidTemporalText(TemporalKind.Date.label, value))
    else
      val yearMagnitude = value.substring(yearStart, monthSeparator).toInt
      if negative && yearMagnitude == 0 then
        Left(GraphicsError.InvalidTemporalText(TemporalKind.Date.label, value))
      else
        val year = if negative then -yearMagnitude else yearMagnitude
        val month = value.substring(monthSeparator + 1, daySeparator).toInt
        val day = value.substring(daySeparator + 1).toInt
        apply(year, month, day)

  def parseUnsafe(value: String): CalendarDate =
    parse(value).orThrow

  def fromEpochDay(value: Long): Either[GraphicsError, CalendarDate] =
    val shifted = value + 719468L
    val era = floorDiv(shifted, 146097L)
    val dayOfEra = shifted - era * 146097L
    val yearOfEra =
      (dayOfEra - dayOfEra / 1460L + dayOfEra / 36524L - dayOfEra / 146096L) / 365L
    val baseYear = yearOfEra + era * 400L
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = (dayOfYear - (153L * monthPrime + 2L) / 5L + 1L).toInt
    val month = (monthPrime + (if monthPrime < 10L then 3L else -9L)).toInt
    val year = baseYear + (if month <= 2 then 1L else 0L)
    if year < MinimumYear || year > MaximumYear then
      Left(
        GraphicsError.TemporalValueOutOfRange(
          TemporalKind.Date.label,
          value.toString,
          "epoch day exceeds the supported year range"
        )
      )
    else apply(year.toInt, month, day)

  def fromEpochDayUnsafe(value: Long): CalendarDate =
    fromEpochDay(value).orThrow

  def isLeapYear(year: Int): Boolean =
    year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

  def daysInMonth(year: Int, month: Int): Int =
    daysInMonthOption(year, month).getOrElse(0)

  private def daysInMonthOption(year: Int, month: Int): Option[Int] =
    month match
      case 1 | 3 | 5 | 7 | 8 | 10 | 12 => Some(31)
      case 4 | 6 | 9 | 11              => Some(30)
      case 2                           => Some(if isLeapYear(year) then 29 else 28)
      case _                           => None

  private def toEpochDay(year: Int, month: Int, day: Int): Long =
    val adjustedYear = year.toLong - (if month <= 2 then 1L else 0L)
    val era = floorDiv(adjustedYear, 400L)
    val yearOfEra = adjustedYear - era * 400L
    val monthPrime = month.toLong + (if month > 2 then -3L else 9L)
    val dayOfYear = (153L * monthPrime + 2L) / 5L + day.toLong - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    era * 146097L + dayOfEra - 719468L

  private[intaglio] def floorDiv(value: Long, positiveDivisor: Long): Long =
    val quotient = value / positiveDivisor
    val remainder = value % positiveDivisor
    if remainder != 0L && value < 0L then quotient - 1L else quotient

  private def checkedAdd(left: Long, right: Long): Either[GraphicsError, Long] =
    val result = left + right
    if ((left ^ result) & (right ^ result)) < 0L then
      Left(
        GraphicsError.TemporalValueOutOfRange(
          TemporalKind.Date.label,
          s"$left + $right",
          "integer addition overflow"
        )
      )
    else Right(result)

  private def checkedMultiply(left: Long, right: Long): Either[GraphicsError, Long] =
    if left == 0L || right == 0L then Right(0L)
    else
      val result = left * right
      if result / right != left then
        Left(
          GraphicsError.TemporalValueOutOfRange(
            TemporalKind.Date.label,
            s"$left * $right",
            "integer multiplication overflow"
          )
        )
      else Right(result)

  private def formatYear(value: Int): String =
    if value >= 0 then leftPad(value.toString, 4)
    else s"-${leftPad((-value).toString, 4)}"

  private def twoDigits(value: Int): String =
    leftPad(value.toString, 2)

  private def leftPad(value: String, width: Int): String =
    "0" * math.max(0, width - value.length) + value

  private def asciiDigits(value: String, from: Int, until: Int): Boolean =
    var index = from
    var valid = from < until
    while index < until && valid do
      val character = value(index)
      valid = character >= '0' && character <= '9'
      index += 1
    valid

/** Dependency-free UTC date-time with exact millisecond precision. */
final case class UtcDateTime private (epochMillis: Long):
  require(epochMillis >= UtcDateTime.MinimumMillis && epochMillis <= UtcDateTime.MaximumMillis)

  def compareTo(that: UtcDateTime): Int =
    if epochMillis < that.epochMillis then -1 else if epochMillis > that.epochMillis then 1 else 0

  def date: CalendarDate =
    CalendarDate.fromEpochDayUnsafe(CalendarDate.floorDiv(epochMillis, UtcDateTime.MillisPerDay))

  def hour: Int = (millisOfDay / 3600000L).toInt
  def minute: Int = ((millisOfDay % 3600000L) / 60000L).toInt
  def second: Int = ((millisOfDay % 60000L) / 1000L).toInt
  def millisecond: Int = (millisOfDay % 1000L).toInt

  def addMillis(value: Long): Either[GraphicsError, UtcDateTime] =
    val result = epochMillis + value
    if ((epochMillis ^ result) & (value ^ result)) < 0L then
      Left(
        GraphicsError.TemporalValueOutOfRange(
          TemporalKind.DateTime.label,
          toString,
          "millisecond addition overflow"
        )
      )
    else UtcDateTime(result)

  def addMillisUnsafe(value: Long): UtcDateTime = addMillis(value).orThrow

  override def toString: String =
    s"${date}T${UtcDateTime.twoDigits(hour)}:${UtcDateTime.twoDigits(minute)}:${UtcDateTime.twoDigits(second)}.${UtcDateTime.threeDigits(millisecond)}Z"

  private def millisOfDay: Long =
    epochMillis - CalendarDate.floorDiv(
      epochMillis,
      UtcDateTime.MillisPerDay
    ) * UtcDateTime.MillisPerDay

object UtcDateTime:
  private[intaglio] val MillisPerDay: Long = 86400000L
  private[intaglio] val MinimumMillis =
    CalendarDate.unsafe(CalendarDate.MinimumYear, 1, 1).toEpochDay * MillisPerDay
  private[intaglio] val MaximumMillis =
    CalendarDate.unsafe(CalendarDate.MaximumYear, 12, 31).toEpochDay * MillisPerDay +
      MillisPerDay - 1L

  def apply(epochMillis: Long): Either[GraphicsError, UtcDateTime] =
    if epochMillis < MinimumMillis || epochMillis > MaximumMillis then
      Left(
        GraphicsError.TemporalValueOutOfRange(
          TemporalKind.DateTime.label,
          epochMillis.toString,
          "epoch milliseconds exceed the supported calendar range"
        )
      )
    else Right(new UtcDateTime(epochMillis))

  def unsafe(epochMillis: Long): UtcDateTime =
    apply(epochMillis).orThrow

  def of(
      date: CalendarDate,
      hour: Int,
      minute: Int,
      second: Int,
      millisecond: Int
  ): Either[GraphicsError, UtcDateTime] =
    if hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59 ||
      millisecond < 0 || millisecond > 999
    then Left(GraphicsError.InvalidUtcDateTime(date.toString, hour, minute, second, millisecond))
    else
      apply(
        date.toEpochDay * MillisPerDay + hour.toLong * 3600000L + minute.toLong * 60000L +
          second.toLong * 1000L + millisecond.toLong
      )

  def ofUnsafe(
      date: CalendarDate,
      hour: Int,
      minute: Int,
      second: Int,
      millisecond: Int
  ): UtcDateTime =
    of(date, hour, minute, second, millisecond).orThrow

  def parse(value: String): Either[GraphicsError, UtcDateTime] =
    val separator = value.indexOf('T')
    val time = if separator >= 0 then value.substring(separator + 1) else ""
    val validShape =
      separator >= 0 && time.length == 13 && time(2) == ':' && time(5) == ':' && time(8) == '.' &&
        time(12) == 'Z' && asciiDigits(time, 0, 2) && asciiDigits(time, 3, 5) &&
        asciiDigits(time, 6, 8) && asciiDigits(time, 9, 12)
    if !validShape then Left(GraphicsError.InvalidTemporalText(TemporalKind.DateTime.label, value))
    else
      val parsed =
        for
          date <- CalendarDate.parse(value.substring(0, separator))
          hour <- parsePart(time.substring(0, 2), value)
          minute <- parsePart(time.substring(3, 5), value)
          second <- parsePart(time.substring(6, 8), value)
          millisecond <- parsePart(time.substring(9, 12), value)
          result <- of(date, hour, minute, second, millisecond)
        yield result
      parsed

  def parseUnsafe(value: String): UtcDateTime =
    parse(value).orThrow

  private def parsePart(value: String, source: String): Either[GraphicsError, Int] =
    value.toIntOption.toRight(
      GraphicsError.InvalidTemporalText(TemporalKind.DateTime.label, source)
    )

  private def twoDigits(value: Int): String =
    if value < 10 then s"0$value" else value.toString

  private def threeDigits(value: Int): String =
    if value < 10 then s"00$value"
    else if value < 100 then s"0$value"
    else value.toString

  private def asciiDigits(value: String, from: Int, until: Int): Boolean =
    var index = from
    var valid = from < until
    while index < until && valid do
      val character = value(index)
      valid = character >= '0' && character <= '9'
      index += 1
    valid

/** Semantic kind of a temporal position scale. Date-time values are UTC instants with exact
  * millisecond precision on every supported platform.
  */
enum TemporalKind(val label: String):
  case Date extends TemporalKind("date")
  case DateTime extends TemporalKind("date-time")

/** Calendar or clock unit used to align temporal axis breaks. */
enum TemporalUnit(val label: String):
  case Millisecond extends TemporalUnit("millisecond")
  case Second extends TemporalUnit("second")
  case Minute extends TemporalUnit("minute")
  case Hour extends TemporalUnit("hour")
  case Day extends TemporalUnit("day")
  case Week extends TemporalUnit("week")
  case Month extends TemporalUnit("month")
  case Year extends TemporalUnit("year")

/** Labels for calendar-date axes. */
trait DateLabeler:
  def apply(values: Vector[CalendarDate]): Vector[String]

object DateLabeler:
  /** ISO-8601 proleptic-Gregorian dates. */
  val iso: DateLabeler =
    values => values.map(_.toString)

/** Labels for UTC instant axes. */
trait DateTimeLabeler:
  def apply(values: Vector[UtcDateTime]): Vector[String]

object DateTimeLabeler:
  /** ISO-8601 UTC labels with an explicit, fixed three-digit millisecond field. */
  val isoUtcMilliseconds: DateTimeLabeler =
    values => values.map(_.toString)

/** Inclusive calendar-date domain. */
final case class DateDomain private (lower: CalendarDate, upper: CalendarDate):
  private[intaglio] val encoded: Interval =
    Interval.unsafe(lower.toEpochDay.toDouble, upper.toEpochDay.toDouble)

object DateDomain:
  def apply(lower: CalendarDate, upper: CalendarDate): Either[GraphicsError, DateDomain] =
    if lower.compareTo(upper) <= 0 then Right(new DateDomain(lower, upper))
    else
      Left(
        GraphicsError.InvalidTemporalDomain(TemporalKind.Date.label, lower.toString, upper.toString)
      )

  def unsafe(lower: CalendarDate, upper: CalendarDate): DateDomain =
    apply(lower, upper).orThrow

  def train(values: IterableOnce[CalendarDate]): Either[GraphicsError, DateDomain] =
    val iterator = values.iterator
    if !iterator.hasNext then Left(GraphicsError.EmptyContinuousRange)
    else
      var lower = iterator.next()
      var upper = lower
      while iterator.hasNext do
        val value = iterator.next()
        if value.compareTo(lower) < 0 then lower = value
        if value.compareTo(upper) > 0 then upper = value
      Right(new DateDomain(lower, upper))

  private[intaglio] def fromEpochDays(
      values: IterableOnce[Double]
  ): Either[GraphicsError, DateDomain] =
    val dates = Vector.newBuilder[CalendarDate]
    val iterator = values.iterator
    var error: Option[GraphicsError] = None
    while iterator.hasNext && error.isEmpty do
      val value = iterator.next()
      TemporalKernel.integralLong(TemporalKind.Date, value).flatMap(CalendarDate.fromEpochDay) match
        case Right(date) => dates += date
        case Left(value) => error = Some(value)
    error.fold(train(dates.result()))(Left(_))

/** Inclusive UTC instant domain. Every accepted value is exactly representable as an epoch
  * millisecond in an IEEE-754 double, preserving byte-identical JVM and Scala.js scale arithmetic.
  */
final case class DateTimeDomain private (
    lower: UtcDateTime,
    upper: UtcDateTime,
    private[intaglio] val encoded: Interval
)

object DateTimeDomain:
  def apply(lower: UtcDateTime, upper: UtcDateTime): Either[GraphicsError, DateTimeDomain] =
    if lower.compareTo(upper) <= 0 then
      Right(
        new DateTimeDomain(
          lower,
          upper,
          Interval.unsafe(lower.epochMillis.toDouble, upper.epochMillis.toDouble)
        )
      )
    else
      Left(
        GraphicsError.InvalidTemporalDomain(
          TemporalKind.DateTime.label,
          lower.toString,
          upper.toString
        )
      )

  def unsafe(lower: UtcDateTime, upper: UtcDateTime): DateTimeDomain =
    apply(lower, upper).orThrow

  def train(values: IterableOnce[UtcDateTime]): Either[GraphicsError, DateTimeDomain] =
    val iterator = values.iterator
    if !iterator.hasNext then Left(GraphicsError.EmptyContinuousRange)
    else
      val valid = iterator.toVector
      val lower = valid.minBy(_.epochMillis)
      val upper = valid.maxBy(_.epochMillis)
      apply(lower, upper)

  private[intaglio] def fromEpochMillis(
      values: IterableOnce[Double]
  ): Either[GraphicsError, DateTimeDomain] =
    val instants = Vector.newBuilder[UtcDateTime]
    val iterator = values.iterator
    var error: Option[GraphicsError] = None
    while iterator.hasNext && error.isEmpty do
      val value = iterator.next()
      TemporalKernel.integralLong(TemporalKind.DateTime, value) match
        case Right(millis) =>
          UtcDateTime(millis) match
            case Right(instant) => instants += instant
            case Left(value)    => error = Some(value)
        case Left(value) => error = Some(value)
    error.fold(train(instants.result()))(Left(_))

/** Deterministic, calendar-aligned temporal break policy. */
sealed trait TemporalBreaks:
  private[intaglio] def date(
      domain: DateDomain
  ): Either[GraphicsError, TemporalBreakResult[CalendarDate]]
  private[intaglio] def dateTime(
      domain: DateTimeDomain
  ): Either[GraphicsError, TemporalBreakResult[UtcDateTime]]

object TemporalBreaks:
  val default: TemporalBreaks =
    automaticUnsafe()

  def automatic(targetCount: Int = 5): Either[GraphicsError, TemporalBreaks] =
    if targetCount < 1 || targetCount > Breaks.MaximumOutputSize then
      Left(GraphicsError.InvalidBreakCount(targetCount))
    else Right(Automatic(targetCount))

  def automaticUnsafe(targetCount: Int = 5): TemporalBreaks =
    automatic(targetCount).orThrow

  def every(step: Int, unit: TemporalUnit): Either[GraphicsError, TemporalBreaks] =
    if step < 1 then Left(GraphicsError.InvalidTemporalBreakStep(step))
    else Right(Every(step, unit))

  def everyUnsafe(step: Int, unit: TemporalUnit): TemporalBreaks =
    every(step, unit).orThrow

  private final case class Automatic(targetCount: Int) extends TemporalBreaks:
    override def date(
        domain: DateDomain
    ): Either[GraphicsError, TemporalBreakResult[CalendarDate]] =
      val selected = selectDate(domain.encoded.width, targetCount)
      generateDate(domain, selected.step, selected.unit)

    override def dateTime(
        domain: DateTimeDomain
    ): Either[GraphicsError, TemporalBreakResult[UtcDateTime]] =
      val selected = selectDateTime(domain.encoded.width, targetCount)
      generateDateTime(domain, selected.step, selected.unit)

  private final case class Every(step: Int, unit: TemporalUnit) extends TemporalBreaks:
    override def date(
        domain: DateDomain
    ): Either[GraphicsError, TemporalBreakResult[CalendarDate]] =
      generateDate(domain, step, unit)

    override def dateTime(
        domain: DateTimeDomain
    ): Either[GraphicsError, TemporalBreakResult[UtcDateTime]] =
      generateDateTime(domain, step, unit)

  private final case class Candidate(step: Int, unit: TemporalUnit, approximateMillis: Double)

  private val MillisPerSecond = 1000.0
  private val MillisPerMinute = 60.0 * MillisPerSecond
  private val MillisPerHour = 60.0 * MillisPerMinute
  private val MillisPerDay = 24.0 * MillisPerHour
  private val MillisPerWeek = 7.0 * MillisPerDay
  private val MillisPerMonth = 30.436875 * MillisPerDay
  private val MillisPerYear = 365.2425 * MillisPerDay

  private val dateCandidates: Vector[Candidate] =
    Vector(
      Candidate(1, TemporalUnit.Day, MillisPerDay),
      Candidate(2, TemporalUnit.Day, 2.0 * MillisPerDay),
      Candidate(1, TemporalUnit.Week, MillisPerWeek),
      Candidate(2, TemporalUnit.Week, 2.0 * MillisPerWeek),
      Candidate(1, TemporalUnit.Month, MillisPerMonth),
      Candidate(3, TemporalUnit.Month, 3.0 * MillisPerMonth),
      Candidate(6, TemporalUnit.Month, 6.0 * MillisPerMonth),
      Candidate(1, TemporalUnit.Year, MillisPerYear),
      Candidate(2, TemporalUnit.Year, 2.0 * MillisPerYear),
      Candidate(5, TemporalUnit.Year, 5.0 * MillisPerYear),
      Candidate(10, TemporalUnit.Year, 10.0 * MillisPerYear),
      Candidate(20, TemporalUnit.Year, 20.0 * MillisPerYear),
      Candidate(50, TemporalUnit.Year, 50.0 * MillisPerYear),
      Candidate(100, TemporalUnit.Year, 100.0 * MillisPerYear),
      Candidate(200, TemporalUnit.Year, 200.0 * MillisPerYear),
      Candidate(500, TemporalUnit.Year, 500.0 * MillisPerYear),
      Candidate(1000, TemporalUnit.Year, 1000.0 * MillisPerYear),
      Candidate(2000, TemporalUnit.Year, 2000.0 * MillisPerYear),
      Candidate(5000, TemporalUnit.Year, 5000.0 * MillisPerYear),
      Candidate(10000, TemporalUnit.Year, 10000.0 * MillisPerYear)
    )

  private val dateTimeCandidates: Vector[Candidate] =
    Vector(
      Candidate(1, TemporalUnit.Millisecond, 1.0),
      Candidate(2, TemporalUnit.Millisecond, 2.0),
      Candidate(5, TemporalUnit.Millisecond, 5.0),
      Candidate(10, TemporalUnit.Millisecond, 10.0),
      Candidate(20, TemporalUnit.Millisecond, 20.0),
      Candidate(50, TemporalUnit.Millisecond, 50.0),
      Candidate(100, TemporalUnit.Millisecond, 100.0),
      Candidate(200, TemporalUnit.Millisecond, 200.0),
      Candidate(500, TemporalUnit.Millisecond, 500.0),
      Candidate(1, TemporalUnit.Second, MillisPerSecond),
      Candidate(2, TemporalUnit.Second, 2.0 * MillisPerSecond),
      Candidate(5, TemporalUnit.Second, 5.0 * MillisPerSecond),
      Candidate(10, TemporalUnit.Second, 10.0 * MillisPerSecond),
      Candidate(15, TemporalUnit.Second, 15.0 * MillisPerSecond),
      Candidate(30, TemporalUnit.Second, 30.0 * MillisPerSecond),
      Candidate(1, TemporalUnit.Minute, MillisPerMinute),
      Candidate(2, TemporalUnit.Minute, 2.0 * MillisPerMinute),
      Candidate(5, TemporalUnit.Minute, 5.0 * MillisPerMinute),
      Candidate(10, TemporalUnit.Minute, 10.0 * MillisPerMinute),
      Candidate(15, TemporalUnit.Minute, 15.0 * MillisPerMinute),
      Candidate(30, TemporalUnit.Minute, 30.0 * MillisPerMinute),
      Candidate(1, TemporalUnit.Hour, MillisPerHour),
      Candidate(2, TemporalUnit.Hour, 2.0 * MillisPerHour),
      Candidate(3, TemporalUnit.Hour, 3.0 * MillisPerHour),
      Candidate(6, TemporalUnit.Hour, 6.0 * MillisPerHour),
      Candidate(12, TemporalUnit.Hour, 12.0 * MillisPerHour)
    ) ++ dateCandidates

  private def selectDate(spanDays: Double, targetCount: Int): Candidate =
    select(dateCandidates, spanDays * MillisPerDay, targetCount)

  private def selectDateTime(spanMillis: Double, targetCount: Int): Candidate =
    select(dateTimeCandidates, spanMillis, targetCount)

  private def select(
      candidates: Vector[Candidate],
      span: Double,
      targetCount: Int
  ): Candidate =
    val desired = span / targetCount.toDouble
    candidates.find(_.approximateMillis >= desired).getOrElse(candidates.last)

  private def generateDate(
      domain: DateDomain,
      step: Int,
      unit: TemporalUnit
  ): Either[GraphicsError, TemporalBreakResult[CalendarDate]] =
    unit match
      case TemporalUnit.Millisecond | TemporalUnit.Second | TemporalUnit.Minute |
          TemporalUnit.Hour =>
        Left(GraphicsError.UnsupportedTemporalBreakUnit(TemporalKind.Date.label, unit.label))
      case _ =>
        alignedDate(domain.lower, step, unit) match
          case Left(GraphicsError.TemporalValueOutOfRange(_, _, _)) =>
            Right(TemporalBreakResult(unit, Vector(domain.lower)))
          case Left(error)  => Left(error)
          case Right(first) =>
            collectDates(first, domain.upper, step, unit).map { values =>
              val nonEmpty = if values.nonEmpty then values else Vector(domain.lower)
              TemporalBreakResult(unit, nonEmpty)
            }

  private def generateDateTime(
      domain: DateTimeDomain,
      step: Int,
      unit: TemporalUnit
  ): Either[GraphicsError, TemporalBreakResult[UtcDateTime]] =
    alignedInstant(domain.lower, step, unit) match
      case Left(GraphicsError.TemporalValueOutOfRange(_, _, _)) =>
        Right(TemporalBreakResult(unit, Vector(domain.lower)))
      case Left(error)  => Left(error)
      case Right(first) =>
        collectInstants(first, domain.upper, step, unit).map { values =>
          val nonEmpty = if values.nonEmpty then values else Vector(domain.lower)
          TemporalBreakResult(unit, nonEmpty)
        }

  private def alignedDate(
      lower: CalendarDate,
      step: Int,
      unit: TemporalUnit
  ): Either[GraphicsError, CalendarDate] =
    unit match
      case TemporalUnit.Day =>
        val epochDay = ceilMultiple(lower.toEpochDay, step.toLong)
        CalendarDate.fromEpochDay(epochDay)
      case TemporalUnit.Week =>
        val width = step.toLong * 7L
        val mondayAnchor = -3L
        val epochDay = mondayAnchor + ceilMultiple(lower.toEpochDay - mondayAnchor, width)
        CalendarDate.fromEpochDay(epochDay)
      case TemporalUnit.Month =>
        alignedCalendarDate(lower, step, months = true)
      case TemporalUnit.Year =>
        alignedCalendarDate(lower, step, months = false)
      case _ =>
        Left(GraphicsError.UnsupportedTemporalBreakUnit(TemporalKind.Date.label, unit.label))

  private def alignedCalendarDate(
      lower: CalendarDate,
      step: Int,
      months: Boolean
  ): Either[GraphicsError, CalendarDate] =
    val aligned =
      if months then
        val monthIndex = lower.year.toLong * 12L + lower.month.toLong - 1L
        val firstIndex = ceilMultiple(monthIndex, step.toLong)
        val year = floorDiv(firstIndex, 12L)
        val month = firstIndex - year * 12L + 1L
        CalendarDate(year.toInt, month.toInt, 1)
      else
        val year = ceilMultiple(lower.year.toLong, step.toLong)
        CalendarDate(year.toInt, 1, 1)
    aligned.flatMap { candidate =>
      if candidate.compareTo(lower) >= 0 then Right(candidate)
      else plusDate(candidate, step, if months then TemporalUnit.Month else TemporalUnit.Year)
    }

  private def alignedInstant(
      lower: UtcDateTime,
      step: Int,
      unit: TemporalUnit
  ): Either[GraphicsError, UtcDateTime] =
    unit match
      case TemporalUnit.Month | TemporalUnit.Year =>
        alignedCalendarDate(
          lower.date,
          step,
          months = unit == TemporalUnit.Month
        ).flatMap { date =>
          UtcDateTime.of(date, 0, 0, 0, 0)
        }
      case _ =>
        val width = unitMillis(step, unit)
        val anchor = if unit == TemporalUnit.Week then -3L * 86400000L else 0L
        val aligned = anchor + ceilMultiple(lower.epochMillis - anchor, width)
        UtcDateTime(aligned)

  private def collectDates(
      first: CalendarDate,
      upper: CalendarDate,
      step: Int,
      unit: TemporalUnit
  ): Either[GraphicsError, Vector[CalendarDate]] =
    val out = Vector.newBuilder[CalendarDate]
    var current = first
    var count = 0
    var error: Option[GraphicsError] = None
    var done = false
    while !done && current.compareTo(upper) <= 0 && error.isEmpty do
      if count >= Breaks.MaximumOutputSize then
        error = Some(
          GraphicsError.BreakOutputLimitExceeded(
            "date-time",
            count + 1,
            Breaks.MaximumOutputSize
          )
        )
      else
        out += current
        count += 1
        if current.compareTo(upper) == 0 then done = true
        else
          plusDate(current, step, unit) match
            case Right(next) if next.compareTo(current) > 0 => current = next
            case Right(next)                                =>
              error = Some(
                GraphicsError.BreakGenerationDidNotProgress(
                  "date-time",
                  current.toEpochDay.toDouble,
                  next.toEpochDay.toDouble
                )
              )
            case Left(GraphicsError.TemporalValueOutOfRange(_, _, _)) => done = true
            case Left(value)                                          => error = Some(value)
    error.fold[Either[GraphicsError, Vector[CalendarDate]]](Right(out.result()))(Left(_))

  private def collectInstants(
      first: UtcDateTime,
      upper: UtcDateTime,
      step: Int,
      unit: TemporalUnit
  ): Either[GraphicsError, Vector[UtcDateTime]] =
    val out = Vector.newBuilder[UtcDateTime]
    var current = first
    var count = 0
    var error: Option[GraphicsError] = None
    var done = false
    while !done && current.compareTo(upper) <= 0 && error.isEmpty do
      if count >= Breaks.MaximumOutputSize then
        error = Some(
          GraphicsError.BreakOutputLimitExceeded(
            "date-time",
            count + 1,
            Breaks.MaximumOutputSize
          )
        )
      else
        out += current
        count += 1
        if current.compareTo(upper) == 0 then done = true
        else
          plusInstant(current, step, unit) match
            case Right(next) if next.compareTo(current) > 0 => current = next
            case Right(next)                                =>
              error = Some(
                GraphicsError.BreakGenerationDidNotProgress(
                  "date-time",
                  current.epochMillis.toDouble,
                  next.epochMillis.toDouble
                )
              )
            case Left(GraphicsError.TemporalValueOutOfRange(_, _, _)) => done = true
            case Left(value)                                          => error = Some(value)
    error.fold[Either[GraphicsError, Vector[UtcDateTime]]](Right(out.result()))(Left(_))

  private def plusDate(
      value: CalendarDate,
      step: Int,
      unit: TemporalUnit
  ): Either[GraphicsError, CalendarDate] =
    unit match
      case TemporalUnit.Day   => value.addDays(step.toLong)
      case TemporalUnit.Week  => value.addWeeks(step.toLong)
      case TemporalUnit.Month => value.addMonths(step.toLong)
      case TemporalUnit.Year  => value.addYears(step.toLong)
      case _                  => Right(value)

  private def plusInstant(
      value: UtcDateTime,
      step: Int,
      unit: TemporalUnit
  ): Either[GraphicsError, UtcDateTime] =
    unit match
      case TemporalUnit.Month | TemporalUnit.Year =>
        val nextDate =
          if unit == TemporalUnit.Month then value.date.addMonths(step.toLong)
          else value.date.addYears(step.toLong)
        nextDate.flatMap(
          UtcDateTime.of(_, value.hour, value.minute, value.second, value.millisecond)
        )
      case _ => value.addMillis(unitMillis(step, unit))

  private def unitMillis(step: Int, unit: TemporalUnit): Long =
    val base =
      unit match
        case TemporalUnit.Millisecond               => 1L
        case TemporalUnit.Second                    => 1000L
        case TemporalUnit.Minute                    => 60000L
        case TemporalUnit.Hour                      => 3600000L
        case TemporalUnit.Day                       => 86400000L
        case TemporalUnit.Week                      => 604800000L
        case TemporalUnit.Month | TemporalUnit.Year =>
          throw new IllegalArgumentException("calendar units have no fixed millisecond width")
    base * step.toLong

  private def ceilMultiple(value: Long, positiveStep: Long): Long =
    val quotient = value / positiveStep
    val remainder = value % positiveStep
    val ceiling = if remainder != 0L && value > 0L then quotient + 1L else quotient
    ceiling * positiveStep

  private def floorDiv(value: Long, positiveDivisor: Long): Long =
    val quotient = value / positiveDivisor
    val remainder = value % positiveDivisor
    if remainder != 0L && value < 0L then quotient - 1L else quotient

private[intaglio] final case class TemporalBreakResult[A](
    unit: TemporalUnit,
    values: Vector[A]
)

private[intaglio] trait TemporalAxisScale:
  private[intaglio] def axisTicksResult: Either[GraphicsError, Vector[AxisTick]]

/** Trained calendar-date position scale. */
final case class DateScale private (
    name: GraphicsName,
    domain: DateDomain,
    breakPolicy: TemporalBreaks,
    labeler: DateLabeler,
    oob: OobPolicy,
    training: ScaleTraining
) extends Scale[CalendarDate, Double],
      TemporalAxisScale:
  override def descriptor: ScaleDescriptor =
    ScaleDescriptor(
      name,
      ScaleKind.Temporal,
      ScaleDomain.Temporal(
        TemporalKind.Date,
        domain.encoded,
        domain.lower.toString,
        domain.upper.toString
      ),
      training
    )

  private[intaglio] override def observation(value: CalendarDate): Option[ScaleObservation] =
    Some(ScaleObservation.Continuous(value.toEpochDay.toDouble))

  private[intaglio] override def trainPlotWide(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[CalendarDate, Double]] =
    training match
      case ScaleTraining.Fixed    => Right(this)
      case ScaleTraining.PlotWide =>
        TemporalKernel.observationValues(observations).flatMap { values =>
          DateScale.trainEpochDays(
            name,
            Iterator(domain.encoded.lower, domain.encoded.upper) ++ values,
            breakPolicy,
            labeler,
            oob,
            training
          )
        }

  private[intaglio] override def trainFacet(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[CalendarDate, Double]] =
    training match
      case ScaleTraining.Fixed    => Right(this)
      case ScaleTraining.PlotWide =>
        TemporalKernel
          .observationValues(observations)
          .flatMap(
            DateScale.trainEpochDays(name, _, breakPolicy, labeler, oob, training)
          )

  override def mapValue(value: CalendarDate): Option[Double] =
    mapValueResult(value).toOption

  override def mapValueResult(value: CalendarDate): Either[ScaleMapFailure, Double] =
    oob(domain.encoded.rescale(value.toEpochDay.toDouble)).toRight(
      ScaleMapFailure.OutOfDomain(name.value, value.toString)
    )

  def inverse(position: Double): Either[GraphicsError, CalendarDate] =
    if !position.isFinite || position < 0.0 || position > 1.0 then
      Left(GraphicsError.InvalidTemporalCoordinate(name.value, position))
    else
      val encoded = domain.encoded.lower + domain.encoded.width * position
      CalendarDate.fromEpochDay(math.round(encoded))

  def roundTrips(value: CalendarDate): Boolean =
    mapValue(value).flatMap(position => inverse(position).toOption).contains(value)

  def breaksResult: Either[GraphicsError, Vector[CalendarDate]] =
    breakPolicy.date(domain).map(_.values)

  def breaks: Vector[CalendarDate] =
    breaksResult.orThrow

  def labelsResult: Either[GraphicsError, Vector[String]] =
    breaksResult.flatMap { values =>
      val labels = labeler(values)
      if labels.length == values.length then Right(labels)
      else Left(GraphicsError.AxisLabelCountMismatch(values.length, labels.length))
    }

  def labels: Vector[String] =
    labelsResult.orThrow

  override private[intaglio] def axisTicksResult: Either[GraphicsError, Vector[AxisTick]] =
    for
      values <- breaksResult
      labels <- labelsResult
      ticks <- TemporalKernel.axisTicks(values, labels, mapValue)
    yield ticks

object DateScale:
  def train(
      name: String,
      values: IterableOnce[CalendarDate],
      breaks: TemporalBreaks = TemporalBreaks.default,
      labeler: DateLabeler = DateLabeler.iso,
      oob: OobPolicy = OobPolicy.Censor,
      training: ScaleTraining = ScaleTraining.PlotWide
  ): Either[GraphicsError, DateScale] =
    for
      scaleName <- GraphicsName(name, "date scale")
      domain <- DateDomain.train(values)
    yield new DateScale(scaleName, domain, breaks, labeler, oob, training)

  def fixed(
      name: String,
      limits: IterableOnce[CalendarDate],
      breaks: TemporalBreaks = TemporalBreaks.default,
      labeler: DateLabeler = DateLabeler.iso,
      oob: OobPolicy = OobPolicy.Censor
  ): Either[GraphicsError, DateScale] =
    train(name, limits, breaks, labeler, oob, ScaleTraining.Fixed)

  private def trainEpochDays(
      name: GraphicsName,
      values: IterableOnce[Double],
      breaks: TemporalBreaks,
      labeler: DateLabeler,
      oob: OobPolicy,
      training: ScaleTraining
  ): Either[GraphicsError, DateScale] =
    DateDomain.fromEpochDays(values).map(new DateScale(name, _, breaks, labeler, oob, training))

  private[intaglio] def fromDomain(
      name: GraphicsName,
      domain: DateDomain,
      breaks: TemporalBreaks,
      labeler: DateLabeler,
      oob: OobPolicy,
      training: ScaleTraining
  ): DateScale =
    new DateScale(name, domain, breaks, labeler, oob, training)

/** Trained UTC instant position scale with exact millisecond resolution. */
final case class DateTimeScale private (
    name: GraphicsName,
    domain: DateTimeDomain,
    breakPolicy: TemporalBreaks,
    labeler: DateTimeLabeler,
    oob: OobPolicy,
    training: ScaleTraining
) extends Scale[UtcDateTime, Double],
      TemporalAxisScale:
  override def descriptor: ScaleDescriptor =
    ScaleDescriptor(
      name,
      ScaleKind.Temporal,
      ScaleDomain.Temporal(
        TemporalKind.DateTime,
        domain.encoded,
        DateTimeLabeler.isoUtcMilliseconds(Vector(domain.lower)).head,
        DateTimeLabeler.isoUtcMilliseconds(Vector(domain.upper)).head
      ),
      training
    )

  private[intaglio] override def observation(value: UtcDateTime): Option[ScaleObservation] =
    Some(ScaleObservation.Continuous(value.epochMillis.toDouble))

  private[intaglio] override def trainPlotWide(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[UtcDateTime, Double]] =
    training match
      case ScaleTraining.Fixed    => Right(this)
      case ScaleTraining.PlotWide =>
        TemporalKernel.observationValues(observations).flatMap { values =>
          DateTimeScale.trainEpochMillis(
            name,
            Iterator(domain.encoded.lower, domain.encoded.upper) ++ values,
            breakPolicy,
            labeler,
            oob,
            training
          )
        }

  private[intaglio] override def trainFacet(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Scale[UtcDateTime, Double]] =
    training match
      case ScaleTraining.Fixed    => Right(this)
      case ScaleTraining.PlotWide =>
        TemporalKernel
          .observationValues(observations)
          .flatMap(
            DateTimeScale.trainEpochMillis(name, _, breakPolicy, labeler, oob, training)
          )

  override def mapValue(value: UtcDateTime): Option[Double] =
    mapValueResult(value).toOption

  override def mapValueResult(value: UtcDateTime): Either[ScaleMapFailure, Double] =
    oob(domain.encoded.rescale(value.epochMillis.toDouble)).toRight(
      ScaleMapFailure.OutOfDomain(name.value, value.toString)
    )

  def inverse(position: Double): Either[GraphicsError, UtcDateTime] =
    if !position.isFinite || position < 0.0 || position > 1.0 then
      Left(GraphicsError.InvalidTemporalCoordinate(name.value, position))
    else
      val encoded = domain.encoded.lower + domain.encoded.width * position
      UtcDateTime(math.round(encoded))

  def roundTrips(value: UtcDateTime): Boolean =
    mapValue(value).flatMap(position => inverse(position).toOption).contains(value)

  def breaksResult: Either[GraphicsError, Vector[UtcDateTime]] =
    breakPolicy.dateTime(domain).map(_.values)

  def breaks: Vector[UtcDateTime] =
    breaksResult.orThrow

  def labelsResult: Either[GraphicsError, Vector[String]] =
    breaksResult.flatMap { values =>
      val labels = labeler(values)
      if labels.length == values.length then Right(labels)
      else Left(GraphicsError.AxisLabelCountMismatch(values.length, labels.length))
    }

  def labels: Vector[String] =
    labelsResult.orThrow

  override private[intaglio] def axisTicksResult: Either[GraphicsError, Vector[AxisTick]] =
    for
      values <- breaksResult
      labels <- labelsResult
      ticks <- TemporalKernel.axisTicks(values, labels, mapValue)
    yield ticks

object DateTimeScale:
  def train(
      name: String,
      values: IterableOnce[UtcDateTime],
      breaks: TemporalBreaks = TemporalBreaks.default,
      labeler: DateTimeLabeler = DateTimeLabeler.isoUtcMilliseconds,
      oob: OobPolicy = OobPolicy.Censor,
      training: ScaleTraining = ScaleTraining.PlotWide
  ): Either[GraphicsError, DateTimeScale] =
    for
      scaleName <- GraphicsName(name, "date-time scale")
      domain <- DateTimeDomain.train(values)
    yield new DateTimeScale(scaleName, domain, breaks, labeler, oob, training)

  def fixed(
      name: String,
      limits: IterableOnce[UtcDateTime],
      breaks: TemporalBreaks = TemporalBreaks.default,
      labeler: DateTimeLabeler = DateTimeLabeler.isoUtcMilliseconds,
      oob: OobPolicy = OobPolicy.Censor
  ): Either[GraphicsError, DateTimeScale] =
    train(name, limits, breaks, labeler, oob, ScaleTraining.Fixed)

  private def trainEpochMillis(
      name: GraphicsName,
      values: IterableOnce[Double],
      breaks: TemporalBreaks,
      labeler: DateTimeLabeler,
      oob: OobPolicy,
      training: ScaleTraining
  ): Either[GraphicsError, DateTimeScale] =
    DateTimeDomain
      .fromEpochMillis(values)
      .map(new DateTimeScale(name, _, breaks, labeler, oob, training))

  private[intaglio] def fromDomain(
      name: GraphicsName,
      domain: DateTimeDomain,
      breaks: TemporalBreaks,
      labeler: DateTimeLabeler,
      oob: OobPolicy,
      training: ScaleTraining
  ): DateTimeScale =
    new DateTimeScale(name, domain, breaks, labeler, oob, training)

/** Row-free declaration for a calendar-date position scale. */
final class DateScaleSpec private (
    val name: GraphicsName,
    val breakPolicy: TemporalBreaks,
    val labeler: DateLabeler,
    val oob: OobPolicy
) extends ScaleSpec[CalendarDate, Double]:
  override val kind: ScaleKind = ScaleKind.Temporal

  private[intaglio] override def observation(value: CalendarDate): Option[ScaleObservation] =
    Some(ScaleObservation.Continuous(value.toEpochDay.toDouble))

  private[intaglio] override def trainSpec(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[CalendarDate, Double]] =
    TemporalKernel.observationValues(observations).flatMap { values =>
      DateDomain.fromEpochDays(values).map { domain =>
        DateScale.fromDomain(name, domain, breakPolicy, labeler, oob, ScaleTraining.PlotWide)
      }
    }

object DateScaleSpec:
  def apply(
      name: String,
      breaks: TemporalBreaks = TemporalBreaks.default,
      labeler: DateLabeler = DateLabeler.iso,
      oob: OobPolicy = OobPolicy.Censor
  ): Either[GraphicsError, DateScaleSpec] =
    GraphicsName(name, "date scale spec").map(new DateScaleSpec(_, breaks, labeler, oob))

/** Row-free declaration for a UTC instant position scale. */
final class DateTimeScaleSpec private (
    val name: GraphicsName,
    val breakPolicy: TemporalBreaks,
    val labeler: DateTimeLabeler,
    val oob: OobPolicy
) extends ScaleSpec[UtcDateTime, Double]:
  override val kind: ScaleKind = ScaleKind.Temporal

  private[intaglio] override def observation(value: UtcDateTime): Option[ScaleObservation] =
    Some(ScaleObservation.Continuous(value.epochMillis.toDouble))

  private[intaglio] override def trainSpec(
      observations: Vector[ScaleObservation],
      theme: Theme,
      facetLocal: Boolean
  ): Either[GraphicsError, Scale[UtcDateTime, Double]] =
    TemporalKernel.observationValues(observations).flatMap { values =>
      DateTimeDomain.fromEpochMillis(values).map { domain =>
        DateTimeScale.fromDomain(
          name,
          domain,
          breakPolicy,
          labeler,
          oob,
          ScaleTraining.PlotWide
        )
      }
    }

object DateTimeScaleSpec:
  def apply(
      name: String,
      breaks: TemporalBreaks = TemporalBreaks.default,
      labeler: DateTimeLabeler = DateTimeLabeler.isoUtcMilliseconds,
      oob: OobPolicy = OobPolicy.Censor
  ): Either[GraphicsError, DateTimeScaleSpec] =
    GraphicsName(name, "date-time scale spec").map(
      new DateTimeScaleSpec(_, breaks, labeler, oob)
    )

private[intaglio] object TemporalKernel:
  private val MaximumExactInteger = 9007199254740992.0

  def integralLong(kind: TemporalKind, value: Double): Either[GraphicsError, Long] =
    if value.isFinite && math.abs(value) <= MaximumExactInteger && math.rint(value) == value then
      Right(value.toLong)
    else
      Left(
        GraphicsError.TemporalValueOutOfRange(
          kind.label,
          value.toString,
          "encoded value must be an exact finite integer"
        )
      )

  def observationValues(
      observations: IterableOnce[ScaleObservation]
  ): Either[GraphicsError, Vector[Double]] =
    Right(
      observations.iterator.collect { case ScaleObservation.Continuous(value) => value }.toVector
    )

  def axisTicks[A](
      values: Vector[A],
      labels: Vector[String],
      map: A => Option[Double]
  ): Either[GraphicsError, Vector[AxisTick]] =
    val out = Vector.newBuilder[AxisTick]
    var index = 0
    var result: Either[GraphicsError, Unit] = Right(())
    while index < values.length && result.isRight do
      map(values(index)) match
        case Some(position) =>
          result = AxisTick(position, labels(index)).map { tick =>
            out += tick
            ()
          }
        case None =>
          result = Left(GraphicsError.InvalidTemporalCoordinate("temporal break", Double.NaN))
      index += 1
    result.map(_ => out.result())
