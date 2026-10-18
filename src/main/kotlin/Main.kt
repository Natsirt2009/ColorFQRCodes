package org.example

import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel
import javax.swing.SwingUtilities
import org.example.net.natsirt.colorfrqcodes.CameraStreamViewer
import org.example.net.natsirt.colorfrqcodes.ColorFQRCode
import org.example.net.natsirt.colorfrqcodes.ImageDecoder
import org.example.net.natsirt.colorfrqcodes.PhoneCameraHttpsServer

fun main(args: Array<String>) {
    when {
        args.isEmpty() || args[0] == "--app" -> showApp()
        args[0] == "--camera" -> CameraStreamViewer().start()
        args[0] == "--phone-camera" -> {
            require(args.size in 2..3) { "Usage: --phone-camera KEYSTORE.p12 [PORT]" }
            val password = System.getenv("COLORFQ_HTTPS_PASSWORD")
                ?: error("Set COLORFQ_HTTPS_PASSWORD to the PKCS12 keystore password.")
            PhoneCameraHttpsServer(Path.of(args[1]), password.toCharArray(), args.getOrNull(2)?.toIntOrNull() ?: 8443)
                .startAndWait()
        }
        args[0] == "--encode" -> encode(args)
        args.size == 2 && args[0] == "--decode" -> {
            val image = requireNotNull(ImageIO.read(Path.of(args[1]).toFile())) { "Unsupported image." }
            println(ImageDecoder().decode(image))
        }
        args.size == 1 && args[0] == "--help" -> println(
            "Usage: --encode TEXT [-o FILE] [--bpp 1..8] [--scale PIXELS] | --decode IMAGE | --camera | --phone-camera KEYSTORE.p12 [PORT]"
        )
        else -> error("Invalid command. Use --help for usage.")
    }
}

private fun encode(args: Array<String>) {
    require(args.size >= 2) { "Missing text after --encode." }
    var output = Path.of("colorcode.png")
    var bitsPerPixel = 2
    var scale = 12
    var index = 2
    while (index < args.size) {
        when (args[index]) {
            "-o", "--output" -> output = Path.of(args.getOrNull(++index) ?: error("Missing output path."))
            "--bpp" -> bitsPerPixel = args.getOrNull(++index)?.toIntOrNull() ?: error("Invalid bpp value.")
            "--scale" -> scale = args.getOrNull(++index)?.toIntOrNull() ?: error("Invalid scale value.")
            else -> error("Unknown encode option: ${args[index]}")
        }
        index++
    }
    ColorFQRCode(args[1].toByteArray(Charsets.UTF_8), bitsPerPixel).writePng(output, scale)
    println("Saved: $output")
}

private fun showApp() {
    if (java.awt.GraphicsEnvironment.isHeadless()) {
        println("ColorFQRCodes: use --encode TEXT [-o FILE] [--bpp 1..8] [--scale PIXELS], --decode IMAGE, or --camera")
        return
    }
    SwingUtilities.invokeLater {
        val text = JTextArea("Hello, world!", 6, 42)
        val output = JLabel("Enter text to create a color code.")
        val bpp = JComboBox((1..8).map { "$it bpp" }.toTypedArray()).apply { selectedIndex = 1 }
        val scale = JSpinner(SpinnerNumberModel(12, 1, 40, 1))
        val preview = JLabel()
        val window = JFrame("ColorFQRCodes")
        window.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        window.minimumSize = Dimension(620, 700)

        val controls = JPanel(FlowLayout(FlowLayout.LEADING)).apply {
            add(JLabel("Palette")); add(bpp)
            add(JLabel("Pixels per module")); add(scale)
            add(JButton("Generate").apply {
                addActionListener {
                    try {
                        val code = ColorFQRCode(text.text.toByteArray(Charsets.UTF_8), bpp.selectedIndex + 1)
                        val image = code.render(scale.value as Int)
                        preview.icon = javax.swing.ImageIcon(image)
                        output.text = "${code.bpp} bpp · ${code.size} × ${code.size} grid · ${image.width} px"
                    } catch (exception: Exception) {
                        output.text = exception.message ?: "Could not encode text."
                    }
                }
            })
            add(JButton("Save PNG").apply {
                addActionListener {
                    val chooser = JFileChooser()
                    if (chooser.showSaveDialog(window) == JFileChooser.APPROVE_OPTION) {
                        try {
                            val code = ColorFQRCode(text.text.toByteArray(Charsets.UTF_8), bpp.selectedIndex + 1)
                            code.writePng(chooser.selectedFile.toPath(), scale.value as Int)
                            output.text = "Saved ${chooser.selectedFile.name}"
                        } catch (exception: Exception) { output.text = exception.message ?: "Could not save PNG." }
                    }
                }
            })
            add(JButton("Open image").apply {
                addActionListener {
                    val chooser = JFileChooser()
                    if (chooser.showOpenDialog(window) == JFileChooser.APPROVE_OPTION) {
                        try {
                            val image: BufferedImage = requireNotNull(ImageIO.read(chooser.selectedFile))
                            output.text = "Decoded: ${ImageDecoder().decode(image)}"
                        } catch (exception: Exception) { output.text = "Decode failed: ${exception.message}" }
                    }
                }
            })
            add(JButton("Live camera").apply { addActionListener { CameraStreamViewer().start() } })
        }
        val content = JPanel(BorderLayout(8, 8))
        content.add(JScrollPane(text), BorderLayout.NORTH)
        content.add(controls, BorderLayout.CENTER)
        content.add(JScrollPane(preview), BorderLayout.SOUTH)
        window.add(content, BorderLayout.CENTER)
        window.add(output, BorderLayout.SOUTH)
        window.pack()
        window.setLocationRelativeTo(null)
        window.isVisible = true
    }
}/*package org.example

import org.example.net.natsirt.colorfrqcodes.ColorFQRCode
import org.example.net.natsirt.colorfrqcodes.CameraStreamViewer
import org.example.net.natsirt.colorfrqcodes.ImageDecoder
import org.example.net.natsirt.colorfrqcodes.ImageFileViewer
import org.example.net.natsirt.colorfrqcodes.PhoneCameraHttpsServer
import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import javax.imageio.ImageIO

fun main(args: Array<String>) {
    require(args.isNotEmpty()) {
        "Usage: --encode TEXT [-o FILE] [--bpp 1..8] [--scale PIXELS] | --decode IMAGE | --view IMAGE | --camera [INDEX] | --phone-camera KEYSTORE.p12 [PORT]"
    }

    if (args[0] == "--decode") {
        require(args.size == 2) { "Usage: --decode IMAGE" }
        val image = ImageIO.read(Paths.get(args[1]).toFile()) ?: error("Unsupported image format.")
        println(ImageDecoder().decode(image))
        return
    }
    if (args[0] == "--view") {
        require(args.size == 2) { "Usage: --view IMAGE" }
        ImageFileViewer.show(args[1])
        return
    }
    if (args[0] == "--camera") {
        val cameraIndex = args.getOrNull(1)?.toIntOrNull() ?: 0
        CameraStreamViewer.show(cameraIndex)
        return
    }
    if (args[0] == "--phone-camera") {
        require(args.size in 2..3) { "Usage: --phone-camera KEYSTORE.p12 [PORT]" }
        val password = System.getenv("COLORFQ_HTTPS_PASSWORD")
            ?: error("Set COLORFQ_HTTPS_PASSWORD to the PKCS12 keystore password.")
        val port = args.getOrNull(2)?.toIntOrNull() ?: 8443
        PhoneCameraHttpsServer(Paths.get(args[1]), password.toCharArray(), port).startAndWait()
        return
    }
    require(args[0] == "--encode") {
        "Usage: --encode TEXT [-o FILE] [--bpp 1..8] [--scale PIXELS] | --decode IMAGE | --view IMAGE | --camera [INDEX] | --phone-camera KEYSTORE.p12 [PORT]"
    }
    require(args.size >= 2) { "Missing text after --encode." }

    var output = "colorcode.png"
    var bpp = 2
    var scale = 12
    var index = 2
    while (index < args.size) {
        when (args[index]) {
            "-o", "--output" -> output = args.getOrNull(++index) ?: error("Missing output path.")
            "--bpp" -> bpp = args.getOrNull(++index)?.toIntOrNull() ?: error("Invalid bpp value.")
            "--scale" -> scale = args.getOrNull(++index)?.toIntOrNull() ?: error("Invalid scale value.")
            else -> error("Unknown option: ${args[index]}")
        }
        index++
    }

    ColorFQRCode(args[1].toByteArray(StandardCharsets.UTF_8), bpp).writePng(Paths.get(output), scale)
    println("Saved: $output")
}

//TIP To <b>Run</b> code, press <shortcut actionId="Run"/> or
// click the <icon src="AllIcons.Actions.Execute"/> icon in the gutter.
fun main() {
    val name = "Kotlin"
    //TIP Press <shortcut actionId="ShowIntentionActions"/> with your caret at the highlighted text
    // to see how IntelliJ IDEA suggests fixing it.
    println("Hello, " + name + "!")

    for (i in 1..5) {
        //TIP Press <shortcut actionId="Debug"/> to start debugging your code. We have set one <icon src="AllIcons.Debugger.Db_set_breakpoint"/> breakpoint
        // for you, but you can always add more by pressing <shortcut actionId="ToggleLineBreakpoint"/>.
        println("i = $i")
    }
}*/