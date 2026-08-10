# Retrofit reads endpoint and parameter annotations at runtime.
-keepattributes Signature,*Annotation*
-keep interface com.smartwatering.app.api.ApiService { *; }

# Moshi adapters are generated for these DTOs. Keep their model metadata and
# constructors so release shrinking cannot break request/response conversion.
-keep class com.smartwatering.app.data.** { *; }
