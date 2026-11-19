# Rust jadro vola metody podle nazvu tridy a metody (JNI), nesmi se prejmenovat ani odstranit.
-keep class cz.reallyfast.RustBridge { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
