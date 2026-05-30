param(
    [ValidateSet("smoke", "realworld-small", "benchmarks", "all")]
    [string]$Suite = "smoke",
    [switch]$IncludeLarge
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$corpus = Join-Path $root "test/corpus"

function Save-Url {
    param(
        [string]$Url,
        [string]$OutFile
    )

    $dir = Split-Path -Parent $OutFile
    New-Item -ItemType Directory -Force -Path $dir | Out-Null

    if (Test-Path $OutFile) {
        Write-Host "exists  $OutFile"
        return
    }

    Write-Host "fetch   $Url"
    Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -MaximumRedirection 10
}

if ($Suite -in @("smoke", "all")) {
    Save-Url "https://protege.stanford.edu/ontologies/pizza/pizza.owl" (Join-Path $corpus "smoke/pizza.owl")
    Save-Url "http://purl.obolibrary.org/obo/bfo.owl" (Join-Path $corpus "smoke/bfo.owl")
}

if ($Suite -in @("realworld-small", "all")) {
    Save-Url "http://purl.obolibrary.org/obo/hp.owl" (Join-Path $corpus "realworld/hp.owl")
    Save-Url "http://purl.obolibrary.org/obo/go/go-basic.owl" (Join-Path $corpus "realworld/go-basic.owl")
    Save-Url "http://purl.obolibrary.org/obo/obi.owl" (Join-Path $corpus "realworld/obi.owl")
}

if ($Suite -in @("benchmarks", "all")) {
    Save-Url "https://raw.githubusercontent.com/kracr/owl2bench/master/UNIV-BENCH-OWL2DL.owl" (Join-Path $corpus "benchmarks/owl2bench/UNIV-BENCH-OWL2DL.owl")
    Save-Url "https://raw.githubusercontent.com/kracr/owl2bench/master/UNIV-BENCH-OWL2EL.owl" (Join-Path $corpus "benchmarks/owl2bench/UNIV-BENCH-OWL2EL.owl")
}

if ($IncludeLarge) {
    Save-Url "http://purl.obolibrary.org/obo/uberon.owl" (Join-Path $corpus "large/uberon.owl")
    Save-Url "http://purl.obolibrary.org/obo/mondo.owl" (Join-Path $corpus "large/mondo.owl")
    Save-Url "https://zenodo.org/records/18578/files/ore2015_sample.zip?download=1" (Join-Path $corpus "large/ore/ore2015_sample.zip")
} else {
    Write-Host "skip    large corpora; pass -IncludeLarge to download Uberon, Mondo, and ORE"
}
