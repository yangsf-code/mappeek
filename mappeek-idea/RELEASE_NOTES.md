# MapPeek <!-- version:begin -->0.1.1<!-- version:end --> 发布说明 / Release Notes

## 中文

**版本**：<!-- version:begin -->0.1.1<!-- version:end -->（首次发布）　**发布日期**：2026-11-24　**兼容**：IntelliJ IDEA 2023.3 (233) 及以上

### 一句话

把 Java 接口里 `Map<String, Object>` 的入参、出参字段从源码里捞出来，在 IDE 内确认后一键导出
JSON Schema 或 OpenAPI 3.0。

### 本次新增

- **Map 字段识别**：支持 `map.put` 字面量、常量 key、`Map.of`，可识别嵌套 Map 与 `List<Map>`
- **MyBatis 支持**：解析 `@Select` 注解与 XML mapper 中的列别名，支持 `<resultMap>` 的 property
- **实体 / DTO / VO / record 支持**：入参与返回值是源码中的类时自动展开字段，兼容嵌套对象、集合与泛型包装，说明取自 `@Schema`、`@ApiModelProperty`、JavaDoc 与 getter 注解，校验注解标记必填；`String` 参数经 `parseObject` / `readValue` / `toBean` / `TypeReference` 转换、以及 `R<LoginVo>` 这类源码外包装也能识别；解析范围用项目根，跨模块 DTO 同样可展开
- **接口标题与分组**：识别 `@Operation`、`@ApiOperation`、`@Tag` 与自定义注解，类与方法 JavaDoc 作为兜底；标题可在工具窗口直接修改
- **对象值展开**：value 为对象时展开其字段，并读取 JavaDoc 作为字段说明
- **入参与出参分离**：出参看方法内构建的 Map，入参看通过 `get` / `containsKey` 读取的 key，两侧结果互不混用
- **工具窗口**：字段表格可直接编辑说明与标题，实时预览，保存后立即重新生成 OpenAPI 与 Markdown；可分别复制入参、出参或两者合并的 JSON Schema
- **五种扫描粒度**：项目、模块、目录或包、类、单个方法，均在右键菜单与 `Tools` 菜单中触发
- **一键导出**：复制标准 JSON Schema（贴进 Apifox 字段定义）或整份 OpenAPI 3.0 文档
- **产物集中存放**：统一写入项目根目录的 `.mappeek/<目标名>/`，不碰业务代码

### 安装

在 `Settings` → `Plugins` 中搜索 **MapPeek** 在线安装，或从 Marketplace 下载 zip 后
`Install Plugin from Disk...`，重启 IDE 生效。

### 环境要求

- IntelliJ IDEA 2023.3 及以上
- 被扫描项目为 Java 项目，语言级别 8 / 11 / 17 / 21
- 无需编译项目，也无需启动 Spring 容器

### 已知限制

- 动态 key（`put(prefix + i, ...)`）无法静态推断，输出为空对象，不做猜测
- 跨模块调用链不追踪，只跟随一层
- 由 `BeanUtil.beanToMap`、`JSON.parseObject`、`convertValue` 等转换生成的 Map 不可见
- 类型为尽力推断，失败时标记 `low` 并降级为 `string`，导入后建议人工确认

---

## English

**Version**: <!-- version:begin -->0.1.1<!-- version:end --> (first release)　**Released**: 2026-11-24　**Requires**: IntelliJ IDEA 2023.3 (233) or later

### In one sentence

Extract the request and response fields of `Map<String, Object>` endpoints from source code, confirm them
inside the IDE, and export JSON Schema or OpenAPI 3.0 in one click.

### What's new

- **Map field detection**: `map.put` literals, constant keys and `Map.of`, including nested Maps and `List<Map>`
- **MyBatis support**: resolves column aliases from `@Select` annotations and XML mappers, including `<resultMap>` properties
- **Entity / DTO / VO / record support**: expands fields when parameters or return types are classes in the source tree, including nested objects, collections and generic wrappers; descriptions come from `@Schema`, `@ApiModelProperty`, JavaDoc and getter annotations, validation annotations mark required fields; also handles `String` parameters converted via `parseObject` / `readValue` / `toBean` / `TypeReference` and unwraps external wrappers like `R<LoginVo>`; resolves across modules by using the project root as the scan scope
- **Endpoint titles and groups**: reads `@Operation`, `@ApiOperation`, `@Tag` and custom annotations, falling back to class/method JavaDoc; titles can be edited in the tool window
- **Object value expansion**: expands object-typed values and reads JavaDoc as field descriptions
- **Separate request and response inference**: response from Maps built inside the method, request from keys read via `get` / `containsKey`
- **Tool window**: edit field descriptions and titles directly in the table with live preview; saving regenerates OpenAPI and Markdown immediately; copy request, response or combined JSON Schema separately
- **Five scan scopes**: project, module, directory or package, class, single method, available from the context menu and `Tools` menu
- **One-click export**: copy standard JSON Schema (for Apifox field definitions) or a complete OpenAPI 3.0 document
- **Centralized output**: everything is written to `.mappeek/<target name>/` in the project root; business code is never touched

### Installation

Search for **MapPeek** in `Settings` → `Plugins`, or download the zip from the Marketplace and use
`Install Plugin from Disk...`; restart the IDE afterwards.

### Requirements

- IntelliJ IDEA 2023.3 or later
- The scanned project must be a Java project with language level 8 / 11 / 17 / 21
- No compilation and no Spring container required

### Known limitations

- Dynamic keys (`put(prefix + i, ...)`) cannot be inferred statically; the output is an empty object with no guessing
- Cross-module call chains are not followed; only one level is followed
- Maps produced by `BeanUtil.beanToMap`, `JSON.parseObject`, `convertValue` and similar converters are invisible
- Types are best-effort: failures are marked `low` and downgraded to `string`; review them after import

---

## Marketplace 更新说明（可直接粘贴） / Marketplace changelog (ready to paste)

**中文**

- 首次发布：识别 `Map` 入参与出参字段，工具窗口内编辑说明并实时预览，一键导出 JSON Schema / OpenAPI 3.0

**English**

- First release: Map request/response field detection, in-IDE editing with live preview, one-click JSON Schema / OpenAPI 3.0 export
