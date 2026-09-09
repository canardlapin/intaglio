package intaglio

/** Exhaustive court for the claim that `Oklab.toRgba(Oklab.fromRgba(color))` returns `color` for
  * every one of the 16 777 216 sRGB colours.
  *
  * It is compiled with the test sources so it cannot drift from the API, and it is deliberately not
  * a test: a full sweep costs about a minute and `ColorSuite` already walks a strided grid on every
  * run. Run it on demand, and record the result:
  *
  * {{{
  * sbt "coreJVM/Test/runMain intaglio.oklabRoundTripCourt"
  * }}}
  *
  * `evidence/diverging-palette/README.md` holds the receipt from the last run.
  */
@main def oklabRoundTripCourt(): Unit =
  var changed = 0L
  var worstChannelError = 0
  var firstFailure = ""

  var red = 0
  while red < 256 do
    var green = 0
    while green < 256 do
      var blue = 0
      while blue < 256 do
        val color = Rgba.unsafe(red, green, blue)
        val roundTripped = Oklab.toRgba(Oklab.fromRgba(color))
        if roundTripped != color then
          changed += 1
          val error = math
            .max(
              math.abs(roundTripped.red - color.red),
              math.max(
                math.abs(roundTripped.green - color.green),
                math.abs(roundTripped.blue - color.blue)
              )
            )
          if error > worstChannelError then worstChannelError = error
          if firstFailure.isEmpty then firstFailure = s"$color -> $roundTripped"
        blue += 1
      green += 1
    red += 1

  println(s"sampled colors:        ${256 * 256 * 256}")
  println(s"round trips that moved: $changed")
  println(s"worst channel error:    $worstChannelError")
  if firstFailure.nonEmpty then println(s"first failure:          $firstFailure")
