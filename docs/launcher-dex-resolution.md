# 桌面手势协调与目标解析

桌面只推迟与扇形候选竞争的手势识别，不持有输入抢占权。SystemUI 的圆角窄弧入口在 DOWN 时请求抢占，准备确认后才允许显示菜单。libxposed API 102 安装 Hook，DexKit 2.3.0 定位混淆的 Quickstep 输入方法。侧边栏的独立适配见 [侧边栏适配与能力校验](sidebar-dex-resolution.md)。

## 两条独立输入路径

桌面普通窗口的 Workspace 可以在斜滑时启动横向翻页；后续收到 `CANCEL` 会对齐到目标页面，因此只取消输入不能消除已经开始的位移。Quickstep 的手势监视器则独立接收导航输入。两者分别保留轻量会话，某一路的 `CANCEL` 不终止另一条输入路径。

- Workspace 的 `dispatchTouchEvent(MotionEvent)` 只观察真实事件，原方法始终执行一次。`DOWN`、点击、长按、普通移动和终止事件继续走原 View 分发。
- 已核对的 `OplusWorkspace.determineScrollingStart(MotionEvent, float)` 与三参数重载只在起点命中圆角窄弧时暂缓翻页识别。实际输入所有者确认未抢占时放行当前真实事件，不复位滚动位置，也不修改图标属性。
- Quickstep 输入入口始终接收原始 `DOWN`、`UP`、`CANCEL` 和多指事件，只暂缓命中窄弧流的 `MOVE`。因此让渡时原链已有有效起点，不需要伪造或重放 `DOWN`。
- 两条路径使用同一个圆角区域判定和配置快照，但不负责显示菜单，不调用桌面的 `pilferPointers()`。命中弧带的流保持视觉门到真实 `UP/CANCEL`，但实际输入所有者确认未抢占时，必须按原始 DOWN 身份主动释放。
- ROM 回注可能改写 `downTime`。重复 `DOWN` 按原事件时间、设备、显示、指针和原始位置去重；真实终止后允许建立新流，不用时间相近猜测是否相同手势。

## 输入入口可用性

桌面收到 SystemUI 的有效就绪状态前不启用视觉门，首次触摸保留系统行为。就绪条件由 SystemUI 的菜单连接及实际输入入口共同决定，不以配置开关代替能力检测。

SystemUI 发布只读状态到桌面包；桌面接收器要求发送方拥有 `android.permission.STATUS_BAR_SERVICE`，并核对 SystemUI UID、协议版本和 Binder 寿命标记。SystemUI 死亡会立即撤销本地就绪缓存。桌面仅在启动或未就绪的新 `DOWN` 请求一次状态，双方一秒限频；请求定向 SystemUI、共享发送者身份，接收侧核对桌面 UID。请求不能启动菜单、改变窗口或修改配置。

`MOVE` 只读取当前流的门控状态，不读取显示几何、不发送请求，也不等待跨进程响应。命中窄弧的旧流不会仅因配置变化重新进入系统动画。实际输入所有者若在抢占前遇到环境拒绝或抢占失败，会发送一次带原始 DOWN 身份的释放通知；接收侧核对同一寿命 token、UID、时间与有限坐标后，立即释放匹配的预测识别。短期释放记录最多保留 16 项、有效期一秒，覆盖通知先于另一条输入通道 DOWN 到达的情况。已抢占的会话不得发送该通知。没有后台无限轮询。

所有 Hook 都明确只执行零次或一次原方法，不缓存 `Chain` 或 `MotionEvent`。会话与宿主使用弱关联；环境观察与已安装 Hook 同桌面进程存活。

## 匹配条件

- 在已核对的 `OplusBaseTouchInteractionService` 内，输入方法必须为实例方法、接收一个 `MotionEvent`、返回 `void`，同时包含 `onDispatchInputEvent: ` 与 `TouchInteractionService.onInputEvent` 两条字符串并调用 `MotionEvent.getActionMasked()`。
- 输入入口必须唯一命中。未匹配或歧义时不安装该 Hook，也不退回单参数签名猜测。
- Workspace 使用已验证的类和精确方法签名，检查 View 继承关系、参数、返回值及实例方法属性。签名缺失时保留系统原逻辑；部分安装失败时撤销这一组 Hook。
- 不再查找或缓存 InputMonitor 字段、包装类及抢占方法。输入缓存升级规则版本，旧格式不复用。
- 定位仍依赖已验证的行为和签名。厂商删除日志、内联方法或改变调用结构时，需重新研究规则，不能宣称自动兼容。

## 生命周期与缓存

包 ClassLoader 就绪后安装 Workspace 适配，并启动一次性后台 Quickstep 解析任务。APK 读取、SHA-256、缓存、native 库加载和查询均在后台执行；DexKit 查询使用一个工作线程，bridge 通过 `use` 释放。

缓存放在桌面自己的 `code_cache/flymefreeform/` 中，只保存消费入口、缓存事件观察入口和单次放行方法名，绑定 APK 内容摘要及规则版本；不保存宿主对象或用户配置。缓存损坏或反射校验失败后只重新解析一次，写缓存失败不阻断已验证目标。

Quickstep 解析结果回到主线程安装。解析期间的输入继续由桌面处理，安装后只观察新的 `DOWN`。十秒后未完成则放弃安装并请求中断；native 查询本身不保证立即响应 Java 中断，迟到结果不会安装 Hook，返回后释放 bridge。没有热路径扫描、常驻线程池或自动重试。

## 验证方式与边界

`LauncherSymbolCacheTest` 验证内容变更、规则变更、损坏缓存、旧格式拒绝和原子替换。可选的 `LauncherDexQueryTest` 使用本地宿主 APK、同版本桌面 native 库和人工核对的三个方法名，验证实际查询唯一性及 Workspace 方法声明；未提供环境变量时跳过该项。

环境变量为 `FLYMEFREEFORM_TEST_LAUNCHER_APK`、`FLYMEFREEFORM_TEST_DEXKIT_LIBRARY` 与 `FLYMEFREEFORM_TEST_LAUNCHER_SYMBOLS`（消费入口、缓存观察入口、放行方法名，逗号分隔）。更换输入文件运行时应强制重新执行测试任务，避免 Gradle 复用旧结果。

静态解析和构建通过不代表真实手势已修复。仍需验证空闲桌面和滚动未结束时再次按下、点击/长按、横向翻页、中央上滑、候选让渡、多指、快速重复操作、导航模式变化及停用恢复。尤其 Quickstep 在 `DOWN` 阶段的原有行为继续执行；SystemUI 唯一抢占者与桌面协调是否消除全部竞争，需要真机联合验证。

消费门控延后 MOVE 前，必须让宿主已有缓存尽早放行，避免触发上滑“无移动”超时。通过已核对的缓存事件入口记录物理 DOWN 身份，只对匹配的当前流调用一次宿主回注方法；重复 DOWN 不重置请求状态。模块不直接调用全局事件注入接口、不构造或改写触摸、不修改系统超时。找不到对应缓存或调用失败时，保留当前 MOVE 的系统处理。

额外匹配 `OplusInputInterceptHelper` 的七参数布尔返回事件方法（首参 MotionEvent），以及接收 boolean、返回 void 且包含缓存 DOWN 放行诊断字符串的方法；两者都必须唯一命中并在绑定时重新校验。缓存版本随三方法协议更新，旧入口缓存不复用。
