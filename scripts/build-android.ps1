param([ValidateSet('arm64-v8a','x86_64')][string]$Abi='arm64-v8a',[switch]$Tests)
$ErrorActionPreference='Stop'
$taskProject=Split-Path -Parent $PSScriptRoot
if(-not $env:JAVA_HOME){throw 'Set JAVA_HOME to a JDK 21 installation.'}
if(-not $env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT){$env:ANDROID_HOME=$env:ANDROID_SDK_ROOT}
if(-not $env:ANDROID_HOME){throw 'Set ANDROID_HOME to the Android SDK directory.'}
if(-not (Test-Path -LiteralPath (Join-Path $taskProject ".runtime/offline/android-assets/$Abi/offline-engine/mysql-runtime.zip"))){throw 'Prepare the offline runtime for this ABI first.'}
$taskDigest=[Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($taskProject))
$taskAlias=Join-Path ([IO.Path]::GetTempPath()) ('kaisersql-'+[BitConverter]::ToString($taskDigest).Replace('-','').Substring(0,12).ToLowerInvariant())
if(-not (Test-Path -LiteralPath $taskAlias)){New-Item -ItemType Junction -Path $taskAlias -Target $taskProject | Out-Null}
$taskLink=Get-Item -LiteralPath $taskAlias
if($taskLink.LinkType -ne 'Junction' -or [IO.Path]::GetFullPath($taskLink.Target).TrimEnd('\') -ne [IO.Path]::GetFullPath($taskProject).TrimEnd('\')){throw 'Build alias belongs to another directory.'}
$taskTargets=@('assembleOfflineDebug')
if($Tests){$taskTargets+='assembleOfflineDebugAndroidTest'}
& (Join-Path $taskAlias 'android/gradlew.bat') -p (Join-Path $taskAlias 'android') --no-daemon --console=plain "-PlabAbi=$Abi" @taskTargets
if($LASTEXITCODE -ne 0){throw 'Android build failed.'}
$taskOutput=Join-Path $taskProject 'outputs/app'
New-Item -ItemType Directory -Path $taskOutput -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $taskAlias 'android/app/build/outputs/apk/offline/debug/app-offline-debug.apk') -Destination (Join-Path $taskOutput "Pocket-MySQL-offline-$Abi-debug.apk") -Force
Write-Output "APK generated in outputs/app for $Abi."
