# CamaroDiag J2534 Bridge — Guide d'installation

Le pont partage désormais l'identité visuelle CamaroDiag : Camaro Synergy Green, face avant inspirée de la ZL1 2016 et nom de produit homogène avec l'application Android.

Le pont relie CamaroDiag sur Android à un fournisseur SAE J2534 Pass-Thru installé sur un PC Windows. Il peut fonctionner avec des pilotes VXDIAG VCX SE ou VCX Nano lorsque le modèle exact, le pilote et le protocole sont compatibles.

## Fonctionnalités

- Connexion à un fournisseur J2534 Windows enregistré.
- Dans l’atelier de calibration Android, la lecture d’identification interroge le calculateur sélectionné et affiche le VIN, les CAL ID, les CVN et le nom ECU disponibles. Cette fonction utilise le mode 09 sur tout pilote Pass-Thru compatible avec le profil CAN choisi. Elle nécessite le pont et l’application de la même PR. Les interfaces ELM327 se connectent directement à Android pour cette lecture.
- ISO15765 HS-CAN, identifiants 11 ou 29 bits, 250/500 kbit/s.
- SW-ISO15765 GMLAN à 62,5 kbit/s si le fournisseur J2534-2 l'accepte.
- Transmission de charges UDS ; le fournisseur J2534 gère ISO-TP et le contrôle de flux.
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


### Programmation E39/E39A avec PCM Hammer (outil externe, expérimental)

Pour les calculateurs E39/E39A, CamaroDiag propose également un **mode Windows local** de délégation à un exécutable **PCM Hammer CLI installé séparément**. Il n'est **pas accessible à distance depuis l'application Android** : le pont réseau continue à refuser les commandes d'effacement et d'écriture directes. PCM Hammer est un programme tiers sous GPLv3 dont les binaires de kernels ont une licence différente ; aucun code ou binaire de ce projet n'est intégré ou redistribué par CamaroDiag. Projet : https://github.com/PcmHammer/PcmHammer (branche `develop`).

Pré-requis : calculateur E39/E39A de **banc remplaçable**, source 12 V stabilisée, connexion J2534 compatible, procédure de récupération testée, exécutable `PcmHammerCLI.exe` et ses kernels issus de la distribution officielle. La voiture ne doit pas être utilisée pour la première validation. Contrôler l'identité VIN/OS, la taille du fichier **3 Mio** et l'empreinte SHA-256 de l'image avant lancement. Le programme se lance exclusivement dans un **terminal Windows interactif** avec les paramètres `--pcmhammer-program --pcmhammer-exe <executable> --image <image.bin> --device <nom-pilote-J2534> --vin <VIN> --sha256 <SHA256> --backup <chemin-original.bin> --mode calibration` (ou `full`).

Avant toute programmation, ce mode lance l'identification physique avec PCM Hammer, vérifie les checksums hors ligne avec le programme tiers, réalise **deux lectures complètes indépendantes** de l'ECU et compare les SHA-256, relit l'identité, puis demande une confirmation locale exacte. Il lance ensuite la commande d'écriture du programme tiers suivie de sa vérification. Le backup précédent n'est jamais écrasé. Une lecture absente, un résultat incohérent ou une confirmation manquante arrête la procédure. **Aucune relance d'écriture automatique** après échec.

Il s'agit d'une **intégration logicielle non encore validée sur ECU réel** : avant tout usage hors banc, contrôler le comportement du logiciel tiers et de la version choisie de son CLI, vérifier les identifiants réels et documenter une récupération démontrée. Les autotests CI ne remplacent pas ces essais. Les fonctions natives d'écriture du pont réseau CamaroDiag restent verrouillées.
