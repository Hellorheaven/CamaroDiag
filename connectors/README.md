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

```bash
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 supported-dtcs
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 permanent-dtcs
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 snapshot-index
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 obd-identify
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 ecu-inventory --ecus 1234,1235
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 obd-ecu-inventory --ecus 1234,1235
python -m camarodiagconnectors.doip_cli --host 192.0.2.10 --vin 1G1FA1RXXR0123456 --tester 0E80 --ecu 1234 monitor-dids --layout F190:17,F187:4 --samples 5
```

L'inventaire des DTC n'est pas une liste de défauts actifs. `permanent-dtcs` lit les défauts permanents UDS si le calculateur prend en charge ce service ; un défaut permanent peut ne plus être actif. L'index snapshot donne les références à lire avec `snapshot --dtc ... --record ...`. Pour le monitoring, indiquer les longueurs réelles de chaque DID ; `obd-monitor-dids` requiert un ECU ayant confirmé OBDonUDS. Les exemples sont illustratifs, pas des définitions ECU. Les échantillons restent provisoires jusqu'à la vérification finale.

The supported-DTC inventory is not an active fault list. `permanent-dtcs` reads permanent UDS DTCs if supported by the ECU; a permanent DTC need not be currently active. The snapshot index gives references for the `snapshot` action. Supply actual DID lengths for monitoring; `obd-monitor-dids` requires confirmed OBDonUDS. Examples do not establish ECU definitions. Samples remain provisional until final verification.

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
