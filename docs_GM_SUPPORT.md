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
| ELM327 SPP | Bluetooth classique | Oui selon qualité ; Mode 01/02/03/06/07/09 selon ECU | Non | Non garanti | ATI/AT@1, ATSP6 ; clones limités selon firmware |
| ELM/STN USB | USB OTG | Oui selon interface | Non | Non garanti |
| ELM/STN Wi-Fi | TCP/IP | Oui selon interface | Non | Non garanti |
| VXDIAG VCX SE | PC Windows + J2534 | Non via pont UDS | Oui, lecture UDS via pont | SW-ISO15765 si pilote J2534-2 compatible |
| VXDIAG VCX Nano | PC Windows + J2534 | Non via pont UDS | Oui selon variante/pilote | Dépend de la révision et du pilote |

Le lecteur UDS direct Android est actuellement destiné à l'OBDLink MX+ authentique compatible STN. Les interfaces VCX passent par le pont PC ; aucun pilote Android VXDIAG natif n'est inclus. La prise en charge de SW-CAN sur VCX Nano n'est pas présumée.

## Fonctions de diagnostic

| Fonction | État |
|---|---|
| VIN, CAL ID, CVN, nom ECU | Lorsque les services standards sont pris en charge et répondent |
| PIDs moteur normalisés | Mode 01, PIDs annoncés et retournés ; décodage des sondes lambda large bande 0x24–0x2B et 0x34–0x3B, pression de rail 0x22, températures catalyseur 0x3C–0x3F et autres PIDs implémentés |
| DTC confirmés / en attente / permanents | Modes 03 / 07 / 0A |
| Freeze-frame | Mode 02, selon données retournées |
| Readiness | Mode 01 |
| Tests embarqués | Mode 06 ; données brutes conservées lorsque le décodage dépend du véhicule |
| Signaux Camaro | Catalogue OBDb : 133 commandes et 136 définitions, filtrées par année et adressage |
| UDS | MX+ ou pont J2534, DID et CAN ID compatibles ; décodage générique des enregistrements DTC 24 bits et des 8 bits d'état UDS (0x19/0x02) |
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

Le catalogue de signaux communautaire est issu du projet **OBDb Chevrolet Camaro** (OBDb contributors), fichier `signalsets/v3/default.json`, dépôt https://github.com/OBDb/Chevrolet-Camaro, révision source `6771593698dff29cc0c6e8d6219ff360b2891883`. Il est distribué sous **Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)** : https://creativecommons.org/licenses/by-sa/4.0/. Les définitions, identifiants, filtres d'années et facteurs provenant de cette source restent soumis à cette licence ; toute adaptation distribuée doit conserver l'attribution et être partagée sous la même licence. Le texte intégral est fourni dans `third_party/obdb/LICENSE-CC-BY-SA-4.0.txt`. Ce jeu de données communautaire n'est ni produit ni certifié par GM.

Références générales : documentation OBDLink (https://support.obdsol.com/support/solutions/articles/43000713351-which-obdlink-adapter-is-right-for-me-), fiche MX+ (https://www.obdlink.com/products/obdlink-mxp/), GM MDI 2 (https://gsi.ext.gm.com/userguides/GM_MDI_2_User_Guide.pdf), GM TIS2Web (https://gsi.ext.gm.com/userguides/GM_TIS2Web_User_Guide.pdf). Les adresses et procédures détaillées doivent provenir des informations de service GM du VIN concerné.

CamaroDiag est un outil indépendant d'aide au diagnostic et ne constitue pas une certification GM.


## Décodage DTC UDS multi-calculateurs

Le décodeur commun `UdsDtcDecoder` interprète la réponse positive UDS `0x59 0x02` après retrait de l'en-tête de service par le client :
- conservation du DTC brut sur 24 bits, du masque de disponibilité et du `DTCFormatIdentifier` ;
- affichage SAE J2012-DA (format 0x00/0x04) sous la forme `P/C/B/Uxxxx-FTB` ; format ISO 14229 constructeur (0x01) et formats non pris en charge conservés en hexadécimal sans conversion supposée ;
- décodage individuel des huit bits d'état (défaut présent, cycle courant, pending, confirmé, tests non terminés/échoués et demande de voyant) ;
- rejet explicite des réponses tronquées ou mal alignées.

Ce décodage de transport est indépendant du calculateur et peut donc être appliqué aux réponses ECM/PCM, TCM, BCM, EBCM, IPC, SDM et autres calculateurs qui répondent réellement au service. Il ne fournit volontairement aucune définition de panne OEM : le libellé et la procédure de réparation nécessitent toujours un catalogue documenté pour le calculateur, le VIN, l'année, le moteur, la transmission et l'identité logicielle correspondants. Un DTC brut décodé n'est pas une preuve qu'un module ou une adresse présumée est présent.


### Familles de calculateurs à découvrir

La topologie Gen 5 et Gen 6 recense désormais, en plus des calculateurs principaux, les candidats optionnels suivants : HVAC, PSCM, RCDLR, RADIO/HMI, AMP, DDM, PDM, FPCM et Park Assist. Ils sont explicitement marqués `DISCOVERY_REQUIRED`, sans adresse TX/RX ni réseau présumé. Cette liste sert à structurer l'inventaire et les futurs scans ; elle ne prétend pas que tous ces modules sont montés sur chaque finition ou chaque année. La présence doit être confirmée par une réponse réelle et le VIN/RPO.


## Catalogue DTC multi-moteurs et multi-calculateurs

Le registre commun `DtcCatalogRegistry` couvre les RPO moteur déclarés dans le modèle véhicule : LLT, LFX, LS3, L99, LSA, LS7, LTG, LGX, LT1 et LT4. Il fournit les libellés SAE génériques disponibles et inventorie ECM, PCM, TCM, EBCM, BCM, IPC, SDM, HVAC, PSCM, RCDLR, RADIO, AMP, DDM, PDM, FPCM, Park Assist et HMI.

Le registre sépare explicitement :
- `SAE_J2012_GENERIC` : libellé normalisé du code, sans prétendre décrire la stratégie GM ;
- `GM_SERVICE_INFORMATION` / `GM_BULLETIN` : définition constructeur avec référence documentaire ;
- `VEHICLE_VALIDATED_CAPTURE` : résultat confirmé sur un véhicule identifié ;
- `UNVERIFIED` : donnée non exploitable pour une interprétation technique.

Une définition OEM ne peut être considérée comme exploitable qu'avec l'identification exacte du véhicule et du calculateur : VIN, année, RPO moteur, transmission, module répondant, référence matériel, OS, CAL ID, CVN et source technique traçable. Les profils UDS avancés existants appliquent également une validation d'identité et de preuve.

À ce stade, les interprétations détaillées moteur présentes dans `CamaroDtcCatalog` restent limitées au LFX Gen 5 2012–2015. Les autres moteurs disposent des libellés SAE génériques, pas de causes/procédures GM extrapolées depuis le LFX. Les calculateurs hors ECM restent au niveau catalogue de découverte tant que leurs DTC et procédures OEM n'ont pas de source vérifiable.

### Intégration des réponses UDS dans l'écran DTC

L'action de lecture UDS 0x19/0x02 utilise désormais le `DTCFormatIdentifier` avant de décoder les enregistrements. Les résultats sont convertis en `DiagnosticDtc` et ajoutés à la liste de diagnostic avec l'adresse de réponse affichée comme identité provisoire (par exemple `ECU 7E8`). Cette identité d'adresse n'est pas transformée en nom de calculateur sans découverte/validation séparée.

Les codes SAE reçoivent le libellé localisé déjà disponible dans les ressources Android. Les codes OEM inconnus restent affichés en hexadécimal avec leurs octets bruts et sans définition inventée. Le suffixe Failure Type Byte est conservé dans le code affiché ; la recherche du catalogue SAE utilise le code de base.

Les avertissements d'applicabilité moteur couvrent notamment les DTC cylindre 7/8 sur moteurs six cylindres et le code de sous-pression P0299 sur les configurations atmosphériques d'origine. Ces avertissements vérifient la cohérence de la configuration ; ils ne remplacent pas une définition de service GM.


Le registre contient également un profil d'architecture par RPO (nombre de cylindres, nombre de bancs, suralimentation, type d'injection et famille ECM). Ces données servent uniquement aux contrôles de cohérence et au routage du catalogue ; elles ne remplacent pas les identifiants matériel/OS/CAL ID/CVN réellement lus sur le calculateur.

L'effacement SAE Mode 04 est strictement séparé des résultats UDS améliorés : après confirmation du Mode 04, seuls les DTC standard ECM sont retirés de la liste. Les défauts UDS associés à d'autres calculateurs sont conservés, car aucune commande UDS d'effacement n'est envoyée. L'avertissement utilisateur sur cette portée est traduit dans les 14 langues de l'application.
