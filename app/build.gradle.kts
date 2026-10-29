plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val defaultDebugSupabaseUrl = (
    (project.findProperty("supabaseUrl") as String?)
        ?.takeIf { it.isNotBlank() }
        ?: "https://nnmcwsdunpwdktexgivr.supabase.co"
).replace("\\", "\\\\").replace("\"", "\\\"")

val defaultDebugSupabaseKey = (
    (project.findProperty("supabaseKey") as String?)
        ?.takeIf { it.isNotBlank() }
        ?: "sb_publishable_74dplURrV6VbGnQHKGTbLw_lpX-pS20"
).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.beekeep.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.beekeep.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 25
        versionName = "1.8.1"
        manifestPlaceholders["appLabel"] = "BeeKeep"
        manifestPlaceholders["authScheme"] = "com.beekeep.app"
        manifestPlaceholders["authHost"] = "auth-callback"
        val supabaseUrl = ((project.findProperty("supabaseUrl") as String?) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        val supabaseKey = ((project.findProperty("supabaseKey") as String?) ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$supabaseKey\"")
        buildConfigField("String", "AUTH_REDIRECT_SCHEME", "\"com.beekeep.app\"")
        buildConfigField("String", "AUTH_REDIRECT_HOST", "\"auth-callback\"")
    }
    buildTypes {
        debug {
            // The normal debug APK is the primary development app and syncs with the existing BeeKeep Supabase project.
            // Only use a publishable/anon client key here; never put a service-role key in an Android app.
            buildConfigField("String", "SUPABASE_URL", "\"$defaultDebugSupabaseUrl\"")
            buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$defaultDebugSupabaseKey\"")
            buildConfigField("String", "AUTH_REDIRECT_SCHEME", "\"com.beekeep.app\"")
            buildConfigField("String", "AUTH_REDIRECT_HOST", "\"auth-callback\"")
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { jvmToolchain(17) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation(platform("io.github.jan-tennert.supabase:bom:3.8.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:functions-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:realtime-kt")
    implementation("io.github.jan-tennert.supabase:storage-kt")
    implementation("io.ktor:ktor-client-android:3.6.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
