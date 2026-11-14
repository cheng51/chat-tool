# 聊天军师 Chat Junshi

基于 [jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis)（MIT）的安卓版改造：
微信 / QQ 聊天窗口里读屏 → 判断对方在说什么 → 给几条候选回复 → 你选一条填进输入框，**发送永远由你自己点**。

> 状态：v0.1.0 开发中。微信适配器按结构规则编写，**尚未在真机上校准**，见下方「已知问题」。

## 和原版的区别

| | jev 原版 | 本项目 |
| --- | --- | --- |
| 微信 | ❌ 明确列在禁止读取名单 | ✅ 新增结构式适配器 |
| QQ | ✅ | ✅ 保留 |
| 飞书 / X / WhatsApp | ✅ | 保留（未重点维护） |
| 知识库 | 手写笔记 + 联系人 | 额外内置 43 篇狗头军师参考资料，按话题自动检索 |
| 关系分析 | 无 | 计划中（导入微信聊天记录） |
| applicationId | `com.jev.probe` | `com.chatjunshi.app`（可与原版共存） |

## 已实现

- **微信适配器**（`core/wechat/WechatRules.kt`）：不依赖混淆的 resource-id，只看结构——
  聊天输入框（唯一可编辑节点）证明"在聊天窗"，消息列表是最大的可滚动容器，
  气泡按贴近列表左/右边缘判定"对方/我"，时间戳、系统提示、语音时长自动丢弃。
- **知识库检索**（`core/kb/KnowledgeRouter.kt`）：43 篇参考资料打包进 APK，
  按当前对话关键词命中 1–2 篇、截取最相关的一节（≤700 字），拼进分析上下文；
  总预算 1200 字，独立于用户自己的笔记。
- **QQ 及原有链路**：读屏、判断、候选排序、填入、悬浮窗全部保留原实现。
- **单元测试**：微信规则 + 知识检索共 8 个 JVM 用例，不需要真机。
- **CI**：GitHub Actions 跑单测 + `assembleDebug`，产物在 Artifact 里下载。

## 未完成

- [ ] 微信真机校准（群聊昵称、引用回复、图片/语音消息的取舍）
- [ ] 微信聊天记录导入（txt / html 导出）→ 关系画像 + 分析报告
- [ ] 知识库开关（有些人不希望对话被"军师化"）
- [ ] 对话历史跨会话召回（原版的 kb 历史默认关闭，保持关闭）

## 构建

需要 JDK 17 + Android SDK 35。本仓库**不包含**签名密钥，release 构建需要自备
`JEV_KEYSTORE_PROPS` 指向的 properties 文件；没有它时 release 为未签名包。

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

不想本地装 SDK：推到 GitHub，`Actions → Build APK` 跑完在 Artifact 下载。

## 已知问题（真机校准前请当成"可能不好用"）

1. 微信的节点树在不同版本差异很大，当前规则是按"结构 + 位置"写的，**没有在真机 dump 上验证过**。
   第一次装好后如果读不到消息，需要抓一份 `uiautomator dump` 来校准阈值。
2. 群聊成员昵称是独立文本节点，可能被当成一条"对方消息"。
3. 引用回复的原文在独立节点里，可能被读成上一条消息。
4. 只会填入草稿，不会点发送；填入失败会自动退回复制到剪贴板。

## 隐私

- 聊天文字会发往**你自己在设置里填的**模型接口，作者不运营任何服务器。
- 截图只在本地 OCR，不上传；知识库、密钥、历史都在 App 私有目录。
- 密钥存在 SharedPreferences（明文，原版如此），手机 root / 备份被拿走会暴露，建议单独开一把限额 key。
- 在微信 / QQ 里用自动化工具可能违反其用户协议，账号风险自担。

## 许可与来源

- 本项目基于 jev-chat-jarvis，MIT，保留其 LICENSE 与 NOTICE。
- `app/src/main/assets/kb/` 下的参考资料来自
  [goutoujunshi](https://github.com/shengjidaguai-china/goutoujunshi)，MIT，
  版权声明见 `assets/kb/LICENSE-GOUTOUJUNSHI.txt`。
