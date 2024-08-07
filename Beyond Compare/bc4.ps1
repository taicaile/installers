
Remove-Item "$env:appdata\Scooter Software\Beyond Compare 4\BCState.xml" -Force -Confirm:$false -ErrorAction SilentlyContinue
Remove-Item "$env:appdata\Scooter Software\Beyond Compare 4\BCState.xml.bak" -Force -Confirm:$false -ErrorAction SilentlyContinue
reg delete "HKCU\Software\Scooter Software\Beyond Compare 4" /v "CacheID" /f
