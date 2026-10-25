# Applied only to the Play release bundle. Debug and the download-page APK are not minified,
# so local and instrumented crash logs stay readable. Play deobfuscates its reports with mapping.txt.

-keep class dev.jdtech.mpv.** { *; }

# Desktop voice enumeration uses Java Sound. Those classes are not on Android, and nothing there calls them.
-dontwarn javax.sound.sampled.**
