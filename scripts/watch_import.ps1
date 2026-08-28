<#
.SYNOPSIS
    Chronometre un import de tickets et en deduit le debit d'insertion (S7-J5).

.DESCRIPTION
    Interroge la base a intervalle regulier et mesure :
      - le temps ecoule depuis la PREMIERE ligne inseree, et non depuis le lancement du script.
        C'est ce qui compte : entre le clic sur Confirmer et la premiere insertion, il y a le
        transfert du fichier, la detection de format et le parsing de l'en-tete. Les inclure
        melangerait le debit du reseau a celui de la base.
      - le debit instantane et le debit moyen, en lignes par seconde. Surveiller l'instantane :
        s'il s'effondre alors que la moyenne tient encore, c'est le signe d'une degradation
        progressive (index qui coutent quand la table grossit, deduplication qui ralentit).

    Le script s'arrete de lui-meme quand la cible est atteinte, ou quand le compteur cesse de
    bouger pendant plusieurs releves consecutifs. Un import interrompu ne doit pas laisser un
    terminal a tourner indefiniment.

.EXAMPLE
    .\scripts\watch_import.ps1
    .\scripts\watch_import.ps1 -Target 50000 -Prefix PERF -WithQueue

.NOTES
    A lancer AVANT de cliquer sur Confirmer dans l'interface.

    CE FICHIER EST EN ASCII STRICT, ET DOIT LE RESTER.
    PowerShell 5 lit un .ps1 sans BOM comme de l'ANSI (CP1252), pas comme de l'UTF-8. Un tiret
    cadratin U+2014 y devient trois caracteres, dont U+201D -- que PowerShell accepte comme
    delimiteur de chaine. La parite des guillemets est alors detruite et le fichier entier devient
    incomprehensible, avec des erreurs de syntaxe pointant a l'interieur de chaines valides.
#>
param(
    [int]$Target = 50000,
    [string]$Prefix = 'PERF',
    [int]$IntervalSeconds = 2,
    # Nombre de releves sans progression avant d'abandonner. 15 x 2 s = 30 s : assez long pour
    # absorber une pause du ramasse-miettes ou un lot un peu lent, assez court pour ne pas laisser
    # le terminal tourner apres un import echoue.
    [int]$StallPolls = 15,
    # La profondeur de file RabbitMQ est instructive (elle montre la publication evenement par
    # evenement) mais rabbitmqctl est lent : desactivee par defaut.
    [switch]$WithQueue
)

function Get-Count {
    param([string]$Filter)
    $sql = "SELECT COUNT(*) FROM tickets WHERE external_ref LIKE '$Filter-%';"
    $raw = docker compose exec -T postgres psql -U supportiq -d supportiq -t -A -c $sql 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $raw) { return -1 }
    return [int]($raw.Trim())
}

function Get-QueueDepth {
    $raw = docker compose exec -T rabbitmq rabbitmqctl list_queues name messages --quiet 2>$null
    if (-not $raw) { return -1 }
    $line = $raw | Where-Object { $_ -match '^tickets\.analyze\s' }
    if (-not $line) { return -1 }
    return [int](($line -split '\s+')[1])
}

Write-Host "Attente de la premiere ligne '$Prefix-'... (Ctrl+C pour arreter)" -ForegroundColor Cyan

$baseline = Get-Count -Filter $Prefix
if ($baseline -lt 0) {
    Write-Host "Base injoignable. Le conteneur postgres tourne-t-il ?" -ForegroundColor Red
    exit 1
}
if ($baseline -gt 0) {
    Write-Host "$baseline lignes '$Prefix-' existent deja : elles sont soustraites du decompte." -ForegroundColor Yellow
}

$start = $null
$previous = $baseline
$previousTime = $null
$stalled = 0

while ($true) {
    Start-Sleep -Seconds $IntervalSeconds
    $now = Get-Date
    $count = Get-Count -Filter $Prefix
    if ($count -lt 0) { continue }

    $inserted = $count - $baseline
    if ($inserted -le 0) { continue }

    if (-not $start) {
        $start = $now
        Write-Host "Premiere ligne detectee, chronometre demarre." -ForegroundColor Green
    }

    $elapsed = ($now - $start).TotalSeconds
    $delta = $count - $previous

    if ($delta -eq 0) { $stalled++ } else { $stalled = 0 }

    $instant = 0
    if ($previousTime) { $instant = $delta / ($now - $previousTime).TotalSeconds }
    $average = 0
    if ($elapsed -gt 0) { $average = $inserted / $elapsed }
    $percent = 0
    if ($Target -gt 0) { $percent = [math]::Round(100 * $inserted / $Target, 1) }

    $line = ('{0,7} / {1}  ({2}%)   {3}s   instantane {4}/s   moyen {5}/s' -f
        $inserted,
        $Target,
        $percent,
        [math]::Round($elapsed, 0),
        [math]::Round($instant, 0),
        [math]::Round($average, 0))

    if ($WithQueue) {
        $depth = Get-QueueDepth
        if ($depth -ge 0) { $line = $line + ('   file ' + $depth) }
    }

    Write-Host $line

    $previous = $count
    $previousTime = $now

    if ($inserted -ge $Target) {
        Write-Host ''
        if ($delta -ge $Target) {
            # Le compteur est passe de zero a la cible en un seul releve. Ce n'est pas un import
            # instantane : l'import Spring s'execute dans UNE SEULE transaction, et rien n'est
            # visible depuis une autre connexion avant le COMMIT final. Les lots de 500 sont des
            # lots d'insertion, pas des commits intermediaires.
            #
            # L'outil dit ce qu'il ne peut pas mesurer plutot que d'afficher un 0 qu'on prendrait
            # pour un resultat.
            Write-Host 'IMPORT ATOMIQUE : les 50 000 lignes sont apparues au COMMIT.' -ForegroundColor Yellow
            Write-Host 'La duree ne se mesure pas ainsi. Utiliser les journaux du backend :' -ForegroundColor Yellow
            Write-Host '  docker compose logs backend --since 30m | Select-String "import"'
            Write-Host ''
            Write-Host 'A noter dans le rapport : un import est tout-ou-rien. Un echec a la ligne'
            Write-Host '49 999 annule les 49 998 precedentes.'
            break
        }
        Write-Host ("TERMINE : $inserted lignes en " + [math]::Round($elapsed, 1) + 's') -ForegroundColor Green
        Write-Host ("Debit moyen : " + [math]::Round($average, 0) + ' lignes/s') -ForegroundColor Green
        Write-Host ''
        Write-Host 'A reporter dans eval/results/perf_s7j5.md, section Contexte de mesure.'
        break
    }

    if ($stalled -ge $StallPolls) {
        Write-Host ''
        Write-Host ("Compteur fige a $inserted lignes depuis " + ($StallPolls * $IntervalSeconds) + 's.') -ForegroundColor Yellow
        Write-Host ('Import termine avant la cible, ou interrompu. Duree mesuree : ' + [math]::Round($elapsed, 1) + 's')
        break
    }
}
