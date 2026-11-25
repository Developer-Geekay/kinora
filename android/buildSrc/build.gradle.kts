plugins {
    `kotlin-dsl`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // both are already in the project-local Gradle cache (they are what the Android Gradle Plugin itself uses)
    implementation("com.android.tools.build:gradle-api:8.5.2")
    implementation("org.ow2.asm:asm-commons:9.6")
}
