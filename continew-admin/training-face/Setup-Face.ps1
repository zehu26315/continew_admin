param([string]$Python = 'py', [string]$Runtime = (Join-Path $PSScriptRoot '../../local-runtime/face'))
$ErrorActionPreference = 'Stop'
$Runtime = [IO.Path]::GetFullPath($Runtime)
New-Item -ItemType Directory -Path "$Runtime/models" -Force | Out-Null
$private = New-Item -ItemType Directory -Path "$Runtime/private" -Force
$acl = Get-Acl -LiteralPath $private.FullName
$acl.SetAccessRuleProtection($true, $false)
$principals = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')
foreach ($sid in $principals) {
    $identity = [Security.Principal.SecurityIdentifier]::new($sid)
    $rule = [Security.AccessControl.FileSystemAccessRule]::new($identity, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    $acl.SetAccessRule($rule)
}
# 重复执行时 ACL 已收紧，收紧操作可能因缺少 SeSecurityPrivilege 失败，视为已就绪
try { Set-Acl -LiteralPath $private.FullName -AclObject $acl } catch { Write-Warning 'Template directory ACL already restricted.' }
if (!(Test-Path "$Runtime/venv/Scripts/python.exe")) {
    & $Python -m venv "$Runtime/venv"
    if ($LASTEXITCODE) { throw 'Unable to create Python environment' }
}
& "$Runtime/venv/Scripts/python.exe" -m pip install --only-binary=:all: -r "$PSScriptRoot/requirements.txt"
if ($LASTEXITCODE) { throw 'Python dependencies failed to install' }
$models = @{
    'face_detection_yunet/face_detection_yunet_2023mar.onnx' = '8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4'
    'face_recognition_sface/face_recognition_sface_2021dec.onnx' = '0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79'
}
foreach ($key in $models.Keys) {
    $path = Join-Path "$Runtime/models" (Split-Path $key -Leaf)
    if ((Test-Path $path) -and (Get-FileHash $path).Hash -eq $models[$key]) { continue }
    Invoke-WebRequest "https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/$key" -OutFile "$path.download"
    if ((Get-FileHash "$path.download").Hash -ne $models[$key]) { throw "Invalid model checksum: $key" }
    Move-Item -LiteralPath "$path.download" -Destination $path -Force
}
