# hCaptcha SDK: работает с WebView и JSON, держим его классы целыми.
-keep class com.hcaptcha.sdk.** { *; }
-dontwarn com.hcaptcha.sdk.**

# Читаемые стектрейсы в отчётах: номера строк оставляем.
-keepattributes SourceFile,LineNumberTable
