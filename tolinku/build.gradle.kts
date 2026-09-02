plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
    id("signing")
}

val sdkVersion = "0.5.0"

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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Play Install Referrer: the deterministic half of deferred linking on
    // Android. Without it a deferred install can only be matched on device
    // signals, which is probabilistic and expires in hours rather than days.
    implementation("com.android.installreferrer:installreferrer:2.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20231013")
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
