-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
	public static void check*(...);
	public static void throw*(...);
}

-assumenosideeffects class java.util.Objects{
    ** requireNonNull(...);
}

-keepnames class moe.shizuku.api.BinderContainer

# Missing class android.app.IProcessObserver$Stub
# Missing class android.app.IUidObserver$Stub
-keepclassmembers class rikka.hidden.compat.adapter.ProcessObserverAdapter {
    <methods>;
}

-keepclassmembers class rikka.hidden.compat.adapter.UidObserverAdapter {
    <methods>;
}

# Entrance of Shizuku service
-keep class rikka.shizuku.server.ShizukuService {
    public static void main(java.lang.String[]);
}

# Entrance of user service starter
-keep class moe.shizuku.starter.ServiceStarter {
    public static void main(java.lang.String[]);
}

# Entrance of shell
-keep class moe.shizuku.manager.shell.Shell {
    public static void main(java.lang.String[], java.lang.String, android.os.IBinder, android.os.Handler);
}

-assumenosideeffects class android.util.Log {
    public static *** d(...);
}

-assumenosideeffects class moe.shizuku.manager.utils.Logger {
    public *** d(...);
}

#noinspection ShrinkerUnresolvedReference
-assumenosideeffects class rikka.shizuku.server.util.Logger {
    public *** d(...);
}

# Keep Shizuku runtime classes untouched. Shizuku is loaded through reflection / external
# launcher paths and must keep names and signatures in release.
-dontobfuscate
-keep class moe.shizuku.** {
    <fields>;
    <methods>;
}
-keep class rikka.shizuku.** {
    <fields>;
    <methods>;
}
-keep class com.termux.shizuku.** {
    <fields>;
    <methods>;
}

-allowaccessmodification
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
