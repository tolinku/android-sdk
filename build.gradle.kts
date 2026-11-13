// AGP 9 has built-in Kotlin support, so the org.jetbrains.kotlin.android plugin
// is neither needed nor allowed here any more. The Kotlin version is the 2.2.10
// that AGP 9.5 depends on at runtime, and it is deliberately not raised.
//
// A library's Kotlin version is a floor on its consumers, not a private choice.
// The compiler reads metadata at most one minor version ahead of itself, so
// building this with Kotlin 2.4 stamps metadata 2.4.0 into the AAR and every
// customer on Kotlin 2.2 or older is then told our classes "were compiled with
// an incompatible version of Kotlin" and cannot build at all. 2.2.10 stamps
// 2.2.0, which Kotlin 2.1 and up can read.
//
// Nothing here uses a language feature newer than that, so raising it would buy
// a narrower set of customers and no functionality. If a future change does need
// a newer Kotlin, add the classpath entry back and say so in the changelog,
// because it is a breaking change for anyone below it:
//
//   classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:<version>")
plugins {
    id("com.android.library") version "9.5.1" apply false
}
