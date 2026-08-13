# Reown WalletKit ships Rust/uniffi native bindings over JNA; R8 must not strip
# them (needed for the R8 release build, alongside android.enableR8.fullMode=false).
-keepattributes *Annotation*
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { native <methods>; *; }
-keep class uniffi.** { *; }
-dontwarn uniffi.**
-dontwarn com.sun.jna.**

# Desktop-only classes referenced by transitive deps pulled in via Reown
# (Jackson's Java7 support; gRPC's JNDI name resolver) that do not exist on
# Android and are never reached at runtime.
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn javax.naming.**
