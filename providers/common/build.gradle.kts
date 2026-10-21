plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android { namespace="com.ckchoi.elibrary.common"; compileSdk=35; buildToolsVersion="35.0.0"; defaultConfig{minSdk=26}; compileOptions{sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}; kotlinOptions{jvmTarget="17"} }
dependencies { implementation("androidx.core:core:1.15.0") }
