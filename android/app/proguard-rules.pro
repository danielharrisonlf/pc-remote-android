# ML Kit's ComponentDiscovery instantiates registrars with getDeclaredConstructor().
# Its consumer rule keeps the classes, but R8 9.2 can remove their no-arg constructors.
# See https://github.com/googlesamples/mlkit/issues/1018
-keep class * implements com.google.firebase.components.ComponentRegistrar { void <init>(); }
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar

# Keep these two tiny methods behind their runtime version guard. R8 otherwise
# inlines API 33 calls into MainActivity, which loads on Android 12 as well.
-keep,allowobfuscation class com.pcremote.BackNavigation { *; }
-keep,allowobfuscation class com.pcremote.BackNavigation$Api33 { *; }
