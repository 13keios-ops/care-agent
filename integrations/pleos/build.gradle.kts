plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "app.carecare.pleos"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    api(project(":core:domain"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 버전은 Pleos SDK 릴리스 노트와 Nexus artifact 일치 확인 (docs/sdk-capability-matrix.md)
    api("ai.pleos.playground:Vehicle:2.3.2")
    api("ai.pleos.playground:FusedLocation:1.1.3")
    api("ai.pleos.playground:NaviHelper:3.0.5")
    api("ai.pleos.playground:TextToSpeech:3.3.1")
    api("ai.pleos.playground:SpeechToText:3.3.1")
}
