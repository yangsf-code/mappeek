# mapdoc 实施方案

> 目标：把存量 Java 接口里 `Map` 类型的入参 / 出参，自动补全成前端可用的字段文档。
> 定位：**存量迁移工具**，不是长期驻留的文档平台。

## 1. 目标与非目标

### 1.1 目标

- 扫描 Controller 中的 `Map` / `Result<Map>` / `List<Map>` 参数与返回值
- 从两类来源自动提取字段：手写 `map.put(...)`、MyBatis 查询列别名
- 产出一份可提交、可 review、可 diff 的 `map-schema.json`
- 通过 springdoc 在 `/v3/api-docs` 中把 `Map` 的 `additionalProperties: {}` 替换为真实字段
- 每个字段标注置信度与源码位置，支持人工确认与覆盖

### 1.2 非目标（第一版明确不做）

- 不做 IDE 插件
- 不做 Gradle 插件
- 不支持 Swagger 2 / Springfox
- 不支持运行时采样反推
- 不做跨方法的过程间数据流分析
- 不自动修改业务代码，不自动替换 Map 为 DTO

范围收窄是方案可落地的前提。每增加一个维度，维护成本都是阶跃式的。

## 2. 总体架构

```
mapdoc/
├── mapdoc-annotations/          零依赖，人工兜底注解
├── mapdoc-core/                 分析引擎，不依赖 Maven / Spring / IDE
├── mapdoc-maven-plugin/         构建期载体，调用 core，产物写进 target/classes
└── mapdoc-spring-boot-starter/  运行期载体，读产物，改 OpenAPI
```

依赖方向严格单向：`plugin -> core`、`starter -> core`，`core` 不反向依赖任何东西。这是后续增加 CLI、Gradle、IDE 载体的前提。

同一份 `core` 必须能被两种调用方式复用：

- 构建期：输入源码根目录 + classpath，输出 JSON
- 工具期：输入同上，输出统计报告（dry-run）

## 3. 数据契约 `map-schema.json`

契约是整个项目的核心资产，先定、后写代码、只加不改。

```json
{
  "schemaVersion": 1,
  "toolVersion": "0.1.0",
  "generatedAt": "2026-11-23T10:00:00Z",
  "apis": {
    "com.demo.web.OrderController#query": {
      "httpMethod": "POST",
      "path": "/orders/query",
      "bindings": {
        "q": {
          "role": "requestBody",
          "kind": "object",
          "open": false,
          "fields": [
            {
              "path": "userId",
              "type": "integer",
              "format": "int64",
              "required": true,
              "nullable": false,
              "description": "用户 ID",
              "confidence": "high",
              "origin": {
                "kind": "put",
                "file": "src/main/java/com/demo/service/OrderService.java",
                "line": 42
              }
            },
            {
              "path": "page.size",
              "type": "integer",
              "format": "int32",
              "required": false,
              "confidence": "medium",
              "origin": { "kind": "put", "file": "...", "line": 58 }
            }
          ]
        }
      }
    }
  }
}
```

### 3.1 字段语义

| 字段 | 说明 |
|---|---|
| `role` | `requestBody` / `requestParam` / `responseBody` |
| `open` | `true` 表示存在无法静态确定的动态 key，渲染为 `additionalProperties: true` |
| `confidence` | `high` 字面量或注解；`medium` 动态 SQL 并集、推断类型；`low` 类型推断失败 |
| `origin` | 字段来源，用于溯源与 IDE 后续跳转；`line` 从 1 开始 |
| `path` | 嵌套用点号扁平化，运行时再还原成树，便于 diff 与 review |

### 3.2 版本演进规则

- 只允许新增可选字段，不允许改语义、不允许删除字段
- `schemaVersion` 在破坏性变更时 +1，starter 遇到不认识的版本直接跳过并告警
- 契约变更必须同时更新单测 fixture 与 starter 的兼容测试

## 4. 核心引擎

### 4.1 流水线

```
定位接口 -> 反向切片 -> 提取字段 -> 推断类型 -> 合并来源 -> 计算置信度 -> 输出
```

五个阶段各自独立可测，任何阶段失败只记录 diagnostic 并跳过当前接口，不中断整个构建。

### 4.2 对外 API

```java
public interface MapSchemaAnalyzer {
    AnalysisResult analyze(AnalysisRequest request);
}

public record AnalysisRequest(
        List<Path> sourceRoots,
        List<Path> classpath,
        List<String> includePackages,
        AnalysisOptions options) {}

public record AnalysisResult(
        Map<String, ApiSchema> apis,
        List<Diagnostic> diagnostics) {}
```

`core` 里不出现 Maven 的 `MavenProject`、Spring 的 `HandlerMethod`。载体负责把各自环境翻译成 `AnalysisRequest`。

### 4.3 接口定位

判定条件收紧，避免误扫：

- 类上有 `@RestController` 或 `@Controller`
- 方法上有 `@RequestMapping` / `@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@PatchMapping`
- 参数或返回值中包含 `Map`，或泛型实参中包含 `Map`

路径拼接：类级 `@RequestMapping.value` + 方法级路径；多路径取第一个，其余记 diagnostic。

### 4.4 反向切片

从返回值表达式（或标注了 `@RequestBody` / `@MapSchemaRef` 的参数）出发，只收集能流到终点的赋值。

变量级符号表，结构为 `变量名 -> 字段集合`：

```java
Map<String, Object> page = new HashMap<>();
page.put("size", 10);
Map<String, Object> root = new HashMap<>();
root.put("page", page);      // 合并为 page.size
return root;
```

需要识别的写操作：

- `put` / `putIfAbsent` / `replace` / `computeIfAbsent`（key 必须是字面量或可折叠常量）
- `putAll`（参数若为已知 Map 变量则合并）
- `Map.of(...)` / `Map.ofEntries(...)` / `ImmutableMap.of(...)`
- `new HashMap<>(existingMap)`

明确不做的事：

- 不展开循环里的 `put(prefix + i, ...)`，直接置 `open = true`
- 不跨方法跟随超过一层；跟随失败同样置 `open = true`
- 不处理方法引用、Stream 归约构造的 Map

### 4.5 类型推断规则

按优先级匹配，命中即停：

| value 表达式 | 推断结果 |
|---|---|
| 字面量 | `integer` / `number` / `boolean` / `string` |
| `CastExpr` | 目标类型 |
| `NameExpr` | 变量声明类型 |
| `MethodCallExpr` | 方法返回类型（SymbolSolver 解析） |
| `FieldAccessExpr` | 字段类型 |
| 三元表达式 | 两支类型一致则取该类型，否则 `string` + `low` |
| 其他 / 解析失败 | `string` + `low` |

Java 类型到 OpenAPI 类型的映射表单独维护一份，集中在一处，禁止散落在各阶段。

### 4.6 置信度规则

| 场景 | confidence |
|---|---|
| 字面量 key + 明确类型 | high |
| 常量折叠后的 key（`static final String`） | high |
| MyBatis 静态 SQL 别名 | high |
| MyBatis 动态 SQL 分支并集 | medium |
| 类型推断失败，退化为 string | low |
| `@MapField` 显式声明 | high（覆盖一切自动结果） |

渲染规则与置信度绑定：`low` 不写进 `required`，description 里追加「自动推断，待确认」。

### 4.7 常量折叠

只支持这几种，超出范围直接降级：

- 同类中 `static final String KEY = "..."` 的直接初始化
- 接口常量引用
- 枚举 `name()` 作为 key 的场景不支持，记 diagnostic

实现要点：取 `NameExpr.resolve()` 得到 `ResolvedFieldDeclaration`，再回到 AST 找 `VariableDeclarator` 的初始化表达式做字面量求值。跨模块拿不到源码时，尝试读常量池（可选，第一版可不做）。

## 5. MyBatis 解析

### 5.1 入口

两个来源都要覆盖：

- XML mapper：`<select>` 的 `id` 与 `resultType` / `resultMap`
- 注解 mapper：`@Select`

命中条件：`resultType` 为 `java.util.Map` / `hashmap`，或方法返回类型含 `Map`。

### 5.2 列别名提取

用 JSqlParser 解析 `SELECT`，取每个 `SelectExpressionItem`：

- 有 alias 用 alias
- 无 alias 用 `Column.getColumnName()`
- `SELECT *` 无法确定，置 `open = true` 并记 diagnostic

动态 SQL 处理：先把 XML 文本节点的内容拼接、把 `<where>` / `<if>` / `<choose>` / `<bind>` 等标签替换为占位或剔除，再交给 JSqlParser。多分支的字段取并集，置信度降为 `medium`。

> JSqlParser 不同版本 API 有差异（`PlainSelect` / `Select`、`getSelectItems()` 的归属），实现时按实际依赖版本调整，隔离在一个 adapter 类里。

### 5.3 与接口的关联

按优先级：

1. 显式绑定：接口参数上有 `@MapSchemaRef("OrderMapper#selectSummary")`
2. 同模块内的直接调用链：`Controller -> Service -> Mapper`，只跟随一层，方法名 + 参数个数匹配
3. 都失败：该 Map 不出字段，只输出 `open: true`

不要试图做全项目调用图。这一层是误报的主要来源，宁可漏。

## 6. 构建期插件

### 6.1 默认行为

**默认 `mode=report`，只产出报告，不写 schema、不改任何东西。** 这是降低接入阻力的关键设计。

```xml
<plugin>
  <groupId>io.github.mapdoc</groupId>
  <artifactId>mapdoc-maven-plugin</artifactId>
  <version>0.1.0</version>
  <executions>
    <execution>
      <phase>process-classes</phase>
      <goals><goal>scan</goal></goals>
    </execution>
  </executions>
  <configuration>
    <includePackages>
      <package>com.demo.web</package>
    </includePackages>
    <mode>report</mode>          <!-- report | write -->
    <reportFile>${project.build.directory}/mapdoc/report.md</reportFile>
    <failOnError>false</failOnError>
  </configuration>
</plugin>
```

### 6.2 产物位置

`write` 模式下写到 `target/classes/META-INF/mapdoc/map-schema.json`，会被自动打进 jar，starter 直接用 `ClassPathResource` 读取，零额外配置。

### 6.3 报告内容

dry-run 报告必须包含四个数字，它们是决定项目是否继续的唯一依据：

- 扫描到的 Map 接口数
- 自动识别字段数
- `confidence=low` 的字段数及占比
- 解析失败的文件与原因清单

### 6.4 构建稳定性

- 任何解析异常都不得导致 `BUILD FAILURE`，除非显式开启 `failOnError`
- 单文件解析失败记录 diagnostic 后继续
- 插件执行耗时应设上限，超时则降级为跳过并告警

## 7. 运行期注入

### 7.1 自动配置

```java
@AutoConfiguration
@ConditionalOnProperty(name = "mapdoc.enabled", havingValue = "true")
public class MapDocAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OperationCustomizer mapDocCustomizer(MapSchemaRegistry registry) {
        return new MapDocCustomizer(registry);
    }
}
```

默认关闭。开启方式：

```yaml
mapdoc:
  enabled: true
```

### 7.2 替换逻辑

```java
@Override
public Operation customize(Operation operation, HandlerMethod handlerMethod) {
    String apiKey = handlerMethod.getBeanType().getName()
            + "#" + handlerMethod.getMethod().getName();
    ApiSchema api = registry.find(apiKey);
    if (api == null) {
        return operation;
    }
    // 1. @RequestParam / @PathVariable 形式的 Map
    operation.getParameters().forEach(p -> replaceIfMap(p.getSchema(), api, p.getName()));
    // 2. requestBody
    if (operation.getRequestBody() != null) {
        replaceInContent(operation.getRequestBody().getContent(), api, "requestBody");
    }
    // 3. responses（需要穿透 Result<T>）
    operation.getResponses().values().forEach(r -> replaceInContent(r.getContent(), api, "responseBody"));
    return operation;
}
```

### 7.3 递归替换规则

目标节点的判定条件，二者满足其一：

- `schema.getType() == "object"` 且 `schema.getProperties()` 为空
- `schema.getAdditionalProperties()` 为 `Boolean.TRUE`

命中后替换为 `ObjectSchema`，字段按 `path` 还原成嵌套结构。遍历时保留原节点上的 `description` 与 `example`。

`Result<T>` 穿透复用 springdoc 已生成的模型，不重新解析 Java 类型：递归遍历 `properties`，遇到 `data` / `result` / `payload` 等包装属性时继续下钻，直到命中 Map 节点。

### 7.4 幂等与安全

- 只替换命中的节点，其余 schema 原样返回
- `registry` 为空或版本不匹配时，整条链路静默跳过并打 warning，绝不影响 `/v3/api-docs` 正常输出

## 8. 人工兜底

```java
@Target({ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface MapSchemaRef {
    String value();          // 关联 map-schema.json 中的 key
}
```

```java
@Target({ElementType.PARAMETER, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(MapFields.class)
public @interface MapField {
    String path();
    String type() default "string";
    boolean required() default false;
    String description() default "";
    String example() default "";
}
```

优先级：`@MapField` > 静态分析 > 不做。注解只用于补漏，不承担主要工作量，否则工具就失去意义了。

## 9. 质量保障

### 9.1 测试分层

| 层次 | 方式 | 覆盖内容 |
|---|---|---|
| 单测 | 源码字符串作为 fixture | put 字面量、常量折叠、Map.of、嵌套、三元降级、动态 key |
| 单测 | SQL 字符串作为 fixture | 别名、无别名、`SELECT *`、动态 SQL 并集 |
| 契约测试 | 固定 JSON 与快照比对 | `map-schema.json` 结构稳定性 |
| 集成测试 | `@SpringBootTest` + 请求 `/v3/api-docs` | Map 处出现 properties，`Result<Map>` 能穿透 |

### 9.2 fixture 目录约定

```
mapdoc-core/src/test/resources/fixtures/
├── put/               每个场景一个子目录，含 input.java + expected.json
├── constant/
├── nested/
├── sql/
└── dynamic-sql/
```

一个场景一个目录，便于新增时不影响已有断言。

### 9.3 验收指标

召回率与错误率必须由真实项目产出，不能靠 fixture 自证。定义：

- 召回率 = 文档中正确出现的字段数 / 实际存在的字段数
- 字段名错误率 = 错误字段名数量 / 自动输出字段总数

## 10. 里程碑

每个阶段都有明确的进入条件和退出条件，不达标不进下一阶段。

### M0 可行性验证（不写插件）

- 输入：真实项目 20 个 Map 接口
- 做法：一个独立脚本，只做字面量 key 与 SQL 别名提取，不做类型推断
- 退出条件：召回率 ≥ 75% 且字段名错误率 < 5%
- 不达标处理：降级为「注解 DSL + 一致性校验」工具，或只做 MyBatis 一半

### M1 最小闭环

- 完成 `core` + `annotations` + `starter`
- 只支持 `put` 字面量与常量，只支持 `requestBody`
- 退出条件：2 个真实项目接入，文档中 Map 处出现字段，`Result<Map>` 能穿透
- 退出条件：注解覆盖能生效，且关闭开关后文档与接入前完全一致

### M2 覆盖率达标

- 补齐 MyBatis XML 与注解两种来源
- 补齐出参、`List<Map>`、嵌套 Map
- 退出条件：dry-run 报告显示 `low` 占比 < 20%，且无 `SELECT *` 之外的静默失败

### M3 稳定性与交付

- Maven 插件进入正式构建，`mode=write`
- 契约兼容测试、版本升级策略、失败降级全部就位
- 退出条件：连续若干次构建无新增 diagnostic 级别错误，接入项目文档无人反馈字段错误

## 11. 风险与对策

| 风险 | 触发信号 | 对策 |
|---|---|---|
| 类型推断不准，前端踩坑 | 类型错误反馈出现 1 次以上 | 宁可降级 `string` + `low`，绝不猜；`low` 不进 required |
| 解析器在多模块下拿不到 classpath | 大量 unknown 类型 | 要求显式配置模块依赖；拿不到就退化为语法级分析 |
| 误改现有文档，引发反弹 | 接入首个项目后出现文档异常 | 默认 `mode=report`；`write` 需显式开启 + 包白名单 |
| 与 smart-doc 双源冲突 | 同一个接口两套文档不一致 | 只选一个宿主，不同时启用；这也是非目标的由来 |
| 兼容性需求失控 | issue 中出现新框架诉求 | 明确支持矩阵：Java 17 + Spring Boot 3 + springdoc 2 + MyBatis；其余走 issue 模板 |
| 维护成本超过收益 | 每次升级都需改解析器 | 把 JavaParser / JSqlParser 隔离在 adapter 层，锁定版本，不追新 |

## 12. 放弃条件

出现以下任一情况，停止投入，转为「注解 DSL + 一致性校验」的轻量工具：

- M0 召回率 < 60%
- 字段名错误率 > 15%
- 接入两个项目后，人工修正的工作量超过手工补文档
- 维护单个版本适配的成本，超过它为团队节省的时间

提前写清楚放弃条件，是为了让投入有边界，而不是做到一半骑虎难下。

## 13. 技术选型

| 用途 | 选型 | 说明 |
|---|---|---|
| 源码解析 | JavaParser + javaparser-symbol-solver | 能拿 AST 与常量，比 ASM 更适合 |
| SQL 解析 | JSqlParser | 隔离在 adapter，锁定版本 |
| 文档注入 | springdoc-openapi 2.x | `OperationCustomizer` 是稳定扩展点 |
| JSON | Jackson | 与 Spring 体系一致 |
| 构建插件 | maven-plugin-api 3.9 | 绑 `process-classes` |
| 测试 | JUnit 5 + AssertJ | 契约测试用快照 |

最低运行环境：JDK 17。

## 14. 下一步

1. 写 M0 验证脚本，拿真实项目跑出召回率与错误率
2. 数字达标后再初始化四个模块，先实现 `core` 的 `put` 字面量分支
3. `starter` 与 `core` 并行开发，用 fixture 保证契约一致
4. M1 验收通过后再决定是否扩到 MyBatis
