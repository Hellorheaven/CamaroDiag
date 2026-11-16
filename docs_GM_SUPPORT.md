# Guide de compatibilité GM — CamaroDiag

Ce guide décrit les fonctions disponibles, les interfaces compatibles et les limites de diagnostic sur Chevrolet Camaro Gen 5 et Gen 6.

## Véhicules catalogués

| Génération | Années | Moteur / RPO | Famille ECM indicative |
|---|---:|---|---|
| Gen 5 | 2010–2011 | 3.6 V6 LLT | Bosch E69 |
| Gen 5 | 2012–2015 | 3.6 V6 LFX | ACDelco E39 / E39A |
| Gen 5 | 2010–2015 | 6.2 V8 LS3 | ACDelco E38 |
| Gen 5 | 2010–2015 | 6.2 V8 L99 | ACDelco E38 |
| Gen 5 | 2012–2015 | 6.2 V8 LSA compressé | ACDelco E67 |
| Gen 5 | 2014–2015 | 7.0 V8 LS7 | ACDelco E38 |
| Gen 6 | 2016–2020 | 2.0 turbo LTG | GM E80, à confirmer |
| Gen 6 | 2016–2024 | 3.6 V6 LGX | GM E92 / E93 selon variante |
| Gen 6 | 2016–2024 | 6.2 V8 LT1 | GM E92 |
| Gen 6 | 2017–2024 | 6.2 V8 LT4 compressé | GM E92 |

Les familles ECM sont des indications de profil. Confirmer le numéro matériel, le VIN, CAL ID, CVN et OS avant toute interprétation avancée.

## Matrice des fonctions par calculateur

| Module | Fonction accessible | Limites |
|---|---|---|
| ECM / PCM | OBD-II standard ; UDS spécifique si interface, DID et adressage compatibles | Pas de lecture mémoire ni flash |
| TCM | Certains signaux OBDb peuvent être lus si le calculateur répond | Pas de scan exhaustif validé |
| BCM | Diagnostic GM étendu exhaustif non disponible | Aucun DID ou commande supposé |
| EBCM / ABS | Diagnostic GM étendu exhaustif non disponible | Pas de scan complet validé |
| IPC | Diagnostic GM étendu exhaustif non disponible | Pas de kilométrage intermodule garanti |
| SDM / airbag | Diagnostic GM étendu exhaustif non disponible | Aucune commande de sécurité |
| Autres modules | Selon réponses réelles et définitions documentées | Pas d'adresses arbitraires |

## Matrice des fonctions par interface

| Interface | Liaison | OBD-II standard | UDS / signaux OBDb | SW-CAN / GMLAN |
|---|---|---|---|---|
| OBDLink MX+ STN authentique | Bluetooth SPP | Oui | Oui, lecture conditionnelle | Support matériel ; accès selon profil |
| OBDLink MX | Bluetooth SPP | Oui | Non activé | Selon modèle |
| OBDLink LX | Bluetooth SPP | Oui | Non | Non annoncé |
| OBDLink EX / SX | USB série | Oui | Non activé | Selon modèle |
| OBDLink CX | Bluetooth LE | Non pour Camaro/GM | Non | Non |
| ELM327 SPP | Bluetooth classique | Oui selon qualité | Non | Non garanti |
| ELM/STN USB | USB OTG | Oui selon interface | Non | Non garanti |
| ELM/STN Wi-Fi | TCP/IP | Oui selon interface | Non | Non garanti |
| VXDIAG VCX SE | PC Windows + J2534 | Non via pont UDS | Oui, lecture UDS via pont | SW-ISO15765 si pilote J2534-2 compatible |
| VXDIAG VCX Nano | PC Windows + J2534 | Non via pont UDS | Oui selon variante/pilote | Dépend de la révision et du pilote |

Le lecteur UDS direct Android est actuellement destiné à l'OBDLink MX+ authentique compatible STN. Les interfaces VCX passent par le pont PC ; aucun pilote Android VXDIAG natif n'est inclus. La prise en charge de SW-CAN sur VCX Nano n'est pas présumée.

## Fonctions de diagnostic

| Fonction | État |
|---|---|
| VIN, CAL ID, CVN, nom ECU | Lorsque les services standards sont pris en charge et répondent |
| PIDs moteur normalisés | Mode 01, PIDs annoncés et retournés |
| DTC confirmés / en attente / permanents | Modes 03 / 07 / 0A |
| Freeze-frame | Mode 02, selon données retournées |
| Readiness | Mode 01 |
| Tests embarqués | Mode 06 ; données brutes conservées lorsque le décodage dépend du véhicule |
| Signaux Camaro | Catalogue OBDb : 133 commandes et 136 définitions, filtrées par année et adressage |
| UDS | MX+ ou pont J2534, DID et CAN ID compatibles |
| Audit kilométrique | Comparaison de sources vérifiées ; pas de valeur inventée |
| Télémétrie | Enregistrement et export CSV |

Les signaux OBDb comprennent notamment températures, pressions, ratés, knock retard, injection, transmission, vitesses de roues et TPMS. Ils sont communautaires et ne sont pas certifiés par GM.

## Atelier de fichiers ECU

L'atelier permet l'import et l'analyse hors véhicule de BIN/ORI/MOD, Intel HEX, Motorola S-record et ZIP, l'empreinte SHA-256, la comparaison binaire et l'édition locale à l'aide de définitions TSV vérifiées. Il n'écrit pas dans le calculateur.

Aucun pack de tables Camaro validé n'est fourni. Les définitions doivent correspondre à l'image et à l'identité ECU exacte. Les formats propriétaires importés ne sont pas automatiquement décodés.

La lecture mémoire directe, l'écriture, le flashage, le recovery, les checksums GM et les procédures Seed/Key ne sont pas disponibles.

## Installation Android

1. Android 10 ou supérieur (API 29 à 36 déclarées).
2. Télécharger l'APK depuis https://github.com/Hellorheaven/CamaroDiag/releases.
3. Autoriser les permissions Bluetooth, USB ou réseau demandées.
4. Connecter l'adaptateur et effectuer d'abord un scan OBD-II standard.

## Installation du pont J2534 Windows

1. Windows 10/11 et pilote constructeur correspondant à l'interface exacte.
2. Pour VXDIAG, installer VX Manager et vérifier le fournisseur J2534.
3. Télécharger l'archive du pont depuis https://github.com/Hellorheaven/CamaroDiag/actions.
4. Choisir x86 ou x64 selon l'architecture du DLL J2534.
5. Lancer CamaroDiag.J2534Bridge.exe, sélectionner le fournisseur si nécessaire.
6. Relever IP, port et jeton affichés ; connecter Android et PC au même réseau privé.
7. Saisir ces valeurs et les CAN ID physiques documentés dans CamaroDiag.

Le pont supporte HS-CAN ISO15765 250/500 kbit/s, 11/29 bits. SW-ISO15765 GMLAN 62,5 kbit/s est conditionnel au support J2534-2 du fournisseur. Les services autorisés sont UDS 0x22, 0x19 et 0x3E. Écritures, routines, effacement UDS, actionneurs, accès sécurité et programmation sont bloqués.

Le pont utilise TCP sans TLS. Ne pas l'exposer à Internet ni l'utiliser sur un réseau public. L'architecture du processus doit correspondre à celle du DLL fournisseur.

## Données communautaires et références

Le catalogue de signaux provient de OBDB Chevrolet Camaro (https://github.com/OBDb/Chevrolet-Camaro), fichier signalsets/v3/default.json, commit 6771593698dff29cc0c6e8d6219ff360b2891883. Licence CC BY-SA 4.0 ; le texte intégral est conservé dans third_party/obdb/. Les définitions originales sont conservées ; les données ne sont pas certifiées par GM.

OpenVCX (https://github.com/erik683/OpenVCX) a été consulté comme référence J2534 pour certaines variantes VCX Nano, sans code ou binaire redistribué. monitoramento_Carro (https://github.com/Carlosh007/monitoramento_Carro) a été consulté comme inspiration d'interface ; aucun code n'est repris.

Références générales : documentation OBDLink (https://support.obdsol.com/support/solutions/articles/43000713351-which-obdlink-adapter-is-right-for-me-), fiche MX+ (https://www.obdlink.com/products/obdlink-mxp/), GM MDI 2 (https://gsi.ext.gm.com/userguides/GM_MDI_2_User_Guide.pdf), GM TIS2Web (https://gsi.ext.gm.com/userguides/GM_TIS2Web_User_Guide.pdf). Les adresses et procédures détaillées doivent provenir des informations de service GM du VIN concerné.

CamaroDiag est un outil indépendant d'aide au diagnostic et ne constitue pas une certification GM.
