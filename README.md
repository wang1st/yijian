# 译键 (Yijian)

把蹩脚英文打成地道中文，同时当你的私人英语老师。

一个基于 [FlorisBoard](https://github.com/florisboard/florisboard) 改造的 Android 输入法：你输入英文（允许语法错、拼写错、想到哪写到哪），它给你中文译文、地道英文说法，并在积累到一定练习量后把学习报告发到你邮箱。

> 个人自用项目，为「英文水平有限的中国人」这个场景专门设计。

---

## 它能做什么

### 输入即翻译
- 键入的英文直接显示在当前输入框，翻译前可以修改
- 点右下角的双对话框翻译键 → 模型返回中文译文
- 点选中文候选后，用译文替换原文
- 要继续写英文，点「保留原文」收起候选

### 私人英语老师
- 每句话都给**最地道的英文说法**（不是逐字直译）
- 拼写、语法、用词、表达问题逐条指出，说明规则
- 键盘输入场景下**不计较大小写和标点**——小写开头、缺逗号、`im`/`u`/`wanna` 都不算错

### 语音输入
- **长按空格键说话**，松开自动识别（中英粤日韩等 20+ 语言自动判别）
- 语音模式下的批改会特别针对「不知道怎么用英文说」的情况
- 老师版那行有 🔊 按钮，用 TTS 朗读英文练听力

### 学习报告
- 每句话自动记录：原句、规范版、问题分类
- 积累满 N 词（默认 200）自动生成报告并**发到你的邮箱**
- 报告是排版好的 PDF 附件：错误分布条形图、最需要练的 5 个点、逐句批改

---

## 技术栈

| 部分 | 实现 |
|---|---|
| 键盘底子 | FlorisBoard fork（Kotlin + Jetpack Compose，keyboard3 架构）|
| 翻译 | DeepSeek Chat API（OpenAI 兼容，可换任意兼容服务）|
| 语音识别 | DeepSeek `POST /v1/tools/asr`（16kHz PCM WAV）|
| 语音合成 | DeepSeek `POST /v1/tools/tts` |
| 邮件 | 自研零依赖 SMTP 客户端（SSL/STARTTLS + AUTH LOGIN）|
| 报告 PDF | `android.graphics.pdf.PdfDocument` 手绘 A4 排版 |

### 模型交互的几个关键决策

单句翻译**不需要推理**。实测 `deepseek-flash` 开启推理时延迟 2.0–2.9s、推理 token 吃掉 60–70% 预算，关掉后 0.9–1.2s 且质量无差。代码里通过 `thinking: {"type": "disabled"}` 关闭，并对不支持该参数的服务商自动降级重试。

预算按句长动态计算（`400 + 词数 × 40`，上限 3000），被截断时**自动加倍重试**，仍失败才报错——用户不该为模型的一次抽风买单。

---

## 构建

### GitHub Actions（推荐）

推送到 `main` 即触发，产物在 Actions 页面的 Artifacts 里。

仓库需要在 Settings → Secrets 里配置（可选，不配则用 debug 签名）：

```
YIJIAN_KEYSTORE_BASE64   # base64 -w0 your.p12
```

签名密码通过环境变量传入，不写进仓库：

```bash
export YIJIAN_KEYSTORE=path/to/yijian.p12
export YIJIAN_STORE_PASSWORD=...
export YIJIAN_KEY_ALIAS=yijian
export YIJIAN_KEY_PASSWORD=...
./gradlew assembleDebug
```

### 本地

需要 JDK 17、Android SDK、CMake、Ninja：

```bash
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

---

## 首次使用

1. 安装后把「译键」设为默认输入法
2. 打开应用 → **AI 翻译** → 填 API Key（[platform.deepseek.com](https://platform.deepseek.com) 申请）
3. 打开「启用 AI 翻译」开关
4. 想要报告的话，在同一页填收件邮箱和 SMTP 信息
   - QQ 邮箱：`smtp.qq.com:465`，密码处填**授权码**（不是登录密码，在邮箱设置里生成）

### 手势

| 操作 | 效果 |
|---|---|
| 打字 | 英文显示在当前输入框 |
| 双对话框键 | 翻译当前文字 |
| 带声调的 ā 键 | 开启或关闭拼音输入 |
| ↵ 回车键 | 在多行输入框换行，在单行输入框执行应用的操作 |
| Shift | 默认只让下一个字母大写，再点一次取消 |
| 长按空格 | 按住录音，松开发送识别 |
| 点候选 | 该译文上屏 |
| 「保留原文」 | 收起翻译面板，保留输入框里的英文 |
| 「✕ 清除」 | 全部丢弃 |

---

## 隐私

- 打字内容**只在触发翻译时**发送给你配置的模型服务商
- 密码框和不可翻译的输入框自动跳过，不发送任何内容
- 练习记录只存在本机 `files/yijian_practice.jsonl`，报告发出后才清除
- 邮件凭据和 API Key 仅存于本应用私有目录

---

## 已知限制

- 语音识别单次上限 120 秒 / 8 MiB（API 限制）
- 报告依赖 SMTP，部分邮箱需先开启 SMTP 服务并生成授权码
- 无离线模式（未实现端侧模型兜底）

---

## 许可证

**个人与非商业使用许可（Non-Commercial License）** —— 见 [LICENSE](LICENSE)。

简单说：**可以随便用、随便改、随便分享，但不能拿它赚钱。**

底层的 FlorisBoard 代码遵循 Apache License 2.0，本项目的修改部分在上游许可基础上增加了商业使用限制。

---

## 致谢

- [FlorisBoard](https://github.com/florisboard/florisboard) —— 键盘基础，Apache-2.0
- [k3lp](https://github.com/k3lp) —— 键盘布局引擎
- [jetpref](https://github.com/patrickgold/jetpref) —— 偏好设置框架
- [DeepSeek](https://www.deepseek.com) —— 翻译与语音 API
