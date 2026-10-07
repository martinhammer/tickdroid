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

# Tink (transitive via androidx.security:security-crypto) references
# errorprone annotations that aren't on the runtime classpath.
-dontwarn com.google.errorprone.annotations.**
# Glance creates widget tap callbacks by reflection (getDeclaredConstructor().newInstance()) from
# the class name in the click intent. Glance's consumer rule keeps the classes but, under R8 full
# mode, not their no-arg constructors, so every widget tap would silently fail in release builds.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { <init>(); }

# Glance finds a widget's ids by the class name of its GlanceAppWidget (provider.canonicalName in
# GlanceAppWidgetManager.getGlanceIds). R8 merged our three widget classes into one, so all three
# got the same name: updateAll() on one kind then also redrew the other kinds' widgets with its
# own UI (a list showing the Single track's "Choose track"). Keep them distinct and named.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget
