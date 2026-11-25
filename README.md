# MixFlipMod OS4 定制增强版 (`io.github.aragamiz.mixflipmod_os4`)

基于 [zeldati / MixFlipMod](https://modules.lsposed.org/) 进行 HyperOS 4 (OS4) 深度适配与扩展定制（扩展包 `com.collasong.flipextra.*`），专为 **Xiaomi MIX Flip 2** 打造。

> [!IMPORTANT]
> 本模块为独立包名派生版（`io.github.aragamiz.mixflipmod_os4`）。安装前请先卸载原版 `cn.zeldati.mixflipmod`，避免双模块 Hook 同名系统方法造成冲突。

## 核心扩展与修复特性

- **外屏真全屏与手势热区优化**（`OuterFullscreenHook`）：隐藏外屏底部 `32px` 防误触遮罩，消除兼容模式 `21px` 底部留白，并将全屏回桌面手势热区放宽至 `140px`。
- **外屏状态栏双击锁屏**（`StatusLockHook`）：精准识别外屏 `1208×1392` 物理分辨率，双击顶部状态栏息屏。
- **通知滑出菜单 & 控制中心恢复**（`OuterNotificationHook` / `ControlCenterOuterHook` / `OuterMediaFocusGlassHook`）：兼容 OS4 `MiuiConfigs.isTinyScreen` 重载与 `PluginFactory.componentName` 更名，恢复完整控制中心、`3+3` 设备中心与媒体/焦点通知柔光玻璃。
- **外屏小部件轻柔光玻璃 & 7 档通透度调节**（`WidgetGlassHook`）：仅通过原生 `setBackgroundBlendColors` 调整磨砂白玻璃浓度（`5%`~`85%` 7 档实时切换），规避 Adreno Vulkan 着色器编译卡死，并在暗色/未补白 App（如 LSPosed/酷安）下自动保持高对比反白文字。
- **微信输入法 4.0.1+ 双旋转适配**（`WeTypeOuterHook`）：正置 `810px` 避让、倒置（`rot==2`）`1208px` 全宽 + 同步防跳动 + 高度修正。
- **外屏拨号盘对齐 & 网易云/计算器修复**（`DialerOuterHook` / `NetEaseOuterHook` / `CalculatorOuterHook`）：拦截网易云外屏 `ViewRootImpl.scrollToRectOrFocus` 异常负向滚动（根治顶部黑边、双重留白及底部导航栏被顶出屏幕）。
- **合盖外屏唤醒小爱直达陪伴模式**（`OuterXiaoAiStandbyHook`）：合盖态长按电源键等快捷方式唤醒超级小爱时，直接进入 Standby 实时语音陪伴。
- **外屏倒置应用卡片动画修正**（`HyperViewScaleFixHook`）：在 `com.android.systemui`（WMShell）侧对 `view_scale_remote_transition` 做坐标预补偿，修复外屏摄像头朝上（`ROTATION_180`）时小红书、B 站等「哪里点开哪里返回」动画偏移，无需额外勾选第三方应用作用域。
- **外屏侧边补色防黑屏闪白**（`OuterShellLetterboxFixHook`）：在 `com.android.systemui`（WMShell）侧拦截外屏 `1208×1392` 下 `MiuiShellLetterboxManagerImpl` 误叠加的纯黑 `ShellLetterboxSurface`，根治半透明子页/弹窗/输入法触发的侧边变黑卡死与小部件闪白屏（全机应用通用，内屏不受影响）。

## 构建说明

1. 复制 `keystore.properties.example` 为 `keystore.properties` 并填入你的签名信息（或设置环境变量 `FLIPCLOCK_KS` / `FLIPCLOCK_KS_PASS` / `FLIPCLOCK_KEY_ALIAS` / `FLIPCLOCK_KEY_PASS`）。
2. 将原版 `MixFlipMod` 基础 APK 路径通过环境变量 `MIXFLIPMOD_BASE_APK` 指定（仅用于本地合并构建，不随源码仓库分发）。
3. 执行构建：
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\build.ps1
   ```
   产物将输出至 `release/<versionCode>-<versionName>/`。

## 致谢

- 感谢原作者 `zeldati` 开源/维护的 **MixFlipMod** 基础框架与核心外屏功能。
