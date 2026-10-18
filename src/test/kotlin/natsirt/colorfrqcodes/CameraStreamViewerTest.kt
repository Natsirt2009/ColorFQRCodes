package org.example.net.natsirt.colorfrqcodes

import java.awt.GraphicsEnvironment
import java.nio.file.Paths
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

class CameraStreamViewerTest {
    @Test
    fun `open HTTPS server for phone camera stream`() {
        assumeTrue(System.getenv("COLORFQ_PHONE_CAMERA_TEST") == "1", "Set COLORFQ_PHONE_CAMERA_TEST=1 to run the phone camera server")
        assumeTrue(!GraphicsEnvironment.isHeadless(), "A desktop display is required for the receiver window")
        val keyStore = System.getenv("COLORFQ_HTTPS_KEYSTORE")
        val password = System.getenv("COLORFQ_HTTPS_PASSWORD")
        assumeTrue(!keyStore.isNullOrBlank(), "Set COLORFQ_HTTPS_KEYSTORE to a PKCS12 keystore trusted by your phone")
        assumeTrue(!password.isNullOrEmpty(), "Set COLORFQ_HTTPS_PASSWORD to the PKCS12 keystore password")
        val port = System.getenv("COLORFQ_HTTPS_PORT")?.toIntOrNull() ?: 8443

        PhoneCameraHttpsServer(Paths.get(keyStore!!), password!!.toCharArray(), port).startAndWait()
    }
}