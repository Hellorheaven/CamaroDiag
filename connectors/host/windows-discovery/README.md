# Découverte USB native Windows

Uniquement le prototype ESP32-S3 binaire isolé. Aucun J2534, CAN réel ni flash implémenté par cet outil. Après reset du prototype, identifier le port COM dans Windows et lancer `dotnet run --project host/windows-discovery -- --port COM5` en remplaçant COM5. Les contrôles identité textuelle, statut, CRC et ordre n'authentifient pas cryptographiquement le périphérique. Ne pas connecter véhicule ou puce. `--self-test` effectue exclusivement des échanges simulés. .NET 8 requis. Le pilote Pass-Thru reste un lot distinct.

## Native CU/1 read campaign (development only)

`WindowsDiscovery --j1979-usb --port COM5 --key-file <32-byte-provisioned-key> --tx <hex> --rx <hex> --budget-ms 60000 --vehicle <label> --ecu <label> --connector <profile> --capture <new.cj> --output <new.json>` (add `--extended` only for a documented 29-bit pair). Requires interactive approval and qualified hardware/firmware. No discovery fallback, no mutating commands. The JSON is a frame-level report, not full semantic or physical verification; inspect the raw capture independently with the Python CJ/1 decoder.

Recette distincte en lecture seule, port COM explicite, clé provisionnée hors session, fichiers exclusifs. Pas de support sur le firmware de découverte.
