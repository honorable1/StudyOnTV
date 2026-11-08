@echo off
chcp 936 >nul
setlocal EnableExtensions EnableDelayedExpansion
title StudyOnTV 控制台

rem ============================================================
rem  StudyOnTV 更新源启停控制台
rem  双击运行 = 菜单；也可带参数：
rem    start / stop / restart / status / install / uninstall
rem  本文件必须以 GBK（ANSI）编码保存，UTF-8 会显示乱码
rem ============================================================

set "SELF=%~f0"
cd /d "%~dp0.." 2>nul || (echo [错误] 无法进入仓库根目录 & pause & exit /b 1)
set "ROOT=%CD%"

rem ---------- 配置（换域名只改这一行） ----------
set "PORT=8788"
set "BASE=http://55egrv9vqmkr.xiaomiqiu.com"
set "TASKNAME=StudyOnTV 更新源"
set "SERVICETITLE=StudyOnTV 服务 %PORT%"
set "URL=%BASE%/update.json"

rem ---------- 定位 node.exe ----------
set "NODE="
for /f "delims=" %%i in ('where node 2^>nul') do if not defined NODE set "NODE=%%i"
if not defined NODE if exist "%ProgramFiles%\nodejs\node.exe" set "NODE=%ProgramFiles%\nodejs\node.exe"
if not defined NODE for /d %%d in ("%USERPROFILE%\.workbuddy\binaries\node\versions\*") do if exist "%%~d\node.exe" set "NODE=%%~d\node.exe"
if not defined NODE (
  echo [错误] 找不到 node.exe
  echo        请安装 Node.js，或修改本脚本里"定位 node.exe"那段的路径
  pause
  exit /b 1
)
for %%i in ("%NODE%") do set "NODEDIR=%%~dpi"

if not exist "%ROOT%\scripts\publish-local.mjs" (
  echo [错误] 找不到 scripts\publish-local.mjs
  echo        本文件必须放在仓库的 scripts 目录里
  pause
  exit /b 1
)

rem ---------- 参数分发 ----------
if /i "%~1"=="auto"      goto :auto
if /i "%~1"=="start"     (call :do_start     & exit /b 0)
if /i "%~1"=="stop"      (call :do_stop      & exit /b 0)
if /i "%~1"=="restart"   (call :do_restart   & exit /b 0)
if /i "%~1"=="status"    (call :do_status    & exit /b 0)
if /i "%~1"=="install"   (call :do_install   & exit /b 0)
if /i "%~1"=="uninstall" (call :do_uninstall & exit /b 0)

rem ---------- 双击 = 菜单 ----------
:menu
cls
echo.
echo   ==================================================
echo       StudyOnTV 更新源控制台    端口 %PORT%
echo       %URL%
echo   ==================================================
echo.
call :do_status
echo.
echo     1. 启动服务
echo     2. 停止服务
echo     3. 重启服务
echo     4. 安装开机自启（任务计划）
echo     5. 移除开机自启
echo     6. 刷新状态
echo     0. 退出
echo.
choice /c 1234560 /n /m "    请选择: "
if errorlevel 7 exit /b 0
if errorlevel 6 goto :menu
if errorlevel 5 (
  call :do_uninstall
  echo.
  pause
  goto :menu
)
if errorlevel 4 (
  call :do_install
  echo.
  pause
  goto :menu
)
if errorlevel 3 (
  call :do_restart
  echo.
  pause
  goto :menu
)
if errorlevel 2 (
  call :do_stop
  echo.
  pause
  goto :menu
)
if errorlevel 1 (
  call :do_start
  echo.
  pause
  goto :menu
)
goto :menu

rem ============================================================
rem  子过程
rem ============================================================

:getpid
set "PID="
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /c:":%PORT% " ^| findstr /i "LISTENING"') do set "PID=%%p"
exit /b 0

rem 等端口就绪：最多 %~1 次，每次约 1 秒；成功 exit /b 0，超时 exit /b 1
:wait_port
set /a _tries=0
:wait_port_loop
call :getpid
if defined PID exit /b 0
set /a _tries+=1
if !_tries! geq %~1 exit /b 1
ping -n 2 127.0.0.1 >nul 2>&1
goto :wait_port_loop

:do_start
call :getpid
if defined PID (
  echo   服务已在运行（PID !PID!），端口 %PORT%
  exit /b 0
)
if not exist "%ROOT%\dist" mkdir "%ROOT%\dist" >nul 2>&1
echo   正在启动服务...
set "PATH=%NODEDIR%;%PATH%"
start "%SERVICETITLE%" /min cmd /k node scripts\publish-local.mjs --no-build --port %PORT% --base %BASE%
call :wait_port 12
call :getpid
if defined PID (
  echo   已启动（PID !PID!）
  echo   对外地址 %URL%
  echo   服务跑在新弹出的最小化窗口里，关掉那个窗口就等于停止服务
) else (
  echo   启动失败：端口 %PORT% 没有监听，请点开最小化窗口查看报错
)
exit /b 0

:do_stop
call :getpid
if not defined PID (
  echo   服务当前没有运行
  exit /b 0
)
set "PNAME=unknown"
for /f "tokens=1 delims=," %%n in ('tasklist /fi "PID eq !PID!" /nh /fo csv 2^>nul') do set "PNAME=%%~n"
echo   正在停止 PID !PID!（!PNAME!）...
taskkill /f /pid !PID! >nul 2>&1
ping -n 3 127.0.0.1 >nul 2>&1
call :getpid
if defined PID (
  echo   停止失败，可能权限不足，请右键"以管理员身份运行"再试
) else (
  echo   已停止。之前那个最小化窗口可以直接关掉
)
exit /b 0

:do_restart
call :do_stop
call :do_start
exit /b 0

:do_status
call :getpid
echo   ---------------- 当前状态 ----------------
if defined PID (echo   端口 %PORT%：监听中（PID !PID!）) else (echo   端口 %PORT%：未监听)
if exist "%ROOT%\dist\update.json" (echo   更新清单：已存在) else (echo   更新清单：缺失，启动时会自动生成)
if exist "%ROOT%\dist\apk" (echo   APK 目录：dist\apk) else (echo   APK 目录：缺失)
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -Command "if (Get-ScheduledTask -TaskName '%TASKNAME%' -ErrorAction SilentlyContinue) { exit 0 } else { exit 1 }" >nul 2>&1
if errorlevel 1 (echo   开机自启：未安装) else (echo   开机自启：已安装)
echo   清单可访问性：
curl -s -o nul -m 6  -w "     local   HTTP %%{http_code}\n" "http://127.0.0.1:%PORT%/update.json" 2>nul
curl -s -o nul -m 10 -w "     public  HTTP %%{http_code}\n" "%URL%" 2>nul
echo   ------------------------------------------
exit /b 0

:do_install
echo   正在创建计划任务...
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -Command "$n='%TASKNAME%'; $x='%SELF%'; $w='%ROOT%'; $q=[char]34; $a=New-ScheduledTaskAction -Execute 'cmd.exe' -Argument ('/c ' + $q + $x + $q + ' auto') -WorkingDirectory $w; $t=New-ScheduledTaskTrigger -AtLogOn; Register-ScheduledTask -TaskName $n -Action $a -Trigger $t -Description 'StudyOnTV 更新源：登录后自动托管更新清单' -Force | Out-Null" > "%TEMP%\sotv-task.log" 2>&1
if errorlevel 1 (
  echo   创建失败，错误信息如下：
  type "%TEMP%\sotv-task.log"
) else (
  echo   已创建计划任务"%TASKNAME%"，下次登录 Windows 会在后台启动
  echo   想现在验证：任务计划程序里右键该任务选"运行"
  echo   查看或删除：taskschd.msc → 任务计划程序库
)
exit /b 0

:do_uninstall
echo   正在移除计划任务...
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -Command "if (Get-ScheduledTask -TaskName '%TASKNAME%' -ErrorAction SilentlyContinue) { Unregister-ScheduledTask -TaskName '%TASKNAME%' -Confirm:$false -ErrorAction Stop; exit 0 } else { exit 2 }" >nul 2>&1
if errorlevel 2 (
  echo   没有找到同名计划任务
) else (
  if errorlevel 1 (
    echo   移除失败，可能权限不足，请右键"以管理员身份运行"
  ) else (
    echo   已移除计划任务"%TASKNAME%"
  )
)
exit /b 0

rem 任务计划调用：和手工启动一样，只是不打印提示
:auto
call :getpid
if defined PID exit /b 0
if not exist "%ROOT%\dist\update.json" exit /b 0
call :do_start
exit /b 0
