# HuaFeng室内定位

`HuaFeng室内定位` 是一个基于 Android 的室内定位与区域识别应用，支持 `BLE Beacon`、`WiFi` 和 `BLE+WiFi` 混合指纹定位。项目使用 `Kotlin + Jetpack Compose` 开发，内置地图采集、指纹管理、区域绑定、算法切换、参数调试与本地数据存储能力，适合做室内定位实验、指纹库构建、算法验证和现场部署测试。

## 功能概览

### 首页
- 自动开始扫描当前可用信号
- 展示当前扫描到的 BLE / WiFi 信号
- 根据设置的信号源模式动态显示数据

### 地图定位
- 支持默认地图和自定义地图
- 支持在地图上选点采集指纹
- 采集时显示加载提示：`正在采集，请保持静止`
- 按“收到新的有效 RSSI 数据”计数，不按固定时间片重复保存
- 每次有效采样都单独入库
- 同坐标采样会在显示和定位时做稳健合并，并剔除离谱点
- 支持地图定位红点显示
- 停止定位后红点自动消失

### 区域定位
- 基于 Beacon 的区域绑定与区域识别
- 可配置区域与 Beacon 的 `uuid / major / minor`
- 支持区域列表与区域编辑

### 设置页
- 信号源切换：
  - `仅BLE`
  - `仅WIFI`
  - `BLE+WIFI`
- 权限状态显示与一键请求
- 定位算法切换与参数保存
- 采集相关参数设置
- 开发者模式入口
  - 点击底部版本号 7 次启用
  - 每次重新打开 App 都需要重新点击进入

## 当前支持的定位算法

### 1NN
- 直接返回最近的参考点坐标

### KNN
- 使用最近的 `K` 个点做普通平均

### WKNN
- 使用距离倒数加权
- 权重形式为 `1 / d^p`

### KNN + 最强N信标
- 先取当前最强的 `N` 个信标
- 再对筛选后的信号做 KNN / 加权匹配

## 定位增强策略

除了核心算法外，项目还支持两类增强策略，并且都可开关、可保存参数。

### 1. 聚类预筛选
- 默认开启
- 按坐标网格对指纹点做预聚类
- 先选出与当前扫描最接近的若干簇，再在簇内做正式定位

可调参数：
- `网格尺寸(px)`
- `保留簇数`

### 2. 时间平滑
- 默认开启
- 对连续定位结果做指数平滑，减少红点抖动

可调参数：
- `平滑系数 α`

## 指纹采集规则

当前采集流程如下：

1. 在地图上点击目标位置
2. 点击采集按钮
3. App 开始等待新的有效扫描数据
4. 每接收到一批新的 RSSI 数据，就保存一次
5. 达到设定的有效采样次数后提前结束
6. 若未达到次数，则在“最长采集时间”到达后结束

可调参数：
- `最长采集时间`：`3s ~ 15s`
- `有效采样次数`：`1 ~ 10`

## 数据存储

项目使用 `Room` 做本地数据库存储，主要保存：

- 地图信息
- 指纹点坐标
- 指纹 RSSI 数据

采样原始数据会先完整保存，后续在地图显示、管理和定位阶段再做合并与异常值剔除。

## 权限说明

根据当前信号源模式，应用会请求不同权限：

### BLE / BLE+WiFi
- `ACCESS_FINE_LOCATION`
- `BLUETOOTH_SCAN`
- `BLUETOOTH_CONNECT`

### WiFi
- `ACCESS_FINE_LOCATION`
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_STATE`

App 启动时会主动尝试请求一次权限，设置页也可以查看权限状态并再次请求。

## 技术栈

- Kotlin
- Jetpack Compose
- Material 3
- AltBeacon Android Beacon Library
- Room
- Coil
- AndroidX Navigation

## 运行环境

- Android Studio
- JDK 11+ 编译环境
- Android `minSdk 26`
- Android `targetSdk 36`

当前应用版本：
- `versionName = 1.45`

## 本地构建

### Debug 包

```powershell
gradlew.bat :app:assembleDebug
```

### Release 包

```powershell
gradlew.bat :app:assembleRelease
```

当前项目的 `release` 配置已经开启：
- `minifyEnabled = true`
- `shrinkResources = true`

## 项目结构

主要代码位置：

- `app/src/main/java/com/huafeng/beaconzone/MainActivity.kt`
  - 应用主入口
  - 底部导航
  - 首页 / 区域页 / 设置页
  - 扫描与权限流程

- `app/src/main/java/com/huafeng/beaconzone/MapCollectScreen.kt`
  - 地图定位页
  - 地图管理
  - 指纹采集
  - 算法参数面板

- `app/src/main/java/com/huafeng/beaconzone/PositioningEngine.kt`
  - 定位算法核心
  - 距离计算
  - KNN / WKNN / 最强N信标
  - 聚类预筛选
  - 时间平滑

- `app/src/main/java/com/huafeng/beaconzone/FingerprintRepo.kt`
  - 指纹数据读写
  - 同坐标数据合并
  - 异常值剔除

- `app/src/main/java/com/huafeng/beaconzone/ZoneScreens.kt`
  - 区域配置页面

- `app/src/main/java/com/huafeng/beaconzone/ZoneDetector.kt`
  - 区域识别逻辑

## 使用建议

为了得到更稳定的定位结果，建议：

- 采集时尽量保持静止
- 同一位置至少采多次
- 地图点位间距保持在合理范围
- 手机姿态尽量一致
- 对明显异常的采样进行清理
- 正式建库时优先使用 `BLE+WiFi`

## 说明

本项目当前更偏向实验和工程验证用途，适合：

- 室内定位课程设计
- 指纹定位算法实验
- BLE / WiFi 混合定位研究
- 小范围室内空间定位测试

