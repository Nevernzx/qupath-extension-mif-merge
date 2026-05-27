# qupath-extension-mif-merge

QuPath 0.8 扩展：把多张 **qptiff**（多通道 mIF，Vectra Polaris 出片）以 DAPI 通道做几何配准，输出一张合并所有通道的 **pyramidal OME-TIFF**。

算法基于已验证的两阶段 SIFT 流程：粗糙缩略图 SIFT（≈4000 px）拿到初值矩阵 → 精修级别 SIFT（≈14000 px）用初值做预过滤 + RANSAC，最终精度 ~4 px @ full-res（~1 μm）。详见 [`VERIFICATION.md`](VERIFICATION.md) — 4 例真实样本端到端与 Python 参考实现对比 Δ < 2 px @ full-res。

---

## 状态

- [x] OpenCV SIFT + RANSAC（`core/SiftRansacAffine.java`）
- [x] 两阶段 runner（`core/TwoStageRegistration.java`）
- [x] 金字塔层选择（`core/DapiPyramidSelector.java`，强制两边 downsample 一致 — 实测对肺 ALK case 把 inliers 从 186 拉到 1019）
- [x] Bio-Formats qptiff 读取 + DAPI 自动识别
- [x] `TransformedServerBuilder` + `OMEPyramidWriter` 合并写出
- [x] QC 报告（checkerboard / abs-diff / overlay PNG）
- [x] GUI 命令：`Extensions > MIF Merge > Run merge…`
- [x] **4/4 测试样本端到端验证通过**

---

## 安装到 QuPath（推荐使用方式）

1. 在本仓库 clone 后构建一次：

   **Windows (PowerShell or cmd)：**
   ```cmd
   gradlew.bat clean jar
   ```

   **Linux / Mac：**
   ```bash
   ./gradlew clean jar
   ```

   首次构建会下载 JDK 25（通过 foojay）、Gradle 9.4、QuPath 0.8.0-SNAPSHOT 依赖与 bytedeco OpenCV。耗时约 5-10 分钟。

2. 产物在 `build/libs/qupath-extension-mif-merge-0.1.0-SNAPSHOT.jar`。

3. 把 jar 拖进 QuPath 主窗口（或者放到 QuPath 的扩展目录），重启 QuPath。

4. 菜单出现 **Extensions > MIF Merge > Run merge…**。点击后：
   - 选多个 qptiff（第一个是 fixed 参考）
   - 设输出路径（默认 `<fixed-stem>-merged.ome.tif`）
   - 可调参数：DAPI 名字匹配（默认 "DAPI"）、stage 1/2 目标长边
   - 点 "Run merge"，看进度日志

---

## 命令行使用（不用启动 QuPath）

适合批量跑、自动化、CI。3 个任务，全平台通用（Windows 用 `gradlew.bat`）。

### `runVerify`：用 Python 黄金矩阵验证算法

```bash
./gradlew runVerify -PcaseDir=/path/to/registrations_batch/<case>
```

读 `<caseDir>/dapi_fixed.png` + `dapi_moving.png`，跑 Java SIFT，与 `matrix_thumbnail_*.npy` 对比矩阵 + 仿射分解。容忍范围：旋转 ±0.01°，缩放 ±0.001，平移 ±2 px。

### `runRegister`：端到端 2 张 qptiff 配准 + QC PNG

```bash
./gradlew runRegister \
  -PfixedPath=path/to/fixed.qptiff \
  -PmovingPath=path/to/moving.qptiff \
  -PoutDir=path/to/output
```

输出在 `<outDir>/`：
- `matrix_full_res.json` — 机器读
- `matrices.txt` — 人读
- `qc_checkerboard.png` / `qc_abs_diff.png` / `qc_overlay.png`

### `runMerge`：N 张 qptiff 配准 + 合并 OME-TIFF

```bash
./gradlew runMerge \
  -PinputPaths=fixed.qptiff,moving1.qptiff,moving2.qptiff \
  -PoutPath=merged.ome.tif
```

第一个 input 是 fixed，其余 moving 按顺序依次配准到 fixed 坐标系。fixed 的所有通道 + 所有 moving 的非 DAPI 通道写到一张 pyramidal OME-TIFF。

---

## 目录结构

```
src/main/java/qupath/ext/mifmerge/
├── MifMergeExtension.java          # ServiceLoader 入口 + Extensions > MIF Merge 菜单
├── core/                            # 算法（与 Bio-Formats / QuPath 解耦）
│   ├── SiftRansacAffine.java         # OpenCV SIFT + Lowe + RANSAC
│   ├── TwoStageRegistration.java     # L4 → L2 两阶段
│   ├── DapiPyramidSelector.java      # 强制两边 downsample 一致
│   ├── MatrixRescaler.java           # 3x3 ↔ AffineTransform / 跨层缩放
│   ├── RegistrationResult.java
│   ├── MifImageSource.java           # 抽象接口（channels / downsamples / read）
│   ├── AutoContrast.java             # uint8/16 → 8-bit autocontrast
│   ├── RegistrationOrchestrator.java # 端到端：source pair → full-res matrix
│   └── MergedChannelLayout.java      # 合并后的通道布局（去重 DAPI 等）
├── io/
│   ├── NumpyReader.java              # 读取 Python 写的 .npy
│   └── BioFormatsMifSource.java      # MifImageSource 的 Bio-Formats 实现
├── merge/
│   ├── MergedServerFactory.java      # TransformedServerBuilder 链
│   └── OmeTiffMergeWriter.java       # OMEPyramidWriter 包装
├── qc/
│   └── QcVisualizer.java             # checkerboard / abs-diff / overlay PNG
├── gui/
│   └── MifMergeCommand.java          # Extensions > MIF Merge > Run merge… 对话框
└── verify/
    ├── VerifyAgainstPython.java      # runVerify 的 main
    ├── RegisterCli.java              # runRegister 的 main
    └── MergeCli.java                 # runMerge 的 main
```

---

## 算法关键点

1. **两阶段 SIFT**：L4 缩略图（OpenCV SIFT，nfeatures=60000）→ 拿初值矩阵 → L2 精修（同样的 SIFT，但用初值在 30 px 半径内预过滤候选匹配）→ RANSAC 仿射 → 鲁棒的 6-DOF 仿射。
2. **金字塔层选择**：按 `target_long_side / full_long_side` 算出目标 downsample factor，两边各自选最近层。避免"fixed 选 level 2、moving 选 level 3 导致 inliers 暴跌"那种坑。
3. **DAPI 自动识别**：按 channel name `contains("DAPI")` 匹配（大小写无关）。Vectra Polaris qptiff 里 DAPI 始终被命名为 "DAPI"。
4. **零拷贝虚拟合并**：用 QuPath 的 `TransformedServerBuilder.transform(...).extractChannels(...).build()` 包每张 moving，再 `concatChannels` 拼到 fixed 上。整个合并 server 是 lazy 的，每个输出 tile 按需反向投影到源 tile，**不需要把整张 WSI 读进内存**。
5. **不要用 Lucas-Kanade 精修**：在 Vectra qptiff 上失败 — FOV stitching artifacts（~0.5-1% 强度跳变）会让 LK 误以为是几何偏差。SIFT 的几何共识对此免疫。

---

## 系统要求

- **必需**：能运行 QuPath 0.8 的系统（Windows 10+、macOS 11+、Linux GLIBC ≥ 2.34）。Gradle 自动通过 foojay 下 JDK 25。
- **磁盘**：~2 GB 用于 Gradle 缓存（依赖 + JDK）。每对配准临时占 ~1 GB 内存。

详细的验证报告与复现步骤见 [`VERIFICATION.md`](VERIFICATION.md)。
