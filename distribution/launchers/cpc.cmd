@echo off
setlocal
set "CPLUS_HOME=%~dp0"
if "%CPLUS_HOME:~-1%"=="\" set "CPLUS_HOME=%CPLUS_HOME:~0,-1%"
set "JAVA_BIN=%CPLUS_JAVA%"
if not defined JAVA_BIN if defined JAVA_HOME set "JAVA_BIN=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_BIN if defined IDEA_JDK set "JAVA_BIN=%IDEA_JDK%\bin\java.exe"
if not defined JAVA_BIN if defined JDK_HOME set "JAVA_BIN=%JDK_HOME%\bin\java.exe"
if not defined JAVA_BIN set "JAVA_BIN=java"
"%JAVA_BIN%" --enable-native-access=ALL-UNNAMED "-Dcplus.home=%CPLUS_HOME%" -jar "%CPLUS_HOME%\c-plus.jar" %*
exit /b %ERRORLEVEL%
