<p align="center">
<img src="https://raw.githubusercontent.com/freerouting/freerouting/master/assets/social_preview/freerouting_social_preview_1280x960_v2.png" alt="Freerouting" title="Freerouting" align="center">
</p>
<h1 align="center">Freerouting-Improve-NEW</h1>
<h5 align="center">基于 Freerouting 的改进版本 — V2 自适应区域并行布线系统</h5>

<p align="center">
    <a href="https://github.com/GLUT16419/freerouting-Improve-NEW"><img src="https://img.shields.io/badge/repo-freerouting--Improve--NEW-blue" alt="Repository" /></a>
    <a href="LICENSE"><img src="https://img.shields.io/github/license/GLUT16419/freerouting-Improve" alt="License"/></a>
</p>

> **本项目基于 [freerouting/freerouting](https://github.com/freerouting/freerouting) (v2.2.5-SNAPSHOT) 改进，实现了 V2 自适应区域并行布线算法。**
> 原项目是一个强大的 PCB 自动布线器，支持通过标准 Specctra 或 Electra DSN 接口与任何 PCB 设计软件配合使用。

<br/>

## 项目特点

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
├── src/
│   ├── main/java/app/freerouting/     # 核心源码
│   │   ├── autoroute/                  # 布线引擎
│   │   │   ├── Region.java             # [NEW] V2 区域数据
│   │   │   ├── RegionDivider.java      # [NEW] V2 区域划分
│   │   │   ├── RegionRouter.java       # [NEW] V2 区域路由
│   │   │   ├── BatchFanout.java        # [MOD] 区域扇出支持
│   │   │   ├── HybridBatchAutorouterWrapper.java # [NEW] V2 入口
│   │   │   ├── BatchAutorouter.java    # 核心路由循环
│   │   │   ├── NetCluster.java         # 网络聚类
│   │   │   └── ...
│   │   ├── board/                      # 电路板数据
│   │   ├── gui/                        # 界面
│   │   ├── interactive/                # 交互逻辑
│   │   └── api/                        # REST API
│   ├── main/resources/                 # 资源文件
│   └── test/java/                      # 测试代码
├── build.gradle                        # Gradle 构建
├── settings.gradle
└── gradlew / gradlew.bat               # Gradle Wrapper
```

> 注意：本仓库不包含 `scripts/`、`fixtures/`、`integrations/`、`website/` 等大目录，仅包含编译和运行必需的文件。

---

## 系统要求

- **Java JDK 17+** (推荐 21 或 25)
- **Gradle** (通过 wrapper 自动下载)

---

## 许可

本项目基于原 Freerouting 的许可证发布。详见 `LICENSE` 文件。
