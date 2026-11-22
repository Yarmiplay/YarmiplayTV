# Applied only to the Play release bundle. Debug and the download-page APK are not minified,
# so local and instrumented crash logs stay readable. Play deobfuscates its reports with mapping.txt.

-keep class dev.jdtech.mpv.** { *; }
-keep class com.rikorose.deepfilternet.** { *; }
-keep class com.kaleyra.noise_filter.** { *; }

# Desktop voice enumeration and desktop-only WebRTC/ONNX classes are not used on Android.
-dontwarn javax.sound.sampled.**
-dontwarn dev.onvoid.webrtc.**
-dontwarn ai.onnxruntime.**

# smbj's event bus can filter with the Java EE expression language, and its SPNEGO
# authenticator references Kerberos. Neither is on Android, and shares here sign in
# with a username or as a guest, which does not use those classes.
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
