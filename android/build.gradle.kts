plugins {
    id("com.android.library")
    id("maven-publish")
}

group = "net.natsirt.colorfqrcodes"
version = "1.1.0"

dependencies {
    implementation("org.opencv:opencv:4.9.0")
}

android {
    namespace = "net.natsirt.colorfqrcodes"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("../shared/src/main/kotlin")
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                groupId = "net.natsirt.colorfqrcodes"
                artifactId = "colorfqrcodes-android"
                version = project.version.toString()
                from(components["release"])
            }
        }
    }
}

