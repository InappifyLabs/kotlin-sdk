import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
    `maven-publish`
}

val sdkGroup: String = providers.gradleProperty("GROUP").get()
val sdkArtifactId: String = providers.gradleProperty("POM_ARTIFACT_ID").get()
val sdkVersion: String = providers.gradleProperty("VERSION_NAME").get()
require(Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-beta\\.(0|[1-9][0-9]*))?").matches(sdkVersion)) {
    "VERSION_NAME must be major.minor.patch or major.minor.patch-beta.N (for example, 0.1.0-beta.0)."
}

group = sdkGroup
version = sdkVersion

android {
    namespace = "com.inappify.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 21

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    // Explicit API mode prevents accidental additions to the published ABI.
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.poolakey)
    // Lightweight verifier; no global JCA provider is installed or replaced.
    implementation(libs.bouncycastle)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = sdkGroup
                artifactId = sdkArtifactId
                version = sdkVersion

                pom {
                    name.set("Inappify Kotlin SDK")
                    description.set(
                        "Native Android SDK for Inappify purchases and customer entitlements.",
                    )
                    url.set(
                        "https://github.com/InappifyLabs/kotlin-sdk",
                    )
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("InappifyLabs")
                            url.set("https://github.com/InappifyLabs")
                        }
                    }
                    scm {
                        connection.set(
                            "scm:git:git://github.com/InappifyLabs/" +
                                "kotlin-sdk.git",
                        )
                        developerConnection.set(
                            "scm:git:ssh://git@github.com/InappifyLabs/" +
                                "kotlin-sdk.git",
                        )
                        url.set(
                            "https://github.com/InappifyLabs/" +
                                "kotlin-sdk",
                        )
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set(
                            "https://github.com/InappifyLabs/" +
                                "kotlin-sdk/issues",
                        )
                    }
                }
            }
        }
    }
}
