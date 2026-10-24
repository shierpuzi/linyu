# 消费合并（能看到今天实际花了多少钱）

> 2026-10-24 实现。**126 个测试全绿**（新增 13 个），dev + store 两个 flavor 都通过。

## 它解决什么

趣智校园按项目拆账，两个板块各有独立钱包和账单：

| 板块 | projectId | 钱包 |
|---|---|---|
| 洗浴 | 4000 | 独立 |
| 洗衣饮水吹风 | 3356 | 独立 |

想知道「我今天到底花了多少」，原来只能自己把两边加起来。开了这个开关，
**钱包页的趋势图直接显示合并后的消费**，不用手算。

## 怎么用

**设置 → 我的 → 消费趋势** 下面多了「消费合并」开关：

```
┌─────────────────────────────────┐
│ 消费趋势                    [开] │
│ 在钱包页显示消费统计图            │
├─────────────────────────────────┤
│ 消费合并                   [开] │  ← 新增
│ 趋势图显示所有板块合计消费（含洗浴）│
└─────────────────────────────────┘
```

- **必须先开「消费趋势」**：合并结果只喂给趋势图，趋势关着时它没有展示出口。
  趋势关闭时这个开关是**禁用**状态，副标题提示「需要先开启消费趋势」。
- 趋势图标题旁会出现「合并」角标，下面标一行「合并自：洗浴 + 洗衣饮水吹风」。
  不给提示的话，「总消费」突然变大用户会以为是 bug。

## 关键设计：为什么不能直接 switchProject

合并要读别的板块账单，但**不能走 `ProjectRepository.switchTo`**：

| | switchTo | withBoardContext（本次新增） |
|---|---|---|
| 改认证参数 | ✅ | ✅ |
| 清项目维度缓存 | ✅ | ❌ |
| 改用户选择 | ✅ | ❌ |
| 界面复位 | ✅ | ❌ |
| 还原 | 不还原（就是切过去） | **finally 必定还原** |

用 switchTo 的话，用户会看到余额闪一下、账单清空再填回来。

### `withBoardContext` 的安全边界

```kotlin
ProjectRepository.withBoardContext(pid) {
    fetchRecentBills()   // 只准调查询接口
}
```

- **finally 还原**：block 抛异常也会恢复成进来时的快照
  （不还原的话 App 会一直用别的板块身份请求，重启前都不自愈）
- **不落盘**：不改 `PrefsHelper.accountId/projectId`
- **不清缓存**：不动 `clearProjectScopedCache`
- **可重入**：还原用「进来时的快照」而不是「另一个板块」

### ⚠️ 已知并发缺陷（架构层面，不是本次引入）

所有请求共用一套 `NetworkModule` 全局认证状态，所以 `withBoardContext` 执行期间，
**其他协程发出的请求会带错参数**。

缓解措施：
- `MainViewModel.isMerging` 挡住重入
- 合并只发 GET、只跑几秒
- 失败就清空 `mergedBills` 退回单板块，不打扰用户

这是这套架构的固有缺陷。彻底解决要给每个板块一套独立的 `NetworkModule`，
改动面很大，本次不做。

## 合并结果的用途限制（重要）

`mergedBills` **只给趋势图用**。绝不能拿去做业务判断：

- 不同板块的 `orderNo` 可能撞号（都是 20 位数字，前缀规则相同）
- `SettlementReconciler` 按 orderNo/时间窗匹配，混入别板块会匹配错
- 关阀、结算、对账必须用当前板块自己的 `billHistory`

代码里 `MainViewModel.mergedBills` 的注释写明了这条。

## 实现要点

| 文件 | 职责 |
|---|---|
| `data/BillMerger.kt` | 纯函数：`merge()` 排序合并、`totalYuan()` 求和、`shortProjectName()` 去学校前缀 |
| `data/ProjectRepository.kt` | 新增 `withBoardContext()` 临时借板块上下文 |
| `utils/PrefsHelper.kt` | `mergeConsume`（用户意愿）+ `canMergeConsume`（实际生效 = 趋势开 && 合并开） |
| `ui/UserSettingsCards.kt` | `MergeConsumeToggleCard`，趋势关闭时禁用 |
| `ui/MainViewModel.kt` | `loadMergedTrend()` / `fetchRecentBills()` / `refreshMergeState()` |
| `ui/SpendingTrendCard.kt` | 新增 `mergedFrom` 参数，显示「合并」角标 |
| `ui/WalletScreen.kt` | `mergedBills ?: billHistory` |
| `test/.../BillMergerTest.kt` | 13 个用例 |

### 关键取舍

**合并时不去重。** 同一天两个板块各花一笔是两笔真实消费，
去重会让「今天花了多少」少算。

**关趋势时不改 `mergeConsume` 存储值。** 那是用户的选择，
重新打开趋势图应该恢复原状，不用再点一次。所以读的时候取交集：

```kotlin
val canMergeConsume: Boolean get() = trendEnabled && mergeConsume
```

**合并失败静默降级。** 拉不到别的板块就退回单板块数据显示，
`mergedBills = null`，用户不会看到报错。

## 学校名：确认没有硬编码

你问的「某某大学是不是硬编码」——**不是，代码里一个硬编码都没有**。

扫描结果（排除注释）：

```
=== 硬编码学校名扫描（排除注释）===
(空 = 无硬编码)
```

数据来源：
- `PrefsHelper.schoolName` 默认值是 **空串**
- 由 `MainViewModel.loadSchoolName()` 调 `/project/info/triple` 的 `projectName` 填入
- 板块名缓存在 `PrefsHelper.projectNames`，同样来自这个接口

### 但有个显示兜底要说明

`/project/info/triple` **只能问当前板块**，没切过去的板块拿不到名字。
原来会显示「项目 3356」，正式发布时用户会以为是 bug。所以加了三层兜底：

```kotlin
fun label(projectId, names, fallbackName): String
    = names[projectId]              // 1. 记住的真名（切过一次就有）
    ?: fallbackName                 // 2. 业务名兜底
    ?: "项目 $projectId"            // 3. 最后兜底
```

业务名兜底在 `MainViewModel.knownBoardName()`：

```kotlin
4000L -> "洗浴"
3356L -> "洗衣饮水吹风"
else  -> null    // 别的学校走「项目 id」，不影响正确性
```

⚠️ **这只是显示兜底，不参与任何业务判断。** 换学校时映射对不上，
但那时真名早就从接口拿到了，走不到这里。

如果你觉得连这个映射都不想留，我可以删掉——代价是首次显示「项目 3356」。
