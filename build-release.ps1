# Kept for people who run it directly: the release build is now a Node script that also works on Linux.
#   npm run release      (same thing)
# Options are passed through:  -Version 1.1  -SkipAndroid  -DebugKey  -RebuildHelper  -NoLinuxTools
param(
    [switch]$DebugKey,
    [switch]$SkipAndroid,
    [switch]$RebuildHelper,
    [switch]$NoLinuxTools,
    [string]$Version = '1.0'
)
$a = @((Join-Path $PSScriptRoot 'scripts\release.mjs'), '--version', $Version)
if ($DebugKey) { $a += '--debug-key' }
if ($SkipAndroid) { $a += '--skip-android' }
if ($RebuildHelper) { $a += '--rebuild-helper' }
if ($NoLinuxTools) { $a += '--no-linux-tools' }
& node @a
exit $LASTEXITCODE
