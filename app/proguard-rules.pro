# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# dadb, libadb-android и sun-security-android не несут собственных consumer-правил (в отличие
# от usb-serial-for-android и conscrypt-android, у тех proguard.txt уже в самом AAR) и разбирают
# протокол ADB/крипто через собственные внутренние классы — сузить до конкретных точек входа
# без глубокого рантайм-тестирования всех ADB-путей рискованно, поэтому просто не трогаем эти
# пакеты, как и сам conscrypt делает для себя.
-keep class dadb.** { *; }
-keep class io.github.muntashirakon.adb.** { *; }
-keep class android.sun.** { *; }
