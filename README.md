# CamaroDiag

CamaroDiag est une application Android de diagnostic pour Chevrolet Camaro Gen 5 (2010–2015) et Gen 6 (2016–2024). Elle lit les informations réellement retournées par le véhicule et l'interface connectée. Elle ne remplace pas les outils officiels de programmation GM.

## Fonctionnalités

- Identification OBD-II : VIN, CAL ID, CVN, nom ECU, protocole et tension lorsque ces données sont disponibles.
- Diagnostic standard en lecture : paramètres moteur Mode 01 (dont pression de rail, sondes lambda large bande et températures catalyseur lorsque les PIDs sont annoncés), freeze-frame Mode 02, DTC confirmés Mode 03, en attente Mode 07, permanents Mode 0A, readiness et résultats Mode 06.
- Compatibilité ELM327 Bluetooth SPP : identification ATI/AT@1, détection du protocole CAN 11 bits 500 kbit/s et filtrage des réponses d'erreur connues ; les clones restent limités selon leur firmware.
- Simulation OBD-II hors véhicule pour tester le scan complet, les réponses DTC, VIN, CAL ID, CVN et données moteur ; tous les résultats simulés sont explicitement signalés.
- Lecture UDS de données et de DTC (0x22 / 0x19) avec OBDLink MX+ STN ou pont PC J2534, lorsque l'adressage physique est connu.
- Catalogue communautaire Camaro OBDb : 133 commandes et 136 définitions de signaux, filtrées par année et adressage.
- Enregistrement télémétrique et export CSV.
- Audit kilométrique fondé sur les sources réellement identifiées.
- Atelier ECU hors véhicule : import et inspection BIN/ORI/MOD, Intel HEX, Motorola S-record et ZIP, empreinte SHA-256, comparaison binaire et édition de tables à partir de définitions TSV vérifiées.
- Pont Windows J2534 facultatif pour certains adaptateurs Pass-Thru, notamment VXDIAG VCX SE et VCX Nano avec pilote compatible.
- Les commandes carrosserie non validées et les fonctions de programmation restent verrouillées.

## Matrice des fonctions par interface

| Interface | Liaison | OBD-II standard | UDS / signaux Camaro | SW-CAN / GMLAN | État et conditions |
|---|---|---|---|---|---|
| OBDLink MX+ authentique (STN) | Bluetooth SPP | Oui | Oui, lecture UDS et signaux OBDb adressables | Support matériel SW-CAN ; accès selon profil et adressage | Interface Android recommandée ; CAN ID physiques exacts requis |
| OBDLink MX | Bluetooth SPP | Oui | Non activé pour le lecteur étendu actuel | Selon modèle et câblage | OBD standard |
| OBDLink LX | Bluetooth SPP | Oui | Non | Non annoncé | OBD standard |
| OBDLink EX / SX | USB série | Oui | Non activé | Selon modèle | USB Host/OTG requis |
| OBDLink CX | Bluetooth LE | Non pour Camaro/GM dans l'application | Non | Non | Détection BLE possible, connexion GM refusée |
| ELM327 Bluetooth SPP | Bluetooth classique | Oui, selon qualité | Non | Non garanti | OBD standard ; clones non garantis |
| ELM/STN USB série | USB OTG | Oui, selon interface | Non, sauf profil validé | Non garanti | USB Host/OTG requis |
| ELM/STN Wi-Fi | TCP/IP | Oui, selon interface | Non, sauf profil validé | Non garanti | IP et port adaptateur requis |
| VXDIAG VCX SE | PC Windows + J2534 | Non via le pont UDS | Oui, lecture UDS via pont | SW-ISO15765 si le pilote J2534-2 l'expose | Pilote Windows exact requis ; pas de pilote Android natif |
| VXDIAG VCX Nano | PC Windows + J2534 | Non via le pont UDS | Oui, lecture UDS via pont selon variante | Dépend révision, câblage et pilote | SW-CAN non présumé ; compatibilité à confirmer |

La connexion d'un adaptateur ne garantit pas l'accès à tous les réseaux GM. Les fonctions dépendent de la référence, révision, firmware, broches DLC, pilote et réponses du calculateur.

## Matrice des véhicules et fonctions

Les familles ECM sont indicatives et doivent être confirmées sur le véhicule par le numéro matériel, VIN, CAL ID, CVN et OS.

| Camaro | Années | Moteur / RPO | Famille ECM indicative | OBD-II standard | Signaux OBDb / UDS | Diagnostic exhaustif BCM/TCM/ABS/IPC/SDM |
|---|---:|---|---|---|---|---|
| Gen 5 | 2010–2011 | 3.6 V6 LLT | Bosch E69 | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |
| Gen 5 | 2012–2015 | 3.6 V6 LFX | ACDelco E39 / E39A | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |
| Gen 5 | 2010–2015 | 6.2 V8 LS3 | ACDelco E38 | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |
| Gen 5 | 2010–2015 | 6.2 V8 L99 | ACDelco E38 | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |
| Gen 5 | 2012–2015 | 6.2 V8 LSA compressé | ACDelco E67 | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |
| Gen 5 | 2014–2015 | 7.0 V8 LS7 | ACDelco E38 | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |
| Gen 6 | 2016–2023 | 2.0 turbo LTG | GM E80 (à confirmer par référence matérielle et CAL ID) | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |
| Gen 6 | 2016–2024 | 3.6 V6 LGX | GM E92 / E93 selon variante | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |
| Gen 6 | 2016–2024 | 6.2 V8 LT1 | GM E92 | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |
| Gen 6 | 2017–2024 | 6.2 V8 LT4 compressé | GM E92 | Oui, selon PIDs | Conditionnel à l'année, DID, CAN ID et interface | Non |

### État des calculateurs

| Calculateur | Disponibilité |
|---|---|
| ECM / PCM | OBD-II standard ; UDS spécifique seulement avec interface, DID et adressage compatibles |
| TCM | Certains signaux communautaires peuvent répondre ; pas de scan exhaustif validé |
| BCM | Diagnostic GM étendu exhaustif non disponible |
| EBCM / ABS | Diagnostic GM étendu exhaustif non disponible |
| IPC | Diagnostic GM étendu exhaustif non disponible |
| SDM / airbag | Diagnostic GM étendu exhaustif non disponible |
| Autres calculateurs | Non garantis ; aucune adresse ou DID n'est supposé |

Le catalogue OBDb est communautaire, non certifié par GM. Un signal répertorié n'est pas nécessairement présent sur chaque finition, marché, transmission ou calibration. Une absence de réponse n'est jamais remplacée par une valeur fictive.

## Télécharger la version Debug

Les APK Debug sont publiés comme artefacts des exécutions GitHub Actions (ils ne sont pas ajoutés aux Releases) :

1. Ouvrir https://github.com/Hellorheaven/CamaroDiag/actions.
2. Ouvrir la dernière exécution réussie de **Android CI** sur `main`.
3. Dans **Artifacts**, télécharger `CamaroDiag-debug-<commit>`.
4. Décompresser l'archive et installer `CamaroDiag-debug.apk`.

Chaque artefact est conservé 30 jours. Les Releases restent réservées aux APK Release et aux installateurs du pont Windows.

## Installation Android

Prérequis : Android 10 ou supérieur (API 29 à 36 déclarées), Bluetooth ou Wi-Fi selon l'adaptateur, et USB Host/OTG pour les interfaces USB.

1. Télécharger l'APK depuis https://github.com/Hellorheaven/CamaroDiag/releases.
2. Installer l'application ; autoriser si besoin l'installation depuis la source choisie.
3. Accorder les autorisations Bluetooth à proximité, USB ou réseau demandées par Android.
4. Brancher l'interface sur la prise diagnostic, mettre le contact selon les instructions du fabricant et sélectionner l'adaptateur.
5. Commencer par l'identification du véhicule et le scan OBD-II standard.

## Installation du pont Windows J2534

Le pont PC est facultatif et nécessaire uniquement pour utiliser un fournisseur J2534 installé sur Windows.

1. Installer Windows 10/11 et le pilote constructeur correspondant exactement au VCX SE/Nano ou à l'interface J2534.
2. Pour VXDIAG, installer VX Manager et vérifier que le fournisseur J2534 est enregistré.
3. Télécharger l'archive Windows depuis les releases ou artefacts CamaroDiag : https://github.com/Hellorheaven/CamaroDiag/actions.
4. Choisir x64 ou x86 selon l'architecture du DLL J2534.
5. Décompresser et lancer CamaroDiag.J2534Bridge.exe.
6. Sélectionner le fournisseur J2534 si plusieurs sont présents. Relever IP, port et jeton d'appairage.
7. Connecter le PC et Android au même réseau privé de confiance.
8. Dans l'espace ECU de CamaroDiag, saisir IP, port, jeton et CAN ID physiques documentés du calculateur.

Le pont est une passerelle J2534 générique : il ne fournit pas de pilote VXDIAG et ne remplace pas VX Manager. Il autorise uniquement la lecture UDS 0x22, 0x19 et TesterPresent 0x3E. Le SW-ISO15765 dépend du support J2534-2 réel du pilote.

Sécurité : le pont utilise TCP local sans TLS. Ne jamais exposer le port à Internet, configurer de redirection de port ou l'utiliser sur un Wi-Fi public. Limiter le pare-feu aux réseaux privés de confiance.

## Atelier ECU et fichiers de calibration

L'atelier permet l'analyse de fichiers locaux : import BIN/ORI/MOD, Intel HEX, Motorola S-record et ZIP ; empreinte SHA-256 ; comparaison binaire ; édition de tables avec définitions TSV vérifiées ; export d'une nouvelle image locale. Le fichier original est conservé.

Aucun pack de tables Camaro validé n'est fourni. Les définitions doivent correspondre exactement au VIN, au calculateur, au CAL ID/CVN, à l'OS et à l'image concernée. Les formats XDF/A2L/XML ou conteneurs propriétaires non décodés ne sont pas automatiquement convertis.

La lecture mémoire directe de l'ECM, l'écriture, le flashage, le recovery, les checksums GM et les procédures Seed/Key ne sont pas disponibles.

## Fonctions non disponibles

- Scan exhaustif garanti de BCM, TCM, EBCM/ABS, IPC, SDM et de tous les calculateurs GM.
- Commandes carrosserie non validées pour le VIN, BCM et calibration.
- Démarrage à distance générique, contournement antidémarrage ou dispositifs antivol.
- Écriture UDS, actionneurs, codage, programmation, lecture mémoire ou flashage GM.

## Origine des données et licences intégrées

Le catalogue de signaux communautaire est issu du projet **OBDb Chevrolet Camaro** (OBDb contributors), fichier `signalsets/v3/default.json`, dépôt https://github.com/OBDb/Chevrolet-Camaro, révision source `6771593698dff29cc0c6e8d6219ff360b2891883`. Il est distribué sous **Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)** : https://creativecommons.org/licenses/by-sa/4.0/. Les définitions, identifiants, filtres d'années et facteurs provenant de cette source restent soumis à cette licence ; toute adaptation distribuée doit conserver l'attribution et être partagée sous la même licence. Le texte intégral est fourni dans `third_party/obdb/LICENSE-CC-BY-SA-4.0.txt`. Ce jeu de données communautaire n'est ni produit ni certifié par GM.

Références utiles : OBDLink (https://support.obdsol.com/support/solutions/articles/43000713351-which-obdlink-adapter-is-right-for-me-), fiche MX+ (https://www.obdlink.com/products/obdlink-mxp/), guide GM MDI 2 (https://gsi.ext.gm.com/userguides/GM_MDI_2_User_Guide.pdf), guide GM TIS2Web (https://gsi.ext.gm.com/userguides/GM_TIS2Web_User_Guide.pdf). Ces documents généraux ne remplacent pas les informations de service GM correspondant au VIN.

CamaroDiag est un outil indépendant d'aide au diagnostic, sans certification GM. Ne pas effectuer de diagnostic ou d'opération lorsque le véhicule est en mouvement.
