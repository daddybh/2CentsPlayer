# Search Page Slide-In Design

## Goal

将当前底部弹出的搜索面板改成一个独立的全屏搜索页面，并以“从右向左推进”的方式进入，返回时向右滑出，保持播放器主页在底层不销毁。

## Scope

本次改动只覆盖搜索入口与搜索页面展示方式，不同时改造收藏页、AI 设置页或歌词页的展示模型。

## Current State

- 搜索当前通过 `PlayerViewModel.openSearch()` / `closeSearch()` 控制可见性。
- UI 上，搜索内容通过 `PlayerScreen.kt` 中的 `ModalBottomSheet` 承载。
- 搜索的数据逻辑、分页、结果选择、收藏切换都已经存在并可复用。

## Chosen Approach

采用轻量页面切换方案，不在本次引入 `Navigation Compose` 或正式 route 栈。

- `MainActivity` 继续只渲染 `PlayerApp()`
- `PlayerApp` / `PlayerScreen` 顶层改成“主页层 + 搜索页层”的双层结构
- 搜索页作为全屏独立页面存在，通过 Compose 过渡动画从右向左进入
- 搜索状态继续留在 `PlayerViewModel` 中，尽量不改现有搜索数据模型

这样可以实现接近原生页面推进的体验，同时将改动限制在当前页面架构内，避免一次性引入完整导航系统的额外复杂度。

## UX Behavior

### Enter

- 点击主页顶部搜索按钮后，显示独立搜索页
- 搜索页从屏幕右侧滑入
- 主页停留在底层，不销毁、不重置滚动或播放状态

### Exit

- 点击搜索页左上角返回按钮，搜索页向右滑出
- 点击 Android 系统返回键，搜索页向右滑出
- 点击搜索结果开始播放后，默认关闭搜索页并回到主页

### Playback

- 搜索页打开期间，当前播放继续进行
- 搜索页只改变用户关注层，不改变播放控制模型

## UI Structure

### Home Layer

主页保留现有播放器主结构，包括：

- Header
- Hero artwork
- Queue section
- 其他已有 overlays / sheets

搜索 `ModalBottomSheet` 将被移除。

### Search Layer

新搜索页为全屏布局，包含：

- 顶部 header
- 左上角返回按钮
- 页面标题
- 常驻搜索输入框
- 搜索结果滚动列表
- 现有空态 / 加载态 / 错误态 / 分页 footer

页面不做半屏停靠，不显示底层主页边缘，不保留 bottom sheet 的视觉语言。

## State Model

`PlayerViewModel` 新增一个轻量的页面可见状态，用于表示独立搜索页是否处于显示中。

保留现有 `SearchUiState` 中的搜索数据职责：

- query
- activeQuery
- isLoading
- isLoadingMore
- results
- pagination offsets

推荐做法：

- 页面显隐状态与搜索数据状态分离
- `openSearch()` / `closeSearch()` 改为驱动独立页面显隐
- 搜索数据继续沿用现有 `searchState`

这样可以减少对搜索流程和分页逻辑的影响，并让页面切换职责更单一。

## Animation

采用 Compose 原生动画实现页面推进效果，优先考虑：

- `AnimatedContent`
- 或 `AnimatedVisibility + slideInHorizontally / slideOutHorizontally`

目标效果：

- 进入：从右到左
- 退出：从左到右反向撤回
- 动画观感接近页面 push，而不是 drawer / bottom sheet

## Implementation Boundaries

本次不做：

- 引入 `NavHost`
- 多页面统一导航重构
- 收藏页改独立页面
- AI 设置页改独立页面
- 新的手势返回系统

## Testing Plan

### ViewModel tests

新增或更新测试，覆盖：

- 打开搜索页会切换到独立页面显示状态
- 关闭搜索页会恢复主页显示状态
- 选择搜索结果后会关闭搜索页并触发播放准备逻辑

### Regression coverage

保留并确认以下行为不受影响：

- 搜索请求与分页
- 搜索结果选择播放
- 收藏切换
- 当前播放状态

## Risks

### Risk 1: State duplication

如果把“搜索页是否显示”和“搜索数据”混在一起，后续很容易让搜索逻辑与页面展示耦合过深。

Mitigation:

- 将页面显隐状态单独建模

### Risk 2: Overlay stacking conflicts

搜索页改成全屏层后，可能与现有 favorites / AI settings / lyrics 的弹层关系冲突。

Mitigation:

- 先只替换搜索
- 保持其他 overlay 逻辑不动
- 在顶层统一排列渲染顺序

### Risk 3: Animation feels like drawer, not page

如果只做简单偏移动画，可能看起来像侧边抽屉而不是页面切换。

Mitigation:

- 使用全屏层
- 避免露出底层大面积内容
- 保持页面 header、背景和内容完整独立

## Recommended Execution Order

1. 在 `PlayerViewModel` 中引入独立搜索页显隐状态，并补 ViewModel 测试
2. 从 `PlayerScreen` 中移除搜索 bottom sheet
3. 新增独立 `SearchPage` 组件或提炼搜索内容组件
4. 在主页与搜索页之间加入右进左出的全屏动画
5. 验证选歌后关闭搜索页并回到主页
6. 跑现有单测和新增测试
