-keep class dev.jdtech.mpv.** { *; }

# Play wants a deobfuscation file for the bundle. The release is not shrunk or renamed;
# R8 still writes mapping.txt, and crash traces stay readable.
-dontobfuscate
-dontshrink
-dontoptimize

# Desktop voice enumeration uses Java Sound. Those classes are not on Android, and nothing there calls them.
-dontwarn javax.sound.sampled.**
