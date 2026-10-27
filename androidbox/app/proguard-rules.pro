# eddsa looks up its curve tables by name; keep the library whole (it is small).
-keep class net.i2p.crypto.eddsa.** { *; }
# curve25519-java picks its provider by class name (native first, then pure Java).
-keep class org.whispersystems.curve25519.** { *; }
-dontwarn org.whispersystems.curve25519.**
-dontwarn sun.security.x509.**

# libwebrtc calls into its Java classes from native code by name.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
