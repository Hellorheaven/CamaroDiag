# CamaroDiagConnectors

Sous-projet logiciel de **[CamaroDiag](https://github.com/Hellorheaven/CamaroDiag)** : interface diagnostic Chevrolet Camaro Gen 5/Gen 6, passerelle Windows SAE J2534 et programmateur EEPROM/flash sur banc.

> **Prototype initial non validé sur matériel.** Aucun pilotage CAN, aucune lecture/écriture EEPROM, aucun pilote J2534 ni programmation ECU réelle n'est implémenté. Ne connecter ce firmware ni à un véhicule, ni à une puce ou à une alimentation automobile.

## Objectifs
- **Véhicule** : HS CAN (DLC 6/14), GM Single-Wire CAN/SAE J2411 (DLC 1) et autres réseaux *après vérification par modèle et année*.
- **Windows** : DLL Pass-Thru J2534 à implémenter ; USB câblé pour les opérations critiques.
- **Android** : protocole USB natif CamaroDiag ; Wi-Fi/BLE envisagés uniquement pour les opérations non destructives.
- **Banc** : EEPROM externes I²C 24xx, SPI 25xx, Microwire 93xx, NOR SPI et adaptateurs spécifiques, lecture double, sauvegarde, comparaison, écriture ciblée et relecture *après validation*.
- **MCU internes** : méthodes BOOT/BDM/Nexus/JTAG distinctes des EEPROM externes ; ne pas supposer de compatibilité à partir du nom d'un ECU.

## État actuel
Les variantes de découverte ASCII et binaire du firmware ESP32-S3 fournissent uniquement `PING`, `CAPS` et `STATUS`. L'export matériel de checkpoints CDJ/USB/1 est différé au chantier Connectors C5 ; l'inspection hors ligne reste disponible. Toutes les fonctions véhicule et les commandes d’écriture restent désactivées. Le prototype STM32G474 démarre avec signaux cibles inactifs et diagnostic ST-LINK. Le client Python propose découverte USB, sauvegardes et planification de patches hors ligne, transport TLS 1.3 de référence sur socket et journal de programmation de simulation. Le client Android USB Host CDC est un prototype de découverte autonome, à qualifier sur carte réelle. Aucun accès physique ECU, SPI interprocesseur ou CAN réel n'est activé.

| Dossier | Contenu |
|---|---|
| `firmware/esp32s3` | Prototype série USB en lecture seule |
| `host/python` | Client de découverte, simulateurs et tests |
| `host/windows-j2534` | Spécification du pilote Windows futur |
| `host/android` | Client natif de découverte USB CDC, non intégré à l'application |
| `firmware/stm32g4` | Prototype Nucleo G474RE isolé |
| `firmware/shared` | Contrat interprocesseur et réassemblage ISO-TP passif, hors matériel |
| `hardware` | Contraintes électriques / transceivers / connectique |
| `docs` | Architecture, protocole, risques et validation |

## Tests logiciels
```shell
cd host/python
python -m unittest discover -s tests -v
```
Voir [découverte USB binaire](docs/user/usb-binary-discovery.md) et [Android](host/android/README.md). Pour tenter la découverte USB sur une carte ESP32-S3 **isolée du véhicule** : flasher `firmware/esp32s3` avec PlatformIO (compilation et qualification physique distinctes), installer `pyserial`, puis `python -m camarodiagconnectors --port COM5 ping`.

## Gouvernance
Code, tests et suivi logiciel centralisés dans CamaroDiag (#80, PR #95). Matériel, schémas, PCB et documents de validation également centralisés ici. Exécuter les commandes depuis `connectors/`.

Aucune licence open source choisie pour le moment. Ne pas publier de dumps, clés ou images firmware GM ni des données véhicules privées.


## Outil de sauvegarde et de patch hors ligne

Les commandes suivantes travaillent **sur des fichiers déjà présents sur le PC**, jamais directement sur une EEPROM ou un ECU :

```bash
cd host/python
python -m camarodiagconnectors.offline_cli backup --target 24LC256 --capacity 32768 --input original.bin --output-prefix sauvegarde
python -m camarodiagconnectors.offline_cli verify --prefix sauvegarde
python -m camarodiagconnectors.offline_cli verify-doip --archive sauvegarde-doip.zip --profile profil-doip.json
python -m camarodiagconnectors.offline_cli plan --target 24LC256 --technology i2c_eeprom --page-size 64 --input original.bin --patch 0x10:01 --proposed-output simulation.bin
```

`backup` crée `sauvegarde.bin` et `sauvegarde.json` en exclusivité (jamais d'écrasement). `verify` contrôle intégrité et couverture déclarée. `plan` indique les pages/secteurs qui changeraient, refuse les régions protégées et préserve tous les octets voisins de l'image. **Ni le fichier candidat ni le manifeste ne constituent une preuve de compatibilité matérielle ou une autorisation d'écriture.**

## Documentation de raccordement

[Guide générique des alimentations, broches et modes de raccordement](docs/user/wiring/README.md) · [Catalogue des fiches par calculateur](docs/user/wiring/ecu/README.md).

USB 5 V prioritaire pour la logique du boîtier ; alimentation externe stabilisée réservée à la cible BENCH, via protections. Une source auxiliaire maintenue peut être requise pour une opération critique. Les fiches documentaires ne constituent pas une qualification physique. Respecter les variantes ECU et les spécifications constructeur.

## Limites de sécurité

La découverte ASCII/binaire ne prouve pas l'identité de la carte. Le TLS mutuel de simulation ne s'applique pas encore au transport USB. Le journal logiciel ne garantit pas la restauration d'un calculateur et ne doit pas servir à déclencher une écriture. Aucun mode de programmation ne peut être activé sans qualification par cible, alimentation et route (#8, #9, #10).

La commande `verify-doip` vérifie hors connexion les archives de zones ECU DoIP produites par Windows ou Android (jusqu’à 16 Mio par zone), avec leur profil original. Elle ne prouve pas la provenance matérielle et n’autorise aucune écriture. / `verify-doip` checks scoped DoIP backups against their original profile, offline (up to 16 MiB per region). It does not establish hardware provenance or authorize programming.

## Diagnostic DoIP réseau du PC / PC network DoIP diagnostics

Le client hôte communique directement avec une passerelle ISO 13400 sur le réseau du PC. Il ne passe pas par le firmware USB ESP32 et ne lui attribue aucune nouvelle capacité. TLS avec validation de certificat et de l'adresse IP par défaut ; `--tcp` uniquement pour une passerelle autorisant explicitement le transport non chiffré. IP numérique et adresses logiques ECU/testeur à fournir depuis le profil réel.

```bash
cd host/python
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 discover
python -m camarodiagconnectors.doip_cli --fr --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 did --did F190
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 --trace monitor --did 1235 --samples 5
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --trace-file doip-discovery.jsonl discover
```

`--trace-file` crée un nouveau journal JSONL, conserve les PDU UDP/TCP/TLS observés et les traces partielles même en cas d'erreur. Il peut être utilisé seul ou avec `--trace` (affichage terminal). Un fichier existant n'est jamais écrasé. Les journaux peuvent contenir un VIN et des données ECU : à conserver localement et à ne partager qu'après vérification. / `--trace-file` creates a new JSONL log of observed UDP/TCP/TLS PDUs, retaining partial traces on failure; combine with `--trace` for terminal output. Existing files are never overwritten. Logs may contain VIN and ECU data; keep private and review before sharing.

Exemples fictifs, à remplacer par les données de la passerelle et du calculateur réellement sélectionnés. Services disponibles : découverte, statut, alimentation, VIN, DID, comptage/liste DTC, instantanés/données étendues et monitoring. Les données constructeur sans définition restent brutes ; aucun flash ni qualification physique. / The host connects directly over IP, not through the USB prototype. Use real verified logical addresses; no physical qualification or programming is implied.

### DTC et monitoring multi-DID / DTCs and multi-DID monitoring

Les commandes `dtcs`, `obd-dtcs`, `count` et `obd-count` indiquent `effective_mask` (masque demandé intersecté avec le masque disponible) et `filter_available`. Un filtre indisponible ne permet pas de conclure à l'absence de défaut. Les DTC listés incluent les identifiants normalisés `status_flags` en plus de l'état brut ; ils ne remplacent pas les définitions constructeur.

The `dtcs`, `obd-dtcs`, `count` and `obd-count` commands include `effective_mask` (requested AND available mask) and `filter_available`. An unavailable filter cannot establish absence of faults. Listed DTCs include standard `status_flags` identifiers alongside the raw status; these are not OEM fault descriptions.

Les rapports WWH 19/42, 19/55 et 19/56 exposent également les états normalisés. Seul 19/42 indique les masques effectifs d'état et de sévérité, car lui seul transmet ces filtres. Les octets de sévérité restent bruts.

WWH reports 19/42, 19/55 and 19/56 also expose standard status flags. Only 19/42 reports effective status and severity masks, because only this request transmits these filters. Severity bytes remain raw.

```bash
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 supported-dtcs
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 permanent-dtcs
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 snapshot-index
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 obd-identify
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 ecu-inventory --ecus 1234,1235
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 obd-ecu-inventory --ecus 1234,1235
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 monitor-dids --layout F190:17,F187:4 --samples 5
```

L'inventaire des DTC n'est pas une liste de défauts actifs. `permanent-dtcs` lit les défauts permanents UDS si le calculateur prend en charge ce service ; un défaut permanent peut ne plus être actif. L'index snapshot donne les références à lire avec `snapshot --dtc ... --record ...`. Pour le monitoring, indiquer les longueurs réelles de chaque DID ; `obd-monitor-dids` requiert un ECU ayant confirmé OBDonUDS. Les exemples sont illustratifs, pas des définitions ECU. Chaque échantillon est vérifié sur le même ECU avant publication. Sur stderr, `partial_sample` contient sa route (VIN, passerelle, testeur, ECU, transport) ; sur échec, `monitor_summary` indique `capture_complete: false` et le nombre d'échantillons effectivement publiés. Le JSON final sur stdout n'est complet qu'après fermeture du transport et du journal.

The supported-DTC inventory is not an active fault list. `permanent-dtcs` reads permanent UDS DTCs if supported by the ECU; a permanent DTC need not be currently active. The snapshot index gives references for the `snapshot` action. Supply actual DID lengths for monitoring; `obd-monitor-dids` requires confirmed OBDonUDS. Examples do not establish ECU definitions. Each sample is verified against the same ECU before publication. On stderr, `partial_sample` includes its route (VIN, gateway, tester, ECU, transport); on failure, `monitor_summary` reports `capture_complete: false` and the number of published samples. The final stdout JSON is complete only after the transport and trace file have closed.

### Données historiques DTC / Historical DTC data

Les snapshots et les données étendues peuvent être décodés avec les longueurs fournies par la documentation du calculateur. Les résultats restent des octets historiques, pas des mesures en temps réel. / Snapshots and extended data can be decoded with ECU-documented lengths. Results remain historical bytes, not live measurements.

```sh
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 snapshot-decoded --dtc 010203 --record FF --did-lengths F40C:2,F40D:1
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 stored-records --did-lengths F40C:2,F40D:1
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 extended-decoded --dtc 010203 --record FF --record-lengths 01:2,90:1
```

Pour les données étendues, FE limite la réponse aux enregistrements OBD réglementaires 90–9F ; FF demande tous les enregistrements. Les actions `obd-snapshot-decoded` et `obd-extended-decoded` vérifient également l'identification OBDonUDS. Les longueurs ci-dessus sont des exemples de syntaxe, à remplacer par les définitions réelles. / FE selects regulated OBD records 90–9F; FF requests all records. OBD-specific actions also verify OBDonUDS identification. Replace example lengths with actual documented ECU lengths.

Les commandes `memory-snapshot-decoded --memory 02 --dtc 010203 --record FF --did-lengths F40D:1` et `memory-extended-decoded --memory 02 --dtc 010203 --record FF --record-lengths 01:2,90:1` utilisent la mémoire explicitement sélectionnée. Les exemples ne définissent pas les longueurs réelles du calculateur. / These commands decode records in the explicitly selected memory; example lengths are not ECU definitions.

Pour décoder un enregistrement mémorisé individuel (19/05), utiliser `stored-record --record 01 --did-lengths F40D:1`. Sans catalogue, la commande conserve son résultat brut historique. / To decode one stored record, supply documented DID lengths; without a catalog the original raw operation is unchanged.

### Acquisition mémoire DoIP / DoIP memory capture

```bash
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 backup --profile profil-doip.json --output capture.zip
python -m camarodiagconnectors.offline_cli verify-doip --archive capture.zip --profile profil-doip.json
```

Les adresses et le VIN proviennent du profil d'origine, contrôlé avant chaque lecture de zone. Deux passes indépendantes et vérification de l'identité du calculateur sont obligatoires. L'archive est contrôlée avant publication exclusive ; le système de fichiers doit prendre en charge les liens physiques. Aucune réécriture d'un fichier existant, aucun déverrouillage ou effacement automatique. Limite : 8192 blocs par passe, 600 s de lecture. / Two independent scoped reads and identity verification, exclusive archive publication, no automatic ECU write or unlock.

La découverte par VIN fonctionne également sans `--host` sur le réseau IPv4 sélectionné par le système ; plusieurs identités concurrentes sont refusées. TLS accepte `--ca-file`, `--client-cert` et `--client-key` (certificats légitimement fournis, vérification serveur maintenue).

`sequence --commands "22F190;1901FF"` envoie au maximum 16 commandes sur une seule session après deux confirmations distinctes, uniquement depuis un terminal interactif. Les exemples sont des lectures, pas des procédures de programmation ECU. Arrêt sur la première erreur, aucune répétition automatique.


### Mémoire DTC sélectionnée / Selected DTC memory

Les commandes `memory-dtcs`, `memory-snapshot` et `memory-extended` lisent uniquement la mémoire explicitement documentée par le constructeur, sans modifier les DTC. Utilisez le sélecteur réel de votre ECU. Les enregistrements sans définition constructeur restent bruts. / These commands read the documented user-defined DTC memory without clearing faults; use the actual ECU memory selector. Unknown record layouts remain raw.

```bash
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 memory-dtcs --memory 02 --mask 08
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 memory-snapshot --memory 02 --dtc 010203 --record 01
```

Adresses, VIN, mémoire et DTC fictifs / Example addresses, VIN, memory and DTC are illustrative.


### Données étendues par numéro / Extended data by record number

`extended-record-support` liste les DTC qui prennent en charge un enregistrement (UDS 19/1A). `extended-record-data` lit cet enregistrement (UDS 19/16) en utilisant la longueur uniforme fournie par la documentation ECU, sans interpréter les octets. / List supporting DTCs, or read their data with an explicitly documented uniform byte length. A different or unknown layout must not be decoded using a guessed size.

```bash
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 extended-record-support --record 01
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 extended-record-data --record 01 --size 2
```

Exemples fictifs / Illustrative parameters only.


### UDS historique : mémoire miroir et émissions / Legacy UDS: mirror memory and emissions

À utiliser uniquement lorsque la documentation du calculateur indique la prise en charge des anciens services ISO 14229 (retirés dans l'édition 2020). Ces commandes ne remplacent pas les lectures de la mémoire principale ni OBDonUDS. / Use only for ECUs supporting these legacy services. No automatic fallback or clearing is performed.

```bash
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 mirror-dtcs --mask 08
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 mirror-count --mask 08
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 mirror-extended --dtc 010203 --record 01
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 legacy-emissions-count --mask 08
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 legacy-emissions-dtcs --mask 08
```

Paramètres fictifs / Illustrative parameters only.

## Recette SAE J1979 CAN depuis un hôte Linux / Linux host CAN recipe

L'hôte Linux peut utiliser une interface SocketCAN ISO-TP **externe** déjà configurée pour le réseau ISO 15765-4 documenté. Aucun changement de bitrate, diffusion fonctionnelle, actionnement, effacement ou programmation ; le firmware USB de découverte ne reçoit aucune nouvelle capacité. Une paire d'adresses physiques vérifiée est indispensable. Exemple illustratif à adapter au véhicule et au calculateur :
```bash
cd host/python
python -m camarodiagconnectors.j1979_cli --interface can0 --tx 7E0 --rx 7E8 --confirm-physical-channel --output j1979-can.json
```

Option `--vin VIN_ATTENDU` : remplacer par les 17 caractères du VIN documenté. Si le VIN initial ne peut pas être lu ou ne correspond pas, la recette s'arrête avant toute autre requête et conserve le rapport JSON (code de sortie non nul). Sans option, comparaison non demandée. / Optional `--vin`: use the documented 17-character VIN. An unavailable or mismatched initial VIN stops the recipe before further requests; the JSON report is retained with a nonzero exit status. A match does not establish physical provenance.

Le rapport JSON privé est créé sans écrasement et contient le VIN observé, les requêtes/réponses et durées, les résultats individuels, les tests Mode 08 annoncés et les opérations non exécutées. Une réponse négative ou absente n'est pas une lecture réussie. Les données non décodées restent brutes ; aucun résultat matériel ne peut être déduit de la seule configuration de l'interface (y compris si elle est virtuelle). Aucun Mode 05 legacy ni opération mutatrice. La recette doit être qualifiée avec un véritable adaptateur et un véhicule compatible.

The Linux host uses an independently configured SocketCAN ISO-TP interface, not the USB discovery firmware. Select a documented physical ECU pair; the command never configures bitrate, broadcasts or performs writes. Export is exclusive and records observed VIN, raw exchanges and individual results, including incomplete transport evidence. A virtual or simulated CAN interface is not a physical vehicle qualification.

### Recette EVAP indépendante / Separate EVAP recipe

Sur un véhicule thermique compatible et selon la procédure constructeur, sélectionner l'ECM physique principal et son VIN documenté. Utiliser la recette séparée `camarodiagconnectors-j1979-evap` (module `camarodiagconnectors.j1979_evap_cli`) avec `--interface`, `--tx`, `--rx`, `--vin`, `--output` et option `--fr`. Le programme exige deux confirmations interactives distinctes ; il ne s'exécute jamais dans le scan de lecture seule. Il exige le VIN réel correspondant, le support TID 01 et des lectures positives de vitesse/régime à zéro, puis une unique demande. Aucun retry, aucun autre TID, aucun changement du firmware USB. Le JSON est créé sans écrasement et distingue acquittement, refus, non testé et état incertain ; il ne prouve pas la fin du moniteur.

Independent interactive Linux CAN EVAP recipe, not part of the read-only scan. Requires documented primary ECM/VIN, fresh support and stationary evidence and two confirmations. No automatic retry. Acknowledgment is not monitor completion.

### Recette Mode 04 indépendante / Separate Mode 04 recipe

Utiliser la commande interactive `camarodiagconnectors-j1979-clear` (module `camarodiagconnectors.j1979_clear_cli`), sur un adaptateur SocketCAN externe et un ECM CAN physique principal documenté. Les paramètres sont `--interface`, `--tx`, `--rx`, `--vin`, `--output` et éventuellement `--fr`. Exporter d'abord le diagnostic complet : le Mode 04 peut supprimer des DTC antipollution, trames figées et états de préparation. Respecter la procédure constructeur, véhicule immobilisé et moteur arrêté. Deux confirmations interactives, VIN réel et baseline de DTC confirmés requis, lectures positives vitesse/régime zéro, demande unique. Le JSON sépare les observations initiales/finales des Modes 03/07/0A et PID 01, l'acquittement et les erreurs. Un mode non relu reste null ; les DTC permanents ne sont jamais présumés effacés. Pas de retry, aucune extension de l'interface de lecture générique ni du firmware USB. Le code de sortie non nul signale aussi les DTC confirmés/en attente persistants.

Independent interactive Mode 04 recipe. Export the original complete scan first, use a documented primary physical ECM and VIN, follow the manufacturer procedure, and confirm twice. Records before/after DTC and readiness evidence, exact reset ACK and uncertainty. Missing modes are null, not zero; residual faults do not become a successful zero-DTC result.

### SAE J1979 legacy — hôte ELM série Windows/Linux

Avec un adaptateur ELM/STN série dédié prenant réellement en charge le réseau sélectionné, utiliser `camarodiagconnectors-j1979-legacy` (module `camarodiagconnectors.j1979_legacy_cli`). Installer l'extra Python `serial` (pyserial). Exemple illustratif : `--port COM5 --baud 38400 --protocol 2 --confirm-dedicated-adapter --output legacy-vpw.json` (sous Linux, utiliser le port série réellement identifié). Les variantes 1 = J1850 PWM, 2 = J1850 VPW, 3 = ISO 9141-2, 4 = ISO 14230-4 slow init, 5 = ISO 14230-4 fast init. La recette reconfigure l'adaptateur dédié, ne réutilise pas une session d'un autre logiciel, confirme la variante après une réponse PID 00 et n'effectue pas de repli CAN.

Découverte des calculateurs répondants (lecture PID 00 seulement) : ajouter `--source discover`. Puis lancer une recette attribuée à la source documentée avec `--source 10 --vin VIN_ATTENDU` (adapter la source et le VIN au véhicule). Les autres réponses sont conservées dans les trames brutes mais jamais fusionnées avec les mesures du calculateur choisi. La requête reste fonctionnelle et la sélection filtre les réponses, sans preuve d'adressage physique ni de couverture du véhicule. Les interfaces qui omettent les octets de contrôle sont refusées dans ce mode, sans repli silencieux vers des réponses anonymes.

Discover responders with `--source discover` (PID 00 only), then select a documented responder using `--source 10 --vin EXPECTED_VIN`. Selection filters header/checksum-validated responses; it does not physically address a single ECU or establish vehicle-wide coverage. Other responders are retained as raw frames, not mixed into selected measurements. Adapters omitting the checksum are refused without anonymous fallback.

Le JSON legacy expose les mesures numériques dont la largeur est connue, les DTC séparés par réponse, les déclencheurs de trame figée et les données encodées des tests Mode 05/06. Les réponses sans en-tête restent anonymes ; aucun verdict de moniteur ni identité ECU n'est déduit. / The legacy JSON includes width-validated numeric readings, per-response DTCs, freeze-frame triggers and encoded Mode 05/06 test records. Headerless responses remain anonymous; no monitor verdict or ECU identity is inferred.

Lecture des modes applicables 01/02/03/05/06/07/08 (inventaire)/09 et 0A si disponible, selon les formats non-CAN de la norme. Export JSON séparé par exécution et protocole, sans écrasement. Réponses sans adresse anonymes : un candidat VIN reconstruit n'est pas un VIN ECU attribué. Absence de réponse distincte d'un zéro ; aucun Mode 04 ou actionnement 08 dans cette recette. Ce client n'est ni un pilote J2534 legacy ni une activation du firmware USB.

The standalone serial ELM host recipe runs on Windows/Linux with an adapter and vehicle supporting the explicitly selected legacy protocol. No automatic CAN fallback, no mutations, no physical attribution of anonymous records. Dedicated adapter configuration is changed; close other diagnostic software first.

### J1979 — préparation du transport embarqué CAN classique

Le noyau partagé `j1979_can_read_session.h`, `j1979_can_read_exchange.h` et `j1979_can_campaign.h` implémente le parcours de lecture sur un canal physique ISO 15765-4 explicitement choisi (11/29 bits). Il vérifie les réponses ISO-TP, gère les trames de contrôle de flux, les NRC et le délai global, et transmet les preuves par étape à un collecteur externe. Les réponses corrélées de format inconnu sont RAW et non PASSED. Les inventaires 01/02/06/08/09 ont des indicateurs de complétude distincts de la fin de campagne ; le Mode 08 n'est qu'un inventaire. Aucun accès mutateur.

L'adaptateur `firmware/esp32s3/src/j1979_twai_read_link.*` est prévu pour un canal TWAI déjà configuré et détenu exclusivement par un profil matériel qualifié. Il exige une confirmation effective TX_SUCCESS, contrôle les pertes, arrête le parcours et verrouille l'instance sur erreur. Il ne configure aucune broche, alimentation ou transceiver et ne lance pas le pilote. Après une erreur de transmission, le propriétaire matériel doit contrôler et arrêter le canal avant toute nouvelle session ; la classe ne garantit pas l'annulation d'une trame déjà soumise au pilote.

**Le firmware USB de découverte reste inchangé et n'annonce aucun accès CAN.** Aucune liaison physique ou qualification matérielle n'est démontrée ; ne pas connecter le prototype au véhicule. Les tests natifs de campagne sont enregistrés pour la clôture logicielle, pas exécutés dans cette session.

### Capture de preuves J1979 embarquées CJ/1

Le format de preuves CJ/1 est **distinct du protocole de découverte CD/1**. Le collecteur `j1979_campaign_evidence.h` transmet enregistrements de début, observations (requête/réponse, statut, durée), puis résumé et indicateurs d'inventaire. CRC-32 et séquence vérifient corruption et ordre, pas l'authenticité. Le format n'accepte aucune commande véhicule. Le lecteur Python vérifie aussi les longueurs et formes applicatives, la distinction RAW/PASSED, les comptes du résumé et la présence de la première réponse PID00. Il préserve le préfixe lisible en cas de capture partielle, sans déclarer l'intégrité complète.

Conversion d'une capture existante, Windows/Linux :
```shell
camarodiagconnectors-j1979-capture --input campaign.cj --output campaign.json --vehicle "Camaro 2012" --ecu "ECM" --connector "bench"
```
L'export est exclusif, et distingue les labels déclarés des informations observées. Il ne prouve ni provenance matérielle ni validation physique.

`esp32s3/src/j1979_usb_campaign.h` relie la campagne, le flux de preuves et le gestionnaire d'exclusivité NativeUsb à un propriétaire matériel abstrait. Celui-ci doit fournir les preuves locales fraîches de qualification, profil, autorisation et stabilité, ainsi que l'arrêt vérifié du canal. Déconnexion ou révocation interrompent la session ; un arrêt non vérifié verrouille l'admission. Aucun de ces éléments ne crée un profil de carte, une authentification ou un point d'entrée USB réel. **Le firmware de découverte reste inchangé.**

Pour le parcours abstrait NativeUsb, le résumé terminal CJ/1 est émis seulement après arrêt du canal vérifié et maintien de l'autorisation du même hôte. Arrêt non vérifié : verrou de récupération, capture partielle sans résumé. Perte d'autorisation à l'arrêt : pas de résumé. Le profil matériel et le point d'entrée USB réel demeurent à intégrer.

### SAE J1979 — découverte CAN et recettes multi-calculateurs / CAN network discovery

Sur une interface Linux SocketCAN déjà configurée pour le réseau documenté (CAN classique), découverte seule :

```sh
camarodiagconnectors-j1979-network --interface can0 --addressing 11bit --discover-only --confirm-functional-pid00 --output sources.json
```

Puis sélectionner les sources documentées à contrôler lors d'une nouvelle recette (exemples uniquement) :

```sh
camarodiagconnectors-j1979-network --interface can0 --addressing 11bit --source 7E8 --source 7E9 --confirm-functional-pid00 --confirm-physical-channels --output network.json
```

`--addressing 29bit` accepte les sources de réponse normalisées 18DAF1xx (xx inférieur à F0). `--vin` facultatif exige le même VIN sur chaque source sélectionnée et interrompt la campagne si absent ou discordant. Aucune source non demandée ne reçoit de scan physique ; la seule requête fonctionnelle est PID 00, les réponses contradictoires sont exclues. Budget global, JSON privé exclusif, aucun Mode 04/08 mutateur, aucune preuve de couverture véhicule.

On an already configured classic SocketCAN interface, use discovery-only or select documented response IDs explicitly. Optional VIN comparison is fail-closed per selected responder; silent or conflicting responders are not silently substituted. No automatic interface setup, CAN FD or write operation.

### SAE J1979 — enregistrement de mesures physiques / live recording

```sh
camarodiagconnectors-j1979-live --interface can0 --tx 7E0 --rx 7E8 --vin VIN_DOCUMENTE --pid 05 --pid 0C --cycles 30 --interval 1 --budget 180 --confirm-physical-channel --output live.json --csv live.csv
```

Remplacer VIN et adresses par ceux documentés. La recette vérifie le VIN avant, pendant les campagnes longues et à la fin. Seuls les PID numériques sélectionnés et confirmés par une chaîne de bitmaps sont interrogés. Chaque valeur possède son horodatage et sa réponse ; zéro n'est jamais utilisé à la place d'un timeout. Les cycles ne sont pas des acquisitions simultanées. Export JSON/CSV exclusif, limite de temps et d'observations. Le rapport est incomplet si l'identité finale est perdue ; aucune preuve physique ou mutation revendiquée.

The live recorder uses one documented physical CAN channel, verified VIN and advertised numeric Mode 01 PIDs. Per-request timestamps and raw evidence, no reused stale values, bounded cycles/deadline, no mutations. Output paths must be new.

### SAE J1979 — enregistrement legacy ELM / legacy live recording

```sh
camarodiagconnectors-j1979-legacy-live --port /dev/ttyUSB0 --protocol 2 --source 10 --vin VIN_DOCUMENTE --pid 05 --pid 0C --cycles 30 --interval 1 --confirm-dedicated-adapter --confirm-functional-scope --output legacy-live.json --csv legacy-live.csv
```

Variantes 1 PWM, 2 VPW, 3 ISO 9141-2, 4 KWP slow init, 5 KWP fast init. Sélectionner la vraie source et le VIN documentés. Chaque contrôle VIN relit PID00, ATDPN, 0900/0901/0902 sur la source choisie ; trames numérotées et checksums obligatoires. Les demandes restent fonctionnelles, aucune preuve d’adressage physique. Le JSON distingue les données applicatives normalisées des trames réellement reçues. Seuls les PID numériques annoncés sont lus, avec horodatage et statistiques ; les absences de réponse ne deviennent jamais des valeurs. Aucun effacement/actionnement. / Dedicated ELM, explicit legacy variant and response source, shared time budget, original frame evidence and no physical addressing or coverage claim.

### Récupération hors ligne — inspection de sauvegardes Android et Windows

```sh
camarodiagconnectors-offline verify-recovery --platform android --journal journal.tsv --family E39 --controller ECM --firmware OS-A --vin VIN_DOCUMENTE --output android-recovery.json
camarodiagconnectors-offline verify-recovery --platform windows --journal journal.jsonl --vin VIN_DOCUMENTE --osid 123 --original-bin original.bin --output windows-recovery.json
```

Conserver le dossier `.blocks` associé au journal et, pour Windows, le manifeste `.meta.json`. Remplacer les identités d'exemple par celles documentées. La recette relit les originaux et les journaux, vérifie la séquence, les empreintes et l'inventaire exact, et calcule le même SHA-256 composite que les applications. `--expected-snapshot` compare une empreinte de rapport préexistante. Pour Windows E39/E39A, le BIN complet de 3 Mio peut être comparé aux originaux ; aucune adresse Android générique n'est supposée être un offset de BIN. Rapport JSON exclusif, aucune modification des preuves ou connexion véhicule, aucune autorisation de restauration. / Offline local evidence verification only; no physical provenance or programming authority.

### Relecture indépendante des enregistrements J1979 / Independent live replay

```sh
camarodiag-offline verify-live --report live.json --output verified.json
camarodiag-offline compare-live --report before.json --report after.json --output comparison.json
```

Relecture hors ligne des réponses Mode 01 et Mode 09, des bitmaps, des cycles, de la source legacy et des statistiques. La comparaison impose un même VIN et canal, sans supposer une acquisition simultanée ou des conditions de conduite identiques. Une preuve cohérente n'est ni une authentification, ni une qualification physique. / Offline consistency replay and per-PID summaries, not hardware qualification or a synchronized diagnostic verdict. Output is exclusive; source files are never modified.

### Checkpoints CDJ/1 hors ligne / Offline checkpoint inspection

```sh
camarodiag-offline inspect-checkpoint --input checkpoint.bin --output inspection.json
camarodiag-offline verify-checkpoint-report --input checkpoint.bin --report android-or-windows.json --output matched.json
camarodiag-offline inspect-checkpoint-history --input prepared.bin --input erase-intent.bin --output history.json
```

La suite est fournie dans l'ordre par l'opérateur. Un état terminal ne prouve pas une restauration physique, et un suffixe ne constitue pas un historique complet. CRC et SHA-256 ne prouvent ni authenticité ni fraîcheur. Aucun accès ECU et aucune autorisation de programmation. / Explicit ordered evidence only; no authenticated history or recovery authorization. Optional transaction and manifest identifiers must match exactly.
