import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.studyontv.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.studyontv.tv"
        minSdk = 21          // Android 5.0
        targetSdk = 34
        versionCode = 24
        versionName = "0.3.19"
    }

    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            applicationId = "com.studyontv.tv"
        }
        create("mobile") {
            dimension = "device"
            applicationId = "com.studyontv.mobile"
        }
    }

    signingConfigs {
        create("release") {
            val ks = localProps.getProperty("KEYSTORE_PATH", "")
            if (ks.isNotEmpty()) {
                storeFile = file(ks)
                storePassword = localProps.getProperty("KEYSTORE_PASS")
                keyAlias = localProps.getProperty("KEY_ALIAS")
                keyPassword = localProps.getProperty("KEY_PASS")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = false
            val cfg = signingConfigs.getByName("release")
            if (cfg.storeFile != null) signingConfig = cfg
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        abortOnError = false
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Bugly 崩溃上报（用户同意后才初始化，见 BuglyInit.kt）
    implementation(files("libs/bugly-crashreport.aar"))
}

// 把网页和题库同步进 assets（仓库里只维护 app/web、admin、data/seed.json 三份源）
val repoRoot = rootProject.projectDir.parentFile.parentFile
val syncWebAssets by tasks.registering(Sync::class) {
    from(File(repoRoot, "app/web")) { into("web") }
    from(File(repoRoot, "admin")) { into("admin") }
    from(File(repoRoot, "data/seed.json")) { rename { "questions.json" } }
    from(File(repoRoot, "shared/katex")) { into("web/assets/vendor/katex") }
    from(File(repoRoot, "shared/math.js")) { into("web/assets/js") }
    from(File(repoRoot, "data/holidays")) { into("holidays") }
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(syncWebAssets) }
