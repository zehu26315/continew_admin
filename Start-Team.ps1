param(
    [switch]$Native,
    [switch]$Build,
    [switch]$Face,
    [switch]$Rag,
    [switch]$Graph,
    [string]$FacePython = '',
    [string]$Python = ''
)

# 工程实训启动脚本：锁定 JDK 17 与全局 Node，缺省连本机 MySQL/Redis，构建胖包后拉起前后端。
# 详细说明见同目录《启动说明.md》。

$ErrorActionPreference = 'Continue'
Set-Location $PSScriptRoot

$api = Join-Path $PSScriptRoot 'continew-admin'
$ui = Join-Path $PSScriptRoot 'continew-admin-ui'
$rt = Join-Path $PSScriptRoot 'local-runtime'
$jar = Join-Path $api 'continew-server/target/continew-admin.jar'
New-Item -Path $rt -ItemType Directory -Force | Out-Null

function Fail($message) {
    Write-Host ''
    Write-Host "启动失败：$message" -ForegroundColor Red
    Write-Host "日志目录：$rt"
    exit 1
}

function Install-PythonDeps($python, $requirementFile, $importCheck, $label) {
    & $python -c $importCheck 2>$null
    if ($LASTEXITCODE -eq 0) {
        Write-Host "$label 依赖已就绪，跳过安装"
        return
    }
    if (Get-Command uv -ErrorAction SilentlyContinue) {
        & uv pip install --python $python -r $requirementFile
    }
    if ($LASTEXITCODE) {
        & $python -m pip install -r $requirementFile
    }
    if ($LASTEXITCODE) { Fail "$label 依赖安装失败，请检查网络后重试。" }
}

function Test-Jdk17($javaHome) {
    if (!$javaHome -or !(Test-Path (Join-Path $javaHome 'bin/java.exe'))) { return $false }
    $release = Join-Path $javaHome 'release'
    if (Test-Path $release) {
        $match = Select-String -Path $release -Pattern 'JAVA_VERSION="(17)[.0-9_]*"' | Select-Object -First 1
        if ($match) { return $true }
    }
    $version = (& (Join-Path $javaHome 'bin/java.exe') -version 2>&1 | Out-String)
    return $version -match '"17\.'
}

# 1. Java：必须是 17，Maven 编译与运行时都要用同一个
$jdkCandidates = @(
    $env:JAVA_HOME,
    (Join-Path $env:USERPROFILE 'scoop/apps/openjdk17/current'),
    'C:\Program Files\Eclipse Adoptium\jdk-17'
)
$jdk = $null
foreach ($candidate in $jdkCandidates) {
    if (Test-Jdk17 $candidate) { $jdk = $candidate; break }
}
if (!$jdk) { Fail '未找到 JDK 17。请安装 JDK 17 并把 JAVA_HOME 指过去。' }
$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path

# 2. Node：优先全局（fnm 管理的 node/pnpm），不再使用任何内嵌 Node
if (Get-Command fnm -ErrorAction SilentlyContinue) {
    try {
        $multishell = (fnm env --json | ConvertFrom-Json).FNM_MULTISHELL_PATH
        if ($multishell -and (Test-Path $multishell)) {
            $env:Path = $multishell + ';' + $env:Path
        }
    } catch {
        Write-Host '提示：fnm 环境读取失败，改用 PATH 中的 node。'
    }
}
foreach ($cmd in 'java', 'node', 'pnpm.cmd') {
    if (!(Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Fail "找不到命令 $cmd。请确认 JDK 17 与全局 Node（含 pnpm）已安装并在 PATH 中。"
    }
}

# 3. 连接参数：未显式设置时用本机方案 A 的默认值
if (!$env:DB_USER) { $env:DB_USER = 'continew_admin' }
if (!$env:DB_PWD) { $env:DB_PWD = 'continew_admin' }
if (!$env:DB_PORT) { $env:DB_PORT = '3306' }
if (!$env:DB_HOST) { $env:DB_HOST = '127.0.0.1' }
if (!$env:DB_NAME) { $env:DB_NAME = 'continew_admin' }
if (!$env:REDIS_HOST) { $env:REDIS_HOST = '127.0.0.1' }
if (!$env:REDIS_PORT) { $env:REDIS_PORT = '6379' }
if (!$env:REDIS_PWD) { $env:REDIS_PWD = '123456' }
if ($Native -and !$env:DB_PWD) { Fail '方案 A 需要 DB_USER、DB_PWD、REDIS_PWD 环境变量。' }

Write-Host ("Java  : " + $env:JAVA_HOME)
Write-Host ("Node  : " + (& node --version) + '  pnpm ' + (& pnpm.cmd --version))
Write-Host ("数据库: {0}@{1}:{2}/{3}" -f $env:DB_USER, $env:DB_HOST, $env:DB_PORT, $env:DB_NAME)

# 4. 数据库与缓存
if ($Native) {
    if (!(Get-NetTCPConnection -LocalPort $env:DB_PORT -State Listen -ErrorAction SilentlyContinue)) {
        Fail "MySQL 未监听 $($env:DB_PORT) 端口。请先启动本机 MySQL 服务。"
    }
    if (!(Get-NetTCPConnection -LocalPort $env:REDIS_PORT -State Listen -ErrorAction SilentlyContinue)) {
        Fail "Redis 未监听 $($env:REDIS_PORT) 端口。请先执行 redis-server --port $($env:REDIS_PORT) --requirepass $($env:REDIS_PWD)。"
    }
} else {
    if (!(Get-Command docker -ErrorAction SilentlyContinue)) {
        Fail '未安装 Docker。请加 -Native 使用本机 MySQL 和 Redis。'
    }
    $config = Join-Path $rt 'compose.env'
    if (!(Test-Path $config)) {
        @(
            "DB_PWD=$([guid]::NewGuid().ToString('N'))",
            "REDIS_PWD=$([guid]::NewGuid().ToString('N'))"
        ) | Set-Content $config -Encoding ascii
    }
    $settings = Get-Content $config -Raw | ConvertFrom-StringData
    $env:DB_PWD = $settings.DB_PWD
    $env:REDIS_PWD = $settings.REDIS_PWD
    $env:DB_USER = 'training'
    $env:DB_PORT = '13306'
    $env:REDIS_PORT = '16379'
    docker compose -p continew-training --env-file $config up -d --wait
    if ($LASTEXITCODE) { Fail 'MySQL 与 Redis 容器启动失败。' }
}

# 4.5 Python：默认优先 uv 管理的 3.12，其次 PATH 中的 python
if (!$Python) {
    if (Get-Command uv -ErrorAction SilentlyContinue) {
        $uvPython = (& uv python find 3.12 2>$null)
        if ($uvPython) { $Python = ($uvPython | Select-Object -First 1).ToString().Trim() }
    }
    if (!$Python) { $Python = 'python' }
}
if ($Face -and !$FacePython) { $FacePython = $Python }
if (-not (Test-Path $Python) -and !(Get-Command $Python -ErrorAction SilentlyContinue)) {
    Fail "找不到 Python 解释器：$Python。请安装 Python 3.12，或用 -Python <路径> 指定。"
}
Write-Host ("Python: " + $Python)

# 5. 课程功能：人脸识别、私人资料问答、图谱问答（按需开启，未开启则不注册对应后台队列）
$env:TRAINING_FACE_HOME = Join-Path $rt 'face'
$env:TRAINING_FACE_WORKER = Join-Path $api 'training-face/worker.py'
if ($Face) {
    if (!(Get-Command $FacePython -ErrorAction SilentlyContinue)) {
        Fail "人脸识别需要 64 位 Python 3.11/3.12，且 $FacePython 可用。用 -FacePython <解释器路径> 指定。"
    }
    & (Join-Path $api 'training-face/Setup-Face.ps1') -Python $FacePython
    if ($LASTEXITCODE) { Fail '人脸模型或依赖安装失败。' }
}
if ($Rag) {
    if (!(Get-Command ollama -ErrorAction SilentlyContinue)) {
        Fail '私人资料问答需要 Ollama，且已拉取 qwen2.5:3b 与 qwen3-embedding:0.6b。'
    }
    $env:TRAINING_RAG_HOME = Join-Path $rt 'rag'
    $env:TRAINING_RAG_PYTHON = Join-Path $rt 'rag/venv/Scripts/python.exe'
    $env:TRAINING_RAG_WORKER = Join-Path $api 'training-rag/worker.py'
    if (!(Test-Path $env:TRAINING_RAG_PYTHON)) {
        & $Python -m venv (Join-Path $rt 'rag/venv')
        if ($LASTEXITCODE) { Fail '问答环境创建失败，需要 Python 3.11/3.12。' }
    }
    Install-PythonDeps $env:TRAINING_RAG_PYTHON (Join-Path $api 'training-rag/requirements.txt') 'import sqlite_vec, pypdf, defusedxml' '问答'
    foreach ($model in 'qwen3-embedding:0.6b', 'qwen2.5:3b') {
        & ollama pull $model
        if ($LASTEXITCODE) { Fail "模型 $model 拉取失败。" }
    }
}
if ($Graph) {
    $env:TRAINING_GRAPH_HOME = Join-Path $rt 'graph'
    $env:TRAINING_GRAPH_PYTHON = Join-Path $rt 'graph/venv/Scripts/python.exe'
    $env:TRAINING_GRAPH_WORKER = Join-Path $api 'training-graph/worker.py'
    if (!(Test-Path $env:TRAINING_GRAPH_PYTHON)) {
        & $Python -m venv (Join-Path $rt 'graph/venv')
        if ($LASTEXITCODE) { Fail '图谱环境创建失败，需要 Python 3.11 及以上。' }
    }
    Install-PythonDeps $env:TRAINING_GRAPH_PYTHON (Join-Path $api 'training-graph/requirements.txt') 'import pypdf, defusedxml' '图谱'
}

# 6. 构建：必须是胖包，否则脚本拿不到可执行的 jar
if ($Build -or !(Test-Path $jar)) {
    Write-Host '正在构建后端胖包（首次或 -Build 时执行，约 5 分钟）…'
    Push-Location $api
    try {
        & .\mvnw.cmd -B -DskipTests -P fat-jar package
        if ($LASTEXITCODE) { Fail 'Maven 构建失败，先看上面的编译错误。' }
    } finally {
        Pop-Location
    }
}
if (!(Test-Path $jar)) { Fail '后端包不存在，请加 -Build 重新构建。' }

# 7. 前端依赖：缺失时才安装
if (!(Test-Path (Join-Path $ui 'node_modules/vite/bin/vite.js'))) {
    Write-Host '正在安装前端依赖（约 3 分钟）…'
    Push-Location $ui
    try {
        & pnpm.cmd install --frozen-lockfile
        if ($LASTEXITCODE) { Fail 'pnpm install 失败。' }
    } finally {
        Pop-Location
    }
}

# 8. 拉起前后端
$env:SERVER_ADDRESS = '127.0.0.1'
$env:CONTINEWSTARTER_REFRESHTOKEN_COOKIEALLOWEDORIGINS = 'http://localhost:5173,http://127.0.0.1:5173'
$backend = $null
$frontend = $null
if (!(Get-NetTCPConnection -LocalPort 8000 -State Listen -ErrorAction SilentlyContinue)) {
    $backend = Start-Process (Join-Path $env:JAVA_HOME 'bin/java.exe') `
        -ArgumentList '-Xmx1024m', '-Dp6spy.config.filter=true', '-Dp6spy.config.exclude=training_document', '-jar', ('"' + $jar + '"') `
        -WorkingDirectory $rt -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $rt 'backend.log') -RedirectStandardError (Join-Path $rt 'backend-errors.log')
}
if (!(Get-NetTCPConnection -LocalPort 5173 -State Listen -ErrorAction SilentlyContinue)) {
    $env:BROWSER = 'none'
    $frontend = Start-Process (Get-Command node).Source `
        -ArgumentList (Join-Path $ui 'node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', '5173', '--strictPort' `
        -WorkingDirectory $ui -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $rt 'frontend.log') -RedirectStandardError (Join-Path $rt 'frontend-errors.log')
}

# 9. 等待就绪
$limit = (Get-Date).AddMinutes(4)
$ready = $false
do {
    if (($backend -and $backend.HasExited) -or ($frontend -and $frontend.HasExited)) {
        Fail '进程提前退出，见 local-runtime 下的 backend-errors.log 与 frontend-errors.log。'
    }
    try {
        $ready = (Invoke-RestMethod 'http://127.0.0.1:5173/dev-api/captcha/image' -TimeoutSec 3).success
    } catch {
        $ready = $false
    }
    if (!$ready) { Start-Sleep -Seconds 2 }
} until ($ready -or (Get-Date) -gt $limit)
if (!$ready) { Fail '4 分钟内未就绪，见 local-runtime 下的日志。' }

Write-Host ''
Write-Host 'Ready: http://localhost:5173/' -ForegroundColor Green
Write-Host '默认账号：admin / admin123（登录后请改密码；test / test123 为普通账号）'
Write-Host "日志：$rt"
Write-Host '停止：Get-NetTCPConnection -LocalPort 8000,5173 -State Listen | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }'
