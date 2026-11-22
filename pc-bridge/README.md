# CamaroDiag J2534 Bridge — Guide d'installation

Le pont partage désormais l'identité visuelle CamaroDiag : Camaro Synergy Green, face avant inspirée de la ZL1 2016 et nom de produit homogène avec l'application Android.

Le pont relie CamaroDiag sur Android à un fournisseur SAE J2534 Pass-Thru installé sur un PC Windows. Il peut fonctionner avec des pilotes VXDIAG VCX SE ou VCX Nano lorsque le modèle exact, le pilote et le protocole sont compatibles.

## Interface Windows : toutes les fonctions sans ligne de commande

**Lancez CamaroDiag J2534 Bridge normalement depuis le menu Démarrer Windows : l'interface graphique s'ouvre automatiquement.** Les commandes terminal restent présentes uniquement pour les diagnostics automatisés et la compatibilité avec les scripts existants.

| Onglet | Fonctions disponibles |
|---|---|
| **Connexion Android** | Choisir et actualiser les pilotes J2534 installés, adresse IP locale du PC, port TLS, profil mémoire facultatif, autorisation facultative d’effacement des défauts antipollution, démarrer/arrêter le pont, copier séparément l'IP, le port et le code d'appairage TLS complet, ou copier les trois valeurs ensemble |
| **Outils ECU E39/E39A** | Lire deux fois l'identification GM VIN/OSID (CAN IDs/profil explicites), inspecter une image brute de 3 Mio, recalculer les checksums GM SUM/CVN des calibrations et enregistrer un nouveau fichier sans écraser les originaux |
| **Console CAN / OBD** | Ouvrir/fermer le canal J2534, envoyer des trames **autorisées en lecture**, afficher distinctement les trames TX et RX, envoyer les trames sans réponse autorisées, identification OBD Mode 09/GM GMLAN, tension du pilote, sauvegarder l'historique |
| **Lecture mémoire ECU** | Créer/charger un profil de lecture avec VIN, plage mémoire, tailles de blocs, session et CAN IDs ; effectuer une lecture UDS 0x23, relire toute la plage, refuser les divergences, annuler, et exporter un nouveau fichier binaire à couverture déclarée |
| **Pilotes et diagnostics** | Tester un ou tous les pilotes (ABI, connexion USB, version firmware/DLL/API), lancer les autotests du pont et copier le diagnostic |
| **Mises à jour / À propos** | Voir la version du pont, rechercher les releases officielles, télécharger/installateur x86/x64 sans commande terminal, consulter le compte rendu d'installation |
| **Aide et utilisation** | Instructions intégrées pas à pas pour toutes les fonctions, sécurité TLS et résolution des pannes courantes |

### Mise en relation de l'application Android

1. Installer le pilote fabricant de l'interface J2534, connecter Windows et Android au même réseau privé.
2. Dans **Connexion Android**, sélectionner l'interface puis l'adresse IP accessible depuis le téléphone.
3. Cliquer **Démarrer le pont** et attendre **En écoute**.
4. Recopier, avec les boutons Copier, l'**adresse IP**, le **port** et le **code sécurisé complet** (jeton + point + empreinte du certificat SHA-256) dans les champs du connecteur Windows/J2534 de CamaroDiag Android.
5. La liaison est obligatoirement TLS et le code change à chaque redémarrage. Ne pas ouvrir le port sur Internet.

**La console Windows, les contrôles de pilote et les lectures locales utilisent exclusivement le canal J2534.** Il faut donc arrêter le serveur Android avant de les utiliser, puis le redémarrer. Le pont autorise uniquement les commandes diagnostiques déjà prévues par son allowlist. **Aucune commande de programmation flash, SecurityAccess, effacement UDS ou actionneur n'est rendue accessible par cette interface.** L’effacement OBD standard des défauts antipollution est une opération distincte, désactivée par défaut. Les composants E39 natifs non qualifiés physiquement restent isolés jusqu'à un essai sur ECU de banc et une récupération démontrée.

## Fonctionnalités

- Connexion à un fournisseur J2534 Windows enregistré.
- Dans l’atelier de calibration Android, la lecture d’identification interroge le calculateur sélectionné et affiche le VIN, les CAL ID, les CVN et le nom ECU disponibles. Cette fonction utilise le mode 09 sur tout pilote Pass-Thru compatible avec le profil CAN choisi. Elle nécessite le pont et l’application de la même PR. Les interfaces ELM327 se connectent directement à Android pour cette lecture.
- ISO15765 HS-CAN, identifiants 11 ou 29 bits, 250/500 kbit/s.
- SW-ISO15765 GMLAN à 62,5 kbit/s si le fournisseur J2534-2 l'accepte.
- Transmission de charges UDS et OBD standard ; le fournisseur J2534 gère ISO-TP et le contrôle de flux.
- Sur le canal physique OBD primaire compatible, lecture des paramètres temps réel, des DTC, du freeze frame, de l'identification et de l'audit kilométrique selon les données réellement fournies. Ce diagnostic ne couvre que le calculateur sélectionné.
- Lecture UDS limitée aux services 0x22, 0x19 et 0x3E.
- Un client Android à la fois, protégé par TLS 1.2/1.3 et un code d'appairage associant un jeton aléatoire à l'empreinte SHA-256 du certificat du pont.

Ce pont est générique J2534. Il ne fournit aucun pilote VXDIAG et ne remplace ni VX Manager ni le DLL PassThru du fabricant.

## Matériel requis

- PC Windows 10 ou 11.
- Interface J2534 avec pilote constructeur installé et enregistré.
- CamaroDiag sur Android 10 ou supérieur.
- PC et Android connectés au même réseau privé.
- Architecture x86/x64 du pont identique à celle du DLL J2534.

## Compatibilité VXDIAG

| Interface | HS-CAN | SW-CAN / GMLAN | Conditions |
|---|---|---|---|
| VCX SE | Oui si exposé par le fournisseur J2534 | Possible si le pilote expose J2534-2 SW-ISO15765 | Pilote correspondant à la référence exacte |
| VCX Nano | Oui si exposé par le fournisseur J2534 | Dépend de la révision, du câblage et du pilote ; non garanti | Vérifier modèle et pilote |

## Installation et connexion

1. Faire installer par un administrateur Windows le pilote correspondant exactement au VCX ou à l'interface J2534.
2. Pour VXDIAG, faire installer VX Manager et vérifier la détection de l'interface. Cette étape nécessite généralement des droits administrateur.
3. Ouvrir les [releases CamaroDiag](https://github.com/Hellorheaven/CamaroDiag/releases/latest).
4. Télécharger l'installateur **Setup Release** correspondant à l'architecture du DLL J2534 :
   - `CamaroDiag-J2534-Bridge-Setup-release-win-x64.exe` pour un fournisseur J2534 64 bits.
   - `CamaroDiag-J2534-Bridge-Setup-release-win-x86.exe` pour un fournisseur J2534 32 bits.
5. Lancer l'installateur. Il installe le pont dans le profil Windows courant, sans droits administrateur, et crée un raccourci dans le menu Démarrer. Le raccourci Bureau est facultatif.
6. Lancer **CamaroDiag J2534 Bridge** depuis le menu Démarrer. Si plusieurs fournisseurs sont présents, sélectionner celui à utiliser.
7. Relever l'adresse IP du PC, le port et **l'intégralité du code d'appairage sécurisé** affiché (forme `jeton.empreinte_SHA256`, sans espace). Le certificat TLS est renouvelé au redémarrage du pont.
8. Connecter le PC et Android au même réseau privé.
9. Saisir IP, port, **code d'appairage sécurisé complet** dans le champ d'appairage de CamaroDiag, ainsi que les identifiants CAN documentés du calculateur. Installer la version Android et le pont Windows de la même mise à jour : les anciens ponts TCP sont volontairement refusés.
10. Si Windows Firewall le demande, demander à l'administrateur d'autoriser le programme uniquement sur les réseaux privés.

**Important pour les PC sans droits administrateur :** l'utilisateur peut installer le pont CamaroDiag avec le Setup dans son profil Windows, mais il ne peut pas installer lui-même VX Manager ni le pilote J2534 si la politique informatique réserve ces opérations à l'administrateur. Le pilote et l'interface doivent donc être préparés par le service informatique avant l'utilisation.

Le contrôle de mise à jour du pont utilise l'installateur Setup Release publié dans la release GitHub ; il le lance après fermeture du pont, sans extraction manuelle de ZIP et sans élévation administrateur. Les builds Debug restent réservés aux vérifications internes et ne sont pas joints aux releases publiques. Les releases publiques ne contiennent que l'APK Android de production et les deux installateurs Windows Release.

## Sécurité réseau

La liaison Android–pont Windows est obligatoirement **chiffrée en TLS 1.2 ou TLS 1.3**. L'application vérifie l'empreinte SHA-256 exacte du certificat présentée par le pont, à partir du code recopié localement : aucune commande ni aucun jeton d'appairage n'est envoyé avant la négociation TLS et le contrôle de l'empreinte. Les clients TCP en clair et les codes d'appairage anciens (jeton seul) sont rejetés. Le certificat et le jeton sont éphémères : recopier le nouveau code à chaque démarrage du pont.

Le pont continue à refuser les adresses distantes publiques et n'accepte que les réseaux privés/locaux (RFC1918, ULA IPv6, link-local). Ne jamais exposer le port à Internet ni configurer de redirection de port. Une mauvaise empreinte, une négociation TLS impossible ou un ancien client doivent entraîner un refus de connexion, jamais un repli vers TCP en clair.

## Fonctions et limites

| Fonction | État |
|---|---|
| Données OBD standard, DTC et lecture freeze frame | Disponibles sur le canal physique primaire compatible |
| Effacement des défauts antipollution OBD | Facultatif, autorisé sur le PC et confirmé dans Android, moteur arrêté et véhicule immobile |
| UDS ReadDataByIdentifier (0x22) | Autorisé |
| UDS ReadDTCInformation (0x19) | Autorisé |
| TesterPresent (0x3E) | Autorisé |
| Écriture DID, effacement UDS | Bloqués |
| RoutineControl, actionneurs | Bloqués |
| Lecture d’une plage mémoire | Disponible avec un profil de lecture configuré sur le PC et un VIN confirmé par le calculateur |
| SecurityAccess, écriture mémoire, flashage | Bloqués |
| CAN FD | Non pris en charge |
| Découverte automatique de tous les calculateurs GM | Non fournie |

Les CAN ID, débits et services doivent provenir d'une documentation fiable pour le VIN et le calculateur. Le pont ne devine aucune adresse ni procédure de programmation.

### Diagnostic OBD et effacement des défauts antipollution

Utilisez la même version du pont et de l’application Android. Sélectionnez le canal OBD physique primaire correspondant au véhicule (par exemple la paire CAN 11 bits 7E0/7E8 à 500 kbit/s lorsque le véhicule l'utilise). Si le calculateur répond aux services OBD standard, les écrans de données temps réel, de lecture des défauts, d’audit kilométrique et de scan OBD peuvent utiliser directement le pont, sans adaptateur ELM supplémentaire. Un service non pris en charge n'est pas assimilé à une absence de défauts.

Pour autoriser l'effacement, arrêtez le pont et cochez **Autoriser l’effacement OBD des défauts antipollution (moteur arrêté)** avant de le redémarrer. Reconnectez Android avec le nouveau code TLS. Immobilisez le véhicule, coupez le moteur en laissant le contact nécessaire au diagnostic, puis confirmez l'effacement dans l'écran DTC. Le calculateur doit confirmer une vitesse et un régime nuls. Relisez les défauts après l'opération. Les données de préparation antipollution et les données de contexte des défauts seront réinitialisées, mais les codes permanents ne sont pas effacés par cette commande. Elle ne répare pas le défaut à l'origine du code. Aucun effacement UDS des autres modules n'est autorisé.

La fonction est en cours de validation sur la PR de développement ; la compatibilité avec votre interface et votre véhicule doit être contrôlée en conditions réelles.

### Sauvegarde d’une plage mémoire

Dans **ECU / cartographies**, sélectionnez le véhicule et connectez le pont. Lorsque le profil de lecture du PC autorise la plage demandée, l’application la lit deux fois et propose de sauvegarder le ZIP si les lectures concordent.

Pour contrôler une sauvegarde, ouvrez la section des fichiers de cartographie et choisissez **Vérifier le ZIP de sauvegarde du calculateur**. Ce contrôle fonctionne aussi hors connexion. Il affiche les adresses archivées, les empreintes SHA-256 et le VIN enregistré lorsque le fichier en contient un. Une sauvegarde peut couvrir une seule plage du calculateur.

## Dépannage

- Aucun fournisseur détecté : demander à l'administrateur de réinstaller le pilote constructeur et vérifier l'enregistrement J2534.
- Erreur DLL : vérifier que le pont et le DLL fournisseur sont tous deux x86 ou tous deux x64.
- Connexion TLS impossible : vérifier IP, port, réseau et pare-feu, ainsi que la version du pont ; un ancien pont TCP n'est plus compatible.
- Erreur d'empreinte TLS : recopier le **nouveau code d'appairage complet** du pont lancé sur le PC. Ne jamais ignorer une erreur de certificat.
- Échec d'ouverture du protocole : le pilote peut ne pas prendre en charge le profil sélectionné.
- Aucune réponse ECU : vérifier contact, broches DLC, débit CAN et paire d'identifiants physiques.
- SW-CAN indisponible : le matériel ou le pilote peut ne pas exposer le protocole J2534-2 requis.

Le pont est un transport de diagnostic. Il ne certifie ni la compatibilité d'un calculateur ni l'aptitude d'une interface à programmer un véhicule.


### Vérifier réellement le pilote J2534 Windows

Dans PowerShell, avec le boîtier branché en USB et son pilote constructeur installé, exécuter le pont avec `--probe-drivers` (exemple : `CamaroDiag.J2534Bridge.exe --probe-drivers`). Le contrôle vérifie pour chaque DLL déclarée dans Windows les fonctions J2534 indispensables, l'ouverture physique de l'interface (`PassThruOpen`), la lecture des versions API/DLL/firmware (`PassThruReadVersion`), puis referme le boîtier (`PassThruClose`). Utiliser la version x86 ou x64 correspondant au pilote. Le code de sortie est `0` si tous les pilotes détectés passent, `3` si aucun pilote n'est trouvé et `7` si au moins un pilote est inutilisable. Aucune commande ECU n'est envoyée.

**Limite :** ce test confirme seulement le pilote et son adaptateur, pas la programmation d'un calculateur. Pour activer l'écriture sur un E39/E39A, il faut encore valider sur banc l'identification exacte, les commandes de programmation propres au firmware, les checksums GM, l'alimentation, la sauvegarde restaurable et la récupération en cas de coupure. Un boîtier J2534 compatible avec SPS2 ne fournit pas à lui seul ces algorithmes à CamaroDiag.


### Programmation ECU native — état de développement

CamaroDiag contient désormais son **propre moteur de transfert UDS** : RequestDownload, transfert par blocs avec compteur, contrôle des réponses positives/négatives et RequestTransferExit. Il contient également un planificateur de secteurs de flash conservant les octets voisins et interdisant toute modification de plage protégée. Ces fonctions font l'objet d'autotests dans la compilation Windows ; aucun outil externe de flashage ni aucun binaire tiers de programmation n'est requis ou lancé.

**Ce moteur est une brique native de programmation, pas encore un pilote complet E39/E39A.** La procédure de passage en session de programmation, l'authentification spécifique au calculateur, la géométrie flash exacte des variantes, les routines d'effacement, les checksums GM, la lecture de vérification et la récupération boot/bench exigent encore une implémentation par famille et une validation sur calculateur de banc. Le pont réseau Android reste en lecture seule pour ces services destructifs. Le fait que les fonctions de transport J2534 soient disponibles n'implique pas que le calculateur accepte leur utilisation pour programmer la mémoire.

Le contrôle du boîtier et du pilote Windows se fait indépendamment avec `--probe-drivers` ; le fournisseur matériel J2534 doit être installé, mais **aucune application de programmation externe** n'est nécessaire.

### Chargeur RAM natif GM E39/E39A

La programmation GM des E39/E39A ne correspond pas exactement au service ISO 14229 `0x34/0x36` générique. CamaroDiag possède désormais une implémentation **native** du cadrage GMLAN pour le téléchargement d'un chargeur en RAM : taille de téléchargement sur trois octets, transfert des fragments à adresses RAM explicites, copie en ordre descendant, bloc final d'exécution et réponses distinctes `0x76` (copie) / `0x99` (lancement). La référence technique est le comportement publié de PCM Hammer et les services GMLAN ; **aucun module PCM Hammer n'est exécuté, lié ou incorporé**, aucun kernel tiers n'est fourni.

Le chargeur se prépare hors ligne et ses paquets sont testés en CI. **Il n'est pas activé sur le pont réseau** : il manque encore une implémentation *native* vérifiable de la charge utile RAM de flash (effacement/programme), de l'accès sécurisé en session constructeur, des checksums et de la récupération sur banc. Les ECU E39 et E39A doivent être distingués par leurs identifiants live, pas par une équivalence supposée des seuls numéros de pièce.

### Inspection des images E39/E39A et checksums

Un second composant natif reconnaît les images **3 Mio** E39/E39A, analyse le tableau de segments (OS et cinq calibrations), vérifie que les pointeurs restent hors boot protégé, calcule les sommes de mots sur 16 bits et les CVN CRC16 GM, puis signale les écarts séparément. Les tests utilisent une image synthétique créée dans CamaroDiag, sans binaire tiers. Une somme incorrecte bloque la préparation de flash ; un CVN incorrect apparaît en avertissement distinct à examiner. Une image structurellement valide ne prouve pas encore la correspondance avec le VIN, la version OS ou le firmware réellement branché.

Les éléments de protocole ont été étudiés en consultant des implémentations publiques dont PCM Hammer, mais **aucun code de celui-ci n'est importé, aucune bibliothèque de flashage externe n'est utilisée**. Le téléchargement et l'exécution d'un vrai kernel de programmation restent désactivés sur l'interface publique en l'absence de charge utile originale validée et de procédure de récupération vérifiée sur banc.

### Commandes flash GMLAN natives E39/E39A

CamaroDiag génère désormais directement les primitives GM du **chargeur exécuté en RAM** : demande d'effacement par secteur `3D 05`, transmission de blocs `36` avec adresse, taille et somme des octets, écriture d'essai `36 44`, et contrôle indépendant CRC32 `3D 02`. Toutes ces trames sont encadrées par des contrôles de taille, d'adresse, de zone de boot protégée et de réponses négatives. Le CRC32 utilisé ici correspond au mode GM non réfléchi (polynôme `04C11DB7`, initialisation nulle), distinct du CRC32 ZIP.

**Ces primitives ne sont pas un kernel exécutable** : elles requièrent encore un chargeur RAM *original CamaroDiag* pour piloter correctement la mémoire C90FL de l'ECU ainsi qu'une implémentation native de l'authentification, de la gestion d'alimentation et de la récupération. Aucun accès à ces commandes n'est ouvert par le pont Android tant que ces étapes et les essais sur calculateur de banc ne sont pas terminés.

### Accès sécurisé GM E39/E39A en code natif

L'algorithme de calcul de clé GMLAN **0xDC** propre au profil E39/E39A a été réécrit en opérations arithmétiques 16 bits et vérifié par des vecteurs de test. Le module gère la lecture de graine `27 01`, la préparation de la réponse `27 02`, les acquittements et les refus de l'ECU. Il n'embarque ni interpréteur ni table d'algorithmes issue d'un autre projet. Il ne tente pas d'autres clés en cas de refus et ne court-circuite pas les temporisations de sécurité.

Le calcul de clé n'est utilisable physiquement qu'après identification du calculateur et entrée dans la session constructeur appropriée. Les fonctions destructives restent inaccessibles à travers la console distante tant que le pilote, le kernel de flash original et la récupération ne sont pas qualifiés sur banc.


### Précontrôle et inspection autonome des images E39/E39A

Dans le terminal Windows, l'exécutable CamaroDiag J2534 accepte désormais `CamaroDiag.J2534Bridge.exe --inspect-e39-image image.bin`. Cette commande **ne nécessite ni interface OBD ni driver matériel** : elle vérifie une image brute de 3 Mio, l'OSID, les six segments, les sommes GM et leurs CVN. Codes de sortie : `0` pour une structure et des sommes/CVN corrects, `9` pour des CVN divergents, `10` pour une image incorrecte/incomplète. Aucun contenu n'est modifié.

Le précontrôle natif `NativeE39ImagePreflight` compare séparément l'image originale et l'image modifiée. Il refuse de préparer une écriture si l'OSID ne correspond pas à celui relevé sur le calculateur, si le boot protégé est modifié, si les checksums/CVN sont incohérents, si une calibration est modifiée hors d'une région reconnue ou si la géométrie des secteurs n'est pas explicitement qualifiée pour le firmware exact.

### Transactions natives de secteurs et journal de récupération

Le séquenceur `NativeE39WriteSequence`, testable hors matériel, vérifie **tous les secteurs avant le premier effacement** : CRC de l'original lu via le kernel, écriture d'essai sans programmation, journalisation persistante avant effacement et écriture, programmation par blocs, puis comparaison CRC indépendante après chaque secteur. Sur perte de liaison ou échec après effacement, il enregistre `RecoveryRequired` et **ne recommence jamais aveuglément**. `NativeE39FileWriteJournal` utilise un fichier JSONL nouveau, en ajout uniquement et avec vidage forcé des données, sans écraser un journal précédent.

Ces composants sont des parties concrètes du futur pilote : **ils ne sont pas exposés par le pont réseau ni par Android**. Ils exigent un kernel RAM original, une géométrie de flash attestée, un ECU de banc et une procédure de récupération matériellement démontrée.

### Identification physique GMLAN en lecture seule

Après l'ouverture d'un canal J2534, l'opération TLS du pont `identifyGmlan` interroge les DID GM `0x90` (VIN) et `0xC1`/`0xC9` (identifiant OS). Le service `0x1A` est autorisé **uniquement pour ces trois identifiants**, en lecture seule. Le résultat indique le VIN, l'OSID, le DID retenu et précise que l'écriture physique n'est pas qualifiée. C'est une lecture réellement pilotée par la DLL J2534 de l'interface, et non une valeur simulée, lorsqu'elle est utilisée sur un PC équipé d'un boîtier connecté.

### Corriger les checksums d'une calibration E39/E39A (hors ligne)

Sur Windows, avec une **sauvegarde brute complète et vérifiée du calculateur** et une image modifiée de même taille (3 Mio), exécuter :

```powershell
CamaroDiag.J2534Bridge.exe --repair-e39-cal-checksums original.bin calibration-modifiee.bin calibration-corrigee.bin
```

Le programme vérifie les deux images, refuse les changements en dehors des calibrations (y compris le boot protégé, le système d'exploitation et les zones non répertoriées), recalcule les sommes de contrôle et CVN des segments modifiés, puis crée un **nouveau** fichier complet. Il ne remplace jamais les fichiers d'entrée ou un fichier de sortie déjà existant. La sortie affiche l'OSID, les segments modifiés et les empreintes SHA-256. Un contrôle de relecture du résultat est effectué avant de déclarer la réussite. Code de sortie `0` en cas de succès, `10` en cas de refus/échec.

**Cette commande n'écrit rien dans le calculateur**. Un fichier avec sommes de contrôle correctes n'est pas une preuve de compatibilité matérielle, ni une autorisation de flash ; la version réelle, l'alimentation et la récupération doivent être validées avec le boîtier J2534 et un calculateur de banc avant toute programmation.

### Vérifier le calculateur physiquement avec une interface J2534 (lecture seule)

Une fois le pilote Windows de l'interface installé et le calculateur correctement alimenté, vérifier d'abord la liste des interfaces :

```powershell
CamaroDiag.J2534Bridge.exe --probe-drivers
```

Puis lancer **en local**, sur le PC relié à l'interface, le test GMLAN :

```powershell
CamaroDiag.J2534Bridge.exe --probe-gmlan-ecu --adapter-index 0 --request-can-id 7E0 --response-can-id 7E8 --protocol 33
```

L'indice d'adaptateur, les identifiants CAN et le profil de liaison doivent être ceux du matériel réellement relié. Les valeurs ci-dessus sont seulement un **exemple courant de CAN haute vitesse 11 bits**, pas une adresse universellement validée pour chaque calculateur. Le programme refuse les paramètres manquants, incompatibles ou ambigus, puis lit deux fois le VIN et l'identifiant du logiciel ECU pour vérifier leur concordance. Il affiche la tension fournie par le pilote J2534 lorsque celui-ci le permet.

Cette commande utilise la DLL du fabricant de l'interface, **sans PCM Hammer ni outil de flash tiers**. Elle n'active pas la session de programmation et ne demande ni déverrouillage, ni effacement, ni écriture. Une identité stable n'est **pas** une validation de programmation : la récupération et le programme de flash natif restent à qualifier sur banc.


### Sauvegardes mémoire et récupération sur Windows

La lecture mémoire sur Windows propose un ZIP vérifiable dans CamaroDiag Android, contenant les deux acquisitions séparées et leurs informations d'intégrité. Le fichier définitif est publié après relecture indépendante sur disque. Un BIN brut reste disponible, mais ne contient pas cette preuve de double lecture. Les archives Windows indiquent une couverture partielle lorsqu'une cartographie complète n'a pas été attestée.

L'écran Récupération ECU permet de consulter un journal de programmation hors ligne : secteurs préparés, écrits, vérifiés, dernière phase et adresse d'un secteur potentiellement incomplet lorsque disponible. L'ouverture du journal ne contacte pas le calculateur et ne relance aucune écriture.

### Écriture locale E39/E39A

Une entrée locale réservée au banc indépendant a été intégrée. Elle refuse les profils non qualifiés avant même de découvrir les interfaces J2534. Pour cette version, aucune autorité de qualification ni aucun noyau de flash physiquement approuvé n'est fourni : **la programmation physique reste fermée**. Un certificat généré pour un test logiciel ne donne aucun droit d'écriture.

Les tests logiciels ne remplacent ni la validation de la révision matérielle, ni la preuve de récupération sur banc. Le pont TLS et la console Android n'exposent aucune commande de programmation.


### Accès ECU : OBD, banc et carte électronique

| Accès | Matériel / connexion | État dans CamaroDiag |
| --- | --- | --- |
| OBD | Prise diagnostic du véhicule, ELM/STN ou J2534 compatible | Diagnostic et télémétrie ; lecture mémoire limitée aux profils J2534 réellement compatibles |
| CAN direct | Interface CAN/J2534 et raccordement documenté | Lecture ciblée avec le transport J2534 existant, si le calculateur répond aux services sélectionnés |
| BENCH | Calculateur déposé, alimentation et connecteur documentés | Lecture ciblée J2534 ; programmation E39/E39A intégrée mais non qualifiée, verrouillée |
| BOOT/BAM | Mode de démarrage spécial du microcontrôleur, interface correspondant à la carte | Préparation et vérification de fichiers ; pilote physique à qualifier |
| BDM | Sonde de débogage compatible avec le processeur | Préparation et vérification de fichiers ; pilote physique manquant |
| JTAG/Nexus | Sonde et points de connexion correspondant au processeur | Préparation et vérification de fichiers ; pilote physique manquant |
| EEPROM ISP | Programmateur de mémoire compatible, connexion en circuit | Préparation et vérification de fichiers ; programmation par pages spécifique à ajouter |
| Chip-off | Programmateur et support pour mémoire déposée | Préparation et vérification de fichiers ; programmation par pages spécifique à ajouter |

Une qualification d'écriture est propre au calculateur, à la version du firmware, à la région mémoire, à l'interface et au mode de raccordement. Les interfaces ne sont pas interchangeables. Les modes mémoire directe ne reposent pas nécessairement sur CAN ; une interface J2534 n'est pas une sonde BDM/JTAG ou un programmateur EEPROM.

Pour archiver deux acquisitions faites avec un programmateur externe : dans Android, importer le BIN original, importer la seconde lecture comme fichier de comparaison, puis ouvrir le mode Expert. Choisir le mode, la région et l'adresse de début effectivement lue. Lorsque les deux images correspondent, employer **Exporter (ZIP)**. Le ZIP est conservé dans le dossier public `/CamaroDiag/` après vérification de sa taille et de son intégrité. Deux images de 15 Mio maximum chacune sont acceptées. Les fichiers importés ne sont pas attestés comme lectures physiques effectuées par CamaroDiag, et ne prouvent pas une sauvegarde complète du calculateur.

Le brouillon d'une géométrie de secteurs flash n'est pas applicable à une EEPROM externe. Ne pas utiliser un brochage d'une autre révision de carte. La qualification physique requiert le matériel et la procédure de récupération adaptés ; aucune connexion automatique aux sondes directes n'est actuellement fournie.


## Découverte locale CamaroDiagConnectors (prototype isolé)

Le pont inclut désormais un mode local explicite : `CamaroDiag.J2534Bridge.exe --discover-connector COM5` (remplacer COM5 par le port vérifié de la carte isolée, firmware CD/1). Cette commande vérifie les trois réponses publiques, ferme le port, et n'exécute ni J2534 ni serveur réseau ni mise à jour. Redémarrer la carte isolée entre sessions. Ne pas utiliser avec un câble KKL/ELM ou un ECU raccordé. Le boîtier n'est pas authentifié, aucune capacité véhicule n'est accordée. Le backend natif sécurisé via pont reste à développer et qualifier.

L'onglet **CamaroDiagConnectors** du pont propose aussi cette découverte sans terminal : actualiser les ports COM, sélectionner uniquement la carte CD/1 isolée, confirmer l'absence de cible et lancer le test. Aucun port n'est sélectionné ou interrogé automatiquement. L'opération exige l'arrêt du pont et de la console ; le résultat n'autorise aucun accès véhicule.
