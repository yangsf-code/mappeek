<p align="center">
  <img src="mappeek-idea/pluginLogo.png" width="120" height="120" alt="MapPeek" />
</p>

<h1 align="center">MapPeek</h1>

<p align="center"><b>自动识别 Java 接口里的 <code>Map</code> 入参与出参，生成可直接交付给前端的接口文档。</b></p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/36420-mappeek"><img alt="version" src="https://img.shields.io/jetbrains/plugin/v/36420?label=version&color=4F63F5" /></a>
  <a href="https://plugins.jetbrains.com/plugin/36420-mappeek"><img alt="downloads" src="https://img.shields.io/jetbrains/plugin/d/36420?color=4F63F5" /></a>
  <img alt="IntelliJ IDEA 2023.3+" src="https://img.shields.io/badge/IntelliJ%20IDEA-2023.3%2B-000000?logo=intellijidea&logoColor=white" />
  <img alt="Java 17" src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" />
  <img alt="license MIT" src="https://img.shields.io/badge/license-MIT-3DA639" />
</p>

<p align="center">
  <a href="#安装">安装</a> ·
  <a href="#功能">功能</a> ·
  <a href="#构建">构建</a> ·
  <a href="https://plugins.jetbrains.com/plugin/36420-mappeek">Marketplace</a> ·
  <a href="mappeek-idea/README.md">中文文档</a> ·
  <a href="mappeek-idea/README.en.md">English</a>
</p>

Java 项目里 `Map<String, Object>` 类型的接口参数一直是文档工具的盲区：标准工具只能渲染成 `additionalProperties`，前端看到的是一个空对象。MapPeek 直接从源码里把字段提取出来，在 IDE 内确认、补注释，再一次导出 OpenAPI 或 JSON Schema。

## 功能

- 识别 `map.put` 字面量、常量 key、`Map.of`，支持嵌套 Map 与 `List<Map>`
- 实体 / DTO / VO / `record` 参数自动展开，读取 `@Schema`、`@ApiModelProperty`、JavaDoc 与校验注解，字段无注解时回退到 getter
- 支持 `Result<T>` 等泛型包装，以及 `parseObject` / `readValue` / `TypeReference` 转成的 DTO
- 解析 MyBatis 列别名，覆盖 `@Select` 注解、XML mapper 与 `<resultMap>`
- 工具窗口内编辑字段说明与类型（类型为下拉选项），自动保存并重新生成文档
- 一键复制标准 JSON Schema 或 OpenAPI 3.0，可直接导入 Apifox / YApi / Postman
- 按项目 / 模块 / 包 / 类 / 方法五种粒度扫描；只读源码，不编译、不启动容器

## 安装

- Marketplace：<https://plugins.jetbrains.com/plugin/36420-mappeek>
- 离线安装：`Settings → Plugins → ⚙ → Install Plugin from Disk...`，选择 `mappeek-idea.zip`

要求 IntelliJ IDEA 2023.3 及以上；被扫描项目需为 Java 项目（语言级别 8 / 11 / 17 / 21）。

## 目录结构

```
mappeek/
├── mappeek-core/     分析器与导出（JavaParser + Jackson，可独立作为 CLI 使用）
├── mappeek-idea/     IntelliJ IDEA 插件（工具窗口、扫描入口、打包脚本）
├── build.bat         一键构建：core 打 jar + 插件编译 + 生成安装包
└── .gitignore
```

## 构建

需要 JDK 17+、Maven 与一个 IntelliJ IDEA 安装目录（用于 `javac` 的 classpath）。默认路径可在 `build.bat` 顶部修改，或通过环境变量覆盖：

```powershell
$env:MAPEEK_JDK  = "D:\Java\jdk-21.0.11"
$env:MAPEEK_IDEA = "D:\JetBrains\IntelliJ IDEA 2026.1.3"
$env:MAPEEK_MVN  = "E:\apache-maven-3.9.16\bin\mvn.cmd"
.\build.bat
```

产物：`mappeek-idea/dist/mappeek-idea.zip`。

## 更多文档

- [插件使用说明（中文）](mappeek-idea/README.md)
- [Plugin guide (English)](mappeek-idea/README.en.md)
- [发布说明](mappeek-idea/RELEASE_NOTES.md) · [上架检查清单](mappeek-idea/RELEASE_CHECKLIST.md)
- [分析器与 CLI](mappeek-core/README.md)

## 许可证

[MIT](LICENSE) © 2026 疯狂de程序员
