plugins {
    kotlin("jvm") version "2.4.20"
    id("com.android.library") version "9.4.0" apply false
    application
    `java-library`
    `maven-publish`
}

group = "net.natsirt.colorfqrcodes"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    api("org.openpnp:opencv:4.9.0-0")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
    sourceSets.getByName("main").kotlin.srcDir("shared/src/main/kotlin")
}

application {
    mainClass.set("org.example.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("colorfqrcodes")
    exclude(
        "org/example/MainKt*.class",
        "org/example/net/natsirt/colorfrqcodes/CameraStreamViewer*.class",
        "org/example/net/natsirt/colorfrqcodes/PhoneCameraHttpsServer*.class"
    )
}

publishing {
    publications {
        create<MavenPublication>("colorFQRCodes") {
            from(components["java"])
            artifactId = "colorfqrcodes"
            pom {
                name.set("ColorFQRCodes")
                description.set("Kotlin library for multicolor matrix encoding and decoding")
            }
        }
    }
}