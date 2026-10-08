# kotlinx.serialization / Retrofit 모델 보존
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class com.onion.macrolearn.**$$serializer { *; }
-keepclassmembers class com.onion.macrolearn.** { *** Companion; }
-keepclasseswithmembers class com.onion.macrolearn.** { kotlinx.serialization.KSerializer serializer(...); }
