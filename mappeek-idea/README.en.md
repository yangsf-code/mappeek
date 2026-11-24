<p align="center">
  <img src="pluginLogo.png" width="120" height="120" alt="MapPeek" />
</p>

<h1 align="center">MapPeek</h1>

<p align="center"><b>Detects the <code>Map</code> request and response parameters of Java endpoints and generates API documentation your frontend can use right away.</b></p>

<!-- The Marketplace ID in the badges and plugin link below comes from marketplace.id in project.properties; fill it in and rebuild before release -->
<p align="center">
  <img alt="version" src="https://img.shields.io/jetbrains/plugin/v/PLUGIN_ID?label=version&color=4F63F5" />
  <img alt="IntelliJ IDEA 2023.3+" src="https://img.shields.io/badge/IntelliJ%20IDEA-2023.3%2B-000000?logo=intellijidea&logoColor=white" />
  <img alt="Java 17" src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" />
  <img alt="license MIT" src="https://img.shields.io/badge/license-MIT-3DA639" />
  <a href="https://plugins.jetbrains.com/plugin/PLUGIN_ID-mappeek"><img alt="Marketplace downloads" src="https://img.shields.io/jetbrains/plugin/d/PLUGIN_ID?color=4F63F5" /></a>
</p>

<p align="center">
  <a href="#installation">Install</a> ·
  <a href="#usage">Usage</a> ·
  <a href="https://plugins.jetbrains.com/plugin/PLUGIN_ID-mappeek">Marketplace</a> ·
  <a href="RELEASE_NOTES.md">Release notes</a> ·
  <a href="README.md">中文</a>
</p>

Parameters typed as `Map<String, Object>` have always been a blind spot for documentation tools: standard tools render them as
`additionalProperties`, so the frontend sees an empty object. MapPeek pulls the fields straight out of the source code, lets you
confirm them inside the IDE, add descriptions, and export OpenAPI or JSON Schema in one go.

## What it solves

- Nobody can say exactly which fields `map.put("orderId", ...)` returns to the frontend
- MyBatis returns a `Map`, but the field names are hidden in SQL aliases, so Swagger shows nothing
- Documenting by hand is expensive, and every code change requires another sync
- The frontend keeps asking "what does this endpoint actually take?", and the backend has to dig through code

MapPeek connects these three steps: **scan → confirm in the IDE → export in one click**.

## Features

- Detects `map.put` literals, constant keys and `Map.of`, including nested Maps and `List<Map>`
- Expands entity / DTO / VO and `record` parameters and return types, including nested objects, collections and generic wrappers such as `Result<T>`; also handles `String` parameters converted via `parseObject` / `readValue` / `toBean` (including `TypeReference`) and unwraps external wrappers like `R<LoginVo>`
- Scans with the project root as the resolution scope, so DTOs / VOs in other modules are found while only endpoints in the selected scope are scanned
- Field descriptions come from `@Schema`, `@ApiModelProperty`, JavaDoc and trailing comments, falling back to annotations on the matching getter; `@NotNull`, `@NotBlank` and `@Schema(required / requiredMode)` mark fields as required
- Resolves MyBatis column aliases from `@Select` annotations and XML mappers, including `<resultMap>`
- Extracts endpoint titles and groups from `@Operation`, `@ApiOperation`, `@Tag` and class/method JavaDoc; custom annotations can be configured with `--title-annotations`
- Understands object-typed values and expands their fields together with JavaDoc comments
- Infers request and response parameters separately: response from Maps built inside the method, request from keys read via `get/containsKey`
- Edit field descriptions in the tool window with live preview; saving regenerates the documentation
- Copy standard **JSON Schema** (paste into Apifox field definitions) or **OpenAPI 3.0** (export a whole API doc) in one click
- Scan by project / module / package / class / single method

## Requirements

- IntelliJ IDEA **2023.3 or later** (see "Compatibility")
- The scanned project must be a Java project with language level 8 / 11 / 17 / 21
- No compilation or Spring container required; the plugin only reads source code

## Installation

1. `Settings` → `Plugins`
2. Gear icon in the top right → `Install Plugin from Disk...`
3. Select `mappeek-idea.zip`
4. Restart the IDE

When installing from the Marketplace, skip the steps above and search for **MapPeek** in `Plugins`. The plugin ID is `mappeek.idea`.

## Usage

Right-click at any level; the scan scope follows your selection:

| Trigger | Scope |
|---|---|
| `Tools` menu (no selection) | Entire project |
| Right-click module / directory / package in the project tree | That module, directory or package |
| Right-click a class file in the project tree | All endpoints of that class |
| Right-click in the editor, or place the caret inside a method | Only that method's endpoint |

Whichever level you choose, the resolution scope is the content root of the containing module, so even when you select
a single class, services and mappers in the same module are still followed and no fields are lost.

When the scan finishes, the **MapPeek** tool window opens on the right.

### Tool window

**Top: field table**

- Request and response are split into two tabs (「入参 / 出参」 on a Chinese IDE, `Request / Response` otherwise) with field counts
- Columns: `API`, `Title`, `Field`, `Type`, `Conf.`, `Description` (the `Dir` column is hidden because the tab indicates direction)
- UI text is bilingual, Chinese first (`接口 / API`, `保存 / Save`)
- The `Title`, `Type` and `Description` columns are editable; Java / database types like `Long`, `BigDecimal` or `List<String>` are normalized to `integer`, `number` or `array` in the exported JSON Schema
- The "target" dropdown at the top switches between results of multiple scans without overwriting each other

**Bottom: documentation preview**

- Refreshes live while you edit, in the form `{ "key": "description" }`, with trailing comments for type and required flag
- `Copy Document`: copies the full `mappeek-openapi.json`; the scope is determined by the scan target
- `Copy Request` / `Copy Response`: copies only the request or the response JSON Schema
- `Save`: writes the descriptions back and immediately regenerates OpenAPI and Markdown

How they differ: OpenAPI is the complete API document (paths, methods, request, response), while JSON Schema only
describes the field structure and fits into the field definition area of Apifox.

The copied JSON Schema looks like this:

```json
{
  "type": "object",
  "required": ["orderId"],
  "properties": {
    "orderId": {
      "type": "string",
      "description": "Order number"
    },
    "items": {
      "type": "array",
      "items": {
        "type": "object",
        "properties": {
          "skuId": { "type": "integer", "description": "SKU id" }
        }
      }
    }
  }
}
```

`required` is an array, so it can be pasted directly into Apifox's JSON Schema import.

## Output location

```
<project root>/.mappeek/<target name>/
├── mappeek-api.md            API field documentation, ready to hand to the frontend
├── mappeek-openapi.json      OpenAPI 3.0, importable into Apifox / YApi / Swagger
├── mappeek-edit.csv          Field description list (data source of the tool window)
├── mappeek-target.properties Scan parameters (used to regenerate on "Save")
└── dto/                      Commented DTO skeletons (only when a single class is selected)
```

Target naming: the directory name for a directory selection, the class name without `.java` for a class,
and `ClassName-methodName` when the caret is inside a method.

Adding `.mappeek` to `.gitignore` is recommended.

## How it works

The plugin is a launcher: it bundles `mappeek.jar` and runs a one-off static analysis with the JBR shipped with the IDE.

- No compilation, no container, no database connection
- Reads only `.java` files and MyBatis XML; the single write is `.mappeek` in the project root
- The same analysis logic serves both the CLI and the plugin, so results are identical on both sides

Field descriptions come from three sources, in priority order: comments above or at the end of a `put` line, the JavaDoc
of the constant field, and the `property` of an SQL `<resultMap>`.

## Known limitations

- Dynamic keys (`put(prefix + i, ...)`) cannot be inferred statically; the output is an empty object with no guessing
- Entity / DTO / VO classes must live in the scanned source tree; types from dependency jars or other modules are not expanded
- Only one level of generic arguments is resolved (e.g. `Result<OrderVO>`); complex `T extends` bounds are not inferred
- Cross-module call chains are not followed; only one level is followed and methods outside the scope are not expanded
- Maps produced by `BeanUtil.beanToMap`, `JSON.parseObject`, `convertValue` and similar converters are invisible to static analysis
- Types are best-effort: fields that cannot be inferred are marked `low` and downgraded to `string`; review them after import
- Chinese comments depend on comments existing in the source; database column comments cannot be retrieved

## FAQ

**The fields are empty after scanning?**
Make sure the endpoint's Map is produced by `put`, `Map.of` or MyBatis aliases. Values coming from third-party converters fall under the known limitations.

**Why can fields in a service be found even when scanning a single class?**
The resolution scope is the module content root, not the class you selected, so no fields are lost.

**I changed a description but the documentation did not change?**
Click "Save" to regenerate; `Copy OpenAPI` reads the file on disk, so unsaved changes are not included.

**Will it modify my business code?**
No. The only write is `.mappeek` in the project root.

## Development and build

Prerequisite: `../mappeek-core/target/mappeek.jar` must exist (run `mvn -DskipTests package` in `mappeek-core`).

```powershell
.\build.ps1

# When the IDE or JDK path differs
.\build.ps1 -IdeaHome "D:\JetBrains\IntelliJ IDEA 2026.1.3" -JdkHome "D:\Java\jdk-21.0.11"
```

The script does three things: compile against the jars bundled with IDEA, package `plugin.xml` and the classes into
`mappeek-idea.jar`, and zip it together with `mappeek.jar` into an installable zip.

### Directory layout

```
mappeek-idea/
├── build.ps1
├── src/main/java/mappeek/idea/
│   ├── ScanAction.java              Scan entry point, builds and runs the command line
│   ├── FieldsPanel.java             Tool window: table, preview, copy
│   ├── FieldsToolWindowFactory.java Tool window registration
│   ├── EditFieldsAction.java        Reopen the previous result
│   └── FieldCsv.java                mappeek-edit.csv read/write
├── src/main/resources/META-INF/plugin.xml
└── dist/mappeek-idea.zip            Installable package
```

## Compatibility

Supports IntelliJ IDEA 2023.3 (build 233) and later. The plugin and the bundled analyzer are both compiled to Java 17
bytecode, matching the JBR 17 shipped since 2023.3.

Verified:

- Compiled with `--release 17`, no JDK APIs newer than Java 17 are referenced
- Class file version of both jars is 61 (Java 17) and can be loaded on JBR 17
- The bundled analyzer runs on Java 17 with the default export and `--overrides` description write-back
- The IntelliJ APIs used (`ToolWindowManager`, `ContentFactory`, `NotificationGroupManager`,
  `Task.Backgroundable`, etc.) are all available in 233

Manual regression in a real 2023.3 IDE has not been done yet. Before publishing, run
[JetBrains Plugin Verifier](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)
against IC-233 to catch any remaining API compatibility issues.

## Author

<!-- author:begin -->
**疯狂de程序员** · yangsf_app@163.com
<!-- author:end -->

## License

MIT, see [LICENSE](LICENSE).
