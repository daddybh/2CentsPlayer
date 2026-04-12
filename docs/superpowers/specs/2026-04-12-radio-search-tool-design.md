# Radio Search Tool Design

## Goal

将 `2CentsPlayer` 当前探索电台的推荐模块改造成“模型调用搜索工具”的两阶段流程：模型先生成一次搜索工具调用，代码执行搜索并返回紧凑候选，再由模型从候选中选择结果。

## Problem

当前探索电台的启动与补队列流程依赖模型直接输出歌曲名和歌手名，再由本地代码去跨源搜索、匹配并解析可播放地址。

这条链路有两个问题：

- 模型输出的歌曲未必是搜索命中率高、可播放率高的候选
- 代码要花大量时间做“从自由文本猜可播歌曲”的匹配工作

结果是：

- AI 响应慢
- 搜索匹配慢
- 候选可播率低
- 启动失败时要多轮重试

## Chosen Approach

采用单轮工具调用方案：

1. 第一轮模型输出一次 `search_tracks` 工具调用
2. 应用执行搜索工具，返回紧凑候选集
3. 第二轮模型只从候选集中选择结果
4. 应用把候选重新映射为 `Track` 并继续走现有播放前过滤/解析链路

本次只改探索电台候选生成，不改普通 AI 推荐列表。

## Why This Approach

与直接让模型输出歌名相比，这个方案的优势是：

- 模型不再负责“猜对平台存在的歌曲”
- 模型只负责“搜索意图”和“候选选择”
- 真实搜索结果由现有 `MusicLibraryRepository` 提供
- 候选结果天然更贴近本地可搜索语义

与多轮工具调用相比，这个方案的优势是：

- 时延更可控
- 状态机更简单
- 更适合移动端探索电台启动链路

## Scope

### In Scope

- 为探索电台新增一个 `search_tracks` 工具协议
- 让探索电台候选生成支持两轮 LLM 交互
- 复用 `MusicLibraryRepository.searchTracks()`
- 返回紧凑候选集给模型
- 让模型输出 `candidateId` 而不是自由文本歌名

### Out of Scope

- 普通推荐列表 `requestRecommendations()` 改造
- 多次连续工具调用
- 新增独立搜索服务
- 新增更多搜索源
- 改动现有播放器 UI

## Architecture

### 1. Search Tool Executor

新增一个很薄的搜索工具执行器，内部直接复用：

- `MusicLibraryRepository.searchTracks(keyword, ...)`

职责：

- 接收 `query` 与 `limit`
- 执行统一搜索
- 生成紧凑候选集
- 为每条候选生成稳定 `candidateId`

推荐输出字段：

- `candidateId`
- `title`
- `artist`
- `album`
- `source`
- `durationMs`

不默认输出：

- `coverUrl`
- `audioUrl`
- 大字段元数据

### 2. Tool-Calling Flow in AI Recommendation Repository

探索电台的候选生成改为两阶段：

#### Stage A: Ask for Tool Call

模型收到：

- favorites / positive / negative / avoid 信息
- 工具定义：`search_tracks(query, limit)`
- 约束：本轮最多调用一次工具

模型输出：

- 一个 `search_tracks` 调用
- 或在极端情况下直接返回空推荐结果

#### Stage B: Search and Choose

代码执行工具搜索后，将紧凑候选集发回模型。

模型第二轮输出：

- `candidateId`
- `reason`
- `bucket`

最终不再让模型直接输出自由文本 `title/artist`。

### 3. Candidate Resolution

应用将 `candidateId -> Track` 做本地映射。

之后沿用现有链路：

- 本地负反馈过滤
- 去重
- `resolvePlayableTracks`
- `QueueComposer.compose`

这一步可以保证当前播放器与探索电台剩余逻辑几乎不变。

## Data Flow

### Exploration Radio Start

1. 用户开启探索电台
2. `PlayerViewModel.refreshAiRecommendations()` 进入 radio candidate 获取流程
3. `AiRecommendationRepository.requestRadioCandidates()` 第一轮请求模型
4. 模型返回一次 `search_tracks(query, limit)` 调用
5. 应用调用 `MusicLibraryRepository.searchTracks()`
6. 应用将搜索候选返回给模型
7. 模型返回选中的 `candidateId` 列表
8. 应用将 `candidateId` 映射成 `Track`
9. 后续继续走现有可播解析和队列拼装

## Prompt Contract

### Tool Definition

工具名：

- `search_tracks`

参数：

- `query: string`
- `limit: integer`

约束：

- 一次推荐循环最多调用一次
- `limit` 由应用限制上限
- 不允许模型指定 source、offset、复杂过滤条件

### Model Output Contract

第二轮模型输出格式：

```json
{
  "recommendations": [
    {
      "candidateId": "cand_1",
      "reason": "一句中文理由",
      "bucket": "safe"
    }
  ]
}
```

## Design Principles

### Single Source of Search Truth

所有搜索都通过 `MusicLibraryRepository.searchTracks()` 完成。

这样可以：

- 复用现有多源搜索逻辑
- 复用后续 Kotlin `Track` 模型
- 避免再引入第二套搜索体系

### Model Chooses, Code Searches

模型不再“猜歌”。

模型只负责：

- 按用户偏好形成搜索意图
- 从候选中做语义选择

代码负责：

- 执行真实搜索
- 管理候选映射
- 可播验证
- 队列安全

## Testing Strategy

### Repository Tests

新增测试覆盖：

- 当模型返回 `search_tracks` 调用时，代码会执行统一搜索
- 搜索结果会被正确压缩为候选集
- 第二轮模型返回 `candidateId` 后能映射为真实 `Track`

### Radio Integration Tests

新增测试覆盖：

- 探索电台候选生成支持两轮模型交互
- 候选选择结果仍能进入现有 `RadioReplenishmentEngine`
- 未命中 `candidateId` 时能优雅失败

### Regression Tests

保留现有：

- `RadioReplenishmentEngineTest`
- `MusicLibraryRepositoryTest`
- `PlayerViewModelTest`

## Risks

### Risk 1: Tool Calling Support Mismatch

如果当前 AI 接口不支持标准 tool calling，需要兼容层。

Mitigation:

- 在实现里优先支持标准 tool calling
- 若接口返回不支持，则退化为轻量结构化协议

### Risk 2: Candidate Set Too Large

候选太多会抬高 token 和响应时间。

Mitigation:

- 只返回紧凑字段
- 严格限制 `limit`

### Risk 3: CandidateId Mapping Drift

如果候选与最终 Track 映射不稳定，模型选择会失效。

Mitigation:

- 搜索结果返回后立即建立单次请求内映射表
- `candidateId` 只在本轮有效

## Recommended Execution Order

1. 定义工具调用数据模型和候选结果结构
2. 封装 `search_tracks` 工具执行器，复用 `MusicLibraryRepository.searchTracks()`
3. 改造 `AiRecommendationRepository.requestRadioCandidates()` 为两阶段调用
4. 把 `candidateId` 解析为 `Track`
5. 补测试并验证不会破坏现有探索电台流程
