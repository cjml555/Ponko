// Yumenode AI · (c) 2026 visitor257 · MIT License · github.com/visitor257/Yumenode AI
import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.yumenode.ai"
    compileSdk = 36
    // 自编 stable-diffusion.cpp 所用的 NDK（CMake 产出的 libomp.so 与 libc++_shared 同源）
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.yumenode.ai"
        minSdk = 28
        targetSdk = 36
        versionCode = 10302
        versionName = "1.3.2"
        ndk {
            // LiteRT-LM 与 llama.cpp 的 native 都只跑 arm64 真机，去掉 x86_64 可减小 APK
            abiFilters += "arm64-v8a"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 签名：优先 keystore.properties（本地，gitignored），其次环境变量
    val keystoreProps = Properties()
    val keystorePropsFile = rootProject.file("keystore.properties")
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { input -> keystoreProps.load(input) }
    }
    val ksPathValue = keystoreProps.getProperty("storeFile")
        ?: System.getenv("KEYSTORE_PATH") ?: "~/Ponko/ponko-release.jks"
    val ksPasswordValue = keystoreProps.getProperty("storePassword")
        ?: System.getenv("KEYSTORE_PASSWORD") ?: ""
    val ksAliasValue = keystoreProps.getProperty("keyAlias")
        ?: System.getenv("KEY_ALIAS") ?: "ponko"
    val ksKeyPasswordValue = keystoreProps.getProperty("keyPassword")
        ?: System.getenv("KEY_PASSWORD") ?: ksPasswordValue

    signingConfigs {
        create("default") {
            // 相对路径按仓库根目录解析；~ 展开为 home
            storeFile = rootProject.file(ksPathValue.replace("~", System.getProperty("user.home")))
            storePassword = ksPasswordValue
            keyAlias = ksAliasValue
            keyPassword = ksKeyPasswordValue
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有口令才签名；否则产出未签名 APK（本地验证 / 之后重签）
            signingConfig = if (ksPasswordValue.isNotBlank())
                signingConfigs.getByName("default") else null
        }
    }

    // 自编 stable-diffusion.cpp：源码在 src/main/cpp（含内联 ggml），
    // 每次 assembleRelease 由 NDK 现场编译，产物直接进 APK，不再手工往 jniLibs 拷贝。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            // 自编的 libstable-diffusion.so（+ libponko_sd.so / libomp.so）依赖它
            pickFirsts += "**/libc++_shared.so"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Google LiteRT-LM Android API (loads & runs .litertlm models)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")
    // Coroutines (the streaming sendMessageAsync returns a Flow)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // GGUF (llama.cpp) local model runtime — full llama.cpp API incl. KV-cache reuse
    // (net.ladenthin:llama-android AAR requires minSdk 28, native = arm64-v8a)
    //
    // AAR local (local-maven/) con el MISMO API de Kotlin que el oficial,
    // pero con la libreria nativa recompilada con un backend de GPU.
    //
    // Vulkan: el .so se recompila con el backend dentro (GGML_VULKAN=ON) y
    // con dos parches que hacen falta en Android:
    //   1) el dispatcher de Vulkan-Hpp se re-inicializa con el device (si
    //      no, las funciones de dispositivo quedan a NULL y el proceso
    //      muere con SIGSEGV en 0x0 al cargar el modelo);
    //   2) se comprueba que el driver exponga vkGetBufferDeviceAddress
    //      (algunos lo anuncian pero no lo exportan).
    // El script reproducible esta en tools/build-llama-vulkan.sh.
    implementation("net.ladenthin:llama-android-vulkan:5.1.0")
    // Markdown rendering for AI replies (bold/lists/headings/code/tables/links)
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("io.noties.markwon:ext-strikethrough:4.6.2")
    implementation("io.noties.markwon:linkify:4.6.2")
    implementation("androidx.viewpager:viewpager:1.1.0")
    // 打标（Tagger）：ONNX Runtime，运行用户导入的 WD14 类 .onnx 模型（arm64-v8a）
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // PDF 文本抽取（对话发送 PDF）：Tom Roush 的 PDFBox Android 移植
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // 绘图（文生图）：自编的 stable-diffusion.cpp（arm64-v8a），源码在 src/main/cpp，
    // 由 NDK/CMake 现场编译出 libstable-diffusion.so + libponko_sd.so（JNI 桥）。
    // 相比现成 AAR，这里能直接控制线程数 / 采样器 / 调度器 / LoRA / 量化类型 / 取消。
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.1.5")
    testImplementation("androidx.test:runner:1.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
    // org.json real para los tests JVM: el stub de android.jar lanza
    // "Method put in org.json.JSONObject not mocked" y la persistencia de
    // los model sets (JSON) no se puede probar sin esto.
    testImplementation("org.json:json:20240303")
    // runTest para probar handlers con corrutinas en JVM.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
