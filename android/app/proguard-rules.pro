# gomobile's JNI runtime looks up these classes and members by their original names.
-keep class go.** { *; }

# The AAR classes.jar places the generated JNI entry points in this bridge package.
-keep class dev.tentacle.tailnet.bridge.tsbridge.** { *; }

# JSch loads algorithms, authentication implementations and loggers by config class name.
# Keep the entire package so upgrades cannot silently remove a reflectively loaded implementation.
-keep class com.jcraft.jsch.** { *; }

# Optional Windows Pageant SSH-agent integration requires JNA, absent on Android.
-dontwarn com.sun.jna.**

# Optional Log4j and SLF4J logger adapters are unused; the app does not configure these adapters.
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**

# Optional Bouncy Castle algorithms are probed by JSch; the app uses platform JCE providers.
-dontwarn org.bouncycastle.**

# Optional Kerberos/GSSAPI authentication is unavailable on Android; the app uses its SSH key.
-dontwarn org.ietf.jgss.**

# Optional junixsocket SSH-agent transport is unused by the app's direct SSH connection.
-dontwarn org.newsclub.net.unix.**
