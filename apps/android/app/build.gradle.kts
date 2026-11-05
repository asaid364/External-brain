plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
 id("org.jetbrains.kotlin.plugin.compose")
}
android {
 namespace = "app.liferecord"
 compileSdk = 35
 testBuildType = "syncSmoke"
 defaultConfig {
  applicationId = "app.liferecord"
  minSdk = 26
  targetSdk = 35
  versionCode = 4
  versionName = "1.3.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  manifestPlaceholders["appLabel"] = "生活记录"
 }
 buildTypes {
  create("syncSmoke") {
   initWith(getByName("debug"))
   applicationIdSuffix = ".syncsmoke"
   versionNameSuffix = "-sync-smoke"
   matchingFallbacks += "debug"
   isDebuggable = true
   manifestPlaceholders["appLabel"] = "生活记录·合成同步测试"
  }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true }
 testOptions {
  unitTests.isIncludeAndroidResources = true
 }
 sourceSets.getByName("test").java.srcDir("src/androidTest/java")
 packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
 implementation(project(":core"))
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
 implementation(platform("androidx.compose:compose-bom:2024.10.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.material:material-icons-core")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
 implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
 implementation("androidx.work:work-runtime-ktx:2.10.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.robolectric:robolectric:4.14.1")
 testImplementation("androidx.test:core:1.6.1")
 testImplementation("androidx.test.ext:junit:1.2.1")
 testImplementation("androidx.test:runner:1.6.2")
 testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
 testImplementation("androidx.compose.ui:ui-test-junit4")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
 androidTestImplementation("androidx.compose.ui:ui-test-junit4")
 debugImplementation("androidx.compose.ui:ui-test-manifest")
 add("syncSmokeImplementation", "androidx.compose.ui:ui-test-manifest")
}
