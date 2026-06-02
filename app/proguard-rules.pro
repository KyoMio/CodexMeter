# ---- kotlinx.serialization (scoped to the app package, minimal keep) ----
# Generated serializers are referenced when a class is serialized; without these,
# R8 can strip the Companion/$$serializer and serialization fails at runtime with
# "Serializer for class 'X' is not found".
-keepclassmembers @kotlinx.serialization.Serializable class com.kmnexus.codexmeter.** {
    *** Companion;
    *** INSTANCE;
}
-if @kotlinx.serialization.Serializable class com.kmnexus.codexmeter.**
-keepclassmembers class com.kmnexus.codexmeter.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kmnexus.codexmeter.**$$serializer { *; }

# Runtime annotation metadata used by serialization.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
