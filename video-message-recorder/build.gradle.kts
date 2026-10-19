import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

group =
    "io.github.webarmour"

version =
    "0.0.3"

android {
    namespace = "io.github.webarmour.videomessagerecorder"
    // Keep the published AAR consumable by applications built with Android API 36.
    compileSdk = libs.versions.libraryCompileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    publishing {
        singleVariant(
            "release"
        ) {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>(
                "release"
            ) {
                groupId =
                    project.group.toString()

                artifactId =
                    "video-message-recorder"

                version =
                    project.version.toString()

                from(
                    components["release"]
                )

                pom {
                    name.set(
                        "Video Message Recorder"
                    )

                    description.set(
                        "Android library for recording short in-app video messages."
                    )

                    licenses {
                        license {
                            name.set(
                                "Apache License 2.0"
                            )

                            url.set(
                                "https://www.apache.org/licenses/LICENSE-2.0"
                            )
                        }
                    }
                }
            }
        }
    }
}
