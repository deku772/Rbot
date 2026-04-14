# Telegram / Discord / 飞书 Tabbed 通道配置 MVP 计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在 Setup 的第 4 步（Channel）里，将 Telegram、Discord、飞书放入同一个 Tab 页面，形成「配置 → 写入配置文件 → 启动网关」的一步直达链路。

**Architecture:** 以 `ChannelFragment` 为 Tab 容器，使用 `TabLayout + ViewPager2` 承载三个子配置页。每个子页负责各自字段校验与 `ChannelSetupHelper.writeChannelConfig(...)` 调用，共用网关启动行为与失败提示。`Launcher`/`Dashboard` 统一按 `openclaw.json` 中 `channels` 的存在与配置状态判断。

**Tech Stack:** Java 17, AndroidX Fragment/ViewPager2, Material TabLayout, JSON + SharedPreferences, Robolectric unit tests.

## Task 1：补齐 Feishu 的 platform 解码与配置写入（TDD）

**Status:** ✅ 已实现 Feishu 专用解码与 `writeFeishuChannelConfig` 写入路径；已按当前实现补齐单测断言与覆盖面，不依赖 `writeChannelConfig("feishu", ...)` 分支。

**Files:**
- `app/src/main/java/app/rbot/ChannelSetupHelper.java`
- `app/src/test/java/app/rbot/ChannelSetupHelperTest.java`

**Step 1: 写失败测试（新增）**
- 在 `ChannelSetupHelperTest` 增加三条测试：
  - `testDecodeSetupCode_validFeishu_decodesCorrectly`
  - `testDecodeSetupCode_feishuPrefixInferred`
  - `testWriteChannelConfig_feishu_writesConfig`

```java
@Test
public void testDecodeSetupCode_validFeishu_decodesCorrectly() {
    String setupCode = buildCode("BOTDROP-fs-", "feishu");
    ChannelSetupHelper.SetupCodeData data = ChannelSetupHelper.decodeSetupCode(setupCode);
    assertEquals("feishu", data.platform);
}
```

**Step 2: 运行测试（失败）**
Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.ChannelSetupHelperTest`
Expected: fail because `decodeSetupCode` 未识别 `feishu`/`fs`，`writeChannelConfig` 未支持 `feishu`。

**Step 3: 实现最小改动（最小可打通）**
- `ChannelSetupHelper` 中新增 `feishu` platform 常量。
- 解码层新增 `fs` 映射（可先兼容 `feishu` 与 `lark` 字段名）。
- `writeFeishuChannelConfig(...)` 写入 `channels.feishu`。
- 先按 MVP 统一字段写入：`enabled=true`, `accounts.main.appId`, `accounts.main.appSecret`, `dmPolicy/allowFrom`。
- 更新 `plugins.entries` 对应 `feishu` 开关。

**Step 4: 运行测试（通过）**
Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.ChannelSetupHelperTest`
Expected: 3 条新增用例通过，其余原有用例保持通过。

**Step 5: 提交**
```bash
git add app/src/main/java/app/rbot/ChannelSetupHelper.java app/src/test/java/app/rbot/ChannelSetupHelperTest.java
git commit -m "feat: add feishu support to setup code decode and channel write"
```

## Task 2：新增通道表单能力抽象（供 Tab 页面复用）

**Files:**
- `app/src/main/java/app/rbot/ChannelConfigMeta.java`（新建）
- `app/src/main/java/app/rbot/ChannelFormFragment.java`（新建，基类）
- `app/src/test/java/app/rbot/ChannelConfigMetaTest.java`（新建）

**Step 1: 写失败测试（新增）**
- `ChannelConfigMetaTest` 覆盖三个平台的验证规则：
  - Telegram：token 含 `:` 且 ownerId 为数字
  - Discord：token 非空
  - 飞书：至少 token 非空，其他字段可选（先 MVP）

**Step 2: 运行测试（失败）**
Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.ChannelConfigMetaTest`
Expected: 测试失败，类与逻辑不存在。

**Step 3: 实现可复用层（最小）**
- `ChannelConfigMeta` 提供：
  - 平台 ID（`telegram|discord|feishu`）
  - 表单标题文案、`setupBotUrl`（先 Telegram 使用现网 TG bot，其他平台用可配常量）
  - 字段校验函数（`isTokenValid`、`isOwnerValid`）
- `ChannelFormFragment` 统一：
  - 绑定服务、启动网关、错误提示和按钮状态复用
  - 抽象接口 `collectConfigFields()` 由子类实现

**Step 4: 运行测试（通过）**
Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.ChannelConfigMetaTest`
Expected: 3 个平台校验行为覆盖通过。

**Step 5: 提交**
```bash
git add app/src/main/java/app/rbot/ChannelConfigMeta.java app/src/main/java/app/rbot/ChannelFormFragment.java app/src/test/java/app/rbot/ChannelConfigMetaTest.java
git commit -m "feat: extract channel form meta and validation helpers"
```

## Task 3：重构 ChannelStep 为 Tab 页面（Telegram + Discord + 飞书）

**Files:**
- `app/src/main/res/layout/fragment_rbot_channel.xml`
- `app/src/main/java/app/rbot/ChannelFragment.java`
- `app/src/main/java/app/rbot/ChannelPagerAdapter.java`（新建）
- `app/src/main/java/app/rbot/TelegramChannelFragment.java`（新建）
- `app/src/main/java/app/rbot/DiscordChannelFragment.java`（新建）
- `app/src/main/java/app/rbot/FeishuChannelFragment.java`（新建）
- `app/src/main/res/layout/fragment_rbot_channel_telegram.xml`（新建）
- `app/src/main/res/layout/fragment_rbot_channel_discord.xml`（新建）
- `app/src/main/res/layout/fragment_rbot_channel_feishu.xml`（新建）

**Step 1: 写失败测试**
- 这里以编译失败为测试触发器：先改 `ChannelFragment` 引入 `TabLayout/ViewPager2`，先不创建新类，`./gradlew :app:compileDebugJavaWithJavac` 预期失败。

**Step 2: 运行测试（失败）**
Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: 找不到 `ChannelPagerAdapter/Fragment` 相关符号。

**Step 3: 实现最小 UI 与路由**
- `fragment_rbot_channel.xml` 改为 `TabLayout + ViewPager2 + container`。
- `ChannelFragment` 初始化 3 个 tab 并绑定 adapter。
- `ChannelPagerAdapter` 仅承载 3 个 Fragment。
- 各子 Fragment 继承 `ChannelFormFragment`，复用 Start Gateway 与失败展示。
- 先把 Telegram 表单逻辑直接复用旧行为；Discord/飞书先提供最小字段+接入按钮。

**Step 4: 运行测试（通过）**
Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: 编译通过，资源命名与 view id 校验通过。

**Step 5: 提交**
```bash
git add app/src/main/java/app/rbot app/src/main/res/layout/fragment_rbot_channel*.xml
git commit -m "feat: add channel setup tab container with telegram/discord/feishu pages"
```

## Task 4：Dashboard 与 Launcher 的通道识别同步到 3 个平台

**Files:**
- `app/src/main/res/layout/activity_rbot_dashboard.xml`
- `app/src/main/java/app/rbot/DashboardActivity.java`
- `app/src/main/java/app/rbot/RbotLauncherActivity.java`

**Step 1: 写失败测试（可选）**
- 无单测文件可覆盖 UI 解析，先新增逻辑分支，预期行为通过手工验证。

**Step 2: 运行测试（失败）**
Run: `./gradlew :app:compileDebugJavaWithJavac`
Expected: 先出现 `findViewById` 的缺失 id / 编译期变量未定义。

**Step 3: 实现同步**
- `activity_rbot_dashboard.xml` 新增飞书行，统一用同类样式并保持可点击。
- `DashboardActivity` 新增 `mFeishuStatus`/`mFeishuChannelRow` 读取 `channels.feishu` 并显示连接状态。
- `RbotLauncherActivity.hasChannelConfigured()` 按 `channels` 下各平台关键字段完整性做判定。
- 将 `openTelegramChannelConfig()` 命名保持不变，点击任意 channel 行都跳转至 `STEP_CHANNEL`（同页 Tab 可供用户切换）。

**Step 4: 运行测试（通过）**
Run: `./gradlew :app:testDebugUnitTest --tests app.rbot.ChannelSetupHelperTest`
Expected: 既有测试通过，新增代码无回归。

**Step 5: 提交**
```bash
git add app/src/main/res/layout/activity_rbot_dashboard.xml app/src/main/java/app/rbot/DashboardActivity.java app/src/main/java/app/rbot/RbotLauncherActivity.java
git commit -m "feat: expose multi-channel status in dashboard and update launcher guard"
```

## Task 5：MVP 验收与风险关闭

**Status:** ✅ 已完成（测试与编译/构建回归完成，待设备验证）。

**Files:**
- 无代码新文件（验证流程）

**Step 1: 本地验证（回归）**
- `./gradlew :app:testDebugUnitTest --tests app.rbot.ChannelConfigMetaTest`
- `./gradlew :app:testDebugUnitTest --tests app.rbot.ChannelSetupHelperTest`
- `./gradlew :app:assembleDebug`
Expected: 所有命令成功完成。

**Step 2: 真实设备验证**
Run: `./gradlew :app:installDebug`
Expected: 安装成功后按场景走一遍：
- 首次启动走 setup -> Step3 Channel tab。
- Telegram/Discord tab 输入字段可直接配置并启动网关。
- 飞书 tab 可提交并落库（字段/映射待后续对齐时可更新）。
- Dashboard 显示 Telegram/Discord 状态；飞书在支持字段确认后可立即可视化。

**Step 3: 结果提交**
```bash
git status --short
git commit -m "feat: complete mvp multi-channel tabbed setup"
```

## 备注与执行选项

Plan complete and saved to `docs/plans/2026-02-24-im-tabs-channel-setup-mvp-plan.md`.

1. Subagent-Driven（本会话）- 每个任务逐步实现并插入人工复核点。
2. Parallel Session（外部分支）- 新开会话使用 `@superpowers:executing-plans`，并按 task checkpoint 并行推进。
