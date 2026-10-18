package org.example.net.natsirt.colorfrqcodes

import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.imageio.ImageIO
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import javax.swing.ImageIcon
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.JTextArea
import org.opencv.core.Mat
import org.opencv.videoio.VideoCapture

class CameraStreamViewer {
    class LiveDecodeReporter(private val output: (String) -> Unit) {
        private var previousPayload: String? = null
        private var previousFailure: String? = null

        fun report(payload: String?) {
            if (payload == null) {
                previousPayload = null
                previousFailure = null
            } else if (payload != previousPayload) {
                output(payload)
                previousPayload = payload
                previousFailure = null
            }
        }

        fun reportFailure(reason: String) {
            previousPayload = null
            if (reason != previousFailure) output("Decode not found: $reason")
            previousFailure = reason
        }
    }

    fun start() {
        check(!GraphicsEnvironment.isHeadless()) { "A desktop display is required for camera preview." }
        OpenCvRuntime.ensureLoaded()
        SwingUtilities.invokeLater {
            val frame = JFrame("ColorFQRCodes camera")
            val imageLabel = JLabel("Starting camera…")
            val statusLabel = JLabel("Starting camera…")
            val decodedText = JTextArea(3, 60).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
            }
            val resultPanel = JPanel(BorderLayout(4, 4)).apply {
                add(statusLabel, BorderLayout.NORTH)
                add(JScrollPane(decodedText), BorderLayout.CENTER)
            }
            frame.add(imageLabel, BorderLayout.CENTER)
            frame.add(resultPanel, BorderLayout.SOUTH)
            frame.setSize(900, 650)
            frame.setLocationRelativeTo(null)
            frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
            frame.isVisible = true

            Thread({
                val camera = VideoCapture(0)
                val mat = Mat()
                val reporter = LiveDecodeReporter { message ->
                    SwingUtilities.invokeLater {
                        if (message.startsWith("Decode not found:")) {
                            statusLabel.text = message
                        } else {
                            decodedText.text = message
                            statusLabel.text = "Decoded and CRC verified."
                        }
                    }
                }
                val decoder = ImageDecoder()
                var lastDecode = 0L
                try {
                    check(camera.isOpened) { "No camera is available." }
                    while (frame.isDisplayable && camera.read(mat)) {
                        if (mat.empty()) continue
                        val image = matToBufferedImage(mat)
                        SwingUtilities.invokeLater { imageLabel.icon = ImageIcon(image); imageLabel.text = "" }
                        val now = System.currentTimeMillis()
                        if (now - lastDecode >= 500) {
                            lastDecode = now
                            try {
                                reporter.report(decoder.decode(image))
                            } catch (exception: Exception) {
                                reporter.reportFailure(exception.message ?: "No code found.")
                            }
                        }
                        Thread.sleep(PREVIEW_INTERVAL_MS.toLong())
                    }
                } catch (exception: Exception) {
                    SwingUtilities.invokeLater {
                        JOptionPane.showMessageDialog(frame, exception.message, "Camera error", JOptionPane.ERROR_MESSAGE)
                        frame.dispose()
                    }
                } finally {
                    camera.release()
                    mat.release()
                }
            }, "colorfq-camera").apply { isDaemon = true; start() }
        }
    }

    private fun matToBufferedImage(mat: Mat): BufferedImage {
        val converted = Mat()
        try {
            org.opencv.imgproc.Imgproc.cvtColor(mat, converted, org.opencv.imgproc.Imgproc.COLOR_BGR2RGB)
            val pixels = ByteArray(converted.rows() * converted.cols() * 3)
            converted.get(0, 0, pixels)
            val image = BufferedImage(converted.cols(), converted.rows(), BufferedImage.TYPE_INT_RGB)
            var offset = 0
            for (y in 0 until converted.rows()) for (x in 0 until converted.cols()) {
                val red = pixels[offset++].toInt() and 0xFF
                val green = pixels[offset++].toInt() and 0xFF
                val blue = pixels[offset++].toInt() and 0xFF
                image.setRGB(x, y, (red shl 16) or (green shl 8) or blue)
            }
            return image
        } finally {
            converted.release()
        }
    }

    companion object {
        const val TARGET_FPS = 30
        const val PREVIEW_INTERVAL_MS = 1000 / TARGET_FPS
    }
}

class PhoneCameraHttpsServer(
    private val keyStorePath: Path,
    private val password: CharArray,
    private val port: Int = 8443
) {
    fun startAndWait() {
        require(Files.isRegularFile(keyStorePath)) { "HTTPS keystore does not exist: $keyStorePath" }
        val keyStore = KeyStore.getInstance("PKCS12")
        Files.newInputStream(keyStorePath).use { keyStore.load(it, password) }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagers.init(keyStore, password)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(keyManagers.keyManagers, null, null)

        val server = HttpsServer.create(java.net.InetSocketAddress(port), 0)
        server.httpsConfigurator = HttpsConfigurator(sslContext)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            val response = if (exchange.requestURI.path == "/decode" && exchange.requestMethod == "POST") {
                val image = exchange.requestBody.use { ImageIO.read(it) }
                val result = if (image == null) "Decode failed: invalid image" else ImageFileViewer.decodeForDisplay(image)
                "{\"result\":\"${jsonEscape(result)}\"}" to "application/json; charset=utf-8"
            } else {
                PHONE_CAMERA_PAGE to "text/html; charset=utf-8"
            }
            val bytes = response.first.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", response.second)
            exchange.responseHeaders.set("Access-Control-Allow-Origin", "*")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        println("Phone camera page: https://<this-computer-ip>:$port/")
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(0) })
        CountDownLatch(1).await()
    }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    private companion object {
        const val PHONE_CAMERA_PAGE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><title>ColorFQ camera</title><h1>ColorFQ camera</h1><video id="v" autoplay muted playsinline style="width:100%"></video><p id="s">Starting camera</p><script>const v=document.querySelector('#v'),s=document.querySelector('#s');navigator.mediaDevices.getUserMedia({video:{facingMode:'environment'},audio:false}).then(stream=>{v.srcObject=stream;setInterval(async()=>{if(v.readyState<2)return;const c=document.createElement('canvas');c.width=v.videoWidth;c.height=v.videoHeight;c.getContext('2d').drawImage(v,0,0);const blob=await new Promise(r=>c.toBlob(r,'image/jpeg',.82));const response=await fetch('/decode',{method:'POST',body:blob});const data=await response.json();s.textContent=data.result;},500)}).catch(e=>s.textContent='Camera error: '+e.message)</script>"""
    }
}