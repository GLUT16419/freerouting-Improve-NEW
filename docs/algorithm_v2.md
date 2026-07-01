# V2 Adaptive Region Parallel Router 算法文档

## 概述

**V2 Adaptive Region Parallel Router**（自适应区域并行布线器）是 FreeRouting 的默认布线算法，采用**6 阶段自适应流水线**架构。核心思路是通过**空间划分 → 区域并行 → 逐阶段降级**的方式，渐进式地解决 PCB 布线问题。

- **版本**: V2 (ccf87ad)
- **入口类**: `HybridBatchAutorouterWrapper` (extends `BatchAutorouter`)
- **核心文件**: `autoroute/` 包下约 40 个文件

---

## 整体架构

### 6 阶段流水线

```
Phase 0: Power/GND 优先级扇出
    |
    ▼
Phase 1: 自适应区域划分 (Region Division)
    |
    ▼
Phase 2: 区域并行信号扇出 (Region-based Parallel Signal Fanout)
    |
    ▼
Phase 3: 区域并行短线路由 (Region-based Parallel Short Net Routing)
    |
    ▼
Phase 4: 区域并行集群路由 + 串行降级 (Region-based Cluster Routing + Sequential Fallback)
    |
    ▼
Phase 5: GND 网络延迟路由与接受 (GND Net Delayed Routing + Acceptance)
    |
    ▼
Phase 6: 最终清理 — Last-mile 多种子重布 + 动态约束优化
```

### 关键设计原则

1. **渐进式降级**: 从最并行的区域路由开始，逐阶段退回到标准串行 ripup/reroute
2. **空间分治**: 通过自适应区域划分将大板分割为多个独立区域并行处理
3. **先 PG 后信号**: Power/GND 网络优先扇出，信号网络随后，未布通 PG 最后处理
4. **无冲突合并**: 区域独立布线后通过 deepCopy + merge 模式合并回主板

---

## 各阶段详解

### Phase 0 — Power/GND 优先级扇出

**方法**: `runBatchLoop()` 约 92-123 行

| 场景 | 行为 |
|------|------|
| 2 层板 | 仅 GND 网络优先扇出 |
| 多层板 | Power + GND 网络优先扇出 |

- 使用 `PowerGndAutoLabeler` 自动识别电源/地网络
- 调用 `BatchFanout.parallelFanoutBoard()` 进行批量扇出
- 目的: 确保电源/地网络先获得扇出过孔，为信号网络腾出布线空间

### Phase 1 — 自适应区域划分

**方法**: `runBatchLoop()` 约 125-148 行

```
核心: RegionDivider.divideBoard()
原理:
  1. 遍历所有引脚，建立密度热力图 (50×50 网格)
  2. 排除 Power/GND 引脚 (因为它们不应影响信号网络的区域划分)
  3. 1D 投影到 X 轴 → 按密度波谷切分列
  4. 1D 投影到 Y 轴 → 按密度波谷切分行
  5. 生成 aspect-ratio 感知的 N 行 × M 列网格
  6. 为每个网格生成 Region 对象，过滤空区域
```

- 区域数 = CPU 核心数 (自适应)
- 如果仅 1 个活跃区域，跳过 Phase 2-4 (区域并行路由不适用)

### Phase 2 — 区域并行信号扇出

**方法**: `RegionRouter.regionFanout()`

```
流程:
  1. 将 SMD 引脚分配到最近活跃区域
  2. 每个区域 deepCopy 整板
  3. 各区域独立运行 BatchFanout (仅限本区域组件)
  4. 收集所有区域的扇出结果
  5. 通过 mergeNewItems() 合并回主板
```

- 仅当活跃区域数 > 1 时执行
- 使用线程池并行 (每个区域一个线程)
- 超时: 120 秒/区域

### Phase 3 — 区域并行短线路由

**方法**: `RegionRouter.regionShortRoute()`

```
流程:
  1. 收集所有未完成的 airline (未连接网络)
  2. 按曼哈顿距离排序，提取最短的 100-300 对
  3. deepCopy 整板
  4. 在副本上对每个短 pair 调用 routeNet(netNo, 1)
  5. 每路由一个 pair 后执行 simpleDefensiveTruncation
  6. merge 回主板
```

- 阈值: 最短距离的前 25% (最少 100 对, 最多 300 对)
- 排除 Power/GND 网络
- 单线程串行 (区域并行尚未实现)

### Phase 4 — 区域并行集群路由 + 串行降级

**方法**: `RegionRouter.regionClusterRoute()` + `super.runBatchLoop()`

#### Phase 4a: 区域集群路由

```
流程:
  1. 收集未完成信号网络
  2. NetCluster.clusterNets() 按空间重叠度 (>30%) 贪心聚类
  3. 每个集群 deepCopy 整板，设置 netsToRoute
  4. 各集群独立运行 BatchAutorouter.runBatchLoop()
  5. merge 回主板
  6. 对合并后的冲突执行 simpleDefensiveTruncation
```

- 集群大小限制: 最多 20 个网络
- 使用线程池 + work-stealing 并行
- 超时: 120 秒/集群

#### Phase 4c: 串行降级

- 当区域并行路由不适用或失败时降级
- 调用 `super.runBatchLoop()` → 标准 `BatchAutorouter` ripup/reroute 循环
- `stopAtPassMinimum = 4`, `stagnationPassLimit = 5`
- 预先填充 powerGndNetsToSkip 跳过已知无法布通的 PG 网络

### Phase 5 — GND 网络延迟路由与接受

```
流程:
  1. 收集 GND 网络的未完成 airline
  2. 调用 runBatchLoop() 快速清理
  3. acceptFanoutOnlyForGndNets(): 将有扇出过孔的 GND 连接标记为"已接受"
     - 在 failureLog 中记录 99 次失败
     - 使后续 pass 跳过这些连接
```

- 使用松弛的过孔成本 (via_cost × 0.7)
- 高 ripup 成本 (start_ripup_costs × 5)
- 激进停滞限制: stopAtPassMinimum=2, stagnationPassLimit=3

### Phase 6 — 最终清理

**方法**: `lastMileRouter()` + `dynamicConstraintRouting()`

#### Last-mile Router (≤3 未完成连接时)

```
流程:
  1. 计算剩余未完成 airline 的 AABB 边界框
  2. 删除 AABB 内的所有走线 (不删过孔和引脚)
  3. 使用 3 个随机种子分别尝试重布
  4. 选择评分最好的结果
```

#### Dynamic Constraint Routing

- 降低过孔成本 (via_cost × 0.7)
- 提高 ripup 成本 (× 5)
- 如果效果更好则保留，否则回退

---

## 核心数据模型

### NetCluster — 网络集群

```java
class NetCluster {
    int clusterId;              // 集群 ID
    List<Integer> netNumbers;   // 内含的网络编号列表
    double minX, minY,          // 外包盒边界
           maxX, maxY;

    // 聚类方法: 按空间重叠度 (IOU > 30%) 贪心聚类
    static List<NetCluster> clusterNets(BasicBoard, Set<Integer> nets);
}
```

- 聚类阈值: `OVERLAP_THRESHOLD = 0.3` (IOU > 30%)
- 集群上限: `CLUSTER_SIZE_LIMIT = 20` 个网络
- 外包盒扩边: `BOUNDING_BOX_MARGIN = 0.2` (20%)

### LayerTaskQueue — 层任务队列

- 每个信号层一个队列
- 存储分配到该层的 NetCluster
- 支持 work-stealing (空闲线程从其他层偷取任务)

---

## 电源/地网络识别

**类**: `PowerGndAutoLabeler`, `NetType`

### NetType.fromName(name) 匹配规则

| 类别 | 匹配条件 (部分) |
|------|----------------|
| **GROUND** | `GND`, `GND.`, `VSS`, `AGND`, `DGND`, `GND_*`, `GND-*`, `GND.*`, 含 `_GND`/`-GND` 等 |
| **POWER** | `VCC`, `VDD`, `3V3`, `+5V`, 以 `+`/`-` 开头, 以 `V` 结尾, `V[0-9]` 格式等 |
| **SIGNAL** | 默认回退 |

### 平面网络分类

- `detectPlaneNets()`: 扫描所有不可布线的连接（铜皮/平面），按其网络名称分类
- 先按 `NetType.fromName()` 分类，失败则通过 `determineTypeFromConnectivity()` 根据引脚名判断

---

## 并行化机制

### 线程模型

| 阶段 | 并行策略 | 线程数 |
|------|---------|--------|
| Phase 2 | 每个区域一个独立线程 | min(区域数, CPU核数) |
| Phase 3 | 单线程串行 | 1 |
| Phase 4a | 每个集群一个独立线程 | min(集群数, CPU核数) |
| Phase 4c | 单线程 | 1 |

### deepCopy + merge 模式

每个并行任务在整板的 deepCopy 上独立运行，完成后通过 mergeNewItems() 合并回主板：

```
1. deepCopy: board.deepCopy() → 获得完整快照
2. 在副本上独立布线
3. merge: 序列化反序列化新 Item (深拷贝) → 插入主板 item_list
4. baseItemIds 跟踪已合并的 Item ID
```

### Work-stealing

- `RegionRouter.stealTask()`: 当某层队列为空时，从其他队列偷取任务
- 选择 pending 任务最多的队列偷取

---

## 配置参数

在 `HybridRouterSettings.java` 中定义：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `phase0Enabled` | true | Phase 0 开关 |
| `phase1Enabled` | true | Phase 1 开关 |
| `phase2Enabled` | true | Phase 2 开关 |
| `phase3Enabled` | true | Phase 3 开关 |
| `congestionCellSizeUm` | 2000 | 拥塞图网格大小 (μm) |
| `shortNetThresholdUm` | 5000 | 短网络阈值 (μm) |
| `maxNetsPerCluster` | 20 | 每集群最大网络数 |
| `negotiationMaxIterations` | 50 | 协商循环最大迭代 |
| `qualityLevel` | BALANCED | DRAFT/BALANCED/HIGH 三级 |

### 质量级别

| 级别 | 启用阶段 | 特点 |
|------|---------|------|
| `DRAFT` | Phase 0+1 | 最快，跳过聚类和 SAT |
| `BALANCED` | Phase 0+1+2 | 默认，均衡质量与速度 |
| `HIGH` | Phase 0+1+2+3 | 最慢，启用全部阶段和 SAT 收尾 |

---

## 关键技术限制

1. **无跨界协商**: 各区域/集群独立布线后直接 merge，不处理区域间冲突
2. **simpleDefensiveTruncation**: 仅删除板框外的走线，不生成中转过孔
3. **贪心聚类**: 使用简单的 IOU 空间重叠度聚类，不感知网络连通性/电气约束
4. **No work-stealing in Phase 4c**: 串行降级阶段退化为单线程
5. **V5/V6 增强已在实验分支中**: 拥塞反馈、软代价层分配、谱聚类等已在后续版本实现
