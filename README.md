# 南通大学课表（ntu-schedule）

一个非官方的**南通大学**课表 App：用学号 + 密码从教务系统导入本学期课表，看周课表和今日课表，并在安卓桌面放一个「今日课表」小组件。

原生 Android（Kotlin + Jetpack Compose），不依赖任何第三方课表服务，数据直接从学校教务系统拉取并存在本机。

---

## 功能

| 功能 | 说明 |
| --- | --- |
| 教务导入 | 学号 + 密码登录统一身份认证，自动拉取本学期课表和校历。**课表页上默认选中的学期不可信**，会按候选顺序自动试几个（见下） |
| 记住账号密码 | 勾选后把学号密码用 Android Keystore 加密存在本机，下次导入直接预填（见下） |
| 今日课表 | 按上课先后列出今天的课，已结束的标灰「已下课」，显示精确到分钟的时间；点卡片看详情。**卡片样式刻意朴素，不做任何高亮** |
| 周课表 | **左右滑动一周一页**，切换周时保持纵向位置；整张网格是一张 14dp 圆角卡片（四周留 8dp、带 0.5dp 外框），连堂课合并成一个**圆角卡片**（8dp 圆角，四周内缩 1.5dp / 1dp，网格线从缝里透出来）；可一键跳转任意周、一键回到本周。**每门课一种底色是全 App 唯一的课程突出方式**（见下） |
| 课程详情 | 周课表里点格子、今日课表里点卡片，弹出时间 / 星期 / 地点 / 教师 / 周次 / 教学班 / 校区 / 课程代码；时间是按**那周所在月份**算的夏令或冬令 |
| 自定义背景 | 全局换背景：默认（**纯白**）/ 8 种纯色 / 6 组渐变 / 相册选图，可加 0–80% 遮罩（**默认 0%，不压暗**）；顶栏、底栏、卡片、空格子都会透出背景 |
| 上课提醒 | 每节课开始前 1 小时，像 QQ / 微信消息那样从屏幕顶部**弹出横幅**（课名 / 时间 / 地点），只排未来 7 天并每天自动续排 |
| 提醒自检面板 | 逐项检测通知权限、提醒样式、电池优化、精确闹钟，并一键跳到**对应厂商**的设置页；带一个「发测试横幅」按钮当场验证 |
| 桌面小组件 | 「今日课表」小组件，显示今天每节课的时间、课名、地点；时间分两行显示**永不截断**，超过 6 门课时末行提示「还有 N 门课」；跨天自动刷新。**不限机型** —— 有些桌面不允许 App 主动放，面板里按机型给出手动添加步骤，见下 |
| 崩溃报告 | 崩了就自动把现场写成本机的纯文本报告：机型 / 系统 / App 版本 / 完整堆栈 / **崩溃前 40 条操作轨迹**。菜单 →「自检与诊断」里可导出、复制、清空；报告里的学号会被抹成 `******` |
| 自检与诊断 | 一键检查运行环境、本地数据、桌面小组件、上课提醒、崩溃报告五个方面，逐项给 `✓ / △ / ✕ / •`，结果可复制成一段文字 |

明确**不做**的事（保持范围收敛）：ICS 导出、多学期切换、手动增删课、主题换色、课前自定义提醒时长（固定 1 小时）。

### 为什么几乎没有高亮

课表本身信息密度已经很高，再加高亮只会让眼睛没有落点。所以除了**周课表里「每门课一种底色」**这一处，其余视觉强调全部去掉了：

| 去掉的 | 原来是什么样 | 现在 |
| --- | --- | --- |
| 周课表「今天那一列」 | 整列铺一层淡蓝底、表头那格填主色 | 7 列完全一样。表头整行有**一层统一的淡底**，但那是「这是标签区」的分区底色（左侧时间轴同色），七天一视同仁，没有单独标今天 |
| 今日课表「正在上」 | 整张卡片变主色 + 一枚「正在上」徽标 | 所有卡片长得一样；只有已结束的课右侧有个灰色的「已下课」 |
| 今日课表左侧色条 | 卡片左边一条 4dp 彩色竖条 | 去掉，卡片从 14dp 内边距直接开始 |
| 课程详情标题色块 | 标题左侧一个 14dp 方块，色同周课表格子 | 标题只有课名 |
| 空格子与有课格子 | 空格子一个底色、有课格子另一个 | 共用同一个底色，网格里**只有文字、0.5dp 细线和内缩的圆角色块**，没有阴影也没有渐变；唯一的边框是整张网格最外面那一圈 |

**保留**的：周课表格子的课程底色（由课程名稳定派生，同一门课永远同色，重装后也不变，见 `core/Schedule.kt` 的 `colorIndex`）。另外顶部工具栏那个「本周」小徽标、周次跳转框里的当前周/选中周标记属于**导航标记**，不是课程高亮，保留。

`ui/theme/Theme.kt` 里的 12 色课程色板仍然只服务周课表格子这一处 —— 今日课表卡片、详情弹窗都不再取色。

### 周课表翻页为什么能跟手

左右划动是最容易掉帧的交互 —— 拖动的每一帧都要重组、测量、绘制整整一页（7 天 × 最多 12 节）。第一版有肉眼可见的顿挫，逐项查下来是四件事，都改掉了：

- **不在组合根读页号**。原来 `WeekScreen` 里读了 `pagerState.currentPage` 来算「现在显示第几周」和日期区间 —— 一读，整个 `WeekScreen`（含上百个格子）就订阅到了页号上，翻一页重组一整棵树。现在页号只在两个地方读：工具栏自己的组合里（翻页只重组工具栏），以及点「跳转」按钮时（不在组合里，不订阅）。
- **整页网格只用一条 `drawBehind` 画线**。原来每个格子各自画顶边和左边，一页是 104 个 `drawBehind` + 96 个 `background`，拖动时每帧要重跑两百多个绘制节点。网格是规则的：横线在 `表头高 + n × 单位高`，竖线按 7 等分，算一次就够。这些线画在父节点上、**在课程色块下面** —— 课程块改成圆角内缩之后，线正好从缝里透出来（见下）。表头/时间轴的分区底色和网格外框后来也塞进了这同一条里，**一个绘制节点都没多**。
- **没课的连续节次合并成一段**。一页 7 天 × 12 节 = 84 个格子，其中绝大多数是空的；合并后一页只剩 20 多个节点，而且空格子退化成一个纯粹的 `Spacer`，不产生任何绘制。
- **点击回调 `remember` 成稳定实例**。原来 `onCourseClick = { detail = it }` 每次重组都是新 lambda，会把无效重组沿 `WeekPage → BlockCell → CourseCellContent` 一路传到每一个格子（等于把每个格子的 `.clickable` 都换掉）。

另外去掉了 `beyondViewportPageCount = 1`：默认只组合「看得见的页 + 正在拖进来的那一页」，设成 1 会让三页同时存在，划动时白白多量一页。

缓存的 key 也收紧了：`CourseBlocks.ofDay` 的结果按 `(本周课程, 首节, 末节)` 缓存，划走再划回来不重算。

### 课程格子为什么是圆角，以及为什么要内缩

课程块是 **8dp 圆角 + 四周内缩**（左右各 1.5dp、上下各 1dp），看起来像浮在网格上的一叠卡片。三个尺寸都在 `ui/WeekScreen.kt` 顶部：

```kotlin
private val COURSE_CORNER   = 8.dp
private val COURSE_INSET_H  = 1.5.dp
private val COURSE_INSET_V  = 1.dp
```

**内缩不是为了好看，是必须的。** 网格线由整页那条 `drawBehind` 画，位置是「表头高 + n × 单位高」和「7 等分」，本身只有 `GRID_STROKE = 0.5dp` 粗。如果色块还铺满整格，圆角处色块边缘正好压在网格线上 —— 0.5dp 的线会被吃掉一半，剩下 0.25dp，横线竖线看起来粗细不匀、像是画歪了。留出 1dp 以上，线就能完整露出来。

**`clip` 必须写在 `background` 和 `clickable` 之前**：

```kotlin
.padding(horizontal = COURSE_INSET_H, vertical = COURSE_INSET_V)
.clip(RoundedCornerShape(COURSE_CORNER))
.background(courseColor(course.colorIndex))
.clickable(onClick = onClick)
.padding(horizontal = 3.dp, vertical = 2.dp)
```

只用 `background(color, shape)` 也能画出圆角，但那样圆的只是背景，`clickable` 的水波纹仍是矩形 —— 点下去涟漪会从四个角溢出色块外面。`clip` 在链条上更靠前，两者就都被裁进同一个圆角里了。

连堂课仍然是**一个**卡片（`CourseBlocks` 合并的结果），圆角只出现在这一整块的外围；同一格真有多门课时（教务数据里节次区间重叠，正常不该出现）才上下分开放，各是一个圆角卡片。

打开 `docs/安装与验证.md` 里「课程格子是圆角」那几条可以逐项核对。

### 网格本身为什么做成一张卡片

整张网格四周留 8dp、外圈 14dp 圆角、加一条 0.5dp 外框，看起来是浮在页面上的一张表：

```kotlin
private val GRID_MARGIN = 8.dp
private val GRID_CORNER = 14.dp
private const val GUTTER_ALPHA    = 0.7f   // 表头 / 时间轴的分区底色
private const val GRID_LINE_ALPHA = 0.6f   // 格内细线相对外框的浓淡
```

**为什么要留 8dp。** 不设自定义背景时页面和卡片都是白的，全靠外框交代两者边界；设了图片或渐变之后，这 8dp 的缝里透出来的就是用户自己的背景，卡片感更明显。圆角取 14dp，和今日课表卡片、设置页卡片是同一个数。

**表头和时间轴是一块「标签区」。** 日期行说明每一列是谁、节次栏说明每一行是第几节 —— 两者用同一层 `surfaceVariant` 冲淡到 0.7 的极淡底色。7 天和 12 节一视同仁，**没有「今天」也没有「当前节次」**，和「几乎没有高亮」那条原则不冲突。

**三档浓淡，各司其职。** 同一个 `outlineVariant` 颜色，分三种用法：

| 用在哪 | 浓度 |
| --- | --- |
| 卡片外框 | 全浓度 |
| 骨架：表头底边、时间轴右沿 | 全浓度 |
| 格内：节次分隔线、星期分隔线 | `GRID_LINE_ALPHA = 0.6` |

**节次横线不穿过时间轴。** 时间轴有自己的底色，是一栏独立的地带，不是网格的一部分；横线从它的右沿起画。星期分界线则相反 —— 从卡片顶起、**穿过表头**，这样「三」这个字和它下面那一列才对得上。

**外框要内缩半条线宽。** `drawRoundRect(..., style = Stroke(stroke))` 是以路径为中心往两侧各画一半的，路径贴着卡片边的话外侧那一半会被 `clip` 切掉，框看起来只剩一半粗：

```kotlin
val inset = stroke / 2f
val cornerPx = GRID_CORNER.toPx() - inset
drawRoundRect(
    color = gridColor,
    topLeft = Offset(inset, inset),
    size = Size(size.width - stroke, size.height - stroke),
    cornerRadius = CornerRadius(cornerPx, cornerPx),
    style = Stroke(stroke),
)
```

**最下面那条线不用补。** 原来网格底部靠一个 `Spacer(0.5dp)` 收口（横线只画每行的顶边，最后一行没有下线），现在卡片外框本身就是那条线，`Spacer` 去掉了。

**分区底色不能被外框盖住，也不能盖住外框。** 这三样都在同一条 `drawBehind` 里按固定顺序画：先两块底色 → 再骨架和格内线 → 最后外框。`drawBehind` 画在子节点之前，所以顺序完全由代码控制，课程色块永远在最上层。

### 主题为什么钉死浅色

`NtuScheduleTheme(darkTheme = false)`，不跟随系统深色模式。默认背景是主题的 `surface`（浅色下就是纯白 `#FFFFFF`），而状态栏/导航栏图标的明暗是另一套开关 —— 如果 App 跟随系统变深、图标却按浅色主题算，就会糊在底色上看不见；反过来也一样。与其去同步这两套开关，不如两边都钉死：`MainActivity` 里用 `SystemBarStyle.light(...)`（含义是「用深色图标」）覆盖不带参数的 `enableEdgeToEdge()`（它按**系统**深色模式决定图标明暗）。

`DarkColors` 和 `darkTheme` 参数都保留着，要做深色模式时改回 `isSystemInDarkTheme()` 再把 `SystemBarStyle` 换成 `auto` 即可。

### 自定义背景是怎么做的

四层背景（`core/Appearance.kt` 只管数据与解析，`ui/AppBackground.kt` 只管画），通过 `LocalAppearance` 这个 `CompositionLocal` 下发，所以**任何界面都能拿到当前背景，不需要层层传参**。

- **图片会被复制进 App 私有目录**（上限 12MB），而不是记住相册的 `content://` URI。URI 的读取权限是临时的，用户也可能随时删掉原图 —— 那时背景会变成一片黑。
- **复制完立刻验一次「这真的是一张能解码的图」**（`AppearanceStore.decodable()`，只读文件头、不读像素）。不验的话，选到 HEIC（部分机型的默认拍照格式，API 24–27 的 `BitmapFactory` 不认）或云盘占位文件时，字节数看着正常、复制也成功，但解码返回 `null` —— 背景层那时什么都不画，整屏只剩窗口底色，用户看到的就是「设了图片却变成一片纯白」。启动读存档时同样验一次（`imageDecodable()`），文件烂了就退回默认背景而不是留一片白。
- **遮罩默认 0%，不压暗**：用户挑的颜色/图片就是他想要的亮度；先盖一层黑等于把「选」这件事白做了，还得先找到滑块才能看回原样。换图片时遮罩也一并归零（`Appearance.withImage()`）—— 上一张图压到 60% 不代表这一张也要压到 60%。
- **不用 Coil / Glide**：只有一张本地图，`decodeSampled` 用 `inJustDecodeBounds` + `inSampleSize` 把图缩到屏幕宽度就够了，不值得为此多一个依赖。
- **解码的 `remember` key 是文件名 + 屏宽，不是整个 `Appearance`**：否则拖遮罩滑块时每一帧都会重新解码一次大图。
- **「默认」背景是不透明的主题 `surface`（浅色主题下就是纯白 `#FFFFFF`），不是透明**：早先这一层画的是全透明，于是整屏底色实际由 `android:windowBackground` 决定 —— 那是个带蓝灰调的 `#F7F8FA`，看起来就不像白底；而且深色主题下窗口底色仍是浅的，文字会糊在浅灰上。现在底色由主题决定，`window_background` 只是对齐它以免冷启动闪色（深色对齐值放在 `res/values-night/colors.xml`）。
- **`panelColor()` 让面板变半透明**：顶栏、底栏、今日卡片、周课表网格卡片在设了背景后都用 `surface.copy(alpha = 0.9f)`（周课表那 8dp 留边透出来的就是它）。不透明色块会把背景切成一格一格，用户会觉得「设了等于没设」。课表格子**不参与**半透明 —— 课名会看不清。
- **`Appearance.DEFAULT` 必须写在两个预设常量之后**：构造参数默认值是 `PRESET_SOLIDS[0]`，而伴生对象是顺序初始化的；放在上面时 `PRESET_SOLIDS` 还是 `null`，构造默认值直接抛 NPE，整个类初始化就废了（这个坑被 `AppearanceTest` 全套 17 个测试同时失败暴露出来）。

### 上课提醒是怎么排的

上课时刻按**那一天的月份**取夏令/冬令表，所以提前一小时也必须按那天的表算：同一个周四 6-7 节的课，9 月 24 日的提醒在 13:00，10 月 1 日就变成 12:30。

排程只覆盖**未来 7 天**（一次排满 19 周会有上千个闹钟，而且课表随时会变），另有一个每日闹钟把窗口往前滚一天。

每次重排前先把 224 个固定闹钟槽位全部取消，再按「天序号 × 32 + 当天第几条」重新登记 —— 猜哪些槽位用过一定会漏，漏了就会收到已经上完的课的提醒。

闹钟优先用 `setExactAndAllowWhileIdle`（到点准时弹），拿不到 `SCHEDULE_EXACT_ALARM` 就退回 `setAndAllowWhileIdle` —— 晚几分钟也要发出来，绝不干脆不提醒。

### 横幅（像 QQ / 微信那样弹出来）是怎么保证的

Android 上「从屏幕顶部弹出来」叫 **heads-up 通知**，需要**同时**满足三个条件，少一个就只会静默躺进通知栏：

1. 渠道重要性 ≥ `IMPORTANCE_HIGH`；
2. 通知带声音或有震动（只满足第 1 条是不够的，Android 7 上尤其明显）；
3. 锁屏可见性为 `VISIBILITY_PUBLIC`，否则锁屏时看不到内容。

还有一个很隐蔽的坑：**通知渠道一旦创建，除了名字和描述，其它一切都不能再改** —— 重要性、声音、震动、锁屏可见性全部被系统冻结，再调 `createNotificationChannel` 只会被忽略。所以 1.x 建的旧渠道没法修补，只能换一个 id（`class_reminder` → `class_reminder_v2`）重建，并把旧渠道删掉。

国产 ROM 还会额外加几道闸，而且**代码申请不到，只能由用户在系统设置里打开**：

| 闸门 | 不打开的后果 | App 能做的 |
| --- | --- | --- |
| 悬浮通知 / 横幅通知开关 | 通知只进通知栏 | 引导到渠道设置页 |
| 自启动 / 后台运行白名单 | 清理后台后到点不提醒，甚至完全不提醒 | 按厂商跳到**正确的那个**设置页 |
| 电池优化 | 闹钟被推迟几十分钟 | 拉起系统白名单确认对话框 |
| 精确闹钟（Android 12+） | 提醒晚几分钟（不影响弹不弹） | 引导到「闹钟和提醒」页 |

菜单里的「上课提醒」就是这些开关的自检面板：逐项 `✓ / ✕ / •` 标出状态，每项都能一键跳到对应的设置页，改完返回时自动重新检测。里面还有一个「发测试横幅」，当场就能验证到底弹不弹得出来 —— 比等明天早上第一节课靠谱得多。

各家的自启动页面包名类名完全不同，同一个品牌在不同系统版本上还换过，所以 `OemSettings` 每家给多个候选逐个试，都打不开就退到应用详情页。厂商识别是纯函数，有单元测试钉着（**荣耀必须排在华为前面**，否则会跳到不存在的华为页面）。

### 为什么有的手机「只能手动加小组件」

先把结论说清楚：**小组件本身就是通用的 Android 桌面组件（AppWidget），不挑机型** —— 小米、OPPO、一加、vivo、华为上跑的是同一个 APK、同一个 `ScheduleWidgetProvider`。差别只在**怎么把它放到桌面上**。

早期版本里，菜单 →「添加桌面小组件」调的是 `AppWidgetManager.requestPinAppWidget()`。这个 API 各家桌面实现得很不一样：

| 桌面 | 按「添加桌面小组件」之后 |
| --- | --- |
| 小米 / Redmi | 不弹确认框，直接静默加到当前页 |
| OPPO / ColorOS、三星 | 弹确认框，空间不够会自动开新一页 |
| 华为 / 荣耀 | 弹确认框，但空间不够**不**自动开新页，只提示「当前页面空间不足」 |
| vivo / iQOO | **完全没有反应** —— 除非接入 vivo 的原子组件 SDK 并把组件上架到 vivo 的组件平台 |
| 部分第三方桌面 | `isRequestPinAppWidgetSupported` 返回 false，这个 API 直接不生效 |

旧代码在「不支持」时是**静默 return**，一句提示都没有。于是在小米上点一下真的加上了，在 OPPO / vivo 上点一下毫无动静 —— 用户很自然地得出结论「只有小米能用」。再叠加各家菜单叫法完全不同（小米「添加小部件」/ ColorOS「卡片」/ OriginOS「原子组件」/ 华为「服务卡片」/ 魅族「添加工具」），拿小米的路径去别的手机上找，本来就找不到。

现在改成：

- **不支持就明说**，并按当前厂商给一段能照着点的步骤（`widget/WidgetPinner.kt` 的 `manualSteps()`，纯函数、有单测钉着）；支持才给「自动添加到桌面」按钮 —— 不给一个按了没反应的按钮，正是以前让人误以为「只支持小米」的根源。
- **加了之后真的去核对**：调用前先数一次 `getAppWidgetIds().size`，返回后再数一次，只有真的多出一块才说「已放到桌面上」。`requestPinAppWidget` 的返回值只代表「支持调用」，华为上连那个成功回调都不会触发。
- 面板里同时显示**当前桌面是哪个启动器**（用 `ACTION_MAIN` + `CATEGORY_HOME` 解析出来的包名）和**桌面上已经有几块小组件**。
- 顺带补了 `previewLayout` / `previewImage`：没有预览图时，系统在小组件选择器里显示的是一张用 `initialLayout` 渲染出来的占位图，看起来像「这个组件是坏的」。
- `minHeight` 从 130dp 收到 **110dp**。Android 12+ 用 `targetCellHeight = 3` 按格子摆，但 Android 11 及以下不认这两个属性、只按 `minHeight` 算，官方换算是 `格数 × 66 − 15` —— 130dp 折算下来要 3 行，很多已经排满的桌面根本腾不出 3 行，拖都拖不进去。

### 崩溃报告是怎么抓到崩溃的

- 装在 **`NtuApp : Application`** 里（`AndroidManifest.xml` 的 `android:name`），而不是 Activity 里 —— 「一打开就闪退」那类崩溃发生在任何 Activity 创建之前，装在 Activity 里就等于什么都没装。
- 写完报告后**再委托给原来的 handler**，系统的崩溃弹窗照旧出现。绝不吞异常，也绝不假装没崩。
- 一份报告分四段：**崩溃现场**（本地时间 + 时区偏移、进程/线程、进程存活时长、堆占用）、**异常**（完整堆栈原文，一个字不改）、**运行环境**（App 版本、系统版本与 API、机型、ABI、语言、包名与是否 debug）、**崩溃前发生了什么**（最近 40 条操作轨迹，新 → 旧）。
- 「面包屑」是内存里的环形缓冲，只记**发生了什么**、不记内容，例如「开始登录并导入课表」→「导入成功，共 14 条课程安排」。它不落盘，所以不会拖慢任何操作。
- **脱敏**：先把学号按字面替换成 `******`，再把 10 位（学号）与 11 位（手机号）的连续数字整段替换掉。**刻意不匹配 13 位时间戳和 8 位日期** —— 否则报告里满屏都是星号，等于白写。
- 最多保留 10 份，更旧的自动删；写盘先写 `.tmp` 再 rename，断电也不会留下半份文件。
- 抓不到的两种：C/C++ 层的崩溃（在 `/data/tombstones`）和 ANR（在「开发者选项 → 错误报告」）。面板里直接写明，不让用户以为「没报告就是没崩」。
- 导出用 `FileProvider` + `ACTION_SEND`，并且**只暴露 `filesDir/crash/` 这一个目录**（`res/xml/file_paths.xml`）—— `schedule.json` / `credentials.json` 一个都不开。不实现「写进下载目录」是故意的：那要申请存储权限，为一份文本不值得。
- 面板里有个「模拟一次崩溃记录」按钮，走**完全同一条写盘路径**，只是不真的崩。不然想验证导出功能，得先想办法把 App 弄崩。

### 记住账号密码是怎么存的

只有勾选「记住账号密码」才会保存，密文由 **Android Keystore** 的 AES/GCM 密钥加密（密钥不离开设备安全存储，App 自己也导不出来）。GCM 自带完整性校验，密文被改动一个 bit 就直接解不开 —— 不会解出乱码当密码去提交，从而白耗一次账号锁定机会。

如果这台设备拿不到 Keystore（极少数定制 ROM），开关会置灰并说明原因，**绝不退化成明文保存**。菜单里的「忘记保存的密码」可以随时清掉它。

---

## 导入时怎么选学期（以及为什么要试好几个）

课表页上有两个下拉框：学年 `xnm`、学期 `xqm`。**它们标着 `selected` 的那个值不可信。**

这不是推测。实测过一套正方教务：服务端给学生渲染出来的是 `xnm=2025`（`2025-2026`）、`xqm=12`（第 2 学期），而这个学生 2026 年才入学 —— 拿这组参数去查，`kbList` 是空的，界面上就显示「该学期没有查询到课程」。真正有课的是 `xnm=2026` / `xqm=3`。

翻遍那个页面与接口，**服务端确实没有给出可信的「当前学期」**：

- 页面里没有任何脚本会改写这两个 `selected`（grep `var xnm` / `xnm\s*[:=]` / `qsxqj` / `xkxqsfkz` / `xskbsfxstkzt` 全部 0 命中），所以它就是模板写死的，不存在「浏览器用 JS 纠正」这回事。
- 课表接口返回的 `xsxx.XNM` / `XQM` 只是**把你问的参数原样回显**：问 2025/12 就回 `XNM="2025"`，问 2026/3 就回 `XNM="2026"`，连 `KCMS`（课程门数）都跟着查询变。
- 把「空响应」与「有课响应」的所有顶层标量逐字段对比（`qsxqj`、`sjfwkg`、`xskbsfxstkzt`、`zckbsfxssj`、`kblx`、`sfxsd`、`jfckbkg`、`xkkg`、`xnxqsfkz` …）**全部相同**，没有一个字段能区分当前学期。
- 登录落地页里 `学年` / `学期` / `xnm` **各 0 次命中**。

所以 `core/TermPicker.kt` 的做法是**排候选、逐个试**，而不是信页面：

1. 按本机日期算出来的当前学期（9 月–次年 1 月算第一学期，2 月–8 月算第二学期）
2. 课表页给的默认值（南通大学这类是对的，排第二不碍事）
3. 同一学年的另一个学期
4. 上一学年的第二学期，再上一学年的第一学期（新学期还没排课时的兜底）

`ScheduleApi.fetchFirstAvailable` 只打开一次课表页（各候选共用同一次会话与 Referer），然后逐个请求，**第一个真的有课的就算数**。遇到会话失效或 HTTP 错误就立刻停 —— 那不是换个学期就能好的事，继续试只会让用户干等，还多撞几次学校的账号风控。

代价是：**新学期还没排课时，会显示上一学期的课表。** 所以导入成功的提示与刷新提示里一定要带上最终用的学期名（「导入成功，共 N 条课程安排（2026-2027 学年 第 1 学期）」）；一个学期都没查到时，提示里也会把试过的学期列出来。这背后有个教训：原来那句「该学期没有查询到课程（2025 学年，xqm=12）」把用户看懵了 —— 他不知道 App 为什么查这一学期，排查只能靠猜。

---

## 作息时间：最容易做错的地方

学校官网《[教学作息时间表](https://www.ntu.edu.cn/2018/0228/c763a35845/page.htm)》规定：

- **5 月–9 月执行夏令时间**：第 6 节 14:00-14:40，第 12 节 20:40-21:20
- **10 月–次年 4 月执行冬令时间**：第 6 节 13:30-14:10，第 12 节 20:10-20:50
- 第 1–5 节两季相同：07:50 / 08:40 / 09:35 / 10:30 / 11:20 开始

关键点：**切换依据是日历月份，不是学期**。所以秋季学期会在 **10 月 1 日中途切换** —— 同一节课在 9 月和 10 月的上课时间不一样。

很多第三方课表脚本写成「按开学月份选一整套时间表」，会让 10 月以后第 6–12 节全部偏 30 分钟。本项目按**每节课的具体日期所在月份**取表（见 `core/ClassTimes.kt` 与 `ClassTimes.SUMMER_MONTHS`）。

---

## 构建

### 环境

- JDK 17
- Android SDK（compileSdk 34、build-tools 34.0.0）
- Gradle 8.6 —— 项目里**带了 wrapper**（`gradlew` / `gradlew.bat`），不用自己装 Gradle
- 首次构建**必须联网**（Gradle 发行包 + Compose BOM + okhttp 都要下载）

构建：

```bash
# Linux / macOS
./gradlew assembleDebug

# Windows
gradlew.bat assembleDebug
```

国内网络的两种卡法，以及各自的解法：

- **卡在 `Downloading https://services.gradle.org/distributions/gradle-8.6-bin.zip`**（wrapper 下发行包）—— 把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换成镜像，文件里已经写好了两个可用的地址（腾讯云 / 阿里云，两个都实测过，与官方包同样大小 132,788,867 字节）。本机已经装了 Gradle 8.6 的话，也可以直接 `gradle assembleDebug` 绕过 wrapper。
- **卡在下载依赖**（Compose BOM / okhttp）—— `settings.gradle` 里已经预置了阿里云镜像，见该文件开头的注释。

产物：`app/build/outputs/apk/debug/app-debug.apk`（applicationId 带 `.debug` 后缀，可与正式版共存）。

装到手机上：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里要有一行 `sdk.dir=...` 指向你的 Android SDK。Android Studio 打开项目时会自动生成；命令行构建时需要自己写一个（该文件已在 `.gitignore` 里，不会被提交）。

<details>
<summary>开发时用的原始命令行（可跳过）</summary>

```powershell
$env:JAVA_HOME = "D:\toolchain\jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME = "D:\toolchain\gradle_home"
$env:ANDROID_HOME = "D:\toolchain\android-sdk"
$env:ANDROID_SDK_ROOT = "D:\toolchain\android-sdk"
Set-Location "D:\ntu-schedule"
& "D:\toolchain\gradle-8.6\bin\gradle.bat" :app:assembleDebug --console=plain
```

</details>

### 中文路径的坑

AGP 拒绝在含非 ASCII 字符的路径下构建，会直接报：

```
> Failed to apply plugin 'com.android.internal.application'.
   > Your project path contains non-ASCII characters.
```

**这个仓库本身不涉及** —— 把项目放在纯英文路径下就能直接构建。这份代码的开发者在 `D:\项目\deepseek v4 pro\南通大学课表App` 下工作，所以当时做了两件事：`gradle.properties` 里加 `android.overridePathCheck=true`，再建一个 ASCII 软链接（junction）用于构建：

```
cmd /c mklink /J "D:\ntu-schedule" "D:\项目\deepseek v4 pro\南通大学课表App"
```

发布版里这两样都已经去掉了：`android.overridePathCheck` 从 `gradle.properties` 删掉了，构建也换到了纯英文路径。**如果你也碰到这个报错，最省事的办法是把项目挪到英文路径**，而不是去关掉那个检查。

---

## 运行单元测试

```bash
./gradlew :app:testDebugUnitTest      # Windows: gradlew.bat :app:testDebugUnitTest
```

控制台中文可能被 GBK 弄乱（Windows），看 XML 报告更准：`app/build/test-results/testDebugUnitTest/*.xml`。

当前 **232 个测试，16 个测试类，全部通过**：

| 测试类 | 数量 | 钉住的东西 |
| --- | --- | --- |
| `AesPasswordTest` | 11 | 密码 AES 加密与学校登录页脚本逐字节一致；自实现 Base64 的往返与非法输入 |
| `CasFormParserTest` | 14 | 真实登录页的表单契约与 salt |
| `ScheduleParserTest` | 17 | 真实课表/校历响应的解析结果 |
| `ClassTimesTest` | 11 | 夏令/冬令按日历月份切换 |
| `WeekParserTest` | 21 | 各种周次写法与单双周，含「区间 + 单周」混排（`3-4周,7周,10-18周`） |
| `TermPickerTest` | 14 | 学期候选的**排序**：课表页默认值是上一学年时，按日期算出的当前学期必须排第一；不重复、不留空学期、上限至少放得下上一学年的两个学期 |
| `DateUtilTest` | 19 | 跨时区的「今天」、异地零点、提醒触发时刻 |
| `CourseBlocksTest` | 15 | 连堂课合并成一个格子、空格子填满网格、每一天的列高一致 |
| `ReminderPlannerTest` | 21 | 提前一小时的时刻、周次（含单双周）过滤、跨夏令冬令切换 |
| `ReminderNotificationTextTest` | 11 | 通知收起态/展开态的文案，哪些信息进横幅、哪些下拉才看 |
| `NotificationChannelsTest` | 10 | 渠道重要性常量与平台一致、「能弹横幅」的分界线正好落在 HIGH |
| `OemSettingsTest` | 9 | 各厂商识别（**荣耀必须排在华为前**），认不出来时退到通用而不是乱认 |
| `AppearanceTest` | 21 | 背景设置的 JSON 往返、遮罩收敛到上限、坏存档一律退回默认而不是黑屏、**遮罩默认 0% 且换图时归零** |
| `CrashReportTest` | 19 | 崩溃报告的四个分节、时间随时区变、面包屑新到旧；以及**学号与手机号必须被抹成 `******`，而 13 位时间戳与 8 位日期必须原样保留** |
| `SelfCheckTextTest` | 8 | 自检信息复制出去之后的排版：分组标题只印一次、四种状态记号各不相同、按**显示宽度**补齐（一个汉字算 2 格） |
| `WidgetPinnerManualStepsTest` | 11 | 各厂商「手动添加小组件」的步骤文案：ColorOS 叫「卡片」、vivo 的通用组件藏在「安卓组件」标签下、一加走 OPPO 那一套；文案里不许出现 Markdown 星号或没替换掉的占位符 |

测试覆盖的是**最不能再靠猜的部分**：

- `AesPasswordTest` —— 用固定的 salt/iv 明文向量，断言 Kotlin 实现产出的密文与学校登录页脚本逐字节相同。这是登录能不能成功的关键（密码要先 AES 加密再提交），一旦写错就是「密码错误」，而学生账号**连错 5 次会被锁定**，所以必须用测试钉死。
- `CasFormParserTest` —— 用抓下来的真实登录页做 fixture。特别钉住两个坑：当前页面的账号密码表单 id 是 `pwdFromId`（不是老资料里的 `casLoginForm`），且 `pwdEncryptSalt` **只有 id 没有 name**，早期实现按 `name` 收集输入框会把它整个跳过，导致密码没加密就提交。
- `TermPickerTest` —— 钉的是**排序而不是过滤**（有没有课只有服务器知道，猜不出来）。核心那条断言直接复刻真实事故：`candidates("2025", "12", 2026-10-08)` 必须**完全等于** `[(2026,3), (2025,12), (2026,12), (2025,3)]` —— 课表页说 2025-2026 第 2 学期，按日期算出来的是 2026-2027 第 1 学期，后者必须排第一。另外钉住「学年从 9 月开始算」（1 月还算上一自然年的第一学期）、时区不同会差一整个学年（上海 9 月 1 日 0 点在 UTC 还是 8 月 31 日）、以及**上限再小也得返回一个候选**（否则调用方拿着空列表去请求）。
- `ScheduleParserTest` —— 用真实接口响应做 fixture，断言 14 条课程、19 周校历、`2026-08-31` 为第 1 周周一、离散周次（`7周,15周`）、同一课程不同地点的分段记录不被合并。
- `ClassTimesTest` / `WeekParserTest` / `DateUtilTest` 等 —— 夏令冬令切换、各种周次写法、跨时区的「今天」。
- `CourseBlocksTest` —— 断言「每一天的格子高度之和恒等于节次总数」。这是连堂合并能成立的前提：只要某一天多出或漏掉一个单位高度，整列就会错位。
- `ReminderPlannerTest` —— 用真实课表断言 10 月 8 日（冬令）的两条提醒是 06:50 与 12:30，并专门验证同一节课在 9 月 24 日提醒于 13:00、到 10 月 1 日就变成 12:30。
- `NotificationChannelsTest` —— 自检面板靠这段逻辑说「能不能弹横幅」。它把「静音」误判成正常，是这里最坏的一种错：用户看着绿灯放心了，第二天却没有提醒。
- `OemSettingsTest` —— 厂商认错就会跳到别人的设置页，等于整条引导路径失效。
- `AppearanceTest` —— 背景存档坏了应该是「背景没了」，不是「App 打不开」。特别钉住：**图片模式下文件名丢失（用户清了缓存、换了手机）必须退回默认，而不是画一片黑**；以及 ARGB 的负颜色值必须原样往返。这套测试还顺手抓到了一个真 bug：`Appearance.DEFAULT` 写在两个预设常量之前，伴生对象顺序初始化时 `PRESET_SOLIDS` 还是 `null`，构造默认值直接抛 NPE —— 表现是**17 个测试同时失败**，而在真机上就是「一打开 App 就闪退」。
- `CrashReportTest` —— 崩溃报告是要**发出去给别人看**的东西，最容易犯的错是「顺手把学号也发出去了」。所以脱敏规则逐条钉死：10 位与 11 位数字串整段替换，13 位时间戳和 8 位日期保持原样（否则报告里满屏星号、没法读），长度不足 4 的 secret 不参与替换（免得把正常文字打成星号）。
- `WidgetPinnerManualStepsTest` —— 那几百字是用户唯一的指路信息，说错了他会以为「这 App 不支持我的手机」。所以厂商分支、菜单叫法、以及「文案里不能出现 `**`」（Compose 的 `Text` 只会原样显示星号，之前真的踩过）全部写成断言。

---

## 代码结构

```
app/src/main/java/com/ntu/schedule/
├── MainActivity.kt              入口：顶栏菜单 + 底部两个标签；没有课表时整屏显示登录页
├── NtuApp.kt                    Application：一进来就装上崩溃处理器（比任何 Activity 都早）
├── core/                        纯逻辑，不依赖 Android（可在 JVM 上单测）
│   ├── AuthResult.kt            登录结果（成功 / 要验证码 / 密码错 / 失败）
│   ├── AesPassword.kt           教务登录的密码 AES 加密（自实现 Base64，为的是能跑 JVM 测试）
│   ├── ClassTimes.kt            作息时间表（按月份取夏令/冬令）
│   ├── WeekParser.kt            "5-18周" / "7周,15周" / "1-16周(单)" 等周次文本解析
│   ├── TermPicker.kt            学年/学期的候选顺序（课表页给的默认学期不可信，逐个试）
│   ├── Schedule.kt              数据模型 + DateUtil（纯整数日期算法，不用 java.time）
│   ├── ScheduleParser.kt        教务 JSON → Schedule；也负责存盘的序列化
│   ├── CourseBlocks.kt          把「连着的几节」合并成一个格子，供周课表画网格
│   ├── ReminderPlanner.kt       算出未来 7 天每节课「提前一小时」的通知时刻
│   ├── Appearance.kt            背景设置的数据与 JSON 解析（不碰 Context/Bitmap，可 JVM 单测）
│   ├── CasFormParser.kt         从 CAS 登录页 HTML 提取表单字段与每会话变化的 salt
│   ├── CasAuthenticator.kt      CAS 登录编排，含「是否需要验证码」的保守判断
│   ├── ScheduleApi.kt           课表与校历接口 + 学期候选的逐个试
│   ├── CookieJar.kt             Cookie 作用域管理（不用 java.net.CookieManager，见下）
│   ├── HttpClient.kt            OkHttp + 手动重定向
│   └── JsonValue.kt             自写 JSON 解析器（理由见下）
├── data/
│   ├── ScheduleStore.kt         课表/票据/学号/加密凭据的本地文件读写（原子写）
│   ├── SecretStore.kt           Android Keystore 加解密（保存密码用）
│   ├── AppearanceStore.kt       背景设置的存盘 + 把相册图片复制进私有目录
│   └── ScheduleRepository.kt    串起登录与导入，划定「什么操作有锁号风险」
├── notify/                      上课提醒：闹钟排程 + 横幅通知 + 国产 ROM 适配
│   ├── ReminderScheduler.kt     把未来 7 天的提醒排进 AlarmManager（精确优先，拿不到就退回）
│   ├── ReminderReceiver.kt      闹钟到点 → 发横幅；每日续排
│   ├── ReminderNotification.kt  横幅通知的构造（重要性/声音/震动/锁屏可见性逐条落实）
│   ├── NotificationChannels.kt  通知渠道的唯一来源 + 「到底能不能弹横幅」的自检
│   └── OemSettings.kt           各厂商自启动 / 电池优化 / 精确闹钟设置页的跳转
├── ui/                          Compose 界面
│   ├── LoginScreen.kt           全屏登录页 + 紧凑版导入对话框（共用同一份表单）
│   ├── TodayScreen.kt           今日课表（点卡片看详情）
│   ├── WeekScreen.kt            周课表：HorizontalPager 左右翻页 + 连堂合并网格（点格子看详情）
│   ├── CourseDetailDialog.kt    课程详情弹窗（时间按那一周所在月份取夏令/冬令）
│   ├── AppBackground.kt         背景绘制 + LocalAppearance + panelColor()（半透明面板）
│   ├── BackgroundSettingsDialog.kt  背景设置面板（默认/纯色/渐变/图片 + 遮罩滑块）
│   ├── ReminderSettingsDialog.kt    提醒自检面板
│   ├── WidgetHelpDialog.kt      小组件面板：本机能不能自动加 + 按厂商的手动步骤
│   ├── DiagnosticsDialog.kt     自检与诊断面板：五项检查 + 崩溃报告的导出/复制/清空
│   ├── AuthorNotice.kt          作者署名与免费声明（登录页与「关于」共用同一份文案）
│   ├── AppViewModel.kt          界面状态 + 导入/刷新/背景/提醒的全部入口
│   └── theme/Theme.kt           配色方案 + 12 色课程色板
├── diagnostics/                 自检与崩溃报告（全部只在本机，不联网）
│   ├── CrashReport.kt           纯数据 + 纯函数渲染：报告长什么样、哪些数字要抹掉（可 JVM 单测）
│   ├── CrashHandler.kt          装 uncaughtExceptionHandler，写完报告再交还给系统
│   ├── CrashStore.kt            报告存盘（原子写、最多 10 份、更旧的自动删）
│   ├── Breadcrumbs.kt           内存里的环形缓冲：崩溃前 40 条操作轨迹
│   ├── SelfCheck.kt             五项检查 + 复制用的文本排版
│   └── DiagnosticsSharing.kt    用 FileProvider 导出报告 / 复制到剪贴板
└── widget/                      RemoteViews 桌面小组件 + AlarmManager 跨天刷新
    └── WidgetPinner.kt          能不能主动放、放上了没有、以及各厂商的手动添加步骤
```

### 几个刻意的技术选择与理由

- **不用 `org.json`，自写 `JsonValue`**：`org.json` 在 `android.jar` 里是桩实现，JVM 单元测试一跑到就抛异常 —— 而课表解析正是最需要测试钉住的一环。
- **不用 `java.time`**：minSdk 24，`java.time` 要 API 26。`DateUtil` 用 Howard Hinnant 的 `civilToEpochDay` / `epochDayToCivil` 纯整数算法，也顺带避开了时区陷阱。
- **不用 `java.net.CookieManager`**：它的 `ACCEPT_ORIGINAL_SERVER` 策略会丢掉正方教务赖以维持会话的路径 cookie（`/jwglxt`），表现为「登录成功但拉课表 401」。
- **不用 Room / DataStore / kotlinx-serialization**：只有一份数据、结构简单，引入注解处理器与额外依赖只增加构建风险。
- **提醒渠道换了一个新 id**：Android 规定渠道一旦创建，除名字和描述外**一切都改不了**（重要性、声音、震动、锁屏可见性全被冻结）。旧渠道在中低重要性上创的，改不回来，只能换 id 重建 —— 顺手把旧渠道删掉，免得系统设置里出现两条同名渠道。
- **精确闹钟「能要就要，要不到就退」**：到点准时弹体验最好，所以优先 `setExactAndAllowWhileIdle`；但 Android 12+ 的这个权限要用户自己去设置里放行，很多用户找不到，所以拿不到时必须退回 `setAndAllowWhileIdle` —— 晚几分钟也要发，绝不干脆不提醒。
- **「发测试横幅」按钮**：横幅涉及通知总开关、渠道重要性、悬浮通知、自启动白名单、电池优化好几道闸，用户没法自查。给一个当场能看到结果的按钮，比让人等到明天早上第一节课靠谱得多。
- **周课表改成左右翻页，而不是一路下滑**：一学期 19 周连排成一条纵向长列表，翻到第 12 周要滑很久，而且很容易在「这周」和「下周」之间迷路。改成一周一页后，屏幕永远只在看一周。**所有页共享同一个纵向 `ScrollState`** —— 否则从第 6 周划到第 7 周时视线会跳回第 1 节，看第 10-12 节的人每次翻页都要重新往下滑。
- **周课表的分块结果按周缓存**：`dayBlocks` 用 `remember(weekCourses, firstPeriod, lastPeriod)` 一次算出 7 天，而不是在 `for (day in 1..7)` 里现算 —— 原来每次重组要重跑 7 遍 `filter` + 分块，这是翻页卡顿的来源。
- **图片选择器用 `ACTION_GET_CONTENT` 而不是 `PickVisualMedia`**：后者在 API 24–29 上不存在，androidx 会退回 `ACTION_OPEN_DOCUMENT`，各家 ROM 行为不一致。我们选完立刻把图复制进私有目录、不需要可持久化权限，所以最朴素的那个反而最稳。
- **小组件的时间列分两行**：`07:50-09:20` 是 11 个字符，11sp 下约需 66dp，而列宽固定 62dp —— 结果就是被省略成 `07:50-0…`。拆成上下两行后每行 5 个字符约 30dp，任何字体缩放下都不会截断，顺带还能把列宽收到 54dp 让课名多 8dp。
- **小组件用 RemoteViews 而非 Glance**：`androidx.glance` 无本地缓存，且 RemoteViews 在国产 ROM 上兼容性最好 —— 目标用户恰好都在国产 ROM 上。
- **小组件不用 `updatePeriodMillis` 跨天**：系统最小只支持 30 分钟、且常被国产 ROM 压制到数小时，表现为「小组件还显示昨天的课」。改用 `AlarmManager.setInexactRepeating` 在本地午夜触发。
- **小组件超过 6 门课不再静默丢弃**：`MAX_ROWS = 6` 是 RemoteViews 不能在运行时拼布局的硬约束，但直接丢掉第 7 门起会让用户以为课表错了。改成末行显示「还有 N 门课 / 把小组件拉高就能看全」。
- **小组件里的日期格式化不用共享的 `SimpleDateFormat`**：它内部持有一个 `Calendar`，`format()` 不是线程安全的 —— 两个线程同时格式化会互相改掉对方的 `Calendar`，轻则日期串成别的日子，重则抛 `ArrayIndexOutOfBoundsException` 崩掉进程。而小组件恰好是并发高发区：`onUpdate` 在主线程、零点刷新/开机/改时间的 `WidgetRefreshReceiver` 在广播线程。改成每次调用新建一个，一次渲染也就格式化一个日期。
- **崩溃报告自己写，不接第三方上报 SDK**：课表和学号都在本机，装一个默认联网上报的 SDK 等于把这个前提推翻。自己写还有一个好处：报告格式完全可控，能针对「谁来看这份报告」做排版（收报告的人可能不是开发者）。
- **导出报告用 `FileProvider`，不写下载目录**：写下载目录要申请存储权限，为一份几百行的文本不值得；`ACTION_SEND` 让用户自己选「发给谁」（微信、邮件、文件管理器都行）。`file_paths.xml` 只开 `filesDir/crash/`，凭据与课表一个都不暴露。

---

## 安全与隐私

- **密码默认不落盘**。只用于当次登录，登录成功后仅保存 Cookie 票据（`session.json`）。`account.json` 只存学号。
- **勾选「记住账号密码」时**，密码用 Android Keystore 的 AES/GCM 加密后存在 `credentials.json`，密钥由系统安全存储保管、App 无法导出；GCM 校验失败即当作没有保存。拿不到 Keystore 的设备上该选项直接置灰，**不会退化成明文保存**。菜单里的「忘记保存的密码」可随时清除。
- **绝不自动重试密码**。学校登录页写着 `_badCredentialsCount = 5`，连错 5 次锁号。因此：
  - 「重新登录」只在用户明确点击登录按钮时才发生，App 没有任何后台自动登录；
  - 无法确定登录页结构或无法确认「本次不需要验证码」时，**直接中止并提示**，不猜测性提交；
  - 需要验证码时不做处理，直接告诉用户去网页端登录（本 App 不实现验证码识别）。
- 用已保存票据刷新失败时只提示「登录已过期，请重新导入」，不影响本地已有课表。
- 本 App 不执行任何退登/注销操作。
- **崩溃报告只存在本机**，不会自动上传到任何地方；只有你主动点「导出」或「复制」才会离开设备。报告在写盘前就把学号抹成 `******`，10 位与 11 位的连续数字也一并抹掉（发给别人之前就已经脱敏，不依赖「发之前记得删」）。
- **自检信息同样只在本机跑**：五项检查全部读本机状态，不联网。复制出去之前，学号也只会显示尾 4 位。

---

## 免责声明

非官方客户端，与南通大学官方无关。课表数据来自学校教务系统，仅供个人查看。请遵守学校的信息系统使用规定。

---

## 作者

Mr_Zhen_cn(狐涂) 为原作者，保留对本 App 的一切权利。

该程序免费，如果你是付费得到的，恭喜你被骗了。

（同样的两句话也显示在 App 里：登录页最底下、以及菜单 →「关于」。文案只有一个来源 —— `app/src/main/java/com/ntu/schedule/ui/AuthorNotice.kt` 里的 `AUTHOR_NAME`，两处从它拼出来，改一处就够。）
