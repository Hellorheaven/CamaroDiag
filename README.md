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
- Découverte USB/OTG directe du prototype CamaroDiagConnectors 0.1.0, sans pont PC (identification et capacités uniquement ; aucune communication ECU disponible sur ce prototype).
- Les commandes carrosserie non validées et les fonctions de programmation restent verrouillées.

## Compte utilisateur et connexion

À l'ouverture de CamaroDiag, un compte est demandé avant d'accéder au diagnostic. Deux méthodes de connexion sont proposées : **Google** ou **adresse e-mail et mot de passe**.

- Pour un nouveau compte e-mail, confirmer l'adresse avec le message reçu. L'écran de vérification permet de contrôler la confirmation et de demander un nouvel envoi.
- En cas d'oubli, demander la réinitialisation du mot de passe depuis l'écran de connexion.
- Une fois connecté, ouvrir **Mon compte** pour voir l'adresse, le fournisseur, ajouter l'autre mode de connexion lorsqu'il utilise la même adresse, se déconnecter ou demander la suppression du compte.
- La première connexion nécessite Internet. Une session déjà vérifiée en ligne sur cet appareil peut poursuivre le diagnostic sans réseau pendant **7 jours maximum** après la dernière validation réussie. Passé ce délai, l'application demande une connexion Internet. La révocation par Firebase ne peut être vérifiée tant que l'appareil est hors ligne.
- Les véhicules et historiques enregistrés sont séparés localement par compte Firebase. Les anciennes données enregistrées avant l'ajout des comptes ne sont pas affectées automatiquement à un utilisateur : leur importation se demande explicitement dans les paramètres du véhicule.

**Limites actuelles :** aucune synchronisation automatique des véhicules ou historiques entre appareils. Les fichiers exportés hors de CamaroDiag et les copies conservées par l'utilisateur ne sont pas supprimés lors de la suppression du compte. Firebase peut demander une connexion récente pour autoriser la suppression. Les opérations de diagnostic sensibles restent soumises à leurs contrôles propres, indépendamment du compte.

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
| CamaroDiagConnectors 0.1.0 | USB-C / OTG | Non | Non | Non | Découverte uniquement, sans ordinateur ; à tester sur carte isolée |
| VXDIAG VCX SE | PC Windows + J2534 | Non via le pont UDS | Oui, lecture UDS via pont | SW-ISO15765 si le pilote J2534-2 l'expose | Pilote Windows exact requis ; pas de pilote Android natif |
| VXDIAG VCX Nano | PC Windows + J2534 | Non via le pont UDS | Oui, lecture UDS via pont selon variante | Dépend révision, câblage et pilote | SW-CAN non présumé ; compatibilité à confirmer |

La connexion d'un adaptateur ne garantit pas l'accès à tous les réseaux GM. Les fonctions dépendent de la référence, révision, firmware, broches DLC, pilote et réponses du calculateur.

## CamaroDiagConnectors — connexion native Android

Le futur [CamaroDiagConnectors](https://github.com/Hellorheaven/CamaroDiagConnectors) est développé séparément. Il communiquera directement avec Android, sans ordinateur ni pont Windows obligatoire. Les interfaces J2534 tierces existantes continuent à utiliser leur propre chemin de connexion.

Pour la version prototype 0.1.0, la seule fonction disponible est la découverte USB de la carte isolée, **sans raccordement au véhicule ni à une mémoire** :

1. Relier le prototype USB au téléphone compatible USB Host/OTG.
2. Ouvrir Adaptateurs et actualiser les périphériques USB.
3. Sélectionner **CamaroDiagConnectors • USB ASCII** pour le firmware normal, ou **CamaroDiagConnectors • USB CD/1** uniquement pour la variante binaire expérimentale. Autoriser l'accès USB dans Android ; si nécessaire, sélectionner de nouveau le bouton.
4. Consulter la version et les capacités retournées. En mode CD/1, une erreur de CRC, version ou séquence arrête la découverte sans repli ASCII. Le CRC ne constitue pas une authentification. Le diagnostic doit rester indisponible : la liaison de découverte est refermée à la fin de cette opération.

Le mode CD/1 exige un seul port série CDC ACM compatible, sans sélection arbitraire de port. Redémarrer uniquement le prototype isolé entre découvertes binaires, conformément à sa documentation. Pour Windows, l'outil autonome de découverte est disponible dans le dépôt CamaroDiagConnectors, indépendamment du pont J2534 ; il ne s'agit pas d'un pilote de programmation.

Le mode CD/1 est prévu pour les essais sur carte isolée, conformément au [guide USB binaire](https://github.com/Hellorheaven/CamaroDiagConnectors/blob/e8593d29c711e7d09f3bb00b69f467f529f75647/docs/user/usb-binary-discovery.md). Aucun mode ne permet de communiquer avec un ECU via ce prototype.

Les futurs modes CAN, BENCH, BOOT/BAM, BDM, JTAG/Nexus, SWD et EEPROM ISP/chip-off nécessitent un firmware et du matériel réellement compatibles et qualifiés. Voir les [exigences du connecteur](https://github.com/Hellorheaven/CamaroDiagConnectors/issues/7). Aucun mode de programmation n'est activé à partir d'une réponse de découverte.

### Préparation du raccordement sur banc

Dans ECU/Maps → Expert, les neuf modes de connexion sont des choix de préparation, pas des capacités matérielles actives. SWD est distinct de BDM et JTAG/Nexus. Les boutons CamaroDiagConnectors donnent accès au guide générique de câblage et à la fiche correspondant au modèle, à l'année et à la famille ECU saisie si une fiche existe ; sinon le catalogue est proposé. E39A dispose d'une fiche indépendante non qualifiée. Les documents de référence sont figés sur la révision revue, et chaque écran indique que le raccordement n'est pas vérifié.

Le boîtier est prévu pour une alimentation logique USB 5 V protégée. L'alimentation stabilisée externe de banc est dédiée au calculateur, avec protection et limitation adaptées au faisceau. Les opérations critiques devront disposer d'une alimentation logique maintenue indépendamment de la liaison USB ; les puces externes nécessitent leur propre VCC conforme à la référence exacte. Aucun brochage, courant ou tension de programmation universel n'est déduit. Ne raccorder aucune cible au prototype actuel.

Voir [le suivi des fiches](https://github.com/Hellorheaven/CamaroDiagConnectors/issues/10). Les fiches documentaires ne valident ni le faisceau, ni le firmware, ni la récupération après interruption.

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

## Installer CamaroDiag sur Android

CamaroDiag nécessite Android 10 ou supérieur. Selon l'adaptateur choisi, le téléphone ou la tablette doit disposer de Bluetooth, de Wi-Fi ou d'un port USB Host/OTG.

1. Télécharger l'APK CamaroDiag depuis les [versions publiées](https://github.com/Hellorheaven/CamaroDiag/releases/latest).
2. Installer l'APK et accepter les autorisations demandées par Android pour l'adaptateur choisi.
3. Brancher l'adaptateur à la prise diagnostic du véhicule, puis mettre le contact selon la procédure adaptée.
4. Ouvrir CamaroDiag, sélectionner le véhicule et le type d'interface.
5. Vérifier la connexion et commencer par la lecture de l'identification ou un diagnostic OBD-II standard.

Les compatibilités indiquées dans les tableaux ci-dessus sont conditionnelles : un adaptateur reconnu ne garantit pas l'accès à chaque calculateur.

## Installer le pont PC Windows (facultatif)

Le pont PC permet d'utiliser un fournisseur J2534 sous Windows 10 ou 11, notamment certaines interfaces VXDIAG VCX SE ou VCX Nano lorsque leur modèle et leur pilote sont compatibles.

1. Installer d'abord le pilote constructeur J2534 correspondant à l'interface (et VX Manager pour les modèles VXDIAG qui le nécessitent).
2. Télécharger depuis les [versions CamaroDiag](https://github.com/Hellorheaven/CamaroDiag/releases/latest) le programme d'installation Windows x86 ou x64 correspondant au pilote J2534.
3. Exécuter l'installateur, puis lancer **CamaroDiag J2534 Bridge** depuis le menu Démarrer.
4. Choisir l'interface et relever l'adresse IP, le port et **le code d'appairage TLS complet** affiché (`jeton.empreinte_SHA256`). Le code change à chaque redémarrage du pont.
5. Relier Android et le PC au même réseau privé, puis renseigner ces informations et les identifiants CAN documentés du calculateur dans CamaroDiag.

La connexion J2534 autorise les lectures UDS et le maintien de session lorsqu'ils sont pris en charge. Elle ne rend pas disponibles le flashage, les commandes constructeur non vérifiées ni tous les réseaux GM.

**Sécurité :** la connexion Android–J2534 exige TLS 1.2/1.3 et vérifie l'empreinte SHA-256 du certificat du pont avant toute transmission du jeton ou de trames ECU. Les anciens clients TCP et les jetons seuls sont refusés. Utiliser exclusivement un réseau local privé et de confiance ; ne jamais exposer le port à Internet ni utiliser le pont sur un réseau Wi-Fi public.

Pour les étapes détaillées, consulter le [guide d'installation du pont PC](pc-bridge/README.md).

## Atelier ECU et fichiers de calibration

L'atelier permet l'analyse de fichiers locaux : import BIN/ORI/MOD, Intel HEX, Motorola S-record et ZIP ; empreinte SHA-256 ; comparaison binaire ; édition de tables avec définitions TSV vérifiées ; export d'une nouvelle image locale. Le fichier original est conservé.

Aucun pack de tables Camaro validé n'est fourni. Les définitions doivent correspondre exactement au VIN, au calculateur, au CAL ID/CVN, à l'OS et à l'image concernée. Les formats XDF/A2L/XML ou conteneurs propriétaires non décodés ne sont pas automatiquement convertis.


La **lecture physique limitée d'une plage mémoire ECU** est disponible via le pont PC J2534, uniquement avec un profil de plage documenté configuré sur Windows, une correspondance VIN vérifiée et deux acquisitions concordantes. Il ne s'agit **ni d'un dump complet garanti ni d'une sauvegarde restaurable**. La lecture complète universelle de l'ECM, l'écriture physique, le flashage, le recovery, les checksums GM et les procédures Seed/Key ne sont pas disponibles.

## Fonctions non disponibles

- Scan exhaustif garanti de BCM, TCM, EBCM/ABS, IPC, SDM et de tous les calculateurs GM.
- Commandes carrosserie non validées pour le VIN, BCM et calibration.
- Démarrage à distance générique, contournement antidémarrage ou dispositifs antivol.
- Écriture UDS, actionneurs, codage, programmation, lecture mémoire **hors profil autorisé**, dump complet garanti ou flashage GM.

## Origine des données et licences intégrées

Le catalogue de signaux communautaire est issu du projet **OBDb Chevrolet Camaro** (OBDb contributors), fichier `signalsets/v3/default.json`, dépôt https://github.com/OBDb/Chevrolet-Camaro, révision source `6771593698dff29cc0c6e8d6219ff360b2891883`. Il est distribué sous **Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)** : https://creativecommons.org/licenses/by-sa/4.0/. Les définitions, identifiants, filtres d'années et facteurs provenant de cette source restent soumis à cette licence ; toute adaptation distribuée doit conserver l'attribution et être partagée sous la même licence. Le texte intégral est fourni dans `third_party/obdb/LICENSE-CC-BY-SA-4.0.txt`. Ce jeu de données communautaire n'est ni produit ni certifié par GM.

Références utiles : OBDLink (https://support.obdsol.com/support/solutions/articles/43000713351-which-obdlink-adapter-is-right-for-me-), fiche MX+ (https://www.obdlink.com/products/obdlink-mxp/), guide GM MDI 2 (https://gsi.ext.gm.com/userguides/GM_MDI_2_User_Guide.pdf), guide GM TIS2Web (https://gsi.ext.gm.com/userguides/GM_TIS2Web_User_Guide.pdf). Ces documents généraux ne remplacent pas les informations de service GM correspondant au VIN.

CamaroDiag est un outil indépendant d'aide au diagnostic, sans certification GM. Ne pas effectuer de diagnostic ou d'opération lorsque le véhicule est en mouvement.


## Guides utilisateur

- [Compatibilité Camaro et adaptateurs](docs_GM_SUPPORT.md)
- [Utiliser un adaptateur ELM327](docs/ELM327_OFFLINE_TESTING.md)
- [Analyser une capture CAN ou UDS](docs/CAN_TRACE_ANALYZER.md)
- [Comprendre les signaux spécifiques Camaro](docs/NATIVE_CAMARO_SIGNAL_CATALOG.md)
- [Nouveautés de la branche de développement](docs/CHANGELOG_PR35.md)
