-keep class rikka.sui.** { *; }
-keep interface rikka.sui.** { *; }
-keep class rikka.rish.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep interface **.I* { *; }
-keep class **.I*$* { *; }

-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
    <fields>;
    <methods>;
}

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
-assumenosideeffects class rikka.sui.util.Logger {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
}
-assumenosideeffects class rikka.shizuku.server.util.Logger {
    public *** v(...);
    public *** d(...);
    public *** i(...);
    public *** w(...);
    public *** e(...);
}

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn android.**
-dontwarn com.android.**
-dontwarn androidx.**
-dontwarn sun.misc.**

# app_process entry
-keep class rikka.sui.server.Starter {
    public static void main(java.lang.String[]);
}

# system_server / bridge classes may be loaded reflectively or through native glue
-keep class rikka.sui.systemserver.** { *; }
-keep class rikka.sui.server.** { *; }

# Kotlin runtime used by sui.dex in app_process/system_server contexts
-keep class kotlin.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-dontwarn kotlin.**
