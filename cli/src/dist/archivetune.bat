@echo off
rem ArchiveTune CLI launcher for Windows.
rem
rem Wraps the fat jar and pins the encodings the CLI needs. Kept as a .bat
rem because that is the one thing on PATH everywhere on Windows, and because
rem a .bat can be dropped into any directory and double-clicked into a shell
rem with archivetune on the PATH.
setlocal

if defined ARCHIVETUNE_JAVA set "JAVA=%ARCHIVETUNE_JAVA%"
if not defined JAVA if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for %%J in (java.exe) do set "JAVA=%%~$PATH:J"

if not defined JAVA (
  echo error: no java found. Set JAVA_HOME or put java.exe on the PATH. 1>&2
  exit /b 1
)

rem The jar is looked for next to this script first, then one level up, so the
rem launcher works both from cli\ and from a copied bin\ directory.
set "JAR=%~dp0cli-all.jar"
if not exist "%JAR%" for %%D in ("%~dp0..") do set "JAR=%%~fD\cli-all.jar"

if not exist "%JAR%" (
  echo error: cli-all.jar not found next to this script. 1>&2
  echo   Build it with: gradlew :cli:fatJar 1>&2
  exit /b 1
)

rem file.encoding covers reading config and cache files, stdout/stderr cover
rem printing CJK titles, and sun.jnu.encoding is set too even though the JVM
rem fixes it during init - it costs nothing and helps on JDKs that honour it.
rem Arguments themselves are recovered from the OS by cli/WindowsArgs.kt, which
rem is the only thing that actually works for a Japanese query.
"%JAVA%" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" %*
exit /b %ERRORLEVEL%
