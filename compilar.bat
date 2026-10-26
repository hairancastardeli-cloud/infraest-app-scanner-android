@echo off
rem Compila o Scanner Campo e roda os testes. Saida: ScannerCampo-0.6.apk nesta pasta.
rem As ferramentas (JDK 17, Gradle 8.11.1, Android SDK) ficam em C:\SCANNER\_ferramentas\android;
rem para outro lugar, defina ANDROID_TOOLS antes de rodar.
cd /d "%~dp0"
if "%ANDROID_TOOLS%"=="" set ANDROID_TOOLS=C:\SCANNER\_ferramentas\android
set A=%ANDROID_TOOLS%
set JAVA_HOME=%A%\jdk-17.0.20.1+1
set GRADLE_USER_HOME=%A%\gradle_home
rem local.properties (fora do git) com o caminho do SDK
> local.properties echo sdk.dir=%A:\=/%/sdk
call "%A%\gradle-8.11.1\bin\gradle.bat" --no-daemon testDebugUnitTest assembleDebug || goto erro
copy /y app\build\outputs\apk\debug\app-debug.apk ScannerCampo-0.6.apk >nul
echo.
echo Pronto: ScannerCampo-0.6.apk
pause
exit /b 0
:erro
echo.
echo FALHOU - veja as mensagens acima.
pause
exit /b 1
