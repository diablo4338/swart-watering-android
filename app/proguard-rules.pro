# Retrofit reads endpoint and parameter annotations at runtime. Retrofit 2.9.0
# does not ship all rules required by R8 full mode, so keep them here.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-keep interface com.smartwatering.app.api.ApiService { *; }
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Suspend endpoint return types are stored in Continuation's generic argument.
-keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation
-keep,allowoptimization,allowshrinking,allowobfuscation class retrofit2.Response

# Moshi adapters are generated for these DTOs. Keep their model metadata and
# constructors so release shrinking cannot break request/response conversion.
-keep class com.smartwatering.app.data.** { *; }
