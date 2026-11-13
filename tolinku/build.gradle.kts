plugins {
    // No org.jetbrains.kotlin.android: AGP 9 compiles Kotlin itself and fails
    // the build if that plugin is also applied. The Kotlin version comes from
    // the buildscript classpath in the root build file.
    id("com.android.library")
    id("maven-publish")
    id("signing")
}

val sdkVersion = "0.7.0"

android {
    namespace = "com.tolinku.sdk"
    compileSdk = 36

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 24

        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")

        consumerProguardFiles("consumer-rules.pro")

        // AGP 9 defaults to requiring that consumers of a library compile
        // against the same or a higher SDK than the library did. Left alone,
        // moving this module to compileSdk 36 would refuse to build in any
        // customer app still on 34 or 35, which is a breaking change none of
        // them asked for and would read to them as our bug. Declaring the real
        // floor keeps those apps compiling.
        aarMetadata {
            minCompileSdk = 34
        }
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

    // android.jar stubs throw "Stub!" by default, which makes plain JVM unit tests
    // impossible for anything touching framework classes such as DisplayMetrics.
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    // Maven Central rejects a publication that has no sources or javadoc jar,
    // so the release variant has to produce both.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 5.3.0 rather than the newest 5.x on purpose. okhttp-android declares a
    // minCompileSdk of 36 from 5.4.0 and 37 from 5.5.1, and that constraint
    // passes straight through to anyone who depends on this library: taking
    // 5.5.1 would both break our own build, since AGP 9.5 cannot compile past
    // API 36.1, and force every customer onto compileSdk 37. 5.3.0 declares no
    // floor at all, so the aarMetadata.minCompileSdk above stays the real one.
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    // Play Install Referrer: the deterministic half of deferred linking on
    // Android. Without it a deferred install can only be matched on device
    // signals, which is probabilistic and expires in hours rather than days.
    implementation("com.android.installreferrer:installreferrer:2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.0")
    testImplementation("org.json:json:20260814")
}

// ---------------------------------------------------------------------------
// Maven publishing (JitPack + Maven Central)
// ---------------------------------------------------------------------------

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "com.tolinku"
                artifactId = "sdk"
                version = sdkVersion

                pom {
                    name.set("Tolinku Android SDK")
                    description.set("Android SDK for the Tolinku deep linking platform. Provides deferred deep links, referral tracking, analytics, and in-app messages.")
                    url.set("https://github.com/tolinku/android-sdk")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
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
                // The old OSSRH host (s01.oss.sonatype.org) was retired in 2025 and
                // now answers 402, so nothing published through it could ever have
                // reached Maven Central. This is the Central Portal's compatibility
                // endpoint, which accepts the same upload and routes it onward.
                url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")

                credentials {
                    // Central Portal user token, generated under Account on
                    // central.sonatype.com. Not the account login.
                    username = (findProperty("centralUsername") ?: findProperty("ossrhUsername") ?: System.getenv("MAVEN_CENTRAL_USERNAME") ?: "").toString()
                    password = (findProperty("centralPassword") ?: findProperty("ossrhPassword") ?: System.getenv("MAVEN_CENTRAL_PASSWORD") ?: "").toString()
                }
            }
        }
    }

    // Sign only when a key is available, so a local build and JitPack, which have
    // no key, still work. Maven Central requires signatures; nothing else does.
    val signingKey = (findProperty("signingKey") ?: System.getenv("SIGNING_KEY"))?.toString()
    val signingPassword = (findProperty("signingPassword") ?: System.getenv("SIGNING_PASSWORD"))?.toString()
    if (!signingKey.isNullOrBlank()) {
        signing {
            // In-memory rather than a keyring on disk, so CI can hold the key in a secret.
            useInMemoryPgpKeys(signingKey, signingPassword ?: "")
            sign(publishing.publications["release"])
        }
    } else if (findProperty("signing.keyId") != null) {
        signing {
            sign(publishing.publications["release"])
        }
    }
}

// The version test reads the README's install line, and Gradle has no way to
// know that. Without this it decides the test task is up to date when only the
// README changed, so the guard that exists to catch a stale install line is the
// one thing that never runs after someone edits it.
tasks.withType<Test>().configureEach {
    inputs.file(rootProject.file("README.md")).withPathSensitivity(PathSensitivity.RELATIVE)
}

// jvmTarget is deliberately not set. With built-in Kotlin it defaults to
// android.compileOptions.targetCompatibility, so the Kotlin and Java halves of
// the library cannot drift apart the way they could when both were written out
// by hand.
