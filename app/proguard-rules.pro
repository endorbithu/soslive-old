# kotlinx.serialization - keep generated serializers for @Serializable DTOs and navigation routes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class info.soslive.stream.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class info.soslive.stream.**$$serializer { *; }

# Retrofit service interfaces (generic signatures are needed for suspend functions)
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# RootEncoder uses reflection-free code, but keep its public API used via JNI-less encoders
-keep class com.pedro.** { *; }
-dontwarn com.pedro.**
