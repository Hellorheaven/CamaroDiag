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

**Limite d'intégration actuelle :** aucun fournisseur matériel de trames CAN brutes n'est raccordé au ViewModel ni à l'interface. Les transports ELM actuels échangent des commandes ASCII et ne sont pas assimilés à un canal J2534/ISO-TP transparent. Le point d'intégration VXDIAG reste un contrat sans pilote fournisseur. La couche UDS est donc compilée et testée sur transport simulé, mais elle ne réalise pas encore un scan GM réel sur BCM, TCM, EBCM/ABS, IPC, SDM ou ECM.

Cette fondation ne contient aucune adresse GM, aucun DID constructeur, aucune clé de sécurité ni aucune procédure de programmation supposée. Elle ne fournit pas de session de programmation, SecurityAccess, RequestDownload, transfert mémoire, écriture de DID, routine d'actionneur, écriture RAM, flashage ou recovery. Ces services restent absents et ne doivent pas être déduits d'un profil d'essai.

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

## BCM / odomètre / pilotage

Le catalogue de commandes carrosserie reste fermé tant qu'une routine OEM documentée n'est pas validée pour le VIN, l'année, le BCM et la version logicielle. L'adressage GM étendu et les DID kilométriques ne doivent pas être devinés à partir de l'année ou du moteur.

Pour l'audit kilométrique, une lecture n'est considérée indépendante que si l'identité ECU, la réponse brute, l'adresse de réponse et le profil véhicule sont vérifiés. Un écart est un indice à investiguer (remplacement de module, reprogrammation, configuration ou réparation), pas une preuve automatique de fraude. Ne jamais substituer un PID « distance depuis effacement » à un kilométrage total.

Le démarrage à distance n'est pas une commande CAN générique. RemoteStartSafetyGate est une évaluation de conditions uniquement ; il ne lance pas le moteur, ne contourne aucun antidémarrage et ne remplace pas le dispositif OEM.

## Compatibilité Android et essais terrain

minSdk = 29 (Android 10), compileSdk/targetSdk = 36. USB Host, réseau TCP et permissions Bluetooth doivent être validés sur les appareils réels visés. Les tests unitaires et l'assemblage APK CI ne remplacent pas les essais sur VCX/ELM, sur toutes les générations et sur chaque calculateur.

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
