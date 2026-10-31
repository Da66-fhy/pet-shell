# 桌宠壳（Android 悬浮窗）

把 DSH 里的 Live2D 桌宠从网页里搬到手机桌面上的最小外壳。

## 它做什么

一个透明悬浮窗，里面嵌 WebView，加载：

```
http://127.0.0.1:3080/dsh-pet/standalone?k=<token>
```

`/dsh-pet/standalone` 是插件自带的「纯净桌宠页」——透明背景、只加载 pet.js，
本来就是给外部桌面壳用的。所以模型、引擎、通信全部复用，壳只负责"窗口"。

## 编译

1. 用 Android Studio 打开这个目录（会自动补 gradle wrapper），或装了 SDK 的机器上 `./gradlew assembleDebug`
2. 产物：`app/build/outputs/apk/debug/app-debug.apk`
3. 传到手机安装（debug 包需要允许"安装未知来源应用"）

需要：JDK 17、Android SDK Platform 34。

## 首次使用

1. 装好打开 → 点「开启悬浮窗」→ 系统会让你授权「显示在其他应用上层」→ 授权后回来再点一次
2. 填 token（见下），点「开启悬浮窗」
3. 桌面上出现她 → 顶部那条黑条：**拖动 = 移动位置，点一下 = 切换"可输入/只观看"**

## token 从哪来

DSH 的 `/dsh-pet/*` 走信任栅栏，全新 WebView 没有 DSH 的会话 cookie 会被 401。
插件为此留了「桌面版通行证」——本机回环 + 令牌就放行：

- 含令牌的文件：`~/.dsh/dsh-live2d-pet-desktop.json`（容器里就是 `/root/.dsh/...`）
- 取 `token` 字段那一串，粘进 App 里

注意：令牌是随机的，删掉那个文件会重新生成，旧值就失效了。

## 已知问题（按坑的严重程度）

1. **DSH 得先跑起来**，而且必须在同一台手机上——壳连的是本机回环 127.0.0.1。
2. **WebView 透明 + 硬件加速**：某些机型上 WebGL 在悬浮窗里会被限制，
   表现是模型不显示或白底。真遇到就换 `setLayerType(LAYER_TYPE_SOFTWARE)` 试试（会更卡）。
3. **省电策略**：荣耀/华为这类会把后台服务的悬浮窗杀掉。需要在系统设置里给本应用
   「允许后台活动 / 忽略电池优化 / 自启动白名单」。
4. **触摸**：现在是"整窗口一个模式"——要么只观看（触摸穿透），要么可输入（抢焦点）。
   更细的做法是给模型做透明区域判定，但那要拿到掩码，属于下一步。
5. 现在尺寸固定 240×360dp，位置只记内存（重启回到右下角）。配置项在 `OverlayService.buildOverlay()` 里改。

## 下一步可以加

- 尺寸/位置持久化（SharedPreferences）
- 开机自启（BOOT_COMPLETED）
- 语音：接 TTS，把气泡内容念出来；口型参数 Soullink 已经支持
- 透明区域点击穿透（按 alpha 判定）
