# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-keep class cn.lyric.getter.api.data.*{*;}
-keep class cn.lyric.getter.api.API{*;}

# -keep class yos.music.player.data.libraries.Music { *; }
# -keep class yos.music.player.data.libraries.PlayList { *; }
# -keep class yos.music.player.data.libraries.PlayStatus { *; }
# -keep class yos.music.player.data.libraries.MusicLibrary { *; }
# -keep class yos.music.player.data.libraries.PlayListBean { *; }
# -keep class yos.music.player.data.libraries.Folder { *; }
-keepnames class yos.music.player.data.libraries.** { *; }

-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keepattributes AnnotationDefault,RuntimeVisibleAnnotations

-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** e(...);
    public static *** i(...);
    public static *** v(...);    public static *** println(...);
    public static *** w(...);
    public static *** wtf(...);
}

-assumenosideeffects class java.io.PrintStream {
    public *** println(...);
    public *** print(...);
}

-keep class com.cormor.overscroll.core.OverScrollKt

# libkugou_server.so JNI shim：全限定类名/方法名必须与 .so 导出的
# Java_com_md3music_md3music_KugouApiService_native* 符号精确匹配，
# 禁止混淆/重命名/裁剪。
-keep class com.md3music.md3music.KugouApiService { *; }