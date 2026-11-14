import java.util.Properties

plugins { id("com.android.application"); kotlin("android"); kotlin("plugin.compose") }

// Pleos Playground 키(Client ID·Secret). Git 제외 파일 car-care-agent/.env에서 읽어 debug 빌드에만 넣는다.
val pleosEnv: Map<String, String> = rootProject.file(".env").takeIf { it.isFile }?.readLines()
    ?.map { it.trim() }?.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
    ?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() } ?: emptyMap()
fun pleosKey(name: String) = "\"" + (pleosEnv[name] ?: System.getenv(name) ?: "") + "\""

// release 서명: 환경변수 CARECARE_SIGNING이 가리키는 properties 파일(storeFile, storePassword, keyAlias, keyPassword). 없으면 서명하지 않는다
val signing: Properties? = System.getenv("CARECARE_SIGNING")?.let { file(it) }?.takeIf { it.isFile }
    ?.let { f -> Properties().also { p -> f.inputStream().use { p.load(it) } } }

android {
    namespace = "app.carecare.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.carecare.agent"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.2-demo"
    }
    signingConfigs {
        if (signing != null) create("release") {
            storeFile = file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "PLEOS_CLIENT_ID", pleosKey("PLEOS_CLIENT_ID"))
            buildConfigField("String", "PLEOS_CLIENT_SECRET", pleosKey("PLEOS_CLIENT_SECRET"))
        }
        release {
            isMinifyEnabled = false
            if (signing != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("String", "PLEOS_CLIENT_ID", "\"\"")
            buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"\"")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module") } }
}
dependencies {
    implementation(project(":core:domain"))
    implementation(project(":integrations:pleos"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    val bom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
