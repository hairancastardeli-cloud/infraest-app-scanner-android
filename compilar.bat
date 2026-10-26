@echo off
rem Compila o Scanner Campo e roda os testes. Saida: ScannerCampo-0.6.apk nesta pasta.
cd /d "%~dp0"
set A=%~dp0..\android
set JAVA_HOME=%A%\jdk-17.0.20.1+1
set GRADLE_USER_HOME=%A%\gradle_home
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
