<#
.SYNOPSIS
  Comprueba, y con -Reparar arregla, lo que necesita el servicio en esta PC.

.DESCRIPTION
  Revisa en orden cada tramo de la cadena Navegador -> Vercel -> Tailscale
  Funnel -> contenedor, y dice cual falla y como arreglarlo:

    1. Docker esta corriendo.
    2. El contenedor persona-resolver-app esta arriba.
    3. El backend responde en 127.0.0.1:<puerto>/api/health.
    4. Ningun otro proceso escucha en ese puerto fuera de 127.0.0.1.
    5. Tailscale esta conectado y su clave no esta por vencer.
    6. El Funnel publica ese puerto.
    7. El Funnel responde desde internet.
    8. Con -Sitio, el sitio publico llega al backend: la cadena completa.

  Al final avisa de lo que haria fallar el servicio otro dia: que la PC se
  suspenda sola o que falte la tarea programada.

  Con -Reparar arregla lo que no necesita a una persona: abre Docker Desktop,
  levanta el contenedor, espera a que Tailscale conecte y vuelve a publicar el
  Funnel. No toca la base de datos ni reconstruye la imagen: para actualizar
  sigue siendo git pull y docker compose up -d --build.

  Con -InstalarTarea registra una tarea programada que lo ejecuta con -Reparar
  al iniciar sesion en Windows y cada dia a las 6:45 (si a esa hora la PC
  estaba apagada o suspendida, en cuanto vuelva). No necesita permisos de
  administrador. El resultado de la ultima ejecucion queda en
  %LOCALAPPDATA%\PersonalResolver\verificar-servicio.log.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\verificar-servicio.ps1 -Sitio https://<tu-proyecto>.vercel.app

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\verificar-servicio.ps1 -Sitio https://<tu-proyecto>.vercel.app -Reparar

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\verificar-servicio.ps1 -Sitio https://<tu-proyecto>.vercel.app -InstalarTarea
#>
param(
    [string]$Sitio = '',
    [switch]$Reparar,
    [switch]$InstalarTarea,
    # Lo usa la tarea programada: guarda la salida en un archivo.
    [switch]$Registro
)

$ErrorActionPreference = 'Stop'

$Repo = Split-Path -Parent $PSScriptRoot
$Contenedor = 'persona-resolver-app'
$NombreTarea = 'Personal Resolver - verificar servicio'

$Sitio = $Sitio.Trim().TrimEnd('/')
if ($Sitio -and $Sitio -notmatch '^https?://') { $Sitio = "https://$Sitio" }

# El puerto sale del .env del repositorio, igual que en docker-compose.yml.
$Puerto = 8099
$EnvFile = Join-Path $Repo '.env'
if (Test-Path $EnvFile) {
    $linea = Select-String -Path $EnvFile -Pattern '^\s*APP_PORT\s*=\s*"?(\d+)' | Select-Object -First 1
    if ($linea) { $Puerto = [int]$linea.Matches[0].Groups[1].Value }
}

if ($Registro) {
    $carpeta = Join-Path $env:LOCALAPPDATA 'PersonalResolver'
    New-Item -ItemType Directory -Force -Path $carpeta | Out-Null
    # Sin -Append: el archivo guarda solo la ultima ejecucion y no crece.
    Start-Transcript -Path (Join-Path $carpeta 'verificar-servicio.log') -Force | Out-Null
}

$script:Fallos = 0

function Ok([string]$Texto) {
    Write-Host "  [OK]    $Texto" -ForegroundColor Green
}

function Falla([string]$Texto, [string]$Arreglo) {
    $script:Fallos++
    Write-Host "  [FALLA] $Texto" -ForegroundColor Red
    if ($Arreglo) { Write-Host "          $Arreglo" -ForegroundColor Yellow }
}

function Aviso([string]$Texto) {
    Write-Host "  [AVISO] $Texto" -ForegroundColor Yellow
}

# Un paso que no se puede revisar por un fallo anterior: no se cuenta dos veces.
function Omitido([string]$Texto) {
    Write-Host "  [--]    $Texto" -ForegroundColor DarkGray
}

function Paso([string]$Texto) {
    Write-Host $Texto -ForegroundColor Cyan
}

# Ruta de un programa de Windows; en otro sistema no hay Program Files.
function Get-ProgramPath([string]$Relativa) {
    if ($env:ProgramFiles) { return (Join-Path $env:ProgramFiles $Relativa) }
    return ''
}

# Busca el programa en el PATH y, si no esta, donde lo deja su instalador.
function Find-Tool([string]$Nombre, [string]$Instalado) {
    $cmd = Get-Command $Nombre -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) { return $cmd.Path }
    if ($Instalado -and (Test-Path $Instalado)) { return $Instalado }
    return $Nombre
}

$Docker = Find-Tool 'docker' (Get-ProgramPath 'Docker\Docker\resources\bin\docker.exe')
$Tailscale = Find-Tool 'tailscale' (Get-ProgramPath 'Tailscale\tailscale.exe')

# Ejecuta un programa sin cortar el script si falla o no existe. Con -Mostrar
# imprime cada linea mientras llega, para comandos que pueden tardar o pedir algo.
function Invoke-Tool([string]$Exe, [string[]]$Arguments, [switch]$SinErrores, [switch]$Mostrar) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ($SinErrores) {
            $out = & $Exe @Arguments 2>$null
        } elseif ($Mostrar) {
            $out = & $Exe @Arguments 2>&1 | ForEach-Object { $l = "$_"; Write-Host "    $l"; $l }
        } else {
            $out = & $Exe @Arguments 2>&1
        }
        $code = $LASTEXITCODE
    } catch {
        $out = @("$_")
        $code = -1
    } finally {
        $ErrorActionPreference = $previous
    }
    [pscustomobject]@{ Code = $code; Output = (($out | ForEach-Object { "$_" }) -join "`n").Trim() }
}

# GET con curl.exe (viene con Windows 10/11). Devuelve el codigo HTTP y el
# cuerpo; si no hubo respuesta, el codigo es 000 y el cuerpo el error de curl.
function Get-Url([string]$Url, [string[]]$Extra = @()) {
    $r = Invoke-Tool 'curl.exe' (@('-s', '-S', '--max-time', '15', '-w', '\n%{http_code}') + $Extra + @($Url))
    if ($r.Code -ne 0) { return [pscustomobject]@{ Code = '000'; Body = $r.Output } }
    $lineas = @($r.Output -split "`n")
    [pscustomobject]@{ Code = $lineas[-1]; Body = (($lineas | Select-Object -SkipLast 1) -join "`n") }
}

function Test-Up([string]$Cuerpo) {
    $Cuerpo -match '"status"\s*:\s*"UP"'
}

# Primeros caracteres de una respuesta, en una linea, para mostrarla.
function Format-Sample([string]$Cuerpo) {
    $muestra = ($Cuerpo -replace '\s+', ' ').Trim()
    if ($muestra.Length -gt 160) { $muestra = $muestra.Substring(0, 160) + '...' }
    return $muestra
}

function Wait-Until([scriptblock]$Condicion, [int]$Segundos) {
    $limite = (Get-Date).AddSeconds($Segundos)
    while ((Get-Date) -lt $limite) {
        if (& $Condicion) { return $true }
        Start-Sleep -Seconds 5
    }
    return [bool](& $Condicion)
}

function Test-Docker {
    (Invoke-Tool $Docker @('info', '--format', '{{.ServerVersion}}') -SinErrores).Code -eq 0
}

function Get-ContainerStatus {
    (Invoke-Tool $Docker @('ps', '-a', '--filter', "name=^$Contenedor$", '--format', '{{.Status}}') -SinErrores).Output
}

function Show-ContainerLogs {
    Write-Host '          Ultimas lineas del contenedor:'
    (Invoke-Tool $Docker @('logs', '--tail', '15', $Contenedor)).Output -split "`n" |
        ForEach-Object { Write-Host "            $_" }
}

function Get-TailscaleStatus {
    $r = Invoke-Tool $Tailscale @('status', '--json', '--peers=false') -SinErrores
    if ($r.Code -ne 0 -or -not $r.Output) { return $null }
    try { return ($r.Output | ConvertFrom-Json) } catch { return $null }
}

# El Funnel publica en internet un proxy hacia 127.0.0.1:<puerto>.
function Test-Funnel {
    $serve = Invoke-Tool $Tailscale @('serve', 'status', '--json') -SinErrores
    if ($serve.Code -ne 0 -or -not $serve.Output) { return $false }
    try { $cfg = $serve.Output | ConvertFrom-Json } catch { return $false }
    if (-not $cfg.Web -or -not $cfg.AllowFunnel) { return $false }

    foreach ($sitioWeb in $cfg.Web.PSObject.Properties) {
        $abierto = $cfg.AllowFunnel.PSObject.Properties | Where-Object { $_.Name -eq $sitioWeb.Name -and $_.Value }
        if (-not $abierto) { continue }
        foreach ($handler in $sitioWeb.Value.Handlers.PSObject.Properties) {
            if ("$($handler.Value.Proxy)" -match "^https?://(127\.0\.0\.1|localhost):$Puerto/?$") { return $true }
        }
    }
    return $false
}

# Direccion de un nombre segun el DNS publico de Google, consultado por HTTPS.
# Devuelve $null si no se pudo consultar y '' si el nombre no tiene direccion.
function Resolve-PublicName([string]$Nombre) {
    $r = Get-Url "https://dns.google/resolve?name=$Nombre&type=A"
    if ($r.Code -ne '200') { return $null }
    try { $json = $r.Body | ConvertFrom-Json } catch { return $null }
    $a = @($json.Answer | Where-Object { $_.type -eq 1 }) | Select-Object -First 1
    if ($a) { return "$($a.data)" }
    return ''
}

# Minutos sin uso tras los que la PC se suspende conectada a la corriente;
# 0 es nunca y $null que no se pudo saber.
function Get-SleepMinutes {
    if (-not (Get-Command 'powercfg' -ErrorAction SilentlyContinue)) { return $null }
    $r = Invoke-Tool 'powercfg' @('/query', 'SCHEME_CURRENT', 'SUB_SLEEP', 'STANDBYIDLE') -SinErrores
    if ($r.Code -ne 0) { return $null }
    # powercfg traduce los textos, no los valores: minimo, maximo, incremento,
    # con corriente y con bateria, en ese orden.
    $valores = @([regex]::Matches($r.Output, '0x([0-9a-fA-F]{8})') | ForEach-Object { [Convert]::ToInt64($_.Groups[1].Value, 16) })
    if ($valores.Count -ne 5) { return $null }
    return [int][Math]::Ceiling($valores[3] / 60)
}

Write-Host "Personal Resolver - $(Get-Date -Format 'yyyy-MM-dd HH:mm')$(if ($Reparar) { ' (reparando)' })"
Write-Host ''

if ($InstalarTarea) {
    Paso 'Tarea programada'
    $argumentos = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$PSCommandPath`" -Reparar -Registro"
    if ($Sitio) { $argumentos += " -Sitio `"$Sitio`"" }

    try {
        $accion = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $argumentos
        # Con el usuario actual explicito, registrarla no necesita ser administrador.
        $alIniciar = New-ScheduledTaskTrigger -AtLogOn -User "$env:USERDOMAIN\$env:USERNAME"
        # Da tiempo a que arranquen Docker Desktop y Tailscale.
        $alIniciar.Delay = 'PT1M'
        $cadaDia = New-ScheduledTaskTrigger -Daily -At ([datetime]::Today.AddHours(6).AddMinutes(45))
        $ajustes = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
            -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Minutes 15)

        Register-ScheduledTask -TaskName $NombreTarea -Action $accion -Trigger @($alIniciar, $cadaDia) `
            -Settings $ajustes -Force `
            -Description 'Comprueba y repara Docker, el contenedor y el Funnel de Personal Resolver.' | Out-Null

        Ok "Tarea '$NombreTarea' registrada: corre 1 minuto despues de iniciar sesion y cada dia a las 6:45."
        Write-Host "          Registro de la ultima ejecucion: $env:LOCALAPPDATA\PersonalResolver\verificar-servicio.log"
        Write-Host ("          Para quitarla: Unregister-ScheduledTask -TaskName '{0}'" -f $NombreTarea)
    } catch {
        Falla "No se pudo registrar la tarea: $($_.Exception.Message)" `
            'Abre PowerShell como administrador y vuelve a ejecutar este comando.'
    }
    Write-Host ''
}

# 1. Docker -----------------------------------------------------------------
Paso '1. Docker'
$dockerOk = Test-Docker

if (-not $dockerOk -and $Reparar) {
    $exe = Get-ProgramPath 'Docker\Docker\Docker Desktop.exe'
    # Al iniciar sesion puede estar abriendose solo, y abrirlo encima mostraria
    # su ventana: desde la tarea programada se le da un minuto antes.
    $espera = 0
    if ($Registro) { $espera = 60 }
    $abierto = Wait-Until { [bool](Get-Process -Name 'Docker Desktop' -ErrorAction SilentlyContinue) } $espera
    if (-not $abierto -and $exe -and (Test-Path $exe)) {
        Write-Host '  Abriendo Docker Desktop...'
        Start-Process $exe
    }
    Write-Host '  Esperando a Docker (hasta 3 minutos)...'
    $dockerOk = Wait-Until { Test-Docker } 180
}

if ($dockerOk) {
    Ok 'Docker esta corriendo.'
} else {
    Falla 'Docker no esta corriendo.' `
        'Abre Docker Desktop y activa Settings > General > Start Docker Desktop when you sign in to your computer.'
}

# 2. Contenedor -------------------------------------------------------------
Paso '2. Contenedor'
$estado = ''
if ($dockerOk) {
    $estado = Get-ContainerStatus

    if ($estado -notmatch '^Up' -and $Reparar) {
        Write-Host '  Levantando el contenedor (docker compose up -d)...'
        Push-Location $Repo
        try {
            Invoke-Tool $Docker @('compose', 'up', '-d') -Mostrar | Out-Null
        } finally {
            Pop-Location
        }
        $estado = Get-ContainerStatus
    }
}

if ($estado -match '^Up') {
    Ok "$Contenedor esta arriba ($estado)."
} elseif (-not $dockerOk) {
    Omitido 'Sin Docker no se puede revisar.'
} elseif ($estado) {
    Falla "$Contenedor no esta corriendo ($estado)." "En $Repo ejecuta: docker compose up -d"
    Show-ContainerLogs
} else {
    Falla "No existe el contenedor $Contenedor." "En $Repo ejecuta: docker compose up -d --build"
}

# 3. Backend local ----------------------------------------------------------
Paso '3. Backend en esta PC'
$urlLocal = "http://127.0.0.1:$Puerto/api/health"
$localOk = Test-Up (Get-Url $urlLocal).Body
if (-not $localOk -and $estado -match '^Up') {
    # Recien levantado tarda unos segundos en responder.
    Write-Host '  Esperando a que responda (hasta 90 s)...'
    $localOk = Wait-Until { Test-Up (Get-Url $urlLocal).Body } 90
}

if ($localOk) {
    Ok "Responde en $urlLocal"
} elseif ($estado -match '^Up') {
    Falla "No responde en $urlLocal" 'Revisa el arranque con: docker compose logs --tail 50 app'
    Show-ContainerLogs
} else {
    Omitido 'Sin el contenedor arriba no puede responder.'
}

# 4. Puerto ------------------------------------------------------------------
Paso "4. Puerto $Puerto"
if (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue) {
    $otros = @(Get-NetTCPConnection -LocalPort $Puerto -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalAddress -ne '127.0.0.1' -and $_.LocalAddress -ne '::1' })
    if ($otros.Count -eq 0) {
        Ok "Nadie mas escucha en el puerto $Puerto."
    } else {
        foreach ($c in $otros) {
            $proceso = (Get-Process -Id $c.OwningProcess -ErrorAction SilentlyContinue).ProcessName
            Falla "Otro proceso escucha en $($c.LocalAddress):$Puerto ($proceso, PID $($c.OwningProcess))." `
                'Detenlo: con el contenedor apagado, el Funnel publicaria ese proceso.'
        }
    }
} else {
    Aviso 'No se pudo revisar (Get-NetTCPConnection no esta disponible).'
}

# 5. Tailscale ---------------------------------------------------------------
Paso '5. Tailscale'
$ts = Get-TailscaleStatus
if ((-not $ts -or $ts.BackendState -ne 'Running') -and $Reparar) {
    # Al iniciar sesion tarda unos segundos en conectar.
    Write-Host '  Esperando a que Tailscale conecte (hasta 1 minuto)...'
    $null = Wait-Until { $s = Get-TailscaleStatus; $s -and $s.BackendState -eq 'Running' } 60
    $ts = Get-TailscaleStatus
}

$tsOk = $false
$dnsName = ''
if (-not $ts) {
    Falla 'No se pudo consultar Tailscale.' 'Comprueba que Tailscale este instalado y abierto (icono junto al reloj).'
} elseif ($ts.BackendState -eq 'NeedsLogin') {
    Falla 'Tailscale pide iniciar sesion.' 'Abre Tailscale e inicia sesion con la cuenta del tailnet.'
} elseif ($ts.BackendState -ne 'Running') {
    Falla "Tailscale no esta conectado (estado: $($ts.BackendState))." `
        'En el icono de Tailscale junto al reloj, elige Connect.'
} else {
    $tsOk = $true
    $dnsName = "$($ts.Self.DNSName)".TrimEnd('.')
    Ok "Conectado como $dnsName"

    # Sin KeyExpiry la clave no vence (Disable key expiry).
    if ($ts.Self.KeyExpiry) {
        try {
            $vence = [datetime]$ts.Self.KeyExpiry
            $dias = [int][Math]::Floor(($vence - (Get-Date)).TotalDays)
            $arreglo = 'En login.tailscale.com/admin/machines, en el menu de este equipo: Disable key expiry.'
            if ($dias -lt 30) {
                Falla "La clave de este equipo en Tailscale vence en $dias dias; ese dia el Funnel deja de responder." $arreglo
            } else {
                Aviso "La clave de este equipo en Tailscale vence el $($vence.ToString('yyyy-MM-dd')); ese dia el Funnel deja de responder."
                Write-Host "          $arreglo" -ForegroundColor Yellow
            }
        } catch {
            Aviso "No se pudo leer cuando vence la clave de Tailscale ($($ts.Self.KeyExpiry))."
        }
    }
}

# 6. Funnel ------------------------------------------------------------------
Paso '6. Funnel'
$funnelOk = $false
if ($tsOk) {
    $funnelOk = Test-Funnel
    if (-not $funnelOk -and $Reparar) {
        Write-Host "  Publicando el puerto $Puerto (tailscale funnel --bg $Puerto)..."
        Invoke-Tool $Tailscale @('funnel', '--bg', "$Puerto") -Mostrar | Out-Null
        $funnelOk = Test-Funnel
    }

    if ($funnelOk) {
        Ok "Publica http://127.0.0.1:$Puerto en https://$dnsName"
    } else {
        Falla "El Funnel no publica el puerto $Puerto." "Ejecuta: tailscale funnel --bg $Puerto"
    }
} else {
    Omitido 'Sin Tailscale conectado no se puede revisar.'
}

# 7. Funnel desde internet ----------------------------------------------------
# Desde esta PC el nombre se resuelve a la IP del tailnet y la prueba no pasaria
# por el Funnel. Se resuelve con DNS publico y se entra por esa direccion, que es
# el camino que sigue Vercel.
Paso '7. Funnel desde internet'
$funnelPublicoOk = $false
$funnelPublicoFalla = $false
if ($funnelOk -and $localOk) {
    $ip = Resolve-PublicName $dnsName
    # 100.64.0.0/10 es el rango del tailnet, no una direccion publica.
    if ($ip -match '^100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.') { $ip = '' }

    if ($null -eq $ip) {
        Aviso 'No se pudo consultar el DNS publico (dns.google); no se probo el Funnel desde internet.'
    } elseif ($ip -eq '') {
        $funnelPublicoFalla = $true
        Falla "$dnsName no tiene direccion publica: el Funnel no se ve desde internet." `
            'Si lo acabas de publicar, espera 10 minutos (lo que tarda el DNS) y vuelve a probar.'
    } else {
        $r = Get-Url "https://$dnsName/api/health" @('--resolve', "${dnsName}:443:$ip")
        if (Test-Up $r.Body) {
            $funnelPublicoOk = $true
            Ok "https://$dnsName responde desde internet."
        } else {
            $funnelPublicoFalla = $true
            Falla "https://$dnsName no responde desde internet (HTTP $($r.Code))." `
                "Vuelve a publicarlo: tailscale funnel --https=443 off y luego tailscale funnel --bg $Puerto"
            $muestra = Format-Sample $r.Body
            if ($muestra) { Write-Host "          Respuesta: $muestra" }
        }
    }
} else {
    Omitido 'Primero tienen que responder el backend y el Funnel.'
}

# 8. Cadena completa ---------------------------------------------------------
Paso '8. Sitio publico'
if (-not $Sitio) {
    Aviso 'Sin -Sitio no se prueba la cadena completa (navegador -> Vercel -> Funnel -> PC).'
} else {
    $publico = Get-Url "$Sitio/api/health"
    if (Test-Up $publico.Body) {
        Ok "$Sitio llega al backend."
    } else {
        $codigo = "HTTP $($publico.Code)"
        $backendUrl = "En Vercel, BACKEND_URL (Production) debe ser https://$dnsName; si la cambias, vuelve a desplegar."
        if ($publico.Code -eq '200' -and $publico.Body -match '^\s*<') {
            Falla "$Sitio devolvio su propia pagina en /api/health: ese despliegue no tiene la ruta al backend." `
                'En Vercel, define BACKEND_URL en Production y vuelve a desplegar.'
        } elseif (-not ($localOk -and $funnelOk) -or $funnelPublicoFalla) {
            Omitido "$Sitio no llega al backend ($codigo), por lo de arriba."
        } elseif ($funnelPublicoOk) {
            Falla "$Sitio no llega al backend ($codigo), aunque el Funnel responde desde internet." $backendUrl
        } else {
            Falla "$Sitio no llega al backend ($codigo), aunque todo en esta PC funciona." `
                "$backendUrl Si ya lo es, el Funnel no se ve desde internet."
        }
        $muestra = Format-Sample $publico.Body
        if ($muestra) { Write-Host "          Respuesta: $muestra" }
    }
}

# Lo que haria fallar el servicio otro dia ------------------------------------
Paso 'Para los proximos dias'
$minutos = Get-SleepMinutes
if ($minutos -gt 0) {
    Aviso "Conectada a la corriente, la PC se suspende tras $minutos min sin uso, y suspendida el servicio no responde."
    Write-Host '          Configuracion > Sistema > Energia: que nunca se suspenda mientras este conectada.' -ForegroundColor Yellow
} elseif ($minutos -eq 0) {
    Ok 'La PC no se suspende sola mientras esta conectada.'
}

if (Get-Command Get-ScheduledTask -ErrorAction SilentlyContinue) {
    if (Get-ScheduledTask -TaskName $NombreTarea -ErrorAction SilentlyContinue) {
        Ok 'La tarea programada repara el servicio al iniciar sesion y cada manana.'
    } else {
        Aviso 'Falta la tarea programada que repara el servicio al iniciar sesion. Instalala una vez con:'
        $sitioArg = ''
        if ($Sitio) { $sitioArg = " -Sitio $Sitio" }
        Write-Host "          powershell -ExecutionPolicy Bypass -File scripts\verificar-servicio.ps1$sitioArg -InstalarTarea" -ForegroundColor Yellow
    }
}

Write-Host ''
if ($script:Fallos -eq 0) {
    Write-Host 'Todo en orden.' -ForegroundColor Green
} else {
    Write-Host "$($script:Fallos) problema(s). Ver arriba." -ForegroundColor Red
}

if ($Registro) { Stop-Transcript | Out-Null }
exit $script:Fallos
