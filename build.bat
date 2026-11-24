@echo off
rem MapPeek one-click package: build core jar + compile plugin + make installable zip
setlocal EnableExtensions

rem ===== Machine-specific paths. Override with env vars if needed. =====
if not defined MAPEEK_JDK  set "MAPEEK_JDK=D:\Java\jdk-21.0.11"
if not defined MAPEEK_IDEA set "MAPEEK_IDEA=D:\JetBrains\IntelliJ IDEA 2026.1.3"
if not defined MAPEEK_MVN  set "MAPEEK_MVN=E:\apache-maven-3.9.16\bin\mvn.cmd"
rem ====================================================================

set "ROOT=%~dp0"
set "ROOT=%ROOT:~0,-1%"

echo.
echo === MapPeek build ===

if not exist "%MAPEEK_JDK%\bin\javac.exe" (
    echo [ERROR] JDK not found: %MAPEEK_JDK%
    echo         Edit MAPEEK_JDK at the top of this script, or set the env var.
    exit /b 1
)
if not exist "%MAPEEK_IDEA%\lib" (
    echo [ERROR] IntelliJ IDEA not found: %MAPEEK_IDEA%
    echo         Edit MAPEEK_IDEA at the top of this script.
    exit /b 1
)
if not exist "%MAPEEK_MVN%" (
    echo [ERROR] Maven not found: %MAPEEK_MVN%
    echo         Edit MAPEEK_MVN at the top of this script.
    exit /b 1
)

set "JAVA_HOME=%MAPEEK_JDK%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [1/2] Building core jar ...
pushd "%ROOT%\mappeek-core"
call "%MAPEEK_MVN%" -B -q clean package -DskipTests
if errorlevel 1 (
    popd
    echo [FAILED] core build failed
    exit /b 1
)
popd

if not exist "%ROOT%\mappeek-core\target\mappeek.jar" (
    echo [FAILED] mappeek.jar not generated
    exit /b 1
)

echo [2/2] Compiling plugin and packing zip ...
powershell -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\mappeek-idea\build.ps1" -IdeaHome "%MAPEEK_IDEA%" -JdkHome "%MAPEEK_JDK%"
if errorlevel 1 (
    echo [FAILED] plugin packaging failed
    exit /b 1
)

set "ZIP=%ROOT%\mappeek-idea\dist\mappeek-idea.zip"
if not exist "%ZIP%" (
    echo [FAILED] zip not generated
    exit /b 1
)

echo.
echo === DONE ===
echo Package: %ZIP%
echo Install: IDEA - Settings - Plugins - gear icon - Install Plugin from Disk
echo.

if /i "%~1"=="/open" explorer "%ROOT%\mappeek-idea\dist"
exit /b 0
