param(
    [string]$IdeaHome = "D:\JetBrains\IntelliJ IDEA 2026.1.3",
    [string]$JdkHome = "D:\Java\jdk-21.0.11"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javac = Join-Path $JdkHome "bin\javac.exe"
$jarTool = Join-Path $JdkHome "bin\jar.exe"

if (-not (Test-Path $javac)) { throw "找不到 javac: $javac" }
if (-not (Test-Path (Join-Path $IdeaHome "lib"))) { throw "找不到 IDEA 安装目录: $IdeaHome" }

$toolJar = Join-Path $root "..\mappeek-core\target\mappeek.jar"
if (-not (Test-Path $toolJar)) {
    throw "缺少 mappeek.jar，请先在 mappeek-core 目录执行: mvn -DskipTests package"
}

$classes = Join-Path $root "build\classes"
$stage = Join-Path $root "build\plugin"
Remove-Item -Recurse -Force $classes, $stage, (Join-Path $root "dist") -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $classes | Out-Null
New-Item -ItemType Directory -Force (Join-Path $stage "META-INF") | Out-Null

# 统一配置：只改 project.properties，下面会把配置同步到 plugin.xml、RELEASE_NOTES.md 与两个 README
$propsPath = Join-Path $root "project.properties"
if (-not (Test-Path $propsPath)) { throw "缺少 project.properties" }
$props = @{}
foreach ($line in [System.IO.File]::ReadAllLines($propsPath, [System.Text.Encoding]::UTF8)) {
    $text = $line.Trim()
    if ($text -eq "" -or $text.StartsWith("#") -or $text.IndexOf("=") -lt 0) { continue }
    $i = $text.IndexOf("=")
    $props[$text.Substring(0, $i).Trim()] = $text.Substring($i + 1).Trim()
}
foreach ($key in @("plugin.name", "plugin.version", "plugin.since-build", "plugin.java-release", "author.name", "author.email")) {
    if (-not $props[$key]) { throw "project.properties 缺少 $key" }
}
$pluginName = $props["plugin.name"]
$pluginVersion = $props["plugin.version"]
$sinceBuild = $props["plugin.since-build"]
$javaRelease = $props["plugin.java-release"]
$marketplaceId = $props["marketplace.id"]
$authorName = $props["author.name"]
$authorEmail = $props["author.email"]
$authorUrl = $props["author.url"]

& $javac -encoding UTF-8 --release $javaRelease -cp "$IdeaHome\lib\*;$IdeaHome\plugins\java\lib\*" -d $classes (Get-ChildItem (Join-Path $root "src\main\java") -Recurse -Filter *.java | ForEach-Object { $_.FullName })
if ($LASTEXITCODE -ne 0) { throw "编译失败" }

$utf8 = New-Object System.Text.UTF8Encoding($false)
$singleline = [System.Text.RegularExpressions.RegexOptions]::Singleline

# plugin.xml：名称、版本、兼容版本、change-notes 版本号、作者
$vendor = if ($authorUrl) { "<vendor url=`"$authorUrl`" email=`"$authorEmail`">$authorName</vendor>" } else { "<vendor email=`"$authorEmail`">$authorName</vendor>" }
$pluginXmlPath = Join-Path $root "src\main\resources\META-INF\plugin.xml"
$xmlText = [System.IO.File]::ReadAllText($pluginXmlPath, [System.Text.Encoding]::UTF8)
$xmlText = [regex]::Replace($xmlText, "<name>.*?</name>", "<name>$pluginName</name>", $singleline)
$xmlText = [regex]::Replace($xmlText, "<version>.*?</version>", "<version>$pluginVersion</version>", $singleline)
$xmlText = [regex]::Replace($xmlText, "<idea-version[^>]*/>", "<idea-version since-build=`"$sinceBuild`"/>", $singleline)
$xmlText = [regex]::Replace($xmlText, "<b>\d+(?:\.\d+)+</b>", "<b>$pluginVersion</b>", $singleline)
$xmlText = [regex]::Replace($xmlText, "<vendor[^>]*>.*?</vendor>", $vendor, $singleline)
[System.IO.File]::WriteAllText($pluginXmlPath, $xmlText, $utf8)

# RELEASE_NOTES.md：用 HTML 注释标记版本号，页面上不显示注释
$notesPath = Join-Path $root "RELEASE_NOTES.md"
$notes = [System.IO.File]::ReadAllText($notesPath, [System.Text.Encoding]::UTF8)
$notes = [regex]::Replace($notes, "(?s)<!-- version:begin -->.*?<!-- version:end -->", "<!-- version:begin -->$pluginVersion<!-- version:end -->")
[System.IO.File]::WriteAllText($notesPath, $notes, $utf8)

# README：作者段，以及 Marketplace 插件 ID
$readmes = @(
    @{ Path = (Join-Path $root "README.md");    Line = "**$authorName**　邮箱：$authorEmail" },
    @{ Path = (Join-Path $root "README.en.md"); Line = "**$authorName** · $authorEmail" }
)
foreach ($readme in $readmes) {
    $md = [System.IO.File]::ReadAllText($readme.Path, [System.Text.Encoding]::UTF8)
    if ($md -notmatch "<!-- author:begin -->") { throw "README 缺少 author:begin 标记: $($readme.Path)" }
    $block = "<!-- author:begin -->`r`n$($readme.Line)`r`n<!-- author:end -->"
    $md = [regex]::Replace($md, "<!-- author:begin -->.*?<!-- author:end -->", $block, $singleline)
    if ($marketplaceId) { $md = $md.Replace("PLUGIN_ID", $marketplaceId) }
    [System.IO.File]::WriteAllText($readme.Path, $md, $utf8)
}
if (-not $marketplaceId) { Write-Host "提示：marketplace.id 未配置，README 徽章保留 PLUGIN_ID 占位符" }

Copy-Item -Recurse (Join-Path $classes "*") $stage
Copy-Item (Join-Path $root "src\main\resources\META-INF\*") (Join-Path $stage "META-INF") -Recurse

$libDir = Join-Path $root "dist\mappeek-idea\lib"
New-Item -ItemType Directory -Force $libDir | Out-Null
& $jarTool cf (Join-Path $libDir "mappeek-idea.jar") -C $stage .
Copy-Item $toolJar $libDir

# 用 jar 打包而不是 Compress-Archive：PowerShell 5 的 Compress-Archive 会把路径写成反斜杠，
# IDEA 读取时会认为压缩包没有有效条目（Corrupted archive / no file entries）。
$zip = Join-Path $root "dist\mappeek-idea.zip"
Remove-Item $zip -Force -ErrorAction SilentlyContinue
& $jarTool cMf $zip -C (Join-Path $root "dist") mappeek-idea
Write-Host "构建完成: dist\mappeek-idea.zip"
