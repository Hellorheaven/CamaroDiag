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
- affichage SAE J2012-DA format 0x00 sous la forme `P/C/B/Uxxxx-FTB` ; format 0x04 conservé en hexadécimal tant que sa table de base distincte n'est pas intégrée ; format ISO 14229 constructeur (0x01) et autres formats non décodés conservés en hexadécimal sans conversion supposée ;
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


Le format SAE J2012-DA 0x04 n'est pas interchangeable avec le format 0x00 : les deux octets de base utilisent des listes de décodage distinctes. Le décodeur garde donc le 0x04 brut plutôt que de produire un faux code P/C/B/U. Références : SAE J2012 (https://saemobilus.sae.org/standards/j2012_201612-diagnostic-trouble-code-definitions) et ISO 27145-2 (https://www.iteh.fr/catalog/standards/iso/16cfed3f-fea5-4b18-9193-e58f635fd493/iso-27145-2-2012).


## Données de surveillance GM documentées – Camaro LFX 2012

Le catalogue d'évidence `Gm2012LfxMonitorCatalog` ajoute les stratégies de surveillance moteur documentées dans le dossier GM **12OBDG05B Engine Diagnostics** pour le groupe OBD 5B. Le résumé officiel 2012 associe explicitement le groupe 5B au moteur 3.6L RPO LFX et aux Camaro (coupé et cabriolet), avec les RPO de transmission MV5/MYB.

Codes couverts par cette première extraction : P0010, P0011, P0013, P0014, P0016, P0017, P0018, P0019, P0020, P0021, P0023, P0024, P0300 et P0420. Pour chaque code, l'application affiche la stratégie de surveillance, le critère de déclenchement, les conditions d'activation, le compteur d'échecs et la classification MIL tels que synthétisés depuis le dossier GM.

**Limite importante :** ces données sont des critères de surveillance OBD de certification 2012, pas l'intégralité des arbres de diagnostic de réparation SI. Elles ne remplacent pas la vérification VIN, RPO de transmission, CAL ID, CVN, OS et matériel. Elles ne sont affichées que pour un profil LFX de l'année modèle 2012 ; elles ne sont pas extrapolées aux LFX 2013–2015 ni aux autres motorisations.

Références primaires :
- GM, *12 OBDG05B Engine Diagnostics*, notamment pages 1–16 : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2012/12OBDG05B%20Engine%20Diagnostics.pdf
- GM, *2012 OBD Group Summary*, tableau groupe 5B : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2012/2012_OBD_GROUP_SUMMARY.pdf

Le catalogue générique SAE a également été complété pour les codes d'actionnement et de corrélation d'arbres à cames manquants (P0013, P0017–P0019, P0020–P0021, P0023–P0024) ainsi que les circuits de chauffage de sondes P0030, P0036 et P0050. Ces libellés sont normalisés SAE ; les critères GM détaillés ne sont affichés que lorsqu'une entrée d'évidence dédiée existe.

Le catalogue d'interprétations LFX FR/EN couvre désormais également les quatre circuits d'actionneurs P0010/P0013/P0020/P0023 et les quatre défauts de performance de déphasage P0011/P0014/P0021/P0024. Les causes restent présentées comme pistes à vérifier ; aucun de ces codes ne déclenche un remplacement automatique de pièce.

Les critères GM 2012 du chauffage des sondes à oxygène ont aussi été intégrés pour P0030, P0036 et P0050 (surveillance électrique du driver) ainsi que P0053, P0054, P0059 et P0060 (résistance du chauffage hors plage 6,1–13,4 Ω). Les quatre derniers moniteurs sont exécutés lors d'un démarrage à froid valide ; l'application affiche cette condition au lieu de présenter le test comme actif en permanence.

La couverture de surveillance GM a été étendue aux mesures d'air et aux corrections carburant : P0101, P0102, P0103, P0128, P0171, P0172, P0174 et P0175. Les critères distinguent les fréquences MAF, les conditions de montée en température, les corrections LTFT/STFT et les tests EVAP intrusifs de purge associés aux défauts de richesse. Les seuils de correction sont conservés sous leur forme de métriques calibrées GM et ne sont pas convertis abusivement en pourcentages génériques.

La surveillance de l'injection directe est maintenant documentée pour P0089 (limites d'autorité de commande de pompe haute pression), P0191 (cohérence du capteur de pression de rampe) et P00C6 (montée en pression pendant le démarrage). P00C6 est volontairement limité au millésime 2012 dans le catalogue détaillé tant que les groupes de certification des années suivantes n'ont pas été vérifiés.


## Données de surveillance GM – Camaro LFX 2013 et 2014

La couverture LFX ne réutilise plus les seuils 2012 pour les années suivantes. Deux documents GM distincts ont été vérifiés :
- 2013 : groupe OBD 5A, RPO LFX, Camaro, dossier 13OBDG05A Engine Diagnostics.
- 2014 : groupe OBD 6A, RPO LFX, Camaro, dossier 14OBDG06A Engine Diagnostics.

Le catalogue Gm2013And2014LfxMonitorCatalog ajoute 12 moniteurs de distribution (P0010, P0011, P0013, P0014, P0016, P0017, P0018, P0019, P0020, P0021, P0023, P0024). Les seuils de performance des arbres à cames sont de 50/150 échantillons pour P0011/P0021 et de 100/300 pour P0014/P0024 ; ils sont distincts de ceux du dossier 2012. Les seuils de corrélation P0016–P0019 sont également ceux du groupe 2013/2014, avec les conditions du diagnostic de pignon intermédiaire.

Références primaires :
- GM, 2013 OBD Group Summary : https://gsi.ext.gm.com/gmspo/mode6/pdf/2013/2013%20OBD%20GROUP%20SUMMARY.pdf
- GM, 13OBDG05A Engine Diagnostics : https://gsi.ext.gm.com/gmspo/mode6/pdf/2013/13OBDG05A%20Engine%20Diagnostics.pdf
- GM, 2014 OBD Group Summary : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2014/2014%20OBD%20GROUP%20SUMMARY.pdf
- GM, 14OBDG06A Engine Diagnostics : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2014/14OBDG06A%20Engine%20Diagnostics.pdf

Ces données restent limitées aux profils LFX 2013 et 2014. Le résumé GM 2015 confirme désormais l'association du Camaro LFX (transmissions MYB/MV5) au groupe 6 et au dossier moteur 15OBDG06 ; le registre expose cette provenance. Les critères de surveillance 2015 ne sont toutefois pas intégrés : aucun seuil 2013/2014 n'est extrapolé au LFX 2015 tant que le dossier moteur 15OBDG06 n'a pas été extrait et vérifié. Référence primaire : GM, 2015 OBD Group Summary, page 2 : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2015/2015_OBD_GROUP_SUMMARY.pdf.


## Données de surveillance GM – Camaro LLT 2010 et 2011

Les profils LLT sont désormais associés à leurs propres dossiers de certification, sans reprendre les seuils LFX :
- 2010 Camaro 3.6L LLT : groupe OBD 2, dossier 10OBDG02 Engine Diagnostics.
- 2011 Camaro 3.6L LLT : groupe OBD 6 SIDI, dossier 11OBDG06 Engine Diagnostics.

Pour chaque année, 18 moniteurs moteur sont disponibles : P0008/P0009 (pignons intermédiaires), P000A–P000D (réponse lente des arbres à cames), P0010/P0013/P0020/P0023 (circuits d'actionneurs), P0011/P0014/P0021/P0024 (cohérence du déphasage) et P0016–P0019 (alignement vilebrequin/arbre à cames).

Les paramètres restent strictement annuels : par exemple, le seuil de réponse lente et les critères de cohérence des arbres à cames ne sont pas identiques entre 2010 et 2011. L'application affiche le dossier GM correspondant au millésime.

Références primaires :
- GM, 2010 OBD Grouping Summary : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2010/2010%20OBD%20Grouping%20Summary.pdf
- GM, 10OBDG02 Engine Diagnostics : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2010/10OBDG02%20Engine%20Diagnostics.pdf
- GM, 2011 OBD Group Summary : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2011/2011%20OBD%20GROUP%20SUMMARY.pdf
- GM, 11OBDG06 Engine Diagnostics : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2011/11OBDG06%20Engine%20Diagnostics.pdf

Les paramètres ci-dessus décrivent les moniteurs de certification OBD. Ils ne remplacent pas les arbres de diagnostic de réparation SI ni la validation par VIN/CAL ID.

Le catalogue d'interprétations FR/EN LLT 2010–2011 couvre maintenant les 18 codes moteur de surveillance de distribution listés dans les dossiers GM 10OBDG02 et 11OBDG06 : P0008/P0009, P000A–P000D, P0010/P0011/P0013/P0014, P0016–P0019 et P0020/P0021/P0023/P0024. Les causes sont des pistes de diagnostic, pas une condamnation automatique d'une pièce.


## Données de surveillance GM – V8 Camaro 2010 et 2011

Les moteurs V8 sont maintenant séparés par RPO et architecture :
- L99 6.2L automatique : VVT et AFM ; moniteurs VVT P0010, P0011, P0013, P0014 et corrélation P0016/P0017.
- LS3 6.2L manuel : pas de VVT d'origine ; seuls les moniteurs de corrélation P0016/P0017 sont exposés dans cette première extraction.

Les groupes GM sont 10OBDG12 pour 2010 et 11OBDG07 pour 2011. Les critères de surveillance et seuils d'alimentation sont propres à chaque année ; les seuils de position d'arbre à cames issus des tables de calibration ne sont pas remplacés par une valeur inventée.

Références primaires :
- GM, 2010 OBD Grouping Summary : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2010/2010%20OBD%20Grouping%20Summary.pdf
- GM, 10OBDG12 Engine Diagnostics : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2010/10OBDG12%20Engine%20Diagnostics.pdf
- GM, 2011 OBD Group Summary : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2011/2011%20OBD%20GROUP%20SUMMARY.pdf
- GM, 11OBDG07 Engine Diagnostics : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2011/11OBDG07%20Engine%20Diagnostics.pdf

Le LS3 et le L99 partagent une base mécanique mais pas l'équipement VVT/AFM. Le catalogue n'affiche donc pas P0010/P0011/P0013/P0014 sur un LS3 d'origine.


## Données GM 2012 – V8 L99, LS3 et LSA

Le dossier de certification 12OBDG07 couvre les Camaro 2012 équipées des RPO L99, LS3 et LSA. Les catalogues distinguent les équipements :
- L99 automatique : VVT ; P0010/P0011/P0013/P0014 et P0016.
- LS3 manuel : pas de VVT ; P0016 uniquement parmi les moniteurs de distribution.
- LSA suralimenté : pas de VVT d'origine ; P0016 et modèle MAF suralimenté.

Les trois profils utilisent les moniteurs de chauffage des sondes à oxygène du groupe 12OBDG07. Les plages de résistance sont propres à la position : 2,8–9,5 Ω pour les sondes 1 et 4,1–10,8 Ω pour les sondes 2. Le seuil MAF est également distinct : atmosphérique (12 g/s et 15 kPa) contre LSA suralimenté (21 g/s et 22 kPa, avec critères MAP/SCIAP supplémentaires).

Références primaires :
- GM, 2012 OBD Group Summary : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2012/2012%20OBD_GROUP_SUMMARY.pdf
- GM, 12OBDG07 Engine Diagnostics : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2012/12OBDG07%20Engine%20Diagnostics.pdf
- GM Service Information, Camaro V8 2012 P0016 : https://charm.li/Chevrolet/2012/Camaro%20V8-6.2L/Repair%20and%20Diagnosis/A%20L%20L%20%20Diagnostic%20Trouble%20Codes%20%28%20DTC%20%29/Testing%20and%20Inspection/P%20Code%20Charts/P0016/
- GM Service Information, Camaro V8 2011 P0011 (L99 VVT) : https://charm.li/Chevrolet/2011/Camaro%20V8-6.2L/Repair%20and%20Diagnosis/A%20L%20L%20%20Diagnostic%20Trouble%20Codes%20%28%20DTC%20%29/Testing%20and%20Inspection/P%20Code%20Charts/P0011/

Les codes P0017 et autres défauts de VVT ne sont pas proposés aux V8 à arbre à cames unique sans preuve d'applicabilité.


## Groupe de certification V8 Camaro 2013 – association documentaire

Le résumé GM 2013 associe les Camaro L99, LS3 et LSA au groupe 7 et au dossier moteur identifié `13OBDG07`. Cette provenance est exposée par `GmV8MonitorCatalog.sourceLabel` sans réutiliser les seuils 2012. Les critères de surveillance 2013 ne sont pas encore intégrés à un catalogue moteur dédié ; les définitions détaillées restent donc indisponibles pour ces profils.

Référence GM primaire : 2013 OBD Group Summary, page 2 : https://gsi.ext.gm.com/gmspo/mode6/pdf/2013/2013%20OBD%20GROUP%20SUMMARY.pdf.

## Groupes de certification V8 Camaro 2014–2015 – association documentaire

Les résumés GM des groupes OBD confirment les associations suivantes pour le groupe 7 :
- 2014 : L99 Camaro, LSA Camaro, LS3 Camaro et LS7 Camaro → `14OBDG07`.
- 2015 : L99 Camaro, LSA Camaro, LS3 Camaro et LS7 Camaro → `15OBDG07`.

Ces associations sont enregistrées dans `GmV8MonitorCatalog.sourceLabel`, y compris pour le LS7. Elles attestent uniquement du dossier de certification à consulter : elles ne constituent pas des définitions DTC. Aucun moniteur LS7 2014–2015 n'est exposé tant que les critères du dossier moteur correspondant n'ont pas été extraits et vérifiés. Les résultats de groupe ne sont pas extrapolés aux années 2010–2013.

Références GM primaires :
- 2014 OBD Group Summary, groupe 7 : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2014/2014%20OBD%20GROUP%20SUMMARY.pdf
- 2015 OBD Group Summary, groupe 7 : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2015/2015_OBD_GROUP_SUMMARY.pdf


## Groupes de certification Gen 6 – Camaro 2016 et 2017

Les résumés GM permettent d'associer les premières motorisations Gen 6 à leurs dossiers de certification :
- 2016 : LTG Camaro → `16OBDG03` ; LGX Camaro → `16OBDG05` ; LT1 Camaro → `16OBDG07A`.
- 2017 : LTG, LGX, LT1 et LT4 Camaro → `17OBDG03`.
- 2018 : LTG, LGX, LT1 et LT4 Camaro → `18OBDG03A`.
- 2019 : dossier `19OBDG03A`, avec tables ECM communes et section ECM dédiée LT4 Camaro.
- 2020 : LTG, LGX, LT1 et LT4 Camaro → `20OBDG03A`.
- 2021 : LTG → `21OBDG03A` ; LGX, LT1 et LT4 → `21OBDG03B`.
- 2022 : LTG → `22OBDG03A` ; LGX, LT1 et LT4 → `22OBDG03B`.
- 2023 : LTG → `23OBDG03A Part2` ; LGX, LT1 et LT4 → `23OBDG03B`.
- 2024 : LGX, LT1 et LT4 → `24OBDG03B Part 2` ; le LTG n'est pas présent dans la matrice Camaro consultée.

`GmGen6MonitorCatalog` expose ces références dans le registre commun. Le LT4 n'est pas associé à une Camaro 2016 dans le résumé GM consulté ; son premier groupe Camaro est documenté ici en 2017. Ces libellés sont des références de certification uniquement : aucune définition détaillée, aucun seuil de surveillance ni procédure de réparation Gen 6 n'est activé par leur seule présence. Les associations sont propres à chaque millésime et moteur ; aucune référence n'est extrapolée aux configurations non listées.

Références GM primaires :
- 2016 OBD Group Summary, pages 2–4 : https://gsi.ext.gm.com/gmspo/mode6/pdf/2016/2016_OBD_GROUP_SUMMARY.pdf
- 2017 OBD Group Summary, pages 2–5 : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2017/2017_OBD_GROUP_SUMMARY.pdf
- 2018 OBD Group Summary, pages 1–4 : https://gsi.ext.gm.com/gmspo/mode6/pdf/2018/2018_OBD_GROUP_SUMMARY.pdf
- 2019 19OBDG03A Diagnostics : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2019/19OBDG03A%20Diagnostics.pdf
- 2020 OBD Group Summary, pages 1–2 : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2020/2020_OBD_GROUP_SUMMARY.pdf
- 2021 OBD Group Summary, pages 1–3 : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2021/2021_OBD_GROUP_SUMMARY.pdf
- 2022 OBD Group Summary, pages 1–2 : https://gsi.ext.gm.com/gmspo/mode6/pdf/2022/2022_OBD_GROUP_SUMMARY.pdf
- 2023 OBD Group Summary, pages 1–2 : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2023/2023_OBD_GROUP_SUMMARY.pdf
- 2024 Engine & Transmission Diagnostic Parameter File Matrix, pages 1–2 : https://gsi.ext.gm.com/gmspo/mode6/pdf/2024/2024%20Engine%20%26%20Transmission%20Diagnostic%20Parameter%20File%20Matrix.pdf


Référence primaire 2019 :
- GM, 19OBDG03A Diagnostics, tables ECM communes et section « ECM Unique (LT4 Camaro) », notamment pages documentaires 1–10 et 603–608 : https://gsitlc.ext.gm.com/gmspo/mode6/pdf/2019/19OBDG03A%20Diagnostics.pdf
