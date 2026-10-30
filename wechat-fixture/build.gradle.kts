plugins { id("com.android.application") }
android {
    namespace = "com.tencent.mm"
    compileSdk = 35
    defaultConfig { applicationId = "com.tencent.mm"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "fixture-only" }
}
androidComponents { beforeVariants(selector().withBuildType("release")) { it.enable = false } }
