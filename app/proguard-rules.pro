# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# WorkManager
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker { public <init>(android.content.Context, androidx.work.WorkerParameters); }

# AndroidX Security supplies consumer rules and is safe to shrink. Keeping all
# Tink classes also retains unrelated server-side key downloaders and dependencies.

# Missing annotations (not needed at runtime)
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**

# Google client models use @Key fields and public constructors through reflection.
# Based on google-http-client-assembly/proguard-google-http-client.txt.
-keepattributes Signature,RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-keepclassmembers class com.google.api.services.drive.model.** {
    public <init>();
}
