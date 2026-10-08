# Draft upstream report (not filed)

Ready to file at https://github.com/scala-js/scala-js/issues. Nothing has been filed. A search of
the issue tracker on 2026-10-08 found no existing report.

---

**Title:** Emitter drops the side effects of the operand of `isInstanceOf[C]` when `C` has no instances

**Scala.js version:** 1.22.0. Also reproduced with 1.21.0, 1.20.1 and 1.19.0.
**Scala versions:** 3.3.8 and 2.13.18. The behaviour is the same with both.
**Configurations:** fullLinkJS, fastLinkJS, and fastLinkJS with `withOptimizer(false)`.

## Summary

If no instance of class `C` exists in the linked program, `e.isInstanceOf[C]` compiles to `false`,
and `e` is not evaluated. Any side effects of `e` are lost, including method calls and possible
NPEs. When `e` contains an iterator's `next()`, a loop that should end runs forever.

## Reproduction

```scala
sealed trait Shape
final case class Circle(r: Int) extends Shape
final case class Square(side: Int) extends Shape // never instantiated

final class Row(val shape: Shape)

object Main {
  private var calls = 0
  private def nextRow(): Row = { calls += 1; new Row(Circle(calls)) }

  private def report(name: String)(body: => Boolean): Unit = {
    calls = 0
    val result = body
    println(s"$name: result=$result nextRow() calls=$calls (expected 1)")
  }

  def main(args: Array[String]): Unit = {
    report("instance test of a call result")((nextRow(): Any).isInstanceOf[Square])
    report("instance test of a field of a call result")(nextRow().shape.isInstanceOf[Square])
    report("control: a class that has instances")(nextRow().shape.isInstanceOf[Circle])

    val rows = Vector(new Row(Circle(1)), new Row(Circle(2)))
    val it = rows.iterator
    var found = false
    while (!found && it.hasNext) found = it.next().shape.isInstanceOf[Square]
    println(s"iterator loop terminated: found=$found")
  }
}
```

The sbt project is `build.sbt` with `enablePlugins(ScalaJSPlugin)`, `scalaJSUseMainModuleInitializer := true`
and `addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.22.0")`. In every configuration listed
above, the output is:

```text
instance test of a call result: result=false nextRow() calls=0 (expected 1)
instance test of a field of a call result: result=false nextRow() calls=0 (expected 1)
control: a class that has instances: result=true nextRow() calls=1 (expected 1)
<hangs: the iterator loop never terminates>
```

The JVM prints `calls=1` three times, then `iterator loop terminated: found=false`.

With the optimizer disabled, the emitted JavaScript (Scala 2.13.18, fastLinkJS) is:

```js
var found = false;
while (((!found) && $n(it).hasNext__Z())) {
  found = false;              // `$n(it).next__O().shape__LShape()` is gone
}
```

and `report(..., () => false)` for the first two cases.

## Cause

`SJSGen.genIsInstanceOfClass` (`linker/shared/.../backend/emitter/SJSGen.scala`) returns
`BooleanLiteral(false)` when `!globalKnowledge.hasInstances(className)`, and discards the
already-transformed `expr`:

```scala
if (!globalKnowledge.hasInstances(className)) {
  /* We need to constant-fold the instance test, to avoid emitting
   * `x instanceof $c_TheClass`, because `$c_TheClass` won't be
   * declared at all. Otherwise, we'd get a `ReferenceError`.
   */
  BooleanLiteral(false)
} else { ... }
```

`FunctionEmitter` treats `IsInstanceOf(expr, _)` as an expression whenever `expr` is one. A
side-effecting `Apply` therefore reaches this method unchanged and is dropped. The optimizer's own
folding for `TypeTestResult.NotAnInstance` keeps the operand
(`Block(finishTransformStat(texpr), BooleanLiteral(false))`), so this path is in the emitter only.

The optimizer exposes the bug in real code. Once it inlines `IterableOnceOps.exists`, the operand is
`it.next().field`. In fullLinkJS the element cast is unchecked, so nothing else binds `next()` to a
local first. In the downstream project, `rows.exists(_.statRow.isInstanceOf[StatRow.Ecdf[?]])` hung
every production bundle that had no ECDF layer.

## Suggested fix

Keep the operand's evaluation, for example by emitting `(expr, false)`, or by unnesting a
non-pure operand before folding, as the optimizer already does.

## Workaround

Binding the operand to a local does not help, because the optimizer inlines a single-use local
back into the test. The downstream project iterates by index instead, so the loop advances outside
the instance test.
