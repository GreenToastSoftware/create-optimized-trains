@echo off
setlocal enabledelayedexpansion

:: ============================================
::  Create Optimized Trains - Build Tool
::  Compila o addon e copia o .jar para /output
:: ============================================

title Create Optimized Trains - Build

echo.
echo  ========================================
echo   Create Optimized Trains - Build Tool
echo  ========================================
echo.

cd /d "%~dp0"

:: Verificar se Java esta instalado
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERRO] Java nao encontrado! Instala o JDK 17+.
    echo        Download: https://adoptium.net/
    echo.
    pause
    exit /b 1
)

:: Verificar se Gradle wrapper existe
if not exist "gradlew.bat" (
    echo [ERRO] gradlew.bat nao encontrado!
    pause
    exit /b 1
)

if not exist "gradle\wrapper\gradle-wrapper.jar" (
    echo [ERRO] gradle-wrapper.jar nao encontrado!
    pause
    exit /b 1
)

echo [INFO] A compilar o mod...
echo [INFO] Isto pode demorar na primeira vez (descarregar dependencias).
echo.

:: Criar pasta output
if not exist "output" mkdir "output"

:: Limpar builds anteriores da pasta output
del /q "output\*.jar" 2>nul

:: Build
call gradlew.bat build
if errorlevel 1 (
    echo.
    echo  ========================================
    echo   [ERRO] Build falhou!
    echo  ========================================
    echo.
    echo  Verifica os erros acima.
    echo  Causas comuns:
    echo   - Erros de sintaxe no codigo Java
    echo   - Dependencias em falta (verifica ligacao a internet)
    echo   - Versao errada do Java (precisa JDK 17+)
    echo.
    pause
    exit /b 1
)

:: Copiar .jar para /output
echo.
echo [INFO] A copiar JAR para pasta output...

set JAR_FOUND=0
for %%f in (build\libs\*.jar) do (
    if not "%%~xf"=="" (
        copy "%%f" "output\" >nul
        echo [OK] %%~nxf
        set JAR_FOUND=1
    )
)

if %JAR_FOUND%==0 (
    echo [AVISO] Nenhum JAR encontrado em build/libs/
    pause
    exit /b 1
)

echo.
echo  ========================================
echo   BUILD COMPLETO!
echo  ========================================
echo.
echo  Os ficheiros .jar estao em: output\
echo.
echo  Para testar:
echo   1. Copia o .jar para a pasta mods/ do Minecraft
echo   2. Certifica-te que tens o Create 6.0.8 instalado
echo   3. Inicia o Minecraft com Forge 1.20.1
echo.

:: Abrir pasta output
explorer "output"

pause
