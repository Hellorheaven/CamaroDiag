# CamaroDiag – intégration GM, diagnostic OBD-II et atelier calibration

## Matériel visé

| Interface | Liaison | État de l'intégration Android |
|---|---|---|
| ELM327 | Bluetooth SPP | Transport ELM ; capacités réelles déterminées par sondage |
| OBDLink / STN | Bluetooth | Transport existant ; accès limité aux commandes et protocoles réellement supportés |
| ELM / STN | USB série | Transport existant via usb-serial-for-android |
| ELM / STN | Wi-Fi TCP | Transport existant ; hôte/port configurables |
| VXDIAG VCX SE | USB-C | Profil matériel déclaré ; SDK/pilote Android constructeur requis |
| VXDIAG VCX SE | Wi-Fi | Profil matériel déclaré ; SDK/protocole constructeur requis |
| VXDIAG VCX NANO | USB | Variante matérielle distincte ; SDK/pilote Android constructeur requis |
| VXDIAG VCX NANO | Wi-Fi | Variante matérielle distincte ; SDK/protocole constructeur requis |

Les variantes VCX SE/NANO sont représentées dans la matrice. VxdiagProvider est un point d'intégration, pas un pilote J2534 effectif : MissingVxdiagProvider retourne une erreur tant que VXDIAG n'a pas fourni un SDK Android redistribuable ou une API documentée et autorisée. Ne pas confondre les SDK disponibles pour d'autres modèles VXDIAG avec ceux de VCX SE/NANO.

## Couverture Camaro Gen 5 / Gen 6

Le catalogue des véhicules comprend dix profils RPO : LLT, LFX, LS3, L99, LSA, LS7, LTG, LGX, LT1 et LT4. Les années, marchés, transmissions et familles de contrôleurs affichées sont des indices de sélection, pas des identifiants de programmation. Avant toute opération sur une image, relever les informations réelles du module : VIN, CAL ID, CVN, nom ECU, références matériel et OS lorsqu'elles sont disponibles.

Les libellés E38, E39/E39A, E67, E69, E80 et E92/E93 sont des familles nominales associées aux profils. Une année ou un RPO ne suffit pas à sélectionner une définition de mémoire.

## Diagnostic OBD-II standard (lecture seule)

L'atelier « Scan OBD complet » utilise le moteur SAE J1979 sur le canal ELM connecté. Il peut interroger, si l'ECU et l'adaptateur les prennent en charge :

- Mode 01 : bitmap des PIDs supportés, données moteur usuelles, état readiness.
- Modes 03, 07 et 0A : DTC confirmés, en attente et permanents.
- Mode 02 : données freeze-frame.
- Mode 06 : résultats de tests de moniteur, présentés comme données brutes lorsqu'ils ne sont pas décodés.
- Mode 09 : VIN, CAL ID, CVN et nom ECU.
- Adaptateur : protocole détecté, tension retournée et journal brut des échanges.

Les résultats sont issus des réponses réelles de l'ECU accessible. Une réponse absente ou mal formée n'est pas remplacée par une valeur fictive. Le scanner générique ne balaie pas à lui seul les modules BCM, IPC, TCM, EBCM, SDM ou les calculateurs de confort. Il ne réalise ni codage, ni tests d'actionneurs, ni accès sécurité, ni programmation.

L'effacement des DTC utilise le service OBD Mode 04 uniquement après confirmation de l'utilisateur. L'application ne confirme l'effacement que si une réponse positive 0x44 est reçue. L'effacement peut réinitialiser les moniteurs readiness et ne répare pas la cause du défaut.

## Atelier fichiers de calibration

Les opérations locales disponibles sont les suivantes :

- Import d'un fichier local (limite applicative 32 Mio), stockage privé, SHA-256 et aperçu hexadécimal.
- Détection des formats binaires bruts BIN, Intel HEX et Motorola S-record.
- Vérification structurelle des enregistrements Intel HEX : checksum, longueur, EOF, types d'enregistrement et chevauchement d'adresses.
- Vérification structurelle des Motorola S-record : compte, checksum, terminaison et chevauchement d'adresses.
- Les formats propriétaires non documentés restent identifiés comme inconnus et ne sont pas interprétés.
- Comparaison octet par octet de deux images BIN brutes, rapport des octets modifiés, plages d'offset, octets ajoutés/manquants.
- Export local d'un fichier importé vers un emplacement choisi par l'utilisateur.

SHA-256, CRC d'intégrité et checksums de format Intel HEX/S-record ne sont pas des checksums de calibration GM. La comparaison binaire ne donne pas de signification moteur aux offsets et ne certifie pas que le fichier peut être utilisé sur un ECM.

## Fondation UDS sur CAN (lecture seule, non raccordée à un matériel réel)

Une base de transport CAN classique, de segmentation ISO-TP et de client UDS en lecture seule est présente dans le module `diagnostic`. Les services exposés sont limités à ReadDataByIdentifier (0x22) et ReadDTCInformation (0x19, sous-fonction 0x02). Le client vérifie le service positif, le DID retourné et les réponses négatives ; la session profilée impose une liste blanche de DID.

Avant d'ouvrir une session, le profil doit correspondre au véhicule sélectionné (année, génération, RPO et VIN), au module, à la famille de contrôleur, aux références matériel/OS, au CAL ID, au CVN, aux DID d'identification et à une référence documentaire traçable. Une discordance empêche l'émission des requêtes. Un profil de test synthétique n'est pas une définition GM exploitable sur un véhicule.

**Intégration matérielle partielle :** un transport UDS réel via STPX est maintenant raccordé au ViewModel pour l'OBDLink MX+ (firmware STN reconnu). Le firmware de l'adaptateur réalise lui-même la segmentation/réassemblage ISO-TP avec STCSEGT/STCSEGR ; l'application envoie une transaction STPX et vérifie le CAN ID de réponse. L'écran ECU permet de saisir les CAN ID physiques vérifiés, de lire un DID (dont F190/VIN) et de lire les DTC UDS (0x19/0x02). Cette voie ne fonctionne pas avec un clone ELM générique, un OBDLink CX, ni avec VCX SE/NANO.

Le client UDS fonctionne aussi sur le transport CAN brut simulé/testé. Le point d'intégration VXDIAG reste un contrat sans pilote fournisseur. Le scan automatique GM de BCM, TCM, EBCM/ABS, IPC, SDM et autres calculateurs n'est pas activé : il faut encore les adresses physiques et les profils DID exacts pour chaque VIN/OS.

La pile générique contient les primitives UDS de session, TesterPresent, lecture/écriture DID, DTC, SecurityAccess seed/key, routines, I/O control, accès mémoire et transferts upload/download. Les opérations mutantes restent refusées par défaut et nécessitent un grant interne lié au VIN, à la famille ECU, au numéro matériel, à l'OS, au CAL ID, au CVN, aux DID/routines/plages mémoire autorisés et à une preuve documentaire. Aucun grant GM réel, algorithme seed-key, checksum ni séquence flash/recovery n'est fourni. L'écran expose la lecture d'un DID et des DTC UDS sur MX+ ; l'utilisateur choisit le preset CAN et fournit les identifiants physiques. Aucune écriture ECU n'est autorisée depuis l'interface.

## Lecture binaire ECU, édition et flashage : limites actuelles

La lecture intégrale de la mémoire ECM et la programmation ECU ne sont pas implémentées. L'éditeur local de tables est disponible, mais il ne décode une table que lorsqu'un fichier de définitions explicite (offset, dimensions, type, endianness et échelle) est fourni et correspond exactement à l'image chargée. Il ne faut pas envoyer de commandes UDS de programmation génériques ni tenter une écriture ECU avec un adaptateur OBD générique.

Pour chaque famille de calculateur, il faut disposer et valider :

1. Une interface et une pile de transport adaptées au canal de programmation réel (pas seulement un transport ELM).
2. Les procédures GM autorisées pour identifier le module, sélectionner la session, sécuriser l'accès et lire/écrire les blocs mémoire.
3. Les définitions OS/calibration et les adresses/formats des tables pour la variante matérielle et logicielle exactes.
4. Les règles de checksum propres au calculateur et à sa calibration.
5. Un contrôle d'alimentation stable, une stratégie de reprise, un mode recovery/bench testé et des images de sauvegarde vérifiées.
6. Un plan de test sur matériel de laboratoire avant toute validation en véhicule.

Les interfaces GM de programmation et leurs logiciels officiels ne sont pas interchangeables avec les interfaces OBD génériques. CamaroDiag ne remplace pas les outils d'atelier GM officiels sans les protocoles, licences, définitions et procédures nécessaires.


## Pile UDS ISO-TP étendue – primitives génériques, accès contrôlé

Le module `diagnostic` contient désormais un client UDS générique supplémentaire au-dessus du transport CAN brut et du canal ISO-TP classique. Le transport STPX MX+ expose les presets STN documentés : 33/34 (HS-CAN ISO 15765 à 500 kbit/s, 11/29 bits), 35/36 (HS-CAN 250 kbit/s) et 63/64 (SW-CAN/GMLAN ISO 15765 à 33,3 kbit/s, 11/29 bits). Le preset doit correspondre au réseau réel et à la longueur des identifiants CAN. Il couvre les services normalisés suivants, lorsque l'ECU les implémente et les autorise :

- Session et maintien : `0x10` DiagnosticSessionControl, `0x3E` TesterPresent (y compris suppression de réponse).
- Identification et diagnostic : `0x22` ReadDataByIdentifier, `0x19` ReadDTCInformation, décodage des enregistrements DTC `0x02`.
- Effacement et redémarrage : `0x14` ClearDiagnosticInformation, `0x11` ECUReset, uniquement avec autorisation explicite.
- Accès protégé : `0x27` SecurityAccess (demande de seed et soumission d'une clé fournie par un fournisseur autorisé ; aucun algorithme de clé GM n'est inventé).
- Paramètres et routines : `0x2E` WriteDataByIdentifier, `0x31` RoutineControl, `0x2F` InputOutputControlByIdentifier, avec listes blanches par DID/routine.
- Mémoire et transfert : `0x23` ReadMemoryByAddressAndLength, `0x3D` WriteMemoryByAddressAndLength, `0x34` RequestDownload, `0x35` RequestUpload, `0x36` TransferData, `0x37` RequestTransferExit.
- Gestion réseau : `0x28` CommunicationControl et `0x85` ControlDTCSetting, soumis à session et autorisation.

La politique par défaut est **lecture seule**. Les écritures DID, routines, contrôles E/S, mémoire, transferts, effacement DTC et reset exigent une politique créée par le chemin interne de validation d'identité. Cette validation lie le VIN, le CAL ID, le CVN et une référence documentaire. Les opérations de programmation exigent en plus une session non-default, les plages mémoire autorisées, une limite de taille et, par défaut, un accès sécurité accepté. Le transfert contrôle le numéro de séquence, la taille de bloc et le nombre exact d'octets demandé.

Le client gère les réponses négatives UDS et attend les réponses positives après NRC `0x78` ResponsePending. Le canal ISO-TP reste limité à Classical CAN et à la longueur normale 12 bits (4095 octets) ; CAN FD, addressing étendu ISO-TP, segmentation étendue 32 bits et adaptation de timing sub-millisecondes ne sont pas annoncés comme supportés.

**Ce code est une pile de protocole générique, pas une validation GM.** Aucun profil de programmation GM n'est fourni : aucun DID d'écriture, aucune routine, aucune plage mémoire, aucune clé de sécurité, aucun checksum ni séquence de flashage n'est renseigné pour les Camaro. Les méthodes d'écriture existent derrière les contrôles, mais aucun véhicule ne reçoit d'autorisation d'écriture par défaut.

**Matériel :** pour l'accès UDS direct Android déjà raccordé, il faut un OBDLink MX+ authentique (STN2255/STN2256) et les CAN ID request/response exacts du calculateur. La documentation STN officielle décrit STPX, STCSEGR/STCSEGT, STCFCPA, les presets HS-CAN et SW-CAN, et le réassemblage ISO-TP. Pour les VCX SE/NANO, il faut encore un pont PC J2534/ISO 22900 ou un SDK Android officiellement fourni. Le GM MDI2 est documenté comme interface Pass-Thru J2534 pilotée par une application PC.

## BCM / odomètre / pilotage

Le catalogue de commandes carrosserie reste fermé tant qu'une routine OEM documentée n'est pas validée pour le VIN, l'année, le BCM et la version logicielle. L'adressage GM étendu et les DID kilométriques ne doivent pas être devinés à partir de l'année ou du moteur.

Pour l'audit kilométrique, une lecture n'est considérée indépendante que si l'identité ECU, la réponse brute, l'adresse de réponse et le profil véhicule sont vérifiés. Un écart est un indice à investiguer (remplacement de module, reprogrammation, configuration ou réparation), pas une preuve automatique de fraude. Ne jamais substituer un PID « distance depuis effacement » à un kilométrage total.

Le démarrage à distance n'est pas une commande CAN générique. RemoteStartSafetyGate est une évaluation de conditions uniquement ; il ne lance pas le moteur, ne contourne aucun antidémarrage et ne remplace pas le dispositif OEM.

## Compatibilité Android et essais terrain

minSdk = 29 (Android 10), compileSdk/targetSdk = 36. USB Host, réseau TCP et permissions Bluetooth doivent être validés sur les appareils réels visés. Les tests unitaires et l'assemblage APK CI ne remplacent pas les essais sur VCX/ELM, sur toutes les générations et sur chaque calculateur.

## Profils d'adaptateurs OBDLink

La détection SW-CAN n'est pas déduite du seul chipset STN ni de la réponse à `STP 61`. Le profil est activé uniquement pour les modèles MX/MX+ explicitement documentés, après acceptation de la commande de protocole. Les autres interfaces restent non vérifiées tant qu'une référence matérielle et son câblage DLC pin 1 ne sont pas confirmés.

- [Matrice officielle des adaptateurs OBDLink](https://support.obdsol.com/support/solutions/articles/43000713351-which-obdlink-adapter-is-right-for-me-) : matrice des protocoles SW-CAN par modèle et compatibilité GM du CX.
- [Fiche officielle OBDLink MX+](https://www.obdlink.com/products/obdlink-mxp/) : support SW-CAN et réseaux GM avancés.

## Références techniques GM

- [GM MDI 2 User Guide](https://gsi.ext.gm.com/userguides/GM_MDI_2_User_Guide.pdf) : décrit le MDI 2, son fonctionnement avec un hôte PC, les interfaces J2534 / ISO 22900 et les logiciels de diagnostic/reprogrammation GM.
- [GM MDI User Guide](https://gsitlc.ext.gm.com/userguides/GM_MDI_User_Guide.pdf) : précise que les emplacements des calculateurs et les connecteurs doivent être recherchés dans le Service Information du véhicule testé.
- [GM TIS2Web User Guide](https://gsi.ext.gm.com/userguides/GM_TIS2Web_User_Guide.pdf) : décrit le rôle du VIN dans la sélection des CAL ID/CVN et les prérequis de programmation SPS, dont l'interface J2534 et la stabilité d'alimentation.

Ces guides sont des documents d'architecture et de procédure générale, pas des listes d'adresses CAN/DID pour chaque Camaro. Les données de diagnostic détaillées doivent être extraites du Service Information correspondant au VIN, à l'année, au module et à sa calibration. Le guide MDI 2 décrit un environnement logiciel hôte PC ; il ne fournit pas de SDK Android directement intégrable dans CamaroDiag.

## Conditions pour achever le flashage GM et VXDIAG

Obtenir auprès de VXDIAG les SDK/API officiellement autorisés pour chaque VCX SE/NANO, les identifiants USB/réseau, versions de firmware, documentation de transport et droits de redistribution. Pour le flash GM, obtenir les définitions, licences, procédures et moyens de récupération approuvés pour chaque ECM. En leur absence, les fonctions correspondantes doivent rester explicitement indisponibles plutôt que d'exécuter des trames supposées.


## Enregistrement télémétrique en direct

L'écran Live peut enregistrer les valeurs réellement retournées par l'interface OBD puis les exporter en CSV. Chaque point comporte l'horodatage, le VIN, le profil véhicule, le PID, le libellé, la valeur et l'unité. Une session est limitée à 10 000 cycles d'acquisition et s'arrête si l'interface réelle est déconnectée ; les valeurs de démonstration ne sont jamais ajoutées à un journal réel.

## Édition des tables et télémétrie live

CamaroDiag comprend un éditeur hors ligne de tables pour une image BIN importée, à condition que l'utilisateur fournisse un fichier de définitions vérifiées pour cette image précise. Chaque définition comporte l'offset, les dimensions, le type numérique, l'ordre des octets, l'échelle, le biais et les limites éventuelles. Le fichier original est conservé et les modifications produisent une image locale distincte.

La lecture/enregistrement des capteurs pendant que le moteur tourne est distincte de l'édition d'une image de calibration. CamaroDiag ne modifie pas actuellement les tables dans l'ECU moteur tournant et n'effectue aucune écriture de calibration en direct. Aucun pack de définitions de tables Gen 5/Gen 6 validé n'est fourni ; la lecture binaire directe de l'ECM et sa programmation restent indisponibles tant que les procédures adaptées au contrôleur/OS exact et la récupération ne sont pas validées.


## Import de cartographies externes

L'import accepte les fichiers sélectionnés par Android et conserve leur contenu original. BIN brut, Intel HEX et Motorola S-record sont inspectés ; les archives ZIP sont identifiées et leurs entrées sont listées sans extraction automatique. Les fichiers de définition externes XDF/A2L/XML et les conteneurs inconnus restent des pièces jointes opaques, sans conversion ou modification.

Pour utiliser l'éditeur de tables, il faut une image BIN brute et un TSV de définitions adapté à son CAL ID/OS. Chaque définition fournit les offsets, dimensions, axes représentés par la matrice, type numérique, ordre des octets, échelle, biais et limites. L'import d'un conteneur propriétaire n'implique ni son décodage, ni l'accès à ses tables, ni l'autorisation de flasher son contenu.

Le chargement d'un TSV de définitions dans l'éditeur exige des métadonnées correspondant au VIN sélectionné, au VIN réellement lu sur l'ECU, au CAL ID et au CVN lus via Mode 09, ainsi qu'à l'empreinte SHA-256 de l'image BIN active. Une définition dont un de ces éléments diffère est refusée. L'OS et la référence matérielle doivent aussi figurer dans le TSV, mais Mode 09 standard ne permet pas de les confirmer ici : ils restent à vérifier dans la documentation spécifique du calculateur.


## Import de projets de cartographie externes

L'importeur accepte les images BIN/ORI/MOD, Intel HEX et Motorola S-record, les archives ZIP, les fichiers de définition externes ainsi que les projets/conteneurs issus d'outils tiers. Les projets dont le format interne n'est pas décodé sont conservés tels quels avec leur nom, taille et empreinte SHA-256, puis peuvent être exportés. Les archives ZIP sont inspectées en lecture seule avec des limites de nombre d'entrées et de taille décompressée.

Pour utiliser l'éditeur de tables, il faut extraire ou exporter depuis le logiciel d'origine une image binaire brute, puis charger un fichier de définitions CamaroDiag contenant offsets, dimensions, types numériques, endian, facteurs d'échelle et limites. Les définitions XML externes ne sont pas encore converties automatiquement. L'import d'un projet propriétaire ne signifie pas que ses tables sont lisibles, que le fichier est compatible avec l'ECM, ou qu'il peut être écrit dans le véhicule.

### Références matérielles et protocole

- [OBDLink Family Reference and Programming Manual, révision F (2025)](https://www.scantool.net/scantool/downloads/682/obdlink_frpm_f.pdf) : commandes STPX, STCSEGR/STCSEGT, STCFCPA, prise en charge raw CAN/ISO-TP.
- [GM MDI 2 User Guide](https://gsi.ext.gm.com/userguides/GM_MDI_2_User_Guide.pdf) : interface J2534 Pass-Thru et configuration sur PC.
- [VXDIAG VCX Nano technical features](https://vxdiag.com/pages/vcx-nano-products-features) : J2534/ISO 22900 et protocoles CAN/SW-CAN côté interface ; cela ne constitue pas un SDK Android.
