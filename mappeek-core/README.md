# MapPeek

用来回答一个问题：**在真实项目里，静态分析到底能自动识别出多少 Map 字段？**

这是《MapPeek 实施方案》里 M0 阶段的工具，不是正式插件。它不生成文档、不改代码、不需要接入业务工程，只读取源码并输出统计结果。

## 能力边界

做了：

- 定位 `@RestController` / `@Controller` 中参数或返回值含 Map 的方法
- 提取 `map.put("literal", ...)` 的字面量 key，含 `static final String` 常量折叠
- 提取 `Map.of(...)` / `Map.ofEntries(...)` 的 key
- 识别一层嵌套 Map（`data.put("page", page)` → `page.size`）
- 解析 MyBatis XML `<select>` 与 `@Select` 的列别名，支持 `<resultMap>`
- 向下跟随一层方法调用（Controller → Service）
- 标出动态 key、`SELECT *` 等无法静态确定的情况

没做：

- 类型推断（这是 M1 的事，现阶段的指标只衡量字段名）
- 跨方法的多层数据流
- `key = prefix + i` 这类动态 key 的具体取值
- 运行时采样

## 构建

需要 JDK 17 及以上。

```bash
mvn -DskipTests package
```

产物：`target/mappeek.jar`（已包含依赖，可直接运行）。

如果本机 `JAVA_HOME` 指向 JDK 8，Maven 会编译失败，需要先切换：

```powershell
$env:JAVA_HOME="D:\Java\jdk-21.0.11"
mvn -DskipTests package
```

## 使用

```bash
# 1. 扫描真实项目（report 会额外生成诊断报告与对照表）
java -jar target/mappeek.jar --src D:\work\your-project --out m0-out --format report

# 2. 填写 m0-out/mappeek-groundtruth.csv 的 expected 列（用 | 分隔字段）
#    至少挑 20 个涉及 Map 的接口填写
#    重复扫描不会覆盖已填写的 expected 列

# 3. 计算指标
java -jar target/mappeek.jar --verify m0-out/mappeek-groundtruth.csv --out m0-out
```

参数：

| 参数 | 说明 |
|---|---|
| `--src` | 必需，分析范围：目录或单个 `.java` 文件 |
| `--resolve` | 可选，解析范围，默认与 `--src` 相同；选单个类时建议给模块根，才能跟到 service |
| `--mapper` | 可选，MyBatis XML 目录，默认与 `--resolve` 相同 |
| `--out` | 可选，输出目录，默认 `./mappeek-out` |
| `--only` | 可选，只处理一个接口，格式 `Class#method` |
| `--overrides` | 可选，人工编辑过的 `mappeek-edit.csv`，用于回填字段说明 |
| `--level` | 可选，被扫描项目的 Java 语言级别，支持 8/11/17/21，默认 17 |
| `--verify` | 校验模式，读取填写好的 groundtruth |

## 扫描粒度

分析范围决定报告里出现哪些接口，解析范围决定能不能找到字段。两者分开，就可以只关心一个接口而不牺牲准确率。

```bash
# 整个模块
java -jar mappeek.jar --src D:\proj\order-service --out out

# 一个包（右键 package 的效果）
java -jar mappeek.jar --src D:\proj\order-service\src\main\java\com\demo\web --resolve D:\proj\order-service --out out

# 一个类
java -jar mappeek.jar --src D:\proj\...\OrderController.java --resolve D:\proj\order-service --out out

# 一个方法
java -jar mappeek.jar --src D:\proj\...\OrderController.java --resolve D:\proj\order-service --only OrderController#query --out out
```

只给 `--src` 时，解析范围自动等于分析范围，行为和之前一致。

## 导出格式

用 `--format` 控制，默认 `openapi,md`，`all` 表示全部：

| 格式 | 文件 | 用途 |
|---|---|---|
| `openapi` | `mappeek-openapi.json` | OpenAPI 3.0，可导入 Apifox / YApi / Postman / Swagger UI |
| `md` | `mappeek-api.md` | 接口字段文档，可直接贴到 Wiki 或发前端 |
| `dto` | `dto/*.java` | 带 JavaDoc 注释的 DTO 骨架，嵌套结构生成内部类 |
| `json` | `mappeek-api.json` | 结构化的简化版接口数据，便于二次加工 |
| `report` | `mappeek-report.md` 等 | 验证期诊断报告，含识别统计、未识别接口、groundtruth 模板 |

日常只需要默认的两个文件；`report` 只在评估扫描效果时打开。

字段说明有三个来源，优先级从高到低：`put` 上一行或行尾的注释、常量字段的 JavaDoc、SQL 的 `<resultMap>` property。类型是尽力推断，推断失败降级为 `string` / `Object` 并标 `low`。

入参与出参分开生成：出参来自方法内构建的 Map，入参来自对 Map 参数 `get/containsKey` 读到的 key。两者都没有时输出空对象，不做猜测。

```bash
java -jar mappeek.jar --src <模块根> --out out --format all
```

## 产物

| 文件 | 何时生成 | 内容 |
|---|---|---|
| `mappeek-edit.csv` | 总是 | 字段说明清单，可人工完善后用 `--overrides` 回填 |
| `mappeek-report.md` | `--format report` | 概览、每个接口的字段、未能识别的接口、诊断信息 |
| `mappeek-fields.csv` | `--format report` | 字段明细，含来源文件与行号 |
| `mappeek-groundtruth.csv` | `--format report` | 待填写的对照表 |
| `mappeek-verify.md` | `--verify` | 校验结果，含每个接口漏掉与多出的字段 |

## 验证其他项目

脚本只读源码，不编译、不改文件、不连数据库，所以对目标项目零侵入。

1. **不用先 build**。直接指向仓库根目录，脚本递归查找 `.java`，自动跳过 `target`、`build`、`src/test`。
2. **多模块项目建议分模块跑**。一次扫整个仓库也能出结果，但模块间调用归因会变松，字段「多出」的概率上升。按业务模块分开跑、分开评估更准：

   ```bash
   java -jar target/mappeek.jar --src D:\work\proj\order-service --out m0-out-order
   java -jar target/mappeek.jar --src D:\work\proj\user-service --out m0-out-user
   ```

3. **Mapper XML 不在 `src` 下时单独指定**。比如 XML 放在独立的 `resources/mybatis` 或配置仓库：

   ```bash
   java -jar target/mappeek.jar --src D:\work\proj --mapper D:\work\proj\resources\mybatis --out m0-out
   ```

4. **注意语言级别**。默认按 Java 17 解析，项目是 Java 8/11/21 时加 `--level`，否则新语法会导致解析失败：

   ```bash
   java -jar target/mappeek.jar --src D:\work\proj --level 8 --out m0-out
   ```

   解析失败的文件会列在报告「诊断信息」里，先看这个数字，非 0 就调 `--level`。

5. **只支持 Java**。Kotlin、Groovy、Scala 写的接口不在扫描范围内。

## 怎么填 expected 列

`expected` 填的是「这个接口真实返回/接收的字段」，不是「代码里能看到的字段」。

三个可靠的来源，优先级从高到低：

1. 抓一次真实请求/响应报文，字段最真实（注意脱敏，别把生产数据贴进仓库）
2. 现有接口文档（Swagger UI、YApi、Apifox 里的历史文档）
3. 读代码，把 `map.put` 和 SQL 别名过一遍

填写规则：字段之间用 `|` 分隔，嵌套用点号，不要加空格。不确定的接口把 `expected` 留空，校验时会算作未填写，不参与统计。

## 判定标准

与方案第 10 节一致：

| 召回率 | 字段名错误率 | 结论 |
|---|---|---|
| ≥ 75% | < 5% | 达标，进入 M1 |
| 60% ~ 75% | < 15% | 中间区间，建议只做 MyBatis 部分 |
| < 60% | ≥ 15% | 不达标，放弃自动化 |

召回率 = 命中字段数 / 人工填写的字段总数
字段名错误率 = 工具输出但人工未填的字段数 / 工具输出的字段总数

## 示例

`sample/` 是一个最小示例工程，包含 `map.put`、常量 key、嵌套 Map、`Map.of`、动态 key、MyBatis 别名、`Result<Map>` 六种典型情况。

```bash
java -jar target/mappeek.jar --src sample --out sample-out
```

预期结果：3 个 Map 接口，识别 7 个字段，`dynamic` 接口因 key 为动态拼接而识别不出字段——这正是脚本要暴露的边界。

## 已知限制

- 只做语法级分析，不解析类型，所以识别到的字段没有类型信息
- 嵌套 Map 依赖变量在 `put` 之前已经填充，反过来的写法会漏
- 方法调用只跟随一层，跨模块调用基本跟不到
- `SELECT *`、`resultMap` 未定义、`<include>` 片段都会导致字段不全，报告中会标出
- 控制台中文在部分 PowerShell 环境下会乱码，输出文件本身是 UTF-8，不影响结果
