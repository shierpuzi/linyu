import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

val localPropertiesFile = rootProject.file("local.properties")
val releaseSigningProperties = Properties().apply {
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}
val releaseSigningFields = listOf("KEYSTORE_FILE", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
val releaseKeystorePath = releaseSigningProperties.getProperty("KEYSTORE_FILE")
val releaseSigningReady = releaseSigningFields.all { !releaseSigningProperties.getProperty(it).isNullOrBlank() } &&
    releaseKeystorePath?.let { file(it).isFile } == true

android {
    namespace = "com.quzhi.lite"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.quzhi.lite"
        minSdk = 26
        targetSdk = 36
        versionCode = 16
        versionName = "3.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 只打 arm64 的 native 库。
        // ML Kit 的 libbarhopper_v3.so 每个 ABI 各存一份，而且在 APK 里是不压缩存储的，
        // x86/x86_64 两份合计 11.5MB，只有模拟器用得到，真机上永远不会加载。
        // ⚠️ 代价：纯 32 位的老机型装不上（minSdk 26 起这类机器已经很少）。
        ndk {
            abiFilters += "arm64-v8a"
        }

        // 界面只有中文。不限定的话 androidx / material 自带的几十种语言翻译
        // 全都会进 resources.arsc
        resourceConfigurations += listOf("zh", "en")
    }

    // ── 共存包 ────────────────────────────────────────────────
    //
    // `dev` flavor 只改 applicationId，让它和原版（com.quzhi.lite）以及官方
    // 趣智校园 App 都能同时装在一台手机上。
    //
    // 为什么要共存：三个 App 共用同一个 loginCode，任何一个登录都会把另外两个挤下线。
    // 想让官方 App 保持登录（你平时要用），同时验证第三个 App，就必须用不同包名。
    //
    // 产物：app-dev-debug.apk
    flavorDimensions += "dist"
    productFlavors {
        create("store") {
            dimension = "dist"
            // 原版，什么都不改。
            // ⚠️ 预置登录态**只在 dev 里注入**：写在 defaultConfig 会让两个 flavor
            //    都带上凭证（实测踩过一次，原版 APK 里真的能搜到 loginCode）。
            //    这里显式给空串，保证 store 包永远不含任何凭证。
            buildConfigField("String", "PREFILL_LOGIN_CODE", "\"\"")
            buildConfigField("String", "PREFILL_USER_ID", "\"\"")
            buildConfigField("String", "PREFILL_ACCOUNT_ID", "\"\"")
            buildConfigField("String", "PREFILL_PROJECT_ID", "\"\"")
            buildConfigField("String", "PREFILL_TELEPHONE", "\"\"")
        }
        create("dev") {
            dimension = "dist"
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // 名字带个后缀，方便在桌面上和原版区分
            resValue("string", "app_name", "趣智Lite·测试版")

            // ── 预置登录态（**只在这个 flavor 里**）─────────────
            //
            // 值来自 local.properties（在 .gitignore 里，不入库）。
            // 没配就是空串，PrefillAuth.available 为 false，行为同原版。
            //
            // 填法（linyu/local.properties，**这个文件不入库**）：
            //   QZXY_PREFILL_LOGIN_CODE=<登录后拿到的 32 位 loginCode>
            //   QZXY_PREFILL_USER_ID=<userId>
            //   QZXY_PREFILL_ACCOUNT_ID=<该板块的 accountId>
            //   QZXY_PREFILL_PROJECT_ID=<该板块的 projectId>
            //   QZXY_PREFILL_TELEPHONE=<手机号>
            //
            // ⚠️ 这里刻意只写占位符、不写真实值：本文件是公开的，
            //    任何真实账号/手机号都不该出现在源码里。
            val prefill = Properties().apply {
                if (localPropertiesFile.exists()) {
                    localPropertiesFile.inputStream().use(::load)
                }
            }
            buildConfigField("String", "PREFILL_LOGIN_CODE",
                "\"${prefill.getProperty("QZXY_PREFILL_LOGIN_CODE").orEmpty()}\"")
            buildConfigField("String", "PREFILL_USER_ID",
                "\"${prefill.getProperty("QZXY_PREFILL_USER_ID").orEmpty()}\"")
            buildConfigField("String", "PREFILL_ACCOUNT_ID",
                "\"${prefill.getProperty("QZXY_PREFILL_ACCOUNT_ID").orEmpty()}\"")
            buildConfigField("String", "PREFILL_PROJECT_ID",
                "\"${prefill.getProperty("QZXY_PREFILL_PROJECT_ID").orEmpty()}\"")
            buildConfigField("String", "PREFILL_TELEPHONE",
                "\"${prefill.getProperty("QZXY_PREFILL_TELEPHONE").orEmpty()}\"")
        }
    }

    val releaseSigningConfig = if (releaseSigningReady) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystorePath!!)
            storePassword = releaseSigningProperties.getProperty("KEYSTORE_PASSWORD")
            keyAlias = releaseSigningProperties.getProperty("KEY_ALIAS")
            keyPassword = releaseSigningProperties.getProperty("KEY_PASSWORD")
        }
    } else {
        logger.warn("未找到完整且有效的 release 签名配置，Release APK 将保持未签名；Debug 与测试任务不受影响。")
        null
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = releaseSigningConfig
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material)
    // material-icons-extended 已移除：全项目只用到它里面 2 个图标（扫码、账单），
    // 而 R8 并没有把它裁掉——release 包里实测仍残留 10660 个图标类。
    // 那两个图标已换成项目自己的 vector drawable（ic_qr_code_scanner / ic_receipt_long）。
    // material-icons-core 仍由 material3 传递进来，Icons.Default.* 照常可用。
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.mqtt.paho)
    implementation(libs.mqtt.android)
    implementation(libs.security.crypto)

    // 扫码
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.ui.tooling)
}
