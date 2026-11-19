-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.kinetic.app.engine.** { *; }
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
