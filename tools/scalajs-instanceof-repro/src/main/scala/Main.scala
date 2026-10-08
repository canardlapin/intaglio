// Reduced reproduction for intaglio bd-01M41R5BTCMQ5R0NPGAZ0S504K, independent of intaglio.
// Valid Scala 2.13 and Scala 3. Run it with ./run.sh; see UPSTREAM-REPORT.md.
sealed trait Shape
final case class Circle(r: Int) extends Shape
final case class Square(side: Int) extends Shape // never instantiated anywhere in the program

final class Row(val shape: Shape)

object Main {
  private var calls = 0
  private def nextRow(): Row = {
    calls += 1
    new Row(Circle(calls))
  }

  private def report(name: String)(body: => Boolean): Unit = {
    calls = 0
    val result = body
    println(s"$name: result=$result nextRow() calls=$calls (expected 1)")
  }

  def main(args: Array[String]): Unit = {
    report("instance test of a call result")((nextRow(): Any).isInstanceOf[Square])
    report("instance test of a field of a call result")(nextRow().shape.isInstanceOf[Square])
    report("control: a class that has instances")(nextRow().shape.isInstanceOf[Circle])

    // The shape that hung intaglio: with the optimizer, `exists` is inlined, the predicate's
    // argument becomes `it.next().shape`, and the dropped `next()` leaves the loop polling
    // `it.hasNext()` forever.
    val rows = Vector(new Row(Circle(1)), new Row(Circle(2)))
    val it = rows.iterator
    var found = false
    while (!found && it.hasNext) found = it.next().shape.isInstanceOf[Square]
    println(s"iterator loop terminated: found=$found")
  }
}
