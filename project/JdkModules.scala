import java.net.URI
import java.nio.file.{FileSystemNotFoundException, FileSystems, Path}

/** JDK modules handed to tastyquery beyond the `java.base` that sbt-tasty-mima supplies on its own.
  *
  * Without this, every signature mentioning an AWT type resolves to nothing and TASTy-MiMa reports
  * `MemberNotFoundException: Member awt not found in PackageRef(java)` as an internal error rather
  * than comparing the member. Nine members of the Java2D renderer's public surface sat behind
  * filters for that reason, which read like a tool quirk and actually meant they went unchecked.
  *
  * These are jrt paths — `modules/java.base` is what the plugin passes — so an entry has to come
  * from the jrt filesystem rather than from a relative file on disk.
  */
object JdkModules {

  /** The module that holds `java.awt`. */
  lazy val desktop: Path = module("java.desktop")

  private def module(name: String): Path = {
    val jrt =
      try FileSystems.getFileSystem(URI.create("jrt:/"))
      catch {
        case _: FileSystemNotFoundException =>
          FileSystems.newFileSystem(URI.create("jrt:/"), new java.util.HashMap[String, String]())
      }
    jrt.getPath("modules", name)
  }
}
