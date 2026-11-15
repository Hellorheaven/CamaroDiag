# CamaroDiag

Application Android native Kotlin / Jetpack Compose pour diagnostic Chevrolet Camaro Gen 5 (2010–2015) et Gen 6 (2016–2024).

## Architecture modulaire

- `:app` — interface Compose, navigation et ViewModel.
- `:core` — modèles véhicule, générations, motorisations et types de données partagés.
- `:diagnostic` — décodage OBD-II/UDS, DTC, profils ECU et audit kilométrique.
- `:communication` — transport Bluetooth SPP, USB série ELM/STN et Wi-Fi TCP.
- `:control` — catalogue des fonctions carrosserie et garde-fous de validation des commandes GM.
- `:storage` — persistance Room des sessions de diagnostic.

## Compatibilité Android

- Android 10 à Android 16 (API 29 à 36), `minSdk = 29`, `compileSdk = 36`, `targetSdk = 36`.
- Interface adaptative Compose.
- Les permissions Bluetooth sont différenciées avant/après Android 12.

## Versioning et releases

Le versionnage Android et GitHub Release utilise `version.properties` comme source unique :
- `VERSION_PREFIX=1.6` définit la série `1.6.x`.
- `VERSION_CODE_BASE=7` définit la base du `versionCode` Android.
- À chaque publication sur `main`, le workflow cherche le dernier tag `v1.6.N`, incrémente automatiquement `N`, puis construit `versionName=1.6.N` et `versionCode=7+N`.
- Le workflow crée le tag et la GitHub Release et joint les APK Debug et Release signés.

Le README décrit les fonctionnalités par série (`1.6.x`) plutôt que de maintenir manuellement le numéro de patch. Pour passer à une nouvelle série MAJOR.MINOR, mettre à jour `VERSION_PREFIX` et choisir une `VERSION_CODE_BASE` strictement supérieure au dernier `versionCode` déjà publié. Ne pas remettre les versions en dur dans `app/build.gradle.kts`.

## Interfaces OBD

- OBDLink MX+ / MX / LX et adaptateurs Bluetooth classiques compatibles : transport Bluetooth SPP.
- OBDLink CX : découverte BLE (scan Android), transport GATT dédié (service FFF0, notification FFF1, écriture FFF2, négociation MTU et segmentation des écritures). Connexion volontairement refusée pour Camaro/GM : OBDLink confirme que le CX ne fonctionne pas sur les véhicules GM.
- OBDLink EX / SX et ELM327 USB série via usb-serial-for-android, OTG Android et débit 115200 bauds pour les appareils identifiés OBDLink.
- OBDLink MX Wi-Fi et adaptateurs ELM327 Wi-Fi via TCP (adresse IP et port configurables; ports usuels 35000 ou 23 selon l'adaptateur).
- Détection Bluetooth des appareils appairés et scan BLE des appareils OBD à proximité. Les modèles BLE nécessitent les autorisations Android Bluetooth Scan/Connect (Android 12+) ou localisation (versions antérieures).
- La prise en charge physique des bus reste propre au modèle : MX+ prend en charge GM SW-CAN; ne pas déduire SW-CAN du seul fait qu'un adaptateur OBDLink est détecté.
- Carbyte est un produit GM dédié à certaines fonctions AFM/DFM/Stop-Start, pas un adaptateur de diagnostic OBD générique exploitable comme MX+/EX.
- VXDIAG/J2534 : non déclaré opérationnel sur Android. Il faut le SDK Android officiel ou un protocole documenté propre au modèle exact; aucune compatibilité n'est simulée.

## Camaro Gen 5 / Gen 6 ECU calibration coverage

The calibration catalogue covers all engine profiles currently represented for Camaro Gen 5 and Gen 6:

| Generation | Engine RPO | Model years | Nominal controller family in the vehicle profile |
|---|---|---:|---|
| Gen 5 | LLT 3.6 V6 | 2010–2011 | Bosch E69 (profile label; confirm on ECU) |
| Gen 5 | LFX 3.6 V6 | 2012–2015 | ACDelco E39 / E39A (profile label; confirm on ECU) |
| Gen 5 | LS3 6.2 V8 | 2010–2015 | ACDelco E38 (profile label; confirm on ECU) |
| Gen 5 | L99 6.2 V8 | 2010–2015 | ACDelco E38 (profile label; confirm on ECU) |
| Gen 5 | LSA 6.2 supercharged V8 | 2012–2015 | ACDelco E67 (profile label; confirm on ECU) |
| Gen 5 | LS7 7.0 V8 | 2014–2015 | ACDelco E38 (profile label; confirm on ECU) |
| Gen 6 | LTG 2.0 turbo I4 | 2016–2020 | GM E80 (confirm part number/CAL ID; GM bulletin PIP5313B identifies E80 on 2016–2017 Camaro LTG) |
| Gen 6 | LGX 3.6 V6 | 2016–2024 | GM E92 / E93 variant-dependent (confirm on ECU) |
| Gen 6 | LT1 6.2 V8 | 2016–2024 | GM E92 (profile label; confirm on ECU) |
| Gen 6 | LT4 6.2 supercharged V8 | 2017–2024 | GM E92 (profile label; confirm on ECU) |

La couverture de ces profils représente les motorisations et années cataloguées dans CamaroDiag. Elle ne garantit pas que chaque combinaison matérielle/logicielle soit déjà prise en charge pour la lecture mémoire ou la programmation. Les références de calculateur doivent être confirmées sur le véhicule, à partir du VIN, du CAL ID, du CVN, du numéro matériel et de l'OS.

The catalogue deliberately treats E-family labels as nominal profile hints. A real operation must identify the installed controller, hardware number, CAL ID, CVN and OS before selecting a binary definition. The Mode 09 CAL ID/CVN read and local file import/export are available as data-handling functions; they do not constitute a binary ECU read or flash.

## ECU / calibration workspace

- The app launcher icon uses a Bumblebee-inspired yellow/black Camaro treatment.
- The new ECU workspace reads the standard OBD Mode 09 CAL ID (PID 04) and CVN (PID 06) from a connected vehicle and displays the raw ECU responses.
- Local calibration-file import is limited to 32 MiB, stores the file in private app storage and records the file name, size, SHA-256 digest and a short hex preview. The imported file can be exported back to a user-selected location using Android Storage Access Framework.
- These identifiers do **not** read the binary memory of the ECU. The local calibration editor can decode and modify tables only when the user imports a matching, explicit table-definition file. It saves a new local image and does not write to the ECU.
- ECU memory read and ECU programming are not enabled. Camaro Gen 5/Gen 6 controllers (Bosch E69, GM E38, E39/E39A, E67, E80, E92/E93 variants) require exact OS/calibration identification, a verified programming procedure, authorized security access where applicable, controller-specific checksum processing, a recovery/bench programming path and validation on matching hardware. No generic OBD command is treated as a programming protocol.
- A future write feature must remain disabled until the exact vehicle/ECU profile and recovery method are confirmed. Never flash a calibration solely because the file extension or nominal engine family matches.

## Pilotage carrosserie

L'écran Pilotage présente les fonctions candidates (vitres, toit ouvrant, verrouillage, éclairage, klaxon, rétroviseurs, coffre). Elles restent verrouillées par défaut. Les commandes GM dépendent du VIN, du BCM, du marché, de la finition, de la calibration et des sessions de sécurité. Ne pas envoyer de trames arbitraires : une fonction ne doit être activée qu'après validation sur un véhicule réel et documentation du DID / service / paramètres.

## Build local

Ouvrir le projet dans Android Studio avec JDK 17 et Android SDK 36, synchroniser Gradle, puis lancer `:app:assembleDebug`.

## GitHub Actions

Le workflow `.github/workflows/release-apk.yml` est conservé et mis à jour. Il compile les APK debug et release, puis publie une GitHub Release et ses APKs lors d'un push sur `main` ou d'un lancement manuel. Les secrets de signature existants doivent être configurés dans GitHub Actions.

## Limites à valider sur véhicule

- La disponibilité des PIDs OBD et l'adressage GM dépendent de l'année, du moteur, du marché et du calculateur.
- L'ELM327 générique ne garantit pas l'accès à tous les réseaux GM (notamment SW-CAN/GMLAN).
- La lecture intermodules du kilométrage et les commandes de carrosserie nécessitent des identifiants et procédures confirmés pour chaque famille ECU.

## Mileage audit (v1.3.0)
- Added the dedicated `:mileageaudit` module for source-aware mileage comparison.
- BCM and IPC are explicitly displayed as unavailable until a VIN/year/calibration-specific GM read profile is verified. The app does not guess ECU addresses or DIDs.
- ECM PID A6/D3 values are treated as powertrain data, not as independent BCM/IPC evidence.
- A discrepancy is flagged only when at least two verified independent sources are available; a mismatch is an investigation lead, not proof of tampering.
- Added unit tests for independent-source discrepancy, non-independent ECM PIDs, and missing data.

## Remote start safety
The control catalog now includes a remote-start entry, but it is intentionally non-executable through generic ELM/OBD commands. Use the vehicle's OEM remote-start system only. No immobilizer, theft-deterrent, transmission interlock, or engine-start security bypass is implemented.

## v1.4.0 – VXDIAG and GM integration foundation

- Added VCX SE USB-C/Wi-Fi and VCX NANO USB/Wi-Fi hardware profiles.
- Added the `VxdiagProvider` integration boundary for a licensed/documented Android vendor SDK.
- Added fail-closed OEM remote-start safety assessment; no direct crank, immobilizer bypass or undocumented CAN command is sent.
- Kept Gen 5 and Gen 6 profiles separate and mileage audit read-only.
- Android API range: minSdk 29 (Android 10), targetSdk 36 (Android 16).
- See `docs_GM_SUPPORT.md` for limitations and the required VXDIAG SDK hand-off.

**Important:** VXDIAG profiles are not equivalent to a working J2534 driver. Actual VCX communication remains disabled until a compatible vendor SDK/API is supplied and tested on hardware.

## v1.5.0 – expanded standards-based OBD-II diagnostics

The `:diagnostic` module now contains `Obd2FeatureEngine`, a read-only SAE J1979 engine wired to the existing Bluetooth/USB/Wi-Fi ELM command manager through `createObd2FeatureEngine()`.

Implemented requests include adapter initialization/protocol/voltage, supported-PID bitmap discovery, common Mode 01 live values (RPM, speed, coolant, intake temperature, MAF, throttle, fuel pressure/level, barometric pressure, module voltage, oil temperature, fuel rate and supported monitor values), Mode 03 confirmed DTCs, Mode 07 pending DTCs, Mode 0A permanent DTCs, Mode 02 freeze-frame values, Mode 09 VIN/calibration/ECU-name records, Mode 01 readiness status and raw Mode 06 monitor-test response.

All queries are read-only. Unsupported PIDs are skipped; raw responses are retained in the scan report. Mode 06 is deliberately kept raw because test-ID scaling and limits are ECU/calibration-specific. Mode 09 multi-frame responses and Mode 02 freeze-frame decoding must be validated against real adapter captures before being treated as complete. This engine does not claim access to GM enhanced modules (BCM/IPC/ABS/SDM/TCM), nor does it send actuator, coding, security-access or engine-start commands. Those require verified vehicle-specific GM service definitions and a capable CAN interface.

The full scan is exposed as `BluetoothObdManager.createObd2FeatureEngine().fullReadOnlyScan()` after a real adapter connection. Demo mode must not be used to represent a vehicle scan.


## v1.6.x – OBD scan and calibration file analysis

The full OBD-II scanner is now exposed in the app navigation. It presents supported PIDs, decoded live values, confirmed/pending/permanent DTCs, freeze-frame data, VIN, CAL ID, CVN, ECU name, readiness, adapter voltage/protocol and raw Mode 06 results. The scanner sends standardized read-only OBD services; a missing ECU response remains missing and is never replaced with sample data. Mode 04 clearing requires a separate user confirmation and is accepted only on a positive response.

The ECU workspace imports raw BIN/ORI/MOD, Intel HEX, Motorola S-record, ZIP archives, external table definitions and external calibration-project packages. Intel HEX and S-record records are checked for structure and format checksums. ZIP archives are inspected within bounded limits and retained byte-for-byte; opaque project packages are also retained byte-for-byte and can be exported. Two raw BIN images can be compared byte-for-byte, with differing offsets, changed-byte count and file-size differences.

The offline table editor works on raw binary images when the user supplies explicit, verified table definitions (offset, dimensions, data type, byte order, scaling and bias). External XDF/A2L/XML-style definitions are preserved as attachments but are not automatically converted into table definitions. Unknown or proprietary project packages are not decoded. These operations are not an ECU memory read or ECU flash. A file hash, Intel HEX checksum or S-record checksum is not a GM calibration checksum. ECU binary read/write remains disabled until the relevant GM controller/OS-specific protocol, authorized security procedures, calibration definitions, checksum routines, power and recovery process are supplied and validated. See `docs_GM_SUPPORT.md`.


## Calibration table editor

The ECU workspace supports offline editing of calibration tables when a matching definition file is supplied. Use the **Export TSV definition template** action, then provide one row per table with these fields:

`id;name;offset;rows;columns;type;endian;scale;bias;unit;minimum;maximum`

Supported storage types are U8, S8, U16, S16, U32, S32 and F32. Endianness is LE or BE. The editor validates image bounds, dimensions, numeric type and optional engineering limits. Applying edits creates a separate local BIN image; the originally imported file is kept as the reference. The active image can then be exported or compared.

No Camaro table addresses, offsets or scaling factors are guessed or bundled. A table definition must be verified against the exact controller hardware, OS and calibration before use. The current editor does not modify the running ECU. The live-data screen remains a diagnostic data stream; ECU programming requires a separate, validated GM programming transport and recovery process.


## Live telemetry recording

The Live screen records values returned by the connected OBD adapter and exports them as CSV. Each captured value includes a timestamp, VIN, vehicle profile, PID, label, numeric value and unit. Recording is limited to 10,000 polling samples per session and stops if the real adapter disconnects; demonstration values are excluded.

## Calibration editing and live data

CamaroDiag includes an offline table editor for an imported raw BIN image when the user supplies a verified table-definition file for the exact image. The definition specifies each table's byte offset, dimensions, data type, byte order, scaling, bias and optional limits. The original file is preserved and edits create a separate local image.

Live sensor acquisition while the engine is running is separate from editing a calibration file. CamaroDiag does not currently change calibration tables inside the running ECM or live-write calibration changes. No verified Gen 5/Gen 6 table-definition packs are bundled yet, and direct ECM binary read/programming remains unavailable pending exact controller/OS procedures and recovery validation.


## Import de cartographies et formats externes

L'import local accepte les fichiers sélectionnés via Android, même lorsque leur conteneur n'est pas décodé. Les formats inspectés sont BIN brut, Intel HEX et Motorola S-record. Les archives ZIP sont reconnues et leurs entrées sont listées dans des limites de sécurité ; elles sont conservées sans extraction automatique. Les fichiers de définition externes (XDF/A2L/XML) et les projets propriétaires non reconnus sont également conservés et peuvent être réexportés tels quels.

L'éditeur de tables ne décode pas encore automatiquement les projets/archives propriétaires ni les définitions XDF/A2L. Pour éditer une cartographie, il faut fournir l'image BIN brute et une définition TSV correspondante (offset, dimensions, type, endian, échelle, biais et limites). Aucun nom de logiciel tiers n'est utilisé comme preuve de compatibilité.

## Version Android et publication

Le fichier racine `version.properties` est l'unique source de vérité :
- `VERSION_PREFIX=1.6` définit la branche majeure/mineure.
- `VERSION_CODE_BASE=7` définit la base du numéro Android.

Les builds locaux utilisent `1.6.0` / versionCode `7`. Le workflow de release recherche le plus grand tag `v1.6.N`, calcule le patch suivant, puis injecte ce même patch dans Gradle. Le nom de version de l'APK, le versionCode, le tag Git et le titre de la release sont ainsi cohérents. Les déclenchements manuels utilisent la même règle.

Lors d'un changement de version majeure/mineure, modifier `VERSION_PREFIX` et choisir un `VERSION_CODE_BASE` strictement supérieur au dernier versionCode publié. Ajouter ensuite une entrée de notes de version correspondante dans ce README.
