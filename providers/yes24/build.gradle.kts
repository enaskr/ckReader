plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace="com.ckchoi.yesss24"; compileSdk=35; buildToolsVersion="35.0.0"; defaultConfig{minSdk=26}; compileOptions{sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}; kotlinOptions{jvmTarget="17"} }
dependencies { implementation(project(":providers:common")); implementation("androidx.core:core:1.15.0"); implementation("androidx.recyclerview:recyclerview:1.4.0"); implementation("org.jsoup:jsoup:1.21.2"); implementation("org.apache.commons:commons-compress:1.27.1")
 implementation("com.tom-roush:pdfbox-android:2.0.27.0")
 implementation("com.google.mlkit:text-recognition-korean:16.0.1")
 testImplementation("junit:junit:4.13.2");testImplementation("org.json:json:20240303") }
