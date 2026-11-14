import java.net.URL

plugins {
    id("com.android.application") version "8.9.1"
    id("org.jetbrains.kotlin.android") version "2.2.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

val manropeGeneratedRes = layout.buildDirectory.dir("generated/res/manrope")
val manropeGeneratedAssets = layout.buildDirectory.dir("generated/assets/manrope")
val effectImageResources = layout.buildDirectory.dir("generated/res/effectImages")

android {
    namespace = "com.kinetic.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kinetic.app"
        minSdk = 26
        targetSdk = 34
        // CI supplies a unique, monotonically increasing version for every APK build.
        // Local builds retain the last checked-in fallback values.
        versionCode = providers.gradleProperty("KINETIC_VERSION_CODE").orElse("3").get().toInt()
        versionName = providers.gradleProperty("KINETIC_VERSION_NAME").orElse("0.3").get()
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-ffast-math")
            }
        }
    }
    buildFeatures {
        compose = true
        prefab = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    sourceSets.getByName("main").res.srcDir(manropeGeneratedRes)
    sourceSets.getByName("main").res.srcDir(effectImageResources)
    sourceSets.getByName("main").assets.srcDir(manropeGeneratedAssets)
}

val downloadEffectCategoryImages by tasks.registering {
    val images = mapOf(
        "effect_category_color_light.jpg" to "https://images.unsplash.com/photo-1550275910-006d8096d19b?auto=format&fit=crop&fm=jpg&q=82&w=900",
        "effect_category_stylize.jpg" to "https://images.unsplash.com/photo-1635431289101-ef85326518f2?auto=format&fit=crop&fm=jpg&q=82&w=900",
        "effect_category_drawing.jpg" to "https://images.unsplash.com/photo-1713332126538-0fe6208bf453?auto=format&fit=crop&fm=jpg&q=82&w=900",
        "effect_category_distortion.jpg" to "https://images.unsplash.com/photo-1635431289101-ef85326518f2?auto=format&fit=crop&fm=jpg&q=82&w=900",
        "effect_category_procedural.jpg" to "https://images.unsplash.com/photo-1713332126538-0fe6208bf453?auto=format&fit=crop&fm=jpg&q=82&w=900",
        "effect_category_blur.jpg" to "https://images.unsplash.com/photo-1617892060511-6bee2d26827e?auto=format&fit=crop&fm=jpg&q=82&w=900",
        "effect_category_3d.jpg" to "https://images.unsplash.com/photo-1713332126538-0fe6208bf453?auto=format&fit=crop&fm=jpg&q=82&w=900",
        "effect_category_text.jpg" to "https://images.unsplash.com/photo-1550275910-006d8096d19b?auto=format&fit=crop&fm=jpg&q=82&w=900",
        "effect_category_audio.jpg" to "https://images.unsplash.com/photo-1617892060511-6bee2d26827e?auto=format&fit=crop&fm=jpg&q=82&w=900",
        "effect_category_other.jpg" to "https://images.unsplash.com/photo-1635431289101-ef85326518f2?auto=format&fit=crop&fm=jpg&q=82&w=900"
    )
    val outputFiles = images.keys.map { effectImageResources.map { dir -> dir.file("drawable-nodpi/$it") } }
    outputs.files(outputFiles)
    doLast {
        images.forEach { (name, source) ->
            val target = effectImageResources.get().file("drawable-nodpi/$name").asFile
            target.parentFile.mkdirs()
            URL(source).openConnection().apply {
                connectTimeout = 20_000
                readTimeout = 30_000
            }.getInputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            require(target.length() > 10_000L) { "Effect category image download was incomplete: $name" }
        }
    }
}

val downloadManropeAssets by tasks.registering {
    val fontFile = manropeGeneratedRes.map { it.file("font/manrope_variable.ttf") }
    val licenseFile = manropeGeneratedAssets.map { it.file("fonts/Manrope-OFL.txt") }
    outputs.files(fontFile, licenseFile)

    doLast {
        val font = fontFile.get().asFile
        font.parentFile.mkdirs()
        URL("https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/Manrope%5Bwght%5D.ttf")
            .openConnection()
            .apply {
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            .getInputStream()
            .use { input -> font.outputStream().use { output -> input.copyTo(output) } }
        require(font.length() > 100_000L) { "Manrope font download was incomplete." }

        val license = licenseFile.get().asFile
        license.parentFile.mkdirs()
        URL("https://raw.githubusercontent.com/google/fonts/main/ofl/manrope/OFL.txt")
            .openConnection()
            .apply {
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            .getInputStream()
            .use { input -> license.outputStream().use { output -> input.copyTo(output) } }
        require(license.length() > 1_000L) { "Manrope OFL license download was incomplete." }
    }
}

tasks.named("preBuild").configure {
    dependsOn(downloadManropeAssets, downloadEffectCategoryImages)
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")

    // Media3 1.5.1 requires compileSdk 35.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")

    // Isolated libmpv playback test backend (GPL-3.0-or-later native bundle).
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
}
