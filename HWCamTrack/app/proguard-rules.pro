# TensorFlow Lite Task Library 通过反射加载自身的原生实现类，混淆后必须保留。
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.task.** { *; }

# ML Kit 的模型实现同样按类名反射装载。
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_pose** { *; }

# 保留行号，方便从崩溃堆栈定位。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
