$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$dest = "backup-$timestamp"
New-Item -ItemType Directory -Path $dest | Out-Null
if (Test-Path "chatapp.db") {
    Copy-Item "chatapp.db" -Destination $dest
}
if (Test-Path "uploads") {
    Copy-Item "uploads" -Destination "$dest\uploads" -Recurse
}
Write-Host "Backup created: $dest"
