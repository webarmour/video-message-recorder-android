import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

group =
    "io.github.webarmour"

version =
    "0.0.1"

android {
    namespace = "io.github.webarmour.videomessagerecorder"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant(
            "release"
        ) {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
