# yt-dlp wrapper + ffmpeg loader and their JSON parser use reflection
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**

# NewPipe Extractor and its dependencies (reflection-heavy parsers)
-keep class org.schabi.newpipe.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.jsoup.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.jsoup.**
-dontwarn com.google.protobuf.**

# app classes (Compose + reflection-safety)
-keep class com.hdo.app.** { *; }

# ffmpeg AAR references a class from the removed yt-dlp library
-dontwarn com.yausername.youtubedl_android.**
