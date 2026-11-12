# 保留 Media3 / OkHttp 反射相关
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
# 资源里的自定义 View / Activity 由 Manifest 保留
-keep class com.freecast.tv.MainActivity { *; }
-keep class com.freecast.tv.LoginActivity { *; }
-keep class com.freecast.tv.PaymentActivity { *; }
-keep class com.freecast.tv.PushActivity { *; }
-keep class com.freecast.tv.FeedbackActivity { *; }
-keep class com.freecast.tv.ActivationActivity { *; }
# 泛型/注解信息裁剪，减小可读性
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
# 移除日志调用
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
