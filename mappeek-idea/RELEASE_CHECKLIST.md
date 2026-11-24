# MapPeek 上架前最终检查清单

适用版本：`0.1.0`（首次发布）　检查日期：2026-11-23

`[x]` 表示当前仓库已满足并已验证，`[ ]` 表示上架前还需处理。带 **阻塞** 标记的项建议在提交审核前完成。

当前构建产物快照（重新构建后会变化，以实际发布包为准）：

| 项目 | 值 |
|---|---|
| 安装包 | `dist/mappeek-idea.zip`（3.38 MB） |
| SHA-256 | `7B2587088B1DA7ACAE32A31054F36A1E4B0233BD9E4AD48199A94A1C090807C1` |
| 包内结构 | `mappeek-idea/lib/mappeek-idea.jar`、`mappeek-idea/lib/mappeek.jar` |
| 插件 jar 元数据 | `plugin.xml`、`pluginIcon.svg`、`pluginIcon_dark.svg` |

## 1. 元数据与标识

- [x] 插件 ID 为 `mappeek.idea`，与 `ScanAction` 里的 `PluginId.getId("mappeek.idea")` 一致
- [x] 版本号统一由 `project.properties` 的 `plugin.version` 管理，构建时自动写入 `plugin.xml`、两处 change-notes 与 `RELEASE_NOTES.md` 的 3 处版本
- [x] `since-build`、Java 目标版本分别由 `plugin.since-build`、`plugin.java-release` 管理，构建脚本直接使用
- [x] 作者名、邮箱统一在 `project.properties`，构建时同步到 `plugin.xml` 的 `<vendor>` 与两个 README
- [x] `marketplace.id` 统一管理 README 的版本徽章、安装量徽章与插件页链接（当前为空，保留占位符）
- [x] `<vendor>` 已带 `url`（`mailto:yangsf_app@163.com`）与 `email`，作者名显示为 `疯狂de程序员`
- [ ] 确认发布后不再修改插件 ID（Marketplace 上架后 ID 不可变更，只能新建插件）

## 2. 文案与素材

- [x] `<description>` 中英双语，中文在前
- [x] `<change-notes>` 中英双语
- [x] `pluginIcon.svg` / `pluginIcon_dark.svg`（40×40）已放入 `META-INF` 并打进 jar
- [x] `pluginLogo.png`（300×300，透明背景）已导出
- [x] README 中英双版本，含徽章封面段与语言切换
- [x] 界面文案中英双语（中文在前），菜单、页签、按钮、通知一致
- [x] 预览区按钮为「复制文档 / 复制入参 / 复制出参」，入参与出参导出标准 JSON Schema
- [x] 字段类型可编辑，`Long` / `BigDecimal` / `List<String>` 等会归一为 JSON Schema 类型并写回 CSV 与导出文件
- [x] 侧边栏工具窗口图标改为插件 logo（`/META-INF/pluginIcon.svg`）
- [x] 扫描与更新文档的进度提示同样跟随 IDE 语言（中文「正在扫描…」/ 英文 `Scanning …`）
- [x] Marketplace 截图已生成：`marketplace/screenshots/` 三张 1280×800（右键入口、字段编辑、复制 Schema）
- [x] Marketplace 描述文案已准备：`marketplace/marketplace-description.md`（中英双语、What's new、标签与上传清单）
- [ ] 截图中的示例项目不要出现真实公司名、内网地址、客户字段

## 3. 功能回归（真实 IDE + 示例项目）

- [x] 标题来源已覆盖 `@Operation`、`@ApiOperation`、`@Tag`、类与方法 JavaDoc 与自定义注解（示例项目实测通过）
- [x] 实体 / DTO / VO 入参与出参展开已验证：`@Schema`、`@ApiModelProperty`、JavaDoc、行尾注释、`@NotNull` 必填、`@JsonProperty` 别名、`@JsonIgnore` 跳过
- [x] 泛型包装已验证：`Result<T>` 能解析出 `data.orderId` 等嵌套字段
- [x] `record` DTO 已验证：组件上的 `@Schema` / `@JsonProperty` 生效，组件说明回退到类 Javadoc 的 `@param`
- [x] 注解在 getter 上（字段无注解）已验证：说明与 `@NotNull` 必填都能读取
- [x] `R<LoginVo>` 这类源码外泛型包装已验证：取实参 `LoginVo` 展开出参
- [x] `@RequestBody String body` + `parseObject(body, LoginBody.class)` 已验证：识别为请求 DTO
- [x] `TypeReference<LoginBody>` 写法已验证
- [x] DTO / VO 位于另一个模块时已验证：解析范围用项目根，仍能展开
- [x] 预览区已有 `Copy Request` / `Copy Response`，`Copy Schema` 同时包含两个方向
- [x] 标题末尾句号自动去除（`登录方法。` → `登录方法`）
- [x] 旧版 7 列 `mappeek-edit.csv` 可正常读取，新写入为 8 列（含 `summary`）
- [ ] 在真实 IDE 中确认「标题」列编辑后保存，能同步到同一接口的所有行并反映到导出文档
- [ ] 确认 Request / Response 两个页签的字段数、编辑与保存联动正常
- [ ] 五种扫描粒度各跑一次：项目、模块、目录/包、类、方法内光标
- [ ] `map.put` 字面量、常量 key、`Map.of`、嵌套 Map、`List<Map>` 各一例
- [ ] MyBatis：`@Select` 注解、XML mapper、`<resultMap>` 各一例
- [ ] 入参识别：`get` 与 `containsKey` 读取的 key 能出现在入参方向
- [ ] 编辑说明 → 预览实时变化 → `保存` → 磁盘上 OpenAPI 与 Markdown 同步更新
- [ ] `复制 OpenAPI` 为整份文档，`复制字段 Schema` 在选中行时只含所选接口与方向
- [ ] 多次扫描后「目标」下拉可切换，结果互不覆盖
- [ ] 空结果（全是动态 key）时给出提示，不抛异常
- [ ] 含语法错误的 `.java` 文件不会中断整次扫描
- [ ] 大项目（万级文件）扫描可取消，UI 不冻结

## 4. 兼容性与 API

- [ ] **阻塞** 在真实 IDEA 2023.3 上安装并跑通一次全流程（README 目前只声称「已验证字节码与 API 存在」）
- [ ] 在最新版 IDEA 上再跑一次，确认无 `NoSuchMethodError`
- [ ] **阻塞** 用 Plugin Verifier 对 IC-233 跑一次兼容性检查

```powershell
# 示例，按本机 verifier 版本调整参数
java -jar plugin-verifier.jar check-plugin dist\mappeek-idea.zip "D:\JetBrains\IntelliJ IDEA 2023.3"
```

- [x] 两个 jar 的 class 文件版本为 61（Java 17），可在 JBR 17 上加载
- [ ] 编译时 `javac` 提示的 deprecation 逐个确认，避免使用 233 已废弃 API
- [ ] macOS / Linux 下验证 `javaExecutable()` 与 `locateJar()` 路径拼装（当前只在 Windows 验证过）

## 5. 打包与安装

- [x] `build.ps1` 会拷贝整个 `META-INF`，图标不会漏打
- [x] 安装包结构与体积已校验（见顶部快照）
- [x] 从零构建可重复通过，构建产物由配置自动生成，无手改残留
- [ ] 用 `Install Plugin from Disk...` 安装 → 重启 → 功能正常
- [ ] 卸载插件后确认无残留配置项、无残留注册表/配置目录
- [ ] 在只读项目目录（或只读 `.mappeek`）下扫描，确认错误提示可读、不崩溃

发布前重新构建并核对：

```powershell
.\build.ps1
jar tf dist\mappeek-idea.zip
Get-FileHash dist\mappeek-idea.zip -Algorithm SHA256
```

## 6. 合规与许可证

- [x] 内置分析器的依赖声明保留在 `META-INF/LICENSE` 与 `META-INF/NOTICE`
- [x] `LICENSE` 已补充 MIT 全文（Copyright (c) 2026 疯狂de程序员），与 README 声明一致
- [x] 确认 `javaparser-core 3.26.4`、`jackson-databind 2.17.2` 的许可证与声明方式：随 `mappeek.jar` 打入 `META-INF/LICENSE`、`META-INF/NOTICE` 及第三方 LICENSE 文件
- [x] 插件无网络请求、无埋点（插件与分析器源码均无 `java.net` / `Socket` / `HttpClient`），Marketplace 隐私声明选「不收集数据」
- [x] 检查打进 jar 的内容不含测试代码、示例项目、内部路径（`mappeek-idea.jar` 仅 class 与 `META-INF`，`mappeek.jar` 仅 `mappeek`、`com`、`META-INF`）

## 7. Marketplace 发布操作

- [ ] 上传 `dist/mappeek-idea.zip`
- [ ] 填写分类（建议 Developer Tools / Code Tools）与标签：`map`、`openapi`、`swagger`、`apifox`、`mybatis`、`json schema`、`api documentation`
- [ ] 许可证选 MIT，定价选免费
- [ ] 上传 `pluginLogo.png` 与截图
- [ ] 在 Marketplace 后台确认描述与更新说明渲染正常（`<code>`、列表不串行）
- [ ] 提交审核，记录提交时间与审核反馈

## 8. 发布后 24 小时内

- [ ] 拿到数字插件 ID 后，填入 `project.properties` 的 `marketplace.id`，执行 `.\build.ps1`；两个 README 的徽章与插件页链接会自动替换，确认变绿
- [ ] 用 IDE 内的 Marketplace 搜索安装一次，确认可搜到、可安装、可卸载
- [ ] 创建 GitHub Release，正文使用 `RELEASE_NOTES.md`，附上 `mappeek-idea.zip`
- [ ] 在示例项目上再跑一次，确认与本地 zip 安装行为一致

## 提交审核前的最后三步

1. 在 IDEA 2023.3 上跑通一条完整链路，并跑 Plugin Verifier
2. 真实 IDE 中确认：入参/出参两个页签有数据、类型列可编辑、双语文案、侧边栏为插件 logo
3. `.\build.ps1` 后核对顶部快照中的结构与哈希，上传 `dist/mappeek-idea.zip`
