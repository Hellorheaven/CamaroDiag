# ISO-TP sur CAN-FD — audit logiciel et cahier de qualification CamaroDiag

Le pont Windows CamaroDiag met en œuvre l'envoi et la réception ISO-TP sur CAN-FD via un adaptateur SAE J2534-2 proposant **réellement** FD_CAN. Il prend en charge trames SF échappées pour DLC > 8, FF 12/32 bits, CF/FC, Block Size, STmin et adressages ISO-TP normaux/étendus/mixtes. Le codec et les tests hors matériel ne garantissent ni compatibilité de la DLL fabricant, ni timing de bus, ni programmabilité d'un ECU.

## Audit hors ligne des captures CAN-FD

La commande suivante **n'accède pas à un calculateur** :

```powershell
CamaroDiag.J2534Bridge.exe --isotp-fd-audit-trace --capture-file canfd-banc.csv --tx-id 7E0 --rx-id 7E8 --sender tx --extended false --n-bs-ms 1000 --n-cr-ms 1000 --max-wait-frames 4
```

Le CSV doit avoir l'en-tête exact `timestamp_us,direction,can_id,payload_hex`, avec chaque ligne horodatée **sur le bus** (microsecondes), une direction `TX` du testeur ou `RX` de l'ECU, l'identifiant CAN en hexadécimal et des octets de données en hexadécimal. Les longueurs FD acceptées sont 1–8, 12, 16, 20, 24, 32, 48 ou 64 octets. `--sender rx` vérifie au contraire la première PDU envoyée par l'ECU. Le bus 29 bits est sélectionné par `--extended true`; les préfixes logiques ISO-TP, lorsqu'ils sont utilisés, par `--tx-address` et `--rx-address`.

Le contrôle traite SF échappée, FF 12/32 bits, CF, FC/CTS/WAIT/OVFLW, séquences, Block Size, STmin, N_Bs/N_Cr et WFTmax. Il rejette une longueur FD non canonique, une SF non échappée sur DLC > 8, un CF non-terminal plus court que le RX_DL de la FF ou une FF déclarant une taille compatible avec SF.

## Interprétation et limites

Les codes de retour sont 0 (PDU observée cohérente), 7 (erreur détectée), 8 (capture incomplète / transfert abandonné), 6 (format/arguments invalides). Les horodatages **hôte** issus de `--trace-file` / `TX_QUEUED` ne sont pas des mesures de bus et sont rejetés. **Un CSV fourni par un opérateur n'atteste pas à lui seul l'authenticité de sa source.** L'audit ne prouve pas le bit-rate switching (BRS), les débits effectifs, les tolérances physiques STmin, la compatibilité ECU, la capacité flash ni un rollback.

Sur la Release, le propriétaire pourra renseigner les essais du Lot 8 (#48) : mode FD_CAN et DLL/VCI/firmware, ID CAN 11/29 bits, STmin 0/5ms/100µs, DLC 8/12/16/20/24/32/48/64, BRS activé/désactivé, capture indépendante, pertes, FC WAIT/OVFLW, annulation, réception/écriture autorisées et comportement ECU. Les essais physiques ne conditionnent pas la clôture de la PR #95 ; **ne jamais annoncer qualification matérielle sans preuves**.
