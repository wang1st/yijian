# 译键 UI 核查报告

按 `android-design`（Material 3 Expressive 设计判断）与 `impeccable`（工艺审查）的标准做的第二轮核查。数据来自代码扫描，不是印象。

## 结论先说

这不是「有些细节要打磨」，而是**设计系统缺失**。我一直在单页面地调样式，从没建立过令牌层，所以每加一个页面就要重抄一遍颜色、字号、圆角、间距。后果是同一套 UI 里有 14 种字号、41 处透明度硬调、3 份重复的卡片底色定义。

按 `android-design` 的三条原则逐项对照：

| 原则 | 现状 | 判定 |
|---|---|---|
| **Emphasis budget**（强调预算） | 首页 7 张卡片、10 个视觉组件同时争夺注意力 | 严重违反 |
| **Role pairs**（颜色只用 X/onX 配对） | 41 处 `.copy(alpha=)` + 41 处裸 hex | 严重违反 |
| **Reveal, not stretch**（大屏展开而非拉伸） | 只做手机，暂不涉及 | 不适用 |

`impeccable` 的判断是另一层：**Refinement preserves; redesign replaces.** 我这两周做的一直是前者——保留旧外观、逐处修补。但修到现在，得到的是「多邻国的皮 + Material 的骨架」，两边都不地道。要么承认这是精修并接受它的边界，要么做一次真正的重设计。

---

## 一、颜色：没有系统，只有散落的字面量

**41 处裸 hex**，集中在：

| 文件 | 裸 hex 数 |
|---|---|
| ReviewTheme.kt | 23 |
| LessonComponents.kt | 18 |
| MainNavigationBar.kt | 7 |
| MainStatsScreen.kt / MainHomeScreen.kt | 各 4 |

**41 处 `.copy(alpha=)`**，最多的是 MainHomeScreen（15 处）。

问题不在于数字本身。问题在于 `.copy(alpha = 0.65f)` 这种写法——它表达的是「比正文淡一点」，但代码里读不出这个意图，换个人来看只知道是 65%。而且深色模式下同一个 alpha 值的观感完全不同：`onSurface.copy(alpha = 0.6f)` 在浅色下是得体的次级文字，在深色下常常糊成一团。

`android-design` 的要求是：颜色只能通过 `X` / `onX` 配对取用，次级信息用 `onSurfaceVariant`，屏幕代码里不出现裸 hex 和 alpha 计算。

## 二、字号：14 种，全部硬编码

| 字号 | 出现次数 |
|---|---|
| 13sp | 24 |
| 12sp | 19 |
| 16sp | 13 |
| 14sp | 12 |
| 11sp | 12 |
| 15sp | 7 |
| 10sp | 7 |
| 19/17/20/24/26/27/28/34/40/56sp | 各 1~3 |

11、12、13、14、15 这五档挤在一起，肉眼几乎分不出，却要维护五套。Material 3 的排版阶梯只有五级（display / headline / title / body / label），每级三个尺寸。

首页顶部的三个状态胶囊用了 16sp 的 emoji + 15sp 的数字 + 10sp 的标签——三档字号塞进一个 60dp 高的块里，这就是「模板感」的来源。

## 三、重复：同一件事写了三遍

**卡片底色**在三处独立定义，内容完全一样：

```kotlin
// MainHomeScreen.kt:84
private fun cardBg(): Color = if (isSystemInDarkTheme()) Color(0xFF1F2023) else Color.White
// MainStatsScreen.kt:270
private fun cardBg(): Color = if (isSystemInDarkTheme()) Color(0xFF1F2023) else Color.White
// ReviewTheme.kt:78
internal fun duoCardBg(): Color = ...
```

**16dp / 12dp 圆角**散落在 8 个文件、21 处，每处都直接写 `RoundedCornerShape(16.dp)`。

**间距**：6dp 用了 13 次、10dp 12 次、20dp 10 次……没有 4 的倍数规律，也没有语义（内边距？卡片间距？区块间距？都用同一个数字表达不同的意思）。

## 四、强调预算：首页什么都在喊

首页有 **7 张卡片**：

1. 输入法状态警告（黄色，最响）
2. 三个状态胶囊（连续 / XP / 正确率）
3. 目标圆环 + 主按钮
4. 今日任务（三条进度）
5. 本周柱状图
6. 薄弱点（AI 分析）
7. 底部说明文字

按 Emphasis budget，一个屏幕应该只有**一个主焦点**。现在圆环和薄弱点卡片都是大块彩色区，平级争夺注意力。用户打开 App 想知道「现在该做什么」，而屏幕上没有任何一处明确地回答这个问题——答案被稀释在七个块里。

另外发现一个数据矛盾：圆环显示 `0 / 20 张`，下方文字说「还有 9 张待练」。今天已经练了 31 张，计数逻辑对不上。

## 五、具体的不一致（抽查）

| 问题 | 位置 |
|---|---|
| 底部导航用 `#58CC02`，练习页主按钮也用 `#58CC02`，但前者是自己定义的，后者来自 ReviewTheme，同一个绿有两个来源 | MainNavigationBar vs ReviewTheme |
| 五角星 → 柱状图改过了，但 `MainTab` 里的语义色仍是按「多邻国那四个 tab 的颜色」定的，而不是按页面职责 | MainNavigationBar |
| 庆祝页的圆环用 `DuoGold.copy(alpha=0.12f)`，首页圆环用 `DuoGreen`，两者无关联 | SessionCelebration vs MainHomeScreen |
| 手势导航留白：练习页底部 19dp，复习页 20dp，首页 20dp | 三处 |

---

## 建议

按投入产出排序，前两项是根，后面都是它的自然结果。

**1. 建立令牌层（一次性，约 200 行）**

一个 `DesignTokens.kt`：

```
Color:  语义名（primary / onPrimary / surface / onSurface / onSurfaceVariant /
        positive / onPositive / negative / onNegative / warning）
Type:   五级阶梯 × 三级尺寸
Shape:  small(8) / medium(12) / large(16) / full
Space:  4 的倍数命名（xs=4 / sm=8 / md=12 / lg=16 / xl=24 / xxl=32）
```

然后把 41 处裸 hex 和 41 处 alpha 全部替换为令牌引用。**这一步做完，「深色模式下某处看不清」这类问题会直接消失**，因为对比度由令牌保证，而不是每处自己调。

**2. 首页按强调预算重排**

七个块收敛成两个：一个主焦点（今天该做什么 + 那一个按钮），一个次级区（其余信息折叠或下移）。薄弱点和本周图表可以放进复习页，首页只留今天。

**3. 字号收进五级阶梯**

14 种降到 5~6 种。11/12/13 合并成 12，14/15 合并成 14。

**4. 消除三处重复定义**

`cardBg` × 3 → 1，圆角字面量 × 21 → 常量引用。

---

## 一个需要你决定的判断

`impeccable` 说得很直白：**精修保留现有身份，重设计把旧外观当作反例，不要在废弃的外观上继续抛光。**

我们现在处在中间状态。接下来有两种走法：

**A. 承认这是精修**：保留多邻国的调性（彩色、活泼、有成就感），但用 Material 3 的系统方式实现它——建令牌、收字号、按强调预算重排。工作量中等，风格不变。

**B. 做一次真正的重设计**：不套多邻国，正面回答「一个中文用户的英文学伴，应该长什么样」。可以在 Material 3 Expressive 的表达力里找自己的语言（比如用中文排版的节奏做视觉结构）。工作量大，但结果会是自己的东西。

我倾向 **A**，理由是你三周以来每次反馈都在指向「更好用、更清楚、更有反馈」，而不是「更不一样」；而且 A 做完之后如果还想走 B，令牌层仍然有用。B 更适合在一个新项目里从零做。

你觉得呢？
