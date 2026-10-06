# kotlinx.serialization keeps its generated serializers via @Serializable; R8
# strips the companion Companion.serializer() lookups without these.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class com.wolfpc.remote.data.model.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.wolfpc.remote.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp reaches for optional platform providers that are absent on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
