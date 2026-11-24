# Keep all MockLocation classes and WebView JS bridge methods
-keepattributes JavascriptInterface
-keepclassmembers class com.mocklocation.assistant.MainActivity$MapJsBridge {
    @android.webkit.JavascriptInterface <methods>;
}
