import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.reallyfast"
    compileSdk = 35

    defaultConfig {
        applicationId = "cz.reallyfast"
        minSdk = 24
        targetSdk = 35
        versionCode = 66
        versionName = "1.47.3"
        ndk {
            // Jen arm64-v8a (Galaxy S25 Ultra a vsechny dnesni telefony); emulator x86_64 se nepodporuje.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Release klic: v local.properties (mimo git) nastav release.keystore, release.storePassword,
    // release.keyAlias a release.keyPassword. Bez nich sestaveni release selze (zadne podepsani debug klicem).
    val localProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.isFile) f.inputStream().use { load(it) }
    }
    val hasReleaseKey = listOf("release.keystore", "release.storePassword", "release.keyAlias", "release.keyPassword")
        .all { !localProps.getProperty(it).isNullOrBlank() }
    // Release bez klice se nesmi sestavit (jinak by se podepsal debug klicem); debug a testy tim nejsou dotceny.
    gradle.taskGraph.whenReady {
        val wantsRelease = allTasks.any { (it.name.startsWith("assemble") || it.name.startsWith("bundle")) && it.name.endsWith("Release") }
        if (wantsRelease && !hasReleaseKey) {
            throw GradleException("reallyFAST!: chybi release.keystore, release.storePassword, release.keyAlias nebo release.keyPassword v local.properties - release se nepodepise debug klicem.")
        }
    }
    if (hasReleaseKey) {
        signingConfigs.create("release") {
            storeFile = file(localProps.getProperty("release.keystore"))
            storePassword = localProps.getProperty("release.storePassword")
            keyAlias = localProps.getProperty("release.keyAlias")
            keyPassword = localProps.getProperty("release.keyPassword")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// ---------------------------------------------------------------------------------------------
// Rust jadro (crates/keyboard-core)
//
// Hotove knihovny libkeyboard_core.so jsou ulozene v app/src/main/jniLibs, takze projekt jde
// sestavit v Android Studiu i bez Rustu. Kdyz je na PATH `cargo` + `cargo-ndk` a v SDK je NDK,
// knihovny se pred buildem automaticky prelozi znovu (jen kdyz se zmenil Rust kod).
// Vypnuti: gradle.properties -> reallyfast.buildRust=false
// ---------------------------------------------------------------------------------------------

val cargoDir: File = rootProject.file("crates/keyboard-core")
val jniLibsDir: File = file("src/main/jniLibs")
val isWindows = System.getProperty("os.name").lowercase().contains("win")

fun findExecutable(name: String): File? {
    val exe = if (isWindows) "$name.exe" else name
    val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it) } +
        File(System.getProperty("user.home"), ".cargo/bin")
    return dirs.map { File(it, exe) }.firstOrNull { it.isFile }
}

fun findNdk(): File? {
    System.getenv("ANDROID_NDK_HOME")?.let { File(it) }?.takeIf { it.isDirectory }?.let { return it }
    val props = rootProject.file("local.properties")
    val sdkFromProps = if (props.isFile) {
        props.readLines().firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter("=")?.replace("\\:", ":")?.replace("\\\\", "\\")
    } else null
    val sdk = sdkFromProps ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return null
    return File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
}

val rustEnabled = (findProperty("reallyfast.buildRust") as String?)?.toBoolean() ?: true
val cargoExe = findExecutable("cargo")
val cargoNdkExe = findExecutable("cargo-ndk")
val ndkDir = findNdk()
val canBuildRust = rustEnabled && cargoExe != null && cargoNdkExe != null && ndkDir != null

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "rust"
    description = "Prelozi keyboard-core pro Android (arm64-v8a) do app/src/main/jniLibs."
    onlyIf { canBuildRust }
    workingDir = cargoDir
    inputs.dir(File(cargoDir, "src"))
    inputs.file(File(cargoDir, "Cargo.toml"))
    outputs.dir(jniLibsDir)
    if (canBuildRust) {
        environment("ANDROID_NDK_HOME", ndkDir!!.absolutePath)
        commandLine(
            cargoExe!!.absolutePath, "ndk",
            "-t", "arm64-v8a",
            "-o", jniLibsDir.absolutePath,
            "build", "--release", "--lib"
        )
    } else {
        commandLine(if (isWindows) listOf("cmd", "/c", "exit", "0") else listOf("true"))
    }
    doFirst {
        if (!canBuildRust) {
            logger.warn("reallyFAST!: VAROVANI - Rust se neprekladal (chybi cargo, cargo-ndk nebo NDK), pouzije se starsi libkeyboard_core.so z jniLibs")
        }
        logger.lifecycle("reallyFAST!: prekladam keyboard-core pres cargo-ndk (NDK ${ndkDir?.name})")
    }
}

tasks.named("preBuild") {
    dependsOn(cargoNdkBuild)
    doFirst {
        if (!File(jniLibsDir, "arm64-v8a/libkeyboard_core.so").isFile) {
            throw GradleException(
                "Chybi app/src/main/jniLibs/arm64-v8a/libkeyboard_core.so. " +
                    "Nainstaluj Rust (rustup), `cargo install cargo-ndk`, " +
                    "`rustup target add aarch64-linux-android` a NDK v SDK Manageru."
            )
        }
    }
}

// Test JNI mostu na pocitaci: prelozi knihovnu pro hostitelsky system a nacte ji do JVM.
val cargoHostBuild = tasks.register<Exec>("cargoHostBuild") {
    group = "rust"
    description = "Prelozi keyboard-core pro tento pocitac (pro JVM test JNI mostu)."
    onlyIf { cargoExe != null }
    workingDir = cargoDir
    commandLine(cargoExe?.absolutePath ?: "cargo", "build", "--release", "--lib")
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoHostBuild)
    val libName = when {
        isWindows -> "keyboard_core.dll"
        System.getProperty("os.name").lowercase().contains("mac") -> "libkeyboard_core.dylib"
        else -> "libkeyboard_core.so"
    }
    systemProperty("reallyfast.hostlib", File(cargoDir, "target/release/$libName").absolutePath)
    systemProperty("reallyfast.dictionary", file("src/main/assets/dictionary.bin").absolutePath)
}
