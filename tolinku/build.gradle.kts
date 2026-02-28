plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

val sdkVersion = "0.1.0"

android {
    namespace = "com.tolinku.sdk"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24

        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")

        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")
}

// ---------------------------------------------------------------------------
// Maven Central publishing
// ---------------------------------------------------------------------------

// Source JAR task
val sourceJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(android.sourceSets["main"].java.srcDirs)
}

// Javadoc JAR task (empty; satisfies Maven Central requirement)
val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

afterEvaluate {
    // Ensure metadata generation declares dependency on artifact tasks
    tasks.named("generateMetadataFileForReleasePublication") {
        dependsOn(sourceJar, javadocJar)
    }

    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.tolinku"
                artifactId = "sdk"
                version = sdkVersion

                artifact(sourceJar)
                artifact(javadocJar)

                pom {
                    name.set("Tolinku Android SDK")
                    description.set("Android SDK for the Tolinku deep linking platform. Provides deferred deep links, referral tracking, analytics, and in-app messages.")
                    url.set("https://github.com/tolinku/android-sdk")

                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }

                    developers {
                        developer {
                            // Fill in via ~/.gradle/gradle.properties or replace inline
                            id.set(findProperty("pom.developer.id")?.toString() ?: "tolinku")
                            name.set(findProperty("pom.developer.name")?.toString() ?: "Tolinku Team")
                            email.set(findProperty("pom.developer.email")?.toString() ?: "dev@tolinku.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/tolinku/android-sdk.git")
                        developerConnection.set("scm:git:ssh://github.com:tolinku/android-sdk.git")
                        url.set("https://github.com/tolinku/android-sdk")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "MavenCentral"
                url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")

                credentials {
                    // Set these in ~/.gradle/gradle.properties:
                    //   ossrhUsername=your-sonatype-username
                    //   ossrhPassword=your-sonatype-password
                    username = findProperty("ossrhUsername")?.toString() ?: ""
                    password = findProperty("ossrhPassword")?.toString() ?: ""
                }
            }
        }
    }

    signing {
        // Reads signing.keyId, signing.password, signing.secretKeyRingFile
        // from ~/.gradle/gradle.properties
        sign(publishing.publications["release"])
    }
}
