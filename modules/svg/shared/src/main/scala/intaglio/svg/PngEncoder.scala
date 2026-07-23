package intaglio.svg

import intaglio.*

/** Portable deterministic PNG encoder for 8-bit RGBA rasters. The DEFLATE
  * stream uses uncompressed stored blocks: output is larger than a tuned PNG,
  * but byte-identical on JVM and Scala.js and requires no platform codec.
  */
private[svg] object PngEncoder:
  private val Signature =
    Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

  private val Alphabet =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  def dataUri(image: RasterImage): String =
    "data:image/png;base64," + base64(encode(image))

  private def encode(image: RasterImage): Array[Byte] =
    val raw = scanlines(image)
    val compressed = storedZlib(raw)
    val output = new Array[Byte](57 + compressed.length)
    var offset = 0
    offset = copy(Signature, output, offset)
    val header = new Array[Byte](13)
    writeInt(header, 0, image.width)
    writeInt(header, 4, image.height)
    header(8) = 8
    header(9) = 6
    header(10) = 0
    header(11) = 0
    header(12) = 0
    offset = writeChunk(output, offset, "IHDR", header)
    offset = writeChunk(output, offset, "IDAT", compressed)
    writeChunk(output, offset, "IEND", Array.emptyByteArray)
    output

  private def scanlines(image: RasterImage): Array[Byte] =
    val stride = image.width * 4 + 1
    val output = new Array[Byte](stride * image.height)
    var y = 0
    while y < image.height do
      var offset = y * stride
      output(offset) = 0
      offset += 1
      var x = 0
      while x < image.width do
        val pixel = image.pixelUnsafe(x, y)
        output(offset) = pixel.red.toByte
        output(offset + 1) = pixel.green.toByte
        output(offset + 2) = pixel.blue.toByte
        output(offset + 3) = pixel.alpha.toByte
        offset += 4
        x += 1
      y += 1
    output

  private def storedZlib(raw: Array[Byte]): Array[Byte] =
    val blocks = (raw.length + 65534) / 65535
    val output = new Array[Byte](2 + raw.length + blocks * 5 + 4)
    output(0) = 0x78
    output(1) = 0x01
    var source = 0
    var target = 2
    while source < raw.length do
      val length = math.min(65535, raw.length - source)
      val last = source + length == raw.length
      output(target) = (if last then 1 else 0).toByte
      output(target + 1) = (length & 0xff).toByte
      output(target + 2) = ((length >>> 8) & 0xff).toByte
      val inverse = (~length) & 0xffff
      output(target + 3) = (inverse & 0xff).toByte
      output(target + 4) = ((inverse >>> 8) & 0xff).toByte
      target += 5
      var idx = 0
      while idx < length do
        output(target + idx) = raw(source + idx)
        idx += 1
      source += length
      target += length
    writeInt(output, target, adler32(raw))
    output

  private def writeChunk(output: Array[Byte], offset: Int, kind: String, data: Array[Byte]): Int =
    writeInt(output, offset, data.length)
    var idx = 0
    while idx < 4 do
      output(offset + 4 + idx) = kind.charAt(idx).toByte
      idx += 1
    val dataOffset = offset + 8
    copy(data, output, dataOffset)
    writeInt(output, dataOffset + data.length, crc32(kind, data))
    dataOffset + data.length + 4

  private def copy(source: Array[Byte], target: Array[Byte], offset: Int): Int =
    var idx = 0
    while idx < source.length do
      target(offset + idx) = source(idx)
      idx += 1
    offset + source.length

  private def writeInt(output: Array[Byte], offset: Int, value: Int): Unit =
    output(offset) = ((value >>> 24) & 0xff).toByte
    output(offset + 1) = ((value >>> 16) & 0xff).toByte
    output(offset + 2) = ((value >>> 8) & 0xff).toByte
    output(offset + 3) = (value & 0xff).toByte

  private def adler32(bytes: Array[Byte]): Int =
    val mod = 65521
    var a = 1
    var b = 0
    var idx = 0
    while idx < bytes.length do
      a = (a + (bytes(idx) & 0xff)) % mod
      b = (b + a) % mod
      idx += 1
    (b << 16) | a

  private def crc32(kind: String, data: Array[Byte]): Int =
    var crc = 0xffffffff
    var idx = 0
    while idx < 4 do
      crc = crcByte(crc, kind.charAt(idx).toInt)
      idx += 1
    idx = 0
    while idx < data.length do
      crc = crcByte(crc, data(idx) & 0xff)
      idx += 1
    ~crc

  private def crcByte(current: Int, value: Int): Int =
    var crc = current ^ value
    var bit = 0
    while bit < 8 do
      crc = if (crc & 1) != 0 then (crc >>> 1) ^ 0xedb88320 else crc >>> 1
      bit += 1
    crc

  private def base64(bytes: Array[Byte]): String =
    val output = new StringBuilder(((bytes.length + 2) / 3) * 4)
    var idx = 0
    while idx < bytes.length do
      val first = bytes(idx) & 0xff
      val hasSecond = idx + 1 < bytes.length
      val hasThird = idx + 2 < bytes.length
      val second = if hasSecond then bytes(idx + 1) & 0xff else 0
      val third = if hasThird then bytes(idx + 2) & 0xff else 0
      val bits = (first << 16) | (second << 8) | third
      output.append(Alphabet.charAt((bits >>> 18) & 0x3f))
      output.append(Alphabet.charAt((bits >>> 12) & 0x3f))
      output.append(if hasSecond then Alphabet.charAt((bits >>> 6) & 0x3f) else '=')
      output.append(if hasThird then Alphabet.charAt(bits & 0x3f) else '=')
      idx += 3
    output.result()
