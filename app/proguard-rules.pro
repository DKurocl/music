# NewPipeExtractor (YouTube Music streams) runs YouTube's player script with Rhino, which reaches
# its own classes by reflection and names desktop-Java APIs Android does not have.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
# The extractor's protobuf messages are read reflectively.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
# Rhino's bytecode optimizer links through jdk.dynalink (desktop Java only); on Android Rhino interprets.
-dontwarn jdk.dynalink.**
