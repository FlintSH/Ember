# Ember's release build relies on the consumer rules shipped by its libraries
# (kotlinx.serialization, OkHttp, Room, WorkManager, Coil and Compose).
# Keep stack traces readable in crash reports from release builds.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
