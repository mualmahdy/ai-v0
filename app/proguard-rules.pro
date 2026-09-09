# ============================================================================
# AI-V0 Release keep rules — P0-4 FIX (audit 2026 §30)
#
# The previous file was the untouched AGP template while release minify was
# disabled — meaning NO real R8 configuration had ever been exercised. The
# rules below cover the reflection/serialization surfaces this codebase
# actually uses:
#   1. Room (KSP codegen + @Entity/@Dao — values()/valueOf of enums used in
#      persisted string form),
#   2. Moshi (reflection-based serialization of retrofit models),
#   3. Retrofit/OkHttp (generic signatures + annotations),
#   4. org.json hand-rolled round-trips (models are constructed field-by-field
#      so only the enum valueOf surfaces matter),
#   5. Kotlin coroutines/Metadata basics.
# ============================================================================

# --- Enum values()/valueOf() are used reflectively by Room/DAO layers -----
-keepclasseswithmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Retrofit (generic signatures erased by R8 otherwise) -----------------
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn javax.annotation.**
-dontwarn kotlin.Unit
-dontwarn retrofit2.KotlinExtensions
-dontwarn retrofit2.KotlinExtensions$*
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>

# --- OkHttp / Okio ---------------------------------------------------------
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Moshi (reflection over Kotlin data classes used by retrofit) ---------
-keep class kotlin.reflect.jvm.internal.** { *; }
-keepclassmembers class com.example.** {
    <init>();
    <init>(...);
}
-keep class com.example.**JsonAdapter { <init>(...); <fields>; <methods>; }
-dontwarn org.eclipse.jetty.**

# --- Room entities are referenced from generated impls (kept by Room's
#     own consumer rules); the string-persisted enums are the risk --------
-keepclassmembers class com.example.** {
    public static ** valueOf(java.lang.String);
    public static **[] values();
}

# --- Debuggability: keep source file + line numbers for crash reports ----
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- ONNX runtime ships its own consumer rules; silence its optional deps -
-dontwarn ai.onnxruntime.**

# --- Firebase (ships consumer rules; silence optional facets) -------------
-dontwarn com.google.firebase.**
