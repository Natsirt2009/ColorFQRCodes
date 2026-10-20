# ColorFQRCodes

ColorFQRCodes is a Kotlin/JVM library and desktop app for custom multicolor matrix codes. It supports 1–8 bits per module (2–256 colors), UTF-8 payloads up to 65,535 bytes, CRC-32 validation, and Reed–Solomon error correction. The encoded format matches `anybpp.py`'s palette, finder layout, framing, and ECC scheme.

## Requirements

- JDK 17 or later
- Gradle (the included wrapper is recommended)

The image decoder uses OpenCV. Its Java and native runtime dependency is declared transitively by the library.

## Use As A Gradle Dependency

### Publish to your local Maven repository

From this repository, run:

```powershell
.\gradlew.bat publishToMavenLocal
```

This compiles and installs `net.natsirt.colorfqrcodes:colorfqrcodes:1.0.0` in your local Maven repository (normally `%USERPROFILE%\.m2\repository`). Run it again after updating the library.

### Add it to another Kotlin/JVM project

In the consumer project's `build.gradle.kts`, add `mavenLocal()` before Maven Central and declare the dependency:

```kotlin
repositories {
	mavenLocal()
	mavenCentral()
}

dependencies {
	implementation("net.natsirt.colorfqrcodes:colorfqrcodes:1.0.0")
}
```

No separate OpenCV dependency is needed: it is published as an API dependency so the decoder's public types resolve for consumers. The artifact targets the JVM and requires JDK 17 or later.

The artifact is currently published to the local Maven repository, not Maven Central. To share it with other developers, publish the same Maven publication to a Maven repository they can access, then add that repository to their `repositories` block. Use the same `implementation("net.natsirt.colorfqrcodes:colorfqrcodes:1.0.0")` declaration.

### Use the Android Bitmap renderer

The optional `:android` library module has no AWT dependency and returns a native Android `Bitmap`. Include this repository as a Gradle project (or publish the `android` module to your Maven repository), then add:

```kotlin
dependencies {
	implementation(project(":android"))
}
```

Render an image for a view or Compose `Image`:

```kotlin
import org.example.net.natsirt.colorfrqcodes.AndroidColorFQRCode

val bitmap = AndroidColorFQRCode("Hello, Android!".toByteArray(), bpp = 4)
	.render(scale = 12)
imageView.setImageBitmap(bitmap)
```

Compose can display the returned bitmap with `Image(bitmap.asImageBitmap(), contentDescription = null)`.

## Library API

Encode UTF-8 text and save a PNG:

```kotlin
import java.nio.file.Path
import org.example.net.natsirt.colorfrqcodes.ColorFQRCode

fun main() {
	val code = ColorFQRCode("Hello, world!".toByteArray(Charsets.UTF_8), bpp = 4)
	code.writePng(Path.of("hello.png"), scale = 12)
}
```

Decode an image file or an image already in memory:

```kotlin
import java.nio.file.Path
import javax.imageio.ImageIO
import org.example.net.natsirt.colorfrqcodes.ImageDecoder

fun main() {
	val image = requireNotNull(ImageIO.read(Path.of("hello.png").toFile()))
	val result = ImageDecoder().decodeWithBounds(image)
	println(result.text)
	println(result.corners) // four detected OpenCV points, useful for overlays
}
```

Other reusable entry points include `ColorFQRCode.render(scale)` for a `BufferedImage`, `ImageDecoder.decode(image)` for text-only decoding, `ImageFileViewer.decode(path)` for file decoding, `Palette.make(bpp)` for the deterministic palette, and `ReedSolomon(eccBytes)` for byte-block ECC.

## Run The Desktop App

The app source stays in this repository and is not included in the published library JAR. Run the Swing encoder/decoder UI with:

```powershell
.\gradlew.bat run
```

The app provides text encoding, PNG export, image-file decoding, and local live-camera preview. Its command-line modes are:

```powershell
.\gradlew.bat run --args="--encode 'Hello, world!' -o hello.png --bpp 4 --scale 12"
.\gradlew.bat run --args="--decode hello.png"
.\gradlew.bat run --args="--camera"
```

The optional phone-camera receiver uses HTTPS because browsers require a secure context for camera access. Supply a trusted PKCS#12 keystore and set its password in `COLORFQ_HTTPS_PASSWORD`:

```powershell
$env:COLORFQ_HTTPS_PASSWORD = 'your-keystore-password'
.\gradlew.bat run --args="--phone-camera camera.p12 8443"
```

## Build And Test This Repository

```powershell
.\gradlew.bat test
.\gradlew.bat build
```
