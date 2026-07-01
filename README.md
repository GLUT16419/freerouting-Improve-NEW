<p align="center">
<img src="https://raw.githubusercontent.com/freerouting/freerouting/master/assets/social_preview/freerouting_social_preview_1280x960_v2.png" alt="Freerouting" title="Freerouting" align="center">
</p>
<h1 align="center">Freerouting-Improve-NEW — UTPR V7</h1>
<h5 align="center">基于 Freerouting 的改进版本 — V7 城市交通规划布线引擎 (Urban Traffic Planning Router)</h5>

<p align="center">
    <a href="https://github.com/GLUT16419/freerouting-Improve-NEW"><img src="https://img.shields.io/badge/repo-freerouting--Improve--NEW-blue" alt="Repository" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/GLUT16419/freerouting-Improve" alt="License"/></a>
</p>

> **本项目基于 [freerouting/freerouting](https://github.com/freerouting/freerouting) (v2.2.5-SNAPSHOT) 改进，实现了 V2→V7 多代布线算法，当前主力为 V7 UTPR (Urban Traffic Planning Router)。**
> 原项目是一个强大的 PCB 自动布线器，支持通过标准 Specctra 或 Electra DSN 接口与任何 PCB 设计软件配合使用。

<br/>

## 版本演进

| 版本 | 核心算法 | 状态 |
|:-----|:---------|:----:|
| **V7 (UTPR)** | CH收缩路网 + CRP多级分区 + 4D STOM时序预约 + 谱聚类 + 自动网络识别 + 智能降级 | **当前主力** |
| V6 | SpectralClusterer + 全局布线 | 可用 |
| V5 | SAT/ILP 精确求解 | 可用 |
| V2 | 自适应区域并行布线 (密度热力图 + 1D 投影均衡) | 可用 |
| V1 | 三阶段混合布线（短线优先→聚类→SAT） | 兼容 |

---

### V7 城市交通规划布线引擎 (Urban Traffic Planning Router)

核心创新：**将 PCB 布线视为城市交通规划**，引入 CH 收缩路网、CRP 多级分区、4D STOM 时空预约、谱聚类、自动网络分析与智能降级。

```
Phase 0 ─ 城市规划 (Urban Planning)
  ├── AutomaticNetworkAnalyzer   # 自动网络智能分析（类型/差分对/等长组）
  ├── ContractionHierarchies     # CH 分层路网
  ├── ProbabilisticCongestionEstimator # 概率拥塞估计
  ├── MainCorridorPlanner        # 主干道走廊保护
  ├── RegionAwareSpectralClusterer # 区域感知谱聚类
  ├── MultiLevelPartitioner      # CRP 多级分区
  ├── DistrictBoundaryPathTable  # 区边界路径表
  ├── GracefulDegradationManager # 5级智能降级策略
  └── LowRequirementRelaxationManager # 低要求网络放松

Phase 1 ─ 骨干路网 (Backbone Network)
  ├── SpatioTemporalOccupancyMap # 4D 时空占用图 (STOM)
  ├── BackboneNetSelector        # 骨干网络筛选（自动优先级）
  ├── HubFanoutTemplates         # 枢纽扇出模板
  └── TimedBidirectionalAStar    # 时序双向 A* 预约式路由

Phase 2 ─ 城区交通填充 (District Routing)
  ├── TrafficModeLayerAssigner   # 交通分流层分配
  ├── DistrictRouter             # 城区并行布线
  ├── CrossDistrictConnector     # 跨区交通对接
  └── IncrementalRerouter        # D* Lite 增量修复

Phase 3 ─ 路口精确调度 (Bottleneck SAT)
  ├── BottleneckAnalyzer         # 瓶颈路口识别
  └── BottleneckSatSolver        # 并行 SAT/ILP 精确求解 + UNSAT 恢复

Final ─ 最终清理通过
  └── SerpentineGenerator        # 蛇形线生成器（等长匹配）
```

#### V7 核心创新点

| 创新 | 说明 |
|:-----|:------|
| **CH 收缩路网** | 带捷径的层次化路由图，长距离搜索自动走"高速层" |
| **4D STOM 预约** | 时空占用图实现事前预约，取代传统事后协商 |
| **CRP 多级分区** | 三级分区 + 边界路径预计算，城区完全独立并行 |
| **时序 A\* 双向搜索** | 真双向 A\* + STOM 预约 + 差分对联动间距 |
| **自动网络识别** | 从网络名自动检测差分对、等长组、总线、高速信号 |
| **区域感知谱聚类** | 类型亲和度 + 分组引力，同类网络倾向同城区 |
| **5 级智能降级** | 差分对/等长组自动降级（严格→宽松→解除→放弃） |
| **低要求放松** | GPIO/控制信号零代价填充，优先被 ripup 释放空间 |
| **D\* Lite 增量修复** | 实时 g/rhs 值的真 D\* Lite 算法 |
| **并行 SAT 求解** | 多变体并行 + UNSAT 局部解冻恢复 |

---

### V2 自适应区域并行布线 (Adaptive Region Parallel Router)

核心创新：**密度热力图 + 1D 投影均衡**的自适应区域划分，实现 6 阶段并行路由流水线。

```
Phase 0 ─ 电源/GND 优先扇出（2层板仅 GND）
Phase 1 ─ 自适应区域划分（密度热力图 + 1D 投影均衡）
Phase 2 ─ 区域并行信号扇出（复用 Phase 1 区域）
Phase 3 ─ 区域并行短网直连（deepCopy 每区域 + 并行）
Phase 4a ─ 区域并行聚类布线（NetCluster 聚类 + deepCopy 并行）
Phase 4c ─ 串行兜底清理
Phase 5 ─ GND 网延迟布线 + Fanout-Only 接受
Phase 6 ─ 最终清理（Last-Mile + 动态约束）
```

#### V2 核心架构

| 新文件 | 说明 |
|--------|------|
| `Region.java` | 区域数据结构，核心 bbox + 5% 扩展 bbox |
| `RegionDivider.java` | 密度热力图 (50×50 grid) + 1D 垂直/水平投影均衡 |
| `RegionRouter.java` | 区域并行路由引擎：fanout / shortRoute / clusterRoute |
| `HybridBatchAutorouterWrapper.java` | V2 入口，6 阶段流水线主控 |

#### 2 层板特定优化

- **电源网**：降级为普通信号网，参与 Phase 1-4 的正常布线
- **GND 网**：保持 Phase 0 优先扇出 + Phase 5 延迟布线
- 多层板模式下电源和 GND 均享受优先级

---

### V1 遗留特性 (Hybrid Three-Phase Router)

- 三阶段混合布线（短线优先 → 聚类层分配布线 → SAT 精确求解）
- 电源网络先扇出、最后布线的策略
- 多轮拆线重布循环
- 层分配与网络标记面板
- 多线程并行清理

---

## 快速开始

### 构建项目

```bash
# 克隆仓库
git clone https://github.com/GLUT16419/freerouting-Improve-NEW.git
cd freerouting-Improve-NEW

# 编译
./gradlew build

# 运行 GUI
./gradlew run

# 运行 CLI 路由
java -jar build/libs/freerouting-*.jar -de input.dsn -do output.ses
```

### 使用 GUI

1. 启动后点击 `File` → `Open...` 选择 `.dsn` 文件
2. 点击魔术棒图标开始自动布线
3. 布线完成后 `File` → `Save as...` 保存为 `.ses` 文件

### 命令行参数

```bash
java -jar freerouting.jar -de input.dsn -do output.ses -l en
```

- `-de` — 输入 DSN 文件
- `-do` — 输出 SES 文件
- `-l` — 语言 (en/zh/de 等)
- `-inc` — 忽略的网络类别

---

## 项目结构

```
freerouting-Improve-NEW/
├── src/main/java/app/freerouting/autoroute/     # 布线引擎核心
│   ├── UrbanTrafficBatchAutorouter.java         # V7 主入口 (UTPR)
│   ├── AutomaticNetworkAnalyzer.java            # [V7] 自动网络智能分析
│   ├── NetClass.java                            # [V7] 网络分类元数据
│   ├── RegionAwareSpectralClusterer.java        # [V7] 区域感知谱聚类
│   ├── GracefulDegradationManager.java          # [V7] 智能降级管理
│   ├── LowRequirementRelaxationManager.java     # [V7] 低要求放松管理
│   ├── DegradationEvent.java                    # [V7] 降级事件记录
│   ├── ContractionHierarchies.java              # [V7] CH 收缩路网
│   ├── SpatioTemporalOccupancyMap.java          # [V7] 4D STOM
│   ├── TimedBidirectionalAStar.java             # [V7] 时序双向 A*
│   ├── MultiLevelPartitioner.java               # [V7] CRP 多级分区
│   ├── DistrictBoundaryPathTable.java           # [V7] 区边界路径表
│   ├── BackboneNetSelector.java                 # [V7] 骨干网络选择
│   ├── CrossDistrictConnector.java              # [V7] 跨区对接
│   ├── IncrementalRerouter.java                 # [V7] D* Lite 修复
│   ├── SerpentineGenerator.java                 # [V7] 蛇形线生成器
│   ├── Region.java                              # [V2] 区域数据
│   ├── RegionDivider.java                       # [V2] 区域划分
│   ├── RegionRouter.java                        # [V2] 区域路由
│   ├── HybridBatchAutorouterWrapper.java        # [V2] V2 入口
│   ├── BatchAutorouter.java                     # 核心路由循环
│   ├── NetCluster.java                          # 网络聚类
│   └── ...
├── src/main/resources/                          # 资源文件
├── src/test/java/                               # 测试代码
├── build.gradle                                 # Gradle 构建
├── settings.gradle
└── gradlew / gradlew.bat                        # Gradle Wrapper
```

> 注意：本仓库不包含 `scripts/`、`fixtures/`、`integrations/`、`website/` 等大目录，仅包含编译和运行必需的文件。

---

## 系统要求

- **Java JDK 17+** (推荐 21 或 25)
- **Gradle** (通过 wrapper 自动下载)

---

## 许可

本项目基于原 Freerouting 的许可证发布。详见 `LICENSE` 文件。
