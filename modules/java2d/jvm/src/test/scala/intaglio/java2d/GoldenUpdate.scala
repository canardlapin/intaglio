package intaglio.java2d

import java.nio.file.Files
import java.security.MessageDigest
import javax.imageio.ImageIO

/** Deliberately gated golden updater. Tests never rewrite their own oracle. */
object GoldenUpdate:
  def main(args: Array[String]): Unit =
    if args.toVector != Vector("--accept") then
      throw new IllegalArgumentException(
        "golden updates require an explicit --accept after reviewing the fixture and thresholds"
      )
    val path = GoldenFixture.repositoryPath
    Files.createDirectories(path.getParent)
    val written = ImageIO.write(GoldenFixture.render(), "png", path.toFile)
    if !written then throw new IllegalStateException("no PNG ImageIO writer is available")
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
    val sha256 = digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
    println(s"updated $path sha256=$sha256")
