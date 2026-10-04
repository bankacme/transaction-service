<#
.SYNOPSIS
  Syncs this service's OpenAPI contract from bank-docs (the source of truth)
  into src/main/resources/openapi/, where the openapi-generator-maven-plugin
  reads it from.

.PARAMETER ServiceName
  Folder name under bank-docs\contracts\, e.g. "customer-service".

.PARAMETER DocsRepo
  Path to the bank-docs checkout. Defaults to a sibling folder
  (..\bank-docs), which matches the layout under C:\dev\bankacme\.

.EXAMPLE
  .\copy-contracts.ps1 -ServiceName customer-service
#>
param(
    [Parameter(Mandatory = $true)][string]$ServiceName,
    [string]$DocsRepo = "..\bank-docs"
)

$ErrorActionPreference = "Stop"

$srcService = Join-Path $DocsRepo "contracts\$ServiceName\openapi.yaml"
$srcCommon  = Join-Path $DocsRepo "contracts\common\common-schemas.yaml"

if (-not (Test-Path $srcService)) {
    throw "Not found: $srcService (check -ServiceName or -DocsRepo)"
}
if (-not (Test-Path $srcCommon)) {
    throw "Not found: $srcCommon"
}

$destService = "src\main\resources\openapi\service"
$destCommon  = "src\main\resources\openapi\common"

New-Item -ItemType Directory -Force -Path $destService | Out-Null
New-Item -ItemType Directory -Force -Path $destCommon  | Out-Null

Copy-Item $srcService (Join-Path $destService "openapi.yaml") -Force
Copy-Item $srcCommon  (Join-Path $destCommon  "common-schemas.yaml") -Force

Write-Host "Copied $ServiceName/openapi.yaml -> $destService"
Write-Host "Copied common/common-schemas.yaml -> $destCommon"
Write-Host "Run '.\mvnw generate-sources' (or 'verify') to regenerate the API interfaces and DTOs."
