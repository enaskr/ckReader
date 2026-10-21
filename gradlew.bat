@echo off
setlocal
set "APP_HOME=%~dp0"
set "APP_BASE_NAME=%~n0"
set "CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar"
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" -version >NUL 2>&1
if errorlevel 1 (
  echo ERROR: Java 17 is required. Configure JAVA_HOME or PATH. 1>&2
  exit /b 1
)
"%JAVA_EXE%" -Xmx64m -Xms64m %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
