# MapPeek · Marketplace 上架素材

> 本目录包含可直接上传的截图与文案。截图为按实际界面 1:1 还原的 1280×800 图片（`screenshots/`），
> 如你更倾向实拍，可在 IDEA 中按同样界面自行截图替换，文案无需改动。

## 1. 基本信息

| 项 | 填写内容 |
|---|---|
| 插件名称 | MapPeek |
| 版本 | 0.1.1 |
| 分类 | Developer Tools → Code Tools |
| 标签 | `map`、`openapi`、`swagger`、`apifox`、`mybatis`、`json schema`、`api documentation` |
| 许可证 | MIT |
| 价格 | Free |
| 隐私声明 | 不收集任何数据（插件无网络请求） |
| 支持邮箱 | yangsf_app@163.com |
| 插件包 | `dist/mappeek-idea.zip` |
| Logo | `pluginLogo.png`（300×300） |

## 2. 一句话简介

**中文**：从 Java 源码提取 `Map` 与 DTO 的入参、出参字段，在 IDE 内确认后一键导出 OpenAPI 与 JSON Schema。

**English**: Extract request and response fields from `Map` and DTO endpoints, review them inside the IDE, and export OpenAPI or JSON Schema in one click.

## 3. 详细描述（中文，可直接粘贴到 Marketplace）

```html
<p><b>MapPeek</b> 解决一个很具体的问题：接口用 <code>Map&lt;String, Object&gt;</code> 返回数据时，Swagger 只能渲染成空对象，前端永远不知道有哪些字段。</p>

<p>MapPeek 直接从源码里把字段找出来，在 IDE 工具窗口中确认、补充说明和类型，然后一键导出成可以直接交付的接口文档。</p>

<h3>功能</h3>
<ul>
  <li>识别 <code>map.put</code> 字面量、常量 key、<code>Map.of</code>，支持嵌套 Map 与 <code>List&lt;Map&gt;</code></li>
  <li>支持实体 / DTO / VO / <code>record</code>：直接展开字段，兼容嵌套对象、集合与 <code>Result&lt;T&gt;</code> 等泛型包装，源码外包装如 <code>R&lt;LoginVo&gt;</code> 也能取实参展开</li>
  <li>兼容 <code>@RequestBody String body</code> 再 <code>parseObject</code> / <code>readValue</code> / <code>TypeReference</code> 转 DTO 的写法</li>
  <li>字段说明取自 <code>@Schema</code>、<code>@ApiModelProperty</code>、JavaDoc、行尾注释，字段无注解时回退到 getter</li>
  <li>必填识别：<code>@NotNull</code>、<code>@NotBlank</code>、<code>@Schema(required / requiredMode)</code></li>
  <li>支持 <code>@Operation</code>、<code>@ApiOperation</code>、<code>@Tag</code> 与自定义注解作为接口标题与分组</li>
  <li>解析 MyBatis 列别名，覆盖 <code>@Select</code> 注解与 XML mapper，支持 <code>&lt;resultMap&gt;</code></li>
  <li>入参、出参分页签编辑，类型列可手工修改，<code>Long</code> / <code>BigDecimal</code> / <code>List&lt;String&gt;</code> 会自动归一为 JSON Schema 类型</li>
  <li>复制文档、复制入参、复制出参三个按钮，直接得到可导入 Apifox / YApi / Postman 的内容</li>
  <li>按项目 / 模块 / 目录或包 / 类 / 单个方法五种粒度扫描</li>
</ul>

<h3>使用方式</h3>
<ol>
  <li>在项目树或编辑器里右键，选择「扫描并编辑字段（MapPeek）」</li>
  <li>工具窗口会自动打开，上方编辑字段说明与类型，下方实时预览</li>
  <li>点击「复制文档」导出整份 OpenAPI，或「复制入参 / 复制出参」只取对应方向的 JSON Schema</li>
</ol>

<h3>产物</h3>
<p>统一写入项目根目录的 <code>.mappeek/&lt;目标名&gt;/</code>，包含 <code>mappeek-openapi.json</code>、<code>mappeek-api.md</code>、<code>mappeek-edit.csv</code> 等，不修改业务代码。</p>

<h3>环境要求</h3>
<p>IntelliJ IDEA 2023.3 及以上，被扫描项目为 Java 8 / 11 / 17 / 21。无需编译、无需启动应用，只读源码。</p>

<h3>隐私</h3>
<p>插件不联网、不上传任何代码或数据，所有分析都在本地完成。</p>

<h3>反馈</h3>
<p>问题或建议请发邮件至 yangsf_app@163.com。</p>
```

## 4. 详细描述（English，可直接粘贴）

```html
<p><b>MapPeek</b> solves one specific problem: when an endpoint returns <code>Map&lt;String, Object&gt;</code>, Swagger renders an empty object and the frontend has no idea which fields exist.</p>

<p>MapPeek extracts those fields straight from the source code, lets you review and edit them inside an IDE tool window, and exports documentation that is ready to hand over.</p>

<h3>Features</h3>
<ul>
  <li>Detects <code>map.put</code> literals, constant keys and <code>Map.of</code>, including nested maps and <code>List&lt;Map&gt;</code></li>
  <li>Expands entity / DTO / VO / <code>record</code> parameters and return types, including nested objects, collections and generic wrappers such as <code>Result&lt;T&gt;</code>; unwraps external wrappers like <code>R&lt;LoginVo&gt;</code></li>
  <li>Handles <code>@RequestBody String body</code> converted via <code>parseObject</code>, <code>readValue</code> or <code>TypeReference</code></li>
  <li>Field descriptions come from <code>@Schema</code>, <code>@ApiModelProperty</code>, JavaDoc and trailing comments, falling back to annotations on the matching getter</li>
  <li>Required fields from <code>@NotNull</code>, <code>@NotBlank</code> and <code>@Schema(required / requiredMode)</code></li>
  <li>Endpoint titles and groups from <code>@Operation</code>, <code>@ApiOperation</code>, <code>@Tag</code> and custom annotations</li>
  <li>Resolves MyBatis column aliases from <code>@Select</code> annotations and XML mappers, including <code>&lt;resultMap&gt;</code></li>
  <li>Request and response are edited in separate tabs; the type column is editable and normalizes Java types like <code>Long</code>, <code>BigDecimal</code> and <code>List&lt;String&gt;</code> to JSON Schema types</li>
  <li>Copy the whole document, only the request schema, or only the response schema — ready for Apifox / YApi / Postman</li>
  <li>Scan by project, module, directory or package, class, or a single method</li>
</ul>

<h3>Usage</h3>
<ol>
  <li>Right-click in the project tree or editor and choose "Scan &amp; Edit Fields (MapPeek)"</li>
  <li>The tool window opens: edit descriptions and types on top, see the live preview below</li>
  <li>Click "Copy Document" for the full OpenAPI file, or "Copy Request" / "Copy Response" for the JSON Schema of one direction</li>
</ol>

<h3>Output</h3>
<p>Everything is written to <code>.mappeek/&lt;target&gt;/</code> in the project root: <code>mappeek-openapi.json</code>, <code>mappeek-api.md</code>, <code>mappeek-edit.csv</code> and more. Business code is never modified.</p>

<h3>Requirements</h3>
<p>IntelliJ IDEA 2023.3 or later. The scanned project can use Java 8 / 11 / 17 / 21. No compilation and no running application required — the plugin reads source code only.</p>

<h3>Privacy</h3>
<p>No network access, no telemetry. All analysis runs locally.</p>

<h3>Support</h3>
<p>Please send questions or suggestions to yangsf_app@163.com.</p>
```

## 5. 更新说明（What's new）

**中文**

```text
首个版本。支持从 Map、实体 / DTO / VO / record 的接口中提取入参与出参字段，兼容 MyBatis 列别名与 String 转 DTO 写法；工具窗口内分页签编辑说明与类型，一键复制 OpenAPI 或入参 / 出参 JSON Schema。
```

**English**

```text
First release. Extracts request and response fields from Map, entity / DTO / VO / record endpoints, with MyBatis aliases and String-to-DTO conversions supported. Edit descriptions and types in tabbed tables, then copy the full OpenAPI document or the request / response JSON Schema in one click.
```

## 6. 截图与说明文字

| 文件 | Marketplace 中的说明（中文） | 说明（English） |
|---|---|---|
| `screenshots/01-context-menu.png` | 在项目树或编辑器右键，直接扫描当前目录、类或方法 | Right-click in the project tree or editor to scan a directory, class or method |
| `screenshots/02-field-editor.png` | 入参、出参分页签编辑，标题、说明、类型都能改，下方实时预览 | Edit request and response in separate tabs; titles, descriptions and types are editable with a live preview |
| `screenshots/03-copy-schema.png` | 复制文档、复制入参、复制出参，直接得到可导入 Apifox 的 JSON Schema | Copy the document, request schema or response schema and paste it into Apifox |

## 7. 上传清单

1. `marketplace/screenshots/` 三张图
2. `pluginLogo.png`
3. 第 3 / 4 节的 HTML 描述
4. 第 5 节的 What's new
5. `dist/mappeek-idea.zip`（SHA-256 `7B2587088B1DA7ACAE32A31054F36A1E4B0233BD9E4AD48199A94A1C090807C1`）

> 注意：本目录只用于上架素材，已排除在插件安装包之外。
