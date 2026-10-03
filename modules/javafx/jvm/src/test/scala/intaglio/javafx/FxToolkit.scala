package intaglio.javafx

import _root_.javafx.application.Platform
import java.util.concurrent.{CountDownLatch, ExecutionException, FutureTask, TimeUnit}

/** One JavaFX toolkit for every suite in the forked test JVM. The toolkit cannot be restarted once
  * it exits, so suites start it through here and never call `Platform.exit`; the JVM ends it.
  */
object FxToolkit:
  @volatile private var started = false

  def start(): Unit = synchronized {
    if !started then
      val latch = new CountDownLatch(1)
      try Platform.startup(() => { Platform.setImplicitExit(false); latch.countDown() })
      catch case _: IllegalStateException => latch.countDown()
      assert(latch.await(30, TimeUnit.SECONDS), "headless FX startup")
      started = true
  }

  /** Run `body` on the FX application thread and wait for its result. */
  def fx[A](body: => A): A =
    val task = new FutureTask[A](() => body)
    Platform.runLater(task)
    try task.get(60, TimeUnit.SECONDS)
    catch case e: ExecutionException => throw e.getCause
