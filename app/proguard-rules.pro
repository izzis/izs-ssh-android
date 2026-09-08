# sshj uses reflection/ServiceLoader for SecurityProviderRegistrar + cipher factories.
# Without this, R8 release builds strip them and crash on connect (a common Android pitfall).
-keep,allowoptimization,allowobfuscation class org.apache.sshd.common.io.nio2.Nio2ServiceFactoryFactory { *; }
-keep,allowoptimization,allowobfuscation class net.schmizz.sshj.** { *; }
-keep,allowoptimization class org.bouncycastle.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
# Tink (via androidx security-crypto) references error-prone annotations that
# exist only at compile time; safe to ignore on Android.
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.crypto.tink.**
# SnakeYAML introspects java.beans on desktop JVMs; on Android that path is
# unused (we parse into plain Maps), so these warnings are safe to ignore.
-dontwarn java.beans.**
# Keep kotlinx.serialization model fields from being obfuscated
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
