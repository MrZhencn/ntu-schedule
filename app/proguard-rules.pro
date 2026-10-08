# 本项目的 Release 构建目前 minifyEnabled = false，这个文件暂时不生效。
# 它存在是因为 app/build.gradle 的 release 块引用了它 —— 缺文件时 AGP 会报
# 「Supplied proguard file ... does not exist」。留着它，并在下面写好真正需要
# 保留的规则，将来某天有人打开混淆时不至于一开就崩。

# 自写的极简 JSON 解析器靠反射了吗？没有 —— 全程手写遍历，无需 keep 规则。
# 需要担心的是以下几类：

# 1) 通过 XML 反射实例化的组件（AppWidgetProvider、BroadcastReceiver）。
#    它们在 AndroidManifest.xml 里按类名注册，混淆改名后系统就找不到它们了。
-keep class com.ntu.schedule.widget.ScheduleWidgetProvider { *; }
-keep class com.ntu.schedule.widget.WidgetRefreshReceiver { *; }

# 2) 小组件布局里通过 android:id 引用的 View 子类是纯框架类，无需保留。
# 3) OkHttp 自带 consumer rules；Compose 同样自带，不必手写。
#    这里刻意不抄一堆网上的「万能规则」—— 抄来的规则既没验证过，又会掩盖真正的问题。

# 保留行号，崩溃栈才有意义（配合 -keepattributes SourceFile,LineNumberTable）。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
