# SoloChat proguard 规则
#
# 目前 release 未开启混淆（isMinifyEnabled = false），这个文件是为将来开启时准备的。

# kotlinx.serialization 需要保留 @Serializable 类的伴生对象与序列化器
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.solochat.**$$serializer { *; }
-keepclassmembers class app.solochat.** {
    *** Companion;
}
-keepclasseswithmembers class app.solochat.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp 在部分 ROM 上会尝试引用可选依赖
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
