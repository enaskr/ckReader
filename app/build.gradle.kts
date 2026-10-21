import java.util.Properties
plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
val signProps=Properties().apply {
 rootProject.file("signing/release.properties").takeIf { it.isFile && !project.hasProperty("ckreaderUnsigned") }?.inputStream()?.use(::load)
}
// Opt-in only: enable on-device debugging without uninstalling an already
// installed Release APK, by using the SAME signing certificate for Debug.
// Default Debug builds still use Android's regular debug keystore.
val reuseReleaseSignerForDebug = providers.gradleProperty("ckreaderDebugUseReleaseSigning")
 .map { it.toBooleanStrictOrNull() ?: false }.getOrElse(false)
val liteOnly = providers.gradleProperty("ckreaderLiteOnly")
 .map { it.toBooleanStrictOrNull() ?: false }.getOrElse(false)
if(reuseReleaseSignerForDebug && signProps.isEmpty())
 throw org.gradle.api.GradleException(
  "ckreaderDebugUseReleaseSigning=true requires signing/release.properties and the original JKS.")
android {
 namespace="com.ckchoi.ckreader"
 compileSdk=35; buildToolsVersion="35.0.0"
 signingConfigs {
  if(signProps.isNotEmpty())create("release"){
   storeFile=rootProject.file(signProps.getProperty("storeFile"))
   storePassword=signProps.getProperty("storePassword")
   keyAlias=signProps.getProperty("keyAlias")
   keyPassword=signProps.getProperty("keyPassword")
   enableV1Signing=true;enableV2Signing=true;enableV3Signing=true
  }
 }
 // All editions keep the same package ID and signing certificate so an existing
 // installation can switch editions without losing its library or preferences.
 flavorDimensions += "screen"
 productFlavors {
  if(!liteOnly) {
   create("standard") {
    dimension="screen"
    manifestPlaceholders["ckreaderEink"]="false"
   }
   create("eink") {
    dimension="screen"
    manifestPlaceholders["ckreaderEink"]="true"
    versionCode=85
    versionNameSuffix="-eink"
   }
  }
  create("lite") {
   dimension="screen"
   manifestPlaceholders["ckreaderEink"]="false"
   versionNameSuffix="-lite"
   buildConfigField("boolean","LITE","true")
  }
 }
 buildFeatures { buildConfig=true }
 // Compress native .so files in the downloadable APK; Android extracts them
 // on installation. This trades installed disk footprint for smaller downloads.
 packaging { jniLibs { useLegacyPackaging=true } }
 defaultConfig{
  applicationId="com.ckchoi.ckreader"
  minSdk=26;targetSdk=35
  ndk { abiFilters += listOf("arm64-v8a") }
  versionCode=85;versionName="1.7.0"
  buildConfigField("boolean","LITE","false")
  testInstrumentationRunner="com.ckchoi.ckreader.ProcessLifecycleInstrumentation"
 }
 buildTypes{
  getByName("debug"){
   if(reuseReleaseSignerForDebug)
    signingConfig=signingConfigs.getByName("release")
  }
  getByName("release"){
   isMinifyEnabled=true
   isShrinkResources=true
   proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),"proguard-rules.pro")
   if(signProps.isNotEmpty())signingConfig=signingConfigs.getByName("release")
  }
 }
 testBuildType="release"
 compileOptions{sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
 kotlinOptions{jvmTarget="17"}
}
dependencies{
 implementation(project(":providers:common"))
 implementation(project(":providers:yes24"))
 implementation("androidx.core:core:1.15.0")
 implementation("androidx.documentfile:documentfile:1.0.1")
 implementation("androidx.recyclerview:recyclerview:1.4.0")
 implementation("androidx.work:work-runtime:2.10.2")
 implementation("org.yaml:snakeyaml:2.4")
 implementation("commons-net:commons-net:3.11.1")
 implementation("com.github.mwiede:jsch:0.2.23")
 implementation("com.tom-roush:pdfbox-android:2.0.27.0")
 implementation("org.apache.commons:commons-compress:1.27.1")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 implementation("com.google.android.gms:play-services-auth:21.6.0")
 implementation("org.jsoup:jsoup:1.21.2")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}

