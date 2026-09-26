<#
  MeshAI for Windows: talk to the model running on your phones.
  The phones do all the work; this laptop only sends questions and shows answers.

    .\mesh.ps1 chat                 open the chat page in the browser
    .\mesh.ps1 status               is the Host phone reachable, which model is running
    .\mesh.ps1 ask "question"       answer streamed in this window
    .\mesh.ps1 review file.py       review a file
    .\mesh.ps1 logs                 open the log folder (send these files when something breaks)

  The Host phone is found automatically when this laptop is on its hotspot (the hotspot's gateway is the phone).
  Otherwise:  .\mesh.ps1 status -HostIp 192.168.x.x   (the address is on the phone's Mesh tab, under the QR)
#>
param(
    [Parameter(Position = 0)][string]$Command = "chat",
    [Parameter(Position = 1)][string]$Arg = "",
    [string]$HostIp = ""
)

$ErrorActionPreference = "Stop"
$Port = 8080
$Home_ = Join-Path $env:LOCALAPPDATA "MeshAI"
$LogDir = Join-Path $Home_ "logs"
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$Log = Join-Path $LogDir ("mesh-" + (Get-Date -Format "yyyy-MM-dd") + ".log")

function Log([string]$msg) {
    $line = "{0}  {1}" -f (Get-Date -Format "HH:mm:ss"), $msg
    Add-Content -Path $Log -Value $line
}

function Say([string]$msg, [string]$color = "Gray") {
    Write-Host $msg -ForegroundColor $color
    Log $msg
}

function Test-Host([string]$ip) {
    try {
        $r = Invoke-WebRequest -Uri "http://${ip}:$Port/health" -UseBasicParsing -TimeoutSec 3
        return ($r.StatusCode -eq 200)
    } catch { return $false }
}

# The Host phone: -HostIp, else the last one that worked, else this laptop's gateways (the phone, on its hotspot).
function Find-Host {
    $saved = Join-Path $Home_ "host.txt"
    $candidates = @()
    if ($HostIp) { $candidates += $HostIp }
    if (Test-Path $saved) { $candidates += (Get-Content $saved -Raw).Trim() }
    try {
        $candidates += (Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue |
            Sort-Object RouteMetric | ForEach-Object { $_.NextHop })
    } catch {}
    foreach ($ip in ($candidates | Where-Object { $_ -and $_ -ne "0.0.0.0" } | Select-Object -Unique)) {
        Log "trying $ip"
        if (Test-Host $ip) {
            Set-Content -Path $saved -Value $ip
            return $ip
        }
    }
    Say "Cannot find the Host phone." Red
    Say "  1. Join this laptop to the Host phone's hotspot (Wi-Fi), or connect it with USB tethering." Yellow
    Say "  2. On the Host phone: MeshAI -> Models -> Run a model." Yellow
    Say "  3. Or pass the address shown on the phone's Mesh tab:  .\mesh.ps1 status -HostIp 192.168.x.x" Yellow
    Log ("network: " + ((Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        ForEach-Object { "$($_.InterfaceAlias)=$($_.IPAddress)" }) -join ", "))
    exit 1
}

function Ask([string]$ip, [string]$question) {
    Add-Type -AssemblyName System.Net.Http
    $body = @{
        messages   = @(@{ role = "user"; content = $question })
        stream     = $true
        max_tokens = 1024
    } | ConvertTo-Json -Depth 5
    $client = New-Object System.Net.Http.HttpClient
    $client.Timeout = [TimeSpan]::FromMinutes(10)
    $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Post, "http://${ip}:$Port/v1/chat/completions")
    $req.Content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, "application/json")
    $t0 = Get-Date
    $first = $null
    $text = New-Object System.Text.StringBuilder
    $resp = $client.SendAsync($req, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result
    if (-not $resp.IsSuccessStatusCode) { Say "HTTP $([int]$resp.StatusCode) from the Host" Red; exit 1 }
    $reader = New-Object System.IO.StreamReader($resp.Content.ReadAsStreamAsync().Result)
    $timings = $null
    while (-not $reader.EndOfStream) {
        $line = $reader.ReadLine()
        if (-not $line.StartsWith("data: ")) { continue }
        $data = $line.Substring(6)
        if ($data -eq "[DONE]") { break }
        $j = $data | ConvertFrom-Json
        if ($j.timings) { $timings = $j.timings }
        $piece = $j.choices[0].delta.content
        if ($piece) {
            if (-not $first) { $first = (Get-Date) - $t0 }
            [void]$text.Append($piece)
            Write-Host -NoNewline $piece
        }
    }
    Write-Host ""
    if ($timings) {
        $s = "[{0:N1} tok/s, {1} tokens, first word {2:N1}s]" -f $timings.predicted_per_second, $timings.predicted_n, $first.TotalSeconds
        Write-Host $s -ForegroundColor DarkGray
        Log "ask ok: $s"
    }
    Log ("Q: " + $question.Substring(0, [Math]::Min(200, $question.Length)))
    Log ("A: " + $text.ToString().Substring(0, [Math]::Min(500, $text.Length)))
}

Log "---- mesh $Command $Arg"
switch ($Command) {
    "logs" { Start-Process $LogDir; break }
    "status" {
        $ip = Find-Host
        $props = Invoke-RestMethod -Uri "http://${ip}:$Port/props" -TimeoutSec 5
        $model = Split-Path -Leaf $props.model_path
        Say "Host phone: $ip" Green
        Say "Model:      $model"
        Say "API:        http://${ip}:$Port/v1   (any OpenAI-compatible tool can use this)"
        break
    }
    "chat" {
        $ip = Find-Host
        Say "Opening the chat page on the Host phone: http://${ip}:$Port/" Green
        Start-Process "http://${ip}:$Port/"
        break
    }
    "ask" {
        if (-not $Arg) { Say 'usage: .\mesh.ps1 ask "question"' Yellow; exit 2 }
        Ask (Find-Host) $Arg
        break
    }
    "review" {
        if (-not (Test-Path $Arg)) { Say "no such file: $Arg" Red; exit 2 }
        $code = Get-Content -Raw -Path $Arg
        Ask (Find-Host) ("Review this file ($Arg). List real bugs first, then risky spots, briefly.`n`n``````n$code`n``````")
        break
    }
    default { Get-Help $MyInvocation.MyCommand.Path; exit 2 }
}
