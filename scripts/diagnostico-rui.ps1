<#
.SYNOPSIS
  Diagnostico de la consulta RUI contra Ventanilla Social.

.DESCRIPTION
  Revisa el HTML y el JavaScript del portal para ver como hace hoy la consulta
  (endpoint, parametros, tipo de documento, captcha, nonce) y la repite desde
  esta maquina (IP colombiana) como el navegador: GET a la landing para abrir
  sesion y POST a /Home/ObtenerDatosRUI con la cabecera X-Rui-Nonce. Muestra lo
  que responde la fuente, que el backend descarta cuando ve ok:false.

  No imprime datos personales: de las respuestas JSON solo muestra los nombres
  de los campos y el tamano de los textos, salvo campos de estado como ok o
  mensaje.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\diagnostico-rui.ps1 -Cedula 1234567890
#>
param(
    [Parameter(Mandatory = $true)][string]$Cedula,
    [int]$Sesiones = 4,
    [string]$BaseUrl = 'https://ventanillasocial.dnp.gov.co'
)

$ErrorActionPreference = 'Stop'

if ($Cedula -notmatch '^\d{6,12}$') {
    throw 'La cedula debe tener entre 6 y 12 digitos, sin puntos ni espacios.'
}

$UserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36'
$AcceptHtml = 'Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8'
$BaseUri = New-Object Uri("$BaseUrl/")
$Tmp = Join-Path ([IO.Path]::GetTempPath()) ('diagnostico-rui-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $Tmp | Out-Null

# Campos cuyo valor se puede mostrar: describen el estado, no a la persona.
$SafeKeys = '^(ok|success|exito|estado|status|codigo|code|error|errores|mensaje|message|msg|descripcion)$'

function Invoke-Curl([string]$Jar, [string[]]$Arguments) {
    # curl.exe viene con Windows 10/11. Se usa en vez de Invoke-WebRequest
    # para que el comportamiento sea el mismo en PowerShell 5.1 y 7.
    $bodyFile = Join-Path $Tmp 'body.txt'
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = & curl.exe -s -S -L --max-time 20 -A $UserAgent -c $Jar -b $Jar -o $bodyFile -w '%{http_code}' @Arguments 2>&1
    } finally {
        $ErrorActionPreference = $previous
    }
    $body = ''
    if (Test-Path $bodyFile) {
        $body = [IO.File]::ReadAllText($bodyFile, [Text.Encoding]::UTF8)
        Remove-Item $bodyFile
    }
    [pscustomobject]@{ Status = (($out | ForEach-Object { "$_" }) -join ' '); Body = $body }
}

function Resolve-Url([string]$Href) {
    try { return New-Object Uri($BaseUri, ($Href -replace '&amp;', '&')) } catch { return $null }
}

function Get-JarCookies([string]$Jar) {
    if (-not (Test-Path $Jar)) { return }
    foreach ($line in Get-Content $Jar) {
        $l = $line -replace '^#HttpOnly_', ''
        if ($l.StartsWith('#') -or [string]::IsNullOrWhiteSpace($l)) { continue }
        $f = $l -split "`t"
        if ($f.Count -ge 7) { [pscustomobject]@{ Name = $f[5]; Value = $f[6] } }
    }
}

function Show-Context([string]$Text, [string]$Pattern, [int]$Max = 3, [int]$Radius = 350) {
    $shown = 0
    foreach ($m in [regex]::Matches($Text, [regex]::Escape($Pattern), 'IgnoreCase')) {
        if ($shown -ge $Max) { break }
        $start = [Math]::Max(0, $m.Index - $Radius)
        $length = [Math]::Min($Text.Length - $start, 2 * $Radius + $Pattern.Length)
        Write-Host ('      ...' + ($Text.Substring($start, $length) -replace '\s+', ' ') + '...') -ForegroundColor DarkGray
        $shown++
    }
}

# El portal envia la consulta con la cabecera X-Rui-Nonce = _ruiPubNonce. Se
# prueban las formas en que esa variable podria llegar a la pagina.
function Get-Nonce([string]$Html, [string]$Jar) {
    $m = [regex]::Match($Html, '_ruiPubNonce\s*=\s*["'']([^"'']+)["'']')
    if ($m.Success) { return [pscustomobject]@{ Source = 'variable JS'; Value = $m.Groups[1].Value } }

    foreach ($tag in [regex]::Matches($Html, '<(?:input|meta)\b[^>]*>', 'IgnoreCase')) {
        if ($tag.Value -notmatch 'nonce') { continue }
        $v = [regex]::Match($tag.Value, '\b(?:value|content)\s*=\s*["'']([^"'']+)["'']', 'IgnoreCase')
        if ($v.Success) { return [pscustomobject]@{ Source = 'input/meta'; Value = $v.Groups[1].Value } }
    }

    $csrf = (Get-JarCookies $Jar | Where-Object { $_.Name -eq '__CsrfToken' } | Select-Object -First 1).Value
    if ($csrf) { return [pscustomobject]@{ Source = 'cookie __CsrfToken'; Value = $csrf } }

    return [pscustomobject]@{ Source = 'no encontrado'; Value = $null }
}

function Invoke-RuiPost([string]$Jar, [string]$Nonce) {
    # Tal como lo hace el navegador: XMLHttpRequest sin X-Requested-With y con
    # la cabecera X-Rui-Nonce.
    $headers = @(
        '-H', 'Accept: */*',
        '-H', 'Content-Type: application/x-www-form-urlencoded',
        '-H', "Origin: $BaseUrl",
        '-H', "Referer: $BaseUrl/")
    if ($Nonce) { $headers += @('-H', "X-Rui-Nonce: $Nonce") }
    Invoke-Curl $Jar ($headers + @('--data', "pNumDoc=$Cedula&pTipDoc=3", "$BaseUrl/Home/ObtenerDatosRUI"))
}

function Describe-Json($Node, [int]$Depth = 0) {
    if ($null -eq $Node) { return 'null' }
    if ($Node -is [bool] -or $Node -is [int] -or $Node -is [long] -or $Node -is [double] -or $Node -is [decimal]) { return "$Node" }
    if ($Node -is [string]) { return "<texto, $($Node.Length) car.>" }
    if ($Node -is [System.Array]) { return "[lista de $($Node.Count)]" }
    if ($Node -is [System.Management.Automation.PSCustomObject]) {
        $parts = foreach ($p in $Node.PSObject.Properties) {
            if ($p.Name -match $SafeKeys -and $p.Value -is [string]) { "$($p.Name)='$($p.Value)'" }
            elseif ($p.Value -is [System.Management.Automation.PSCustomObject] -and $Depth -lt 2) {
                "$($p.Name)={" + (Describe-Json $p.Value ($Depth + 1)) + '}'
            }
            else { "$($p.Name)=" + (Describe-Json $p.Value ($Depth + 1)) }
        }
        return ($parts -join ', ')
    }
    return "<$($Node.GetType().Name)>"
}

function Format-RuiBody([string]$Body) {
    if ([string]::IsNullOrWhiteSpace($Body)) { return 'cuerpo vacio' }
    $json = $null
    try { $json = $Body | ConvertFrom-Json } catch { }
    if ($null -eq $json) {
        $plain = (($Body -replace '<[^>]+>', ' ') -replace '\s+', ' ').Trim()
        return 'NO es JSON: ' + $plain.Substring(0, [Math]::Min(300, $plain.Length))
    }
    if ($json -is [System.Management.Automation.PSCustomObject] -and $json.ok -eq $true) {
        return 'ok=true, RUI devolvio datos. Campos: ' + (($json.PSObject.Properties | ForEach-Object { $_.Name }) -join ', ')
    }
    return 'JSON: ' + (Describe-Json $json)
}

try {
    # --- 1. Como hace hoy la consulta el propio portal ----------------------
    Write-Host "`n== 1. Portal: como hace hoy la consulta" -ForegroundColor Cyan

    $jar = Join-Path $Tmp 'portal.jar'
    $landing = Invoke-Curl $jar @('-H', $AcceptHtml, $BaseUri.AbsoluteUri)
    Write-Host "GET / -> HTTP $($landing.Status), $($landing.Body.Length) caracteres"
    Write-Host ('Cookies: ' + ((Get-JarCookies $jar | ForEach-Object { $_.Name }) -join ', '))

    # Paginas a revisar: la landing y las del mismo sitio que mencionen RUI.
    $pages = [ordered]@{ $BaseUri.AbsoluteUri = $landing.Body }
    foreach ($m in [regex]::Matches($landing.Body, 'href\s*=\s*["'']([^"'']*rui[^"'']*)["'']', 'IgnoreCase')) {
        if ($pages.Count -ge 6) { break }
        $uri = Resolve-Url $m.Groups[1].Value
        if ($null -eq $uri -or $uri.Host -ne $BaseUri.Host -or $pages.Contains($uri.AbsoluteUri)) { continue }
        $pages[$uri.AbsoluteUri] = (Invoke-Curl $jar @('-H', $AcceptHtml, $uri.AbsoluteUri)).Body
    }

    $patterns = 'ObtenerDatosRUI', 'pNumDoc', 'pTipDoc', 'RequestVerificationToken', 'captcha', 'turnstile'
    $scripts = [ordered]@{}

    foreach ($url in $pages.Keys) {
        $html = $pages[$url]
        Write-Host "`nPagina $url ($($html.Length) car.)"
        foreach ($p in $patterns) {
            $n = [regex]::Matches($html, [regex]::Escape($p), 'IgnoreCase').Count
            if ($n -gt 0) { Write-Host "  contiene '$p': $n" }
        }
        Show-Context $html 'ObtenerDatosRUI'

        foreach ($m in [regex]::Matches($html, '\.send\([^;]*pNumDoc[^;]*\)')) {
            Write-Host "  envio de la consulta: $($m.Value)"
        }
        foreach ($p in '_ruiPubNonce', 'X-Rui-Nonce', 'CsrfToken') {
            $n = [regex]::Matches($html, [regex]::Escape($p), 'IgnoreCase').Count
            if ($n -eq 0) { continue }
            Write-Host "  '$p' aparece $n veces:"
            Show-Context $html $p 6 200
        }

        foreach ($m in [regex]::Matches($html, '<option[^>]*value\s*=\s*["'']?([^"''>\s]+)["'']?[^>]*>([^<]*)</option>', 'IgnoreCase')) {
            $label = [System.Net.WebUtility]::HtmlDecode($m.Groups[2].Value).Trim()
            if ($label -match 'c.dula|tarjeta|extranjer|pasaporte|registro civil|permiso') {
                Write-Host ("  opcion tipo documento: value={0} -> {1}" -f $m.Groups[1].Value, $label)
            }
        }

        foreach ($m in [regex]::Matches($html, '<script[^>]*\ssrc\s*=\s*["'']([^"'']+)["'']', 'IgnoreCase')) {
            $uri = Resolve-Url $m.Groups[1].Value
            if ($null -ne $uri -and $uri.Host -eq $BaseUri.Host) { $scripts[$uri.AbsoluteUri] = $true }
        }
    }

    Write-Host "`nScripts propios del portal: $($scripts.Count)"
    $endpoints = @{}
    foreach ($url in @($scripts.Keys)) {
        $js = (Invoke-Curl $jar @($url)).Body
        foreach ($m in [regex]::Matches($js, '["''](/?Home/[A-Za-z_]+)["'']')) { $endpoints[$m.Groups[1].Value] = $true }
        $hits = foreach ($p in $patterns) {
            $n = [regex]::Matches($js, [regex]::Escape($p), 'IgnoreCase').Count
            if ($n -gt 0) { "$p=$n" }
        }
        if ($hits) {
            Write-Host "  $url"
            Write-Host ('    contiene: ' + ($hits -join ', '))
            Show-Context $js 'ObtenerDatosRUI'
        }
    }
    foreach ($m in [regex]::Matches((@($pages.Values) -join ' '),'["''](/?Home/[A-Za-z_]+)["'']')) { $endpoints[$m.Groups[1].Value] = $true }
    Write-Host ('Endpoints /Home/* referenciados: ' + ((@($endpoints.Keys) | Sort-Object) -join ', '))

    # --- 2. La consulta como la hace el navegador, con X-Rui-Nonce ----------
    Write-Host "`n== 2. Consulta con X-Rui-Nonce, como el navegador ($Sesiones sesiones nuevas)" -ForegroundColor Cyan

    $found = 0
    for ($i = 1; $i -le $Sesiones; $i++) {
        $jar = Join-Path $Tmp "sesion$i.jar"
        $get = Invoke-Curl $jar @('-H', $AcceptHtml, $BaseUri.AbsoluteUri)
        $kemp = (Get-JarCookies $jar | Where-Object { $_.Name -eq 'KEMP_STICKY' } | Select-Object -First 1).Value
        $nonce = Get-Nonce $get.Body $jar
        $preview = if ($nonce.Value) { "$($nonce.Value.Length) car., empieza por '$($nonce.Value.Substring(0, [Math]::Min(6, $nonce.Value.Length)))'" } else { '-' }

        Write-Host ("[{0}] GET / HTTP {1} | KEMP_STICKY={2}" -f $i, $get.Status, $kemp)
        Write-Host ("    nonce: {0} ({1})" -f $nonce.Source, $preview)

        $post = Invoke-RuiPost $jar $nonce.Value
        $summary = Format-RuiBody $post.Body
        if ($summary.StartsWith('ok=true')) { $found++ }
        Write-Host "    POST con nonce -> HTTP $($post.Status) | $summary"

        # Solo en la primera sesion: si el nonce sirve una sola vez, el backend
        # no puede reutilizar la sesion sin pedir uno nuevo.
        if ($i -eq 1) {
            $again = Invoke-RuiPost $jar $nonce.Value
            Write-Host "    POST repitiendo el mismo nonce -> HTTP $($again.Status) | $(Format-RuiBody $again.Body)"
        }
    }

    Write-Host "`n== Resumen: $found de $Sesiones sesiones devolvieron datos con X-Rui-Nonce" -ForegroundColor Cyan
} finally {
    Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
}
