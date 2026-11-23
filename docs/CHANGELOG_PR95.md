# PR #95 — Évolutions utilisateur

- Console CAN/OBD avec historique émission/réception et commandes explicites.
- Amélioration des échanges entre application Android et pont J2534 Windows.
- Retrait des restrictions de protocole ajoutées par CamaroDiag lorsqu'elles ne sont pas exigées par l'ECU.
- **Avant une transmission potentiellement modificatrice** : avertissement de risque avec OK, puis confirmation Oui / Non. Non et fermeture annulent sans émission.
- Licences CamaroDiag et exigences réelles du matériel/constructeur conservées.

Les compatibilités d'écriture non testées sur calculateur réel restent à valider.

## Vérification du bloc ECU et reprise des chantiers (23 novembre 2026)
- Ajout d'une validation pré-effacement et pré-transmission vérifiant la correspondance exacte région/adresse/taille/données avec l'image modifiée sur Android et avec le contenu de la trame de programmation E39 sur Windows.
- Des tests de régression simulent une erreur d'adresse, de longueur, de secteur, de données et de checksum et doivent empêcher l'envoi.
- Règles durables ajoutées à la racine du dépôt (`AGENTS.md`), aux consignes Copilot et au prompt de reprise pour les futurs chats.
- Audit de reprise de tous les chantiers C1/C2/C4/C6/C8 documenté dans `docs/AUDIT_PR95_C1_C2_C4_C6_C8.md`.
- **Validation du dernier lot : à constater sur le HEAD et la campagne Debug complète C8.** Pas d'écriture sur ECU réel ni de firmware universel déclaré.

## Sauvegarde préalable réelle des blocs ECU — correction du 23/11/2026
- Avant d'effacer un secteur, le moteur Android et le flux E39 Windows sauvegardent désormais **les octets physiquement relus** sur le calculateur, avec adresse/taille/empreinte, dans des fichiers séparés synchronisés sur disque et revérifiés.
- Arrêt avant effacement en cas d'écart entre les octets du calculateur, l'image d'origine, le secteur prévu ou la sauvegarde enregistrée.
- Le bloc réellement envoyé reste comparé à la bonne adresse, longueur, zone et séquence d'octets de l'image cible, y compris checksum du paquet E39.
- Règle transversale inscrite dans `AGENTS.md`, `.github/copilot-instructions.md` et `docs/PROMPT_REPRISE.md`.
- La qualification sur ECU/adapter réel et la récupération complète ne sont toujours pas démontrées ; C2/C6/C8 ne doivent pas être présentés comme validés matériellement.

## C2 — préparation d'images de restauration hors ligne (23 novembre 2026)
- Assemblage des régions mémoire à partir de lectures indépendantes dont les octets sont cohérents, uniquement lorsque la cartographie physique fournie couvre exactement les adresses attendues sans trou ni chevauchement.
- Création de fichiers image par région contenant les octets originaux, le VIN, l'identité du calculateur/firmware, l'adresse absolue, la longueur et l'empreinte SHA-256. Écriture dans un fichier neuf, synchronisation disque et vérification par relecture.
- Tests ajoutés pour l'intégrité, l'immuabilité, les régions incomplètes, les mauvais plans mémoire, l'altération et la troncature.
- **Statut : code commité, tests de ce complément non encore exécutés.** L'image d'une région n'est pas une sauvegarde complète ECU : il manque toujours la qualification de la cartographie exhaustive et le pilote de restauration physique vérifié. Les protocoles standard restent hors de ce lot et sont traités séparément sur la même PR.

## C2 — images de restauration multi-régions (suite)
- Vérification stricte de la couverture : aucune capture ECU ignorée, prise en charge de régions disjointes et rejet d'une région trop volumineuse pour le format.
- Ajout d'un dossier de sauvegarde multi-régions contenant une image indépendante par région et un index de contrôle contenant les empreintes SHA-256 des fichiers (contrôle d'intégrité, **sans signature ni autorisation ajoutée**). L'index n'est publié qu'après les images ; ouverture refusée si les fichiers manquent, diffèrent, se chevauchent ou ont été altérés.
- Raccordement logiciel de la double capture J2534 vérifiée à l'export d'une image de région, sans modifier les couches de protocole.
- Tests ajoutés pour interruptions, altération, VIN différent, provenance simulée, carte mémoire incomplète et deux régions disjointes.
- **Pas d'affirmation de sauvegarde complète de l'ECU ni de restauration physique** : cartes mémoire et pilote réel par firmware encore manquants. Les tests de ces commits ne sont pas encore exécutés afin de respecter la campagne regroupée par chantier et l'autre intervention prioritaire sur les protocoles.

## C2 — contrôle d'intégrité des images et vérification de restauration (session suivante)
- Revalidation des octets importés contre le manifeste au moment même de l'assemblage de l'image : une capture modifiée après import ne peut plus être publiée sans être détectée.
- Limitation explicite de la taille de chaque image et de la somme des images, et vérification de la forme des identifiants ECU dans les métadonnées.
- Ajout d'une comparaison indépendante des octets et des adresses physiques après restauration, incluant toutes les régions attendues et l'adresse exacte du premier écart. Ce composant est **hors ligne**, ne commande aucune écriture et ne remplace pas la validation physique d'un pilote.
- Tests de régression ajoutés pour modification des deux lectures après vérification, mauvaise adresse, longueur, octets différents, régions manquantes ou dupliquées.
- **Validation de ce complément : non encore exécutée**, chantier C2 non clôturé ; qualification et carte mémoire réelles toujours à établir. Aucune modification des protocoles gérés par l'autre chat.

## C2 — couverture explicite des régions de sauvegarde (suite)
- Les objets image refusent à la construction une différence entre la taille de leur région mémoire et le nombre réel d'octets conservés.
- Ajout d'un rapport de couverture du plan mémoire fourni : statut « carte inconnue », « invalide », « partielle » ou « couvre le plan fourni », avec adresses et longueurs exactes des lacunes.
- Les limites physiques et l'exhaustivité du plan constructeur ne sont jamais déduites du simple contenu d'une archive. Une couverture du plan fourni n'est **pas** une image garantie complète ECU.
- Tests ajoutés pour les tailles fausses, les cartes mémoire inconnues, les trous et les captures hors périmètre.
- **Code commité, validations non lancées sur ces commits** ; aucune qualification de restauration réelle annoncée.

## C2 — paquet transportable de sauvegarde des images ECU
- Export des régions d'image déjà vérifiées dans **un fichier ZIP unique**, contenant index et données binaires, sans changer les protocoles de communication.
- Réimport dans un nouveau dossier uniquement ; vérification des noms attendus, limites de volume, fin effective de ZIP, index de couverture et SHA-256 des régions. Rejet des ZIP tronqués, altérés, avec fichiers inattendus ou membres non conformes.
- Tests ajoutés pour aller-retour des octets, adresses physiques et VIN, ZIP incomplet et chemins non attendus.
- Cette archive reste **une sauvegarde portable hors ligne**, et non une image certifiée complète et restaurable sur le véhicule : la cartographie intégrale et le pilote OEM de programmation/récupération physique demeurent à qualifier.
- Tests de ces modifications **non encore exécutés**, conformément à la règle de validation par chantier.

## C4 — historique des lectures carburant LFX, hors protocoles
- Nouveau calculateur d'historique en mémoire (`Lfx2012FuelSessionHistory`) pour les PID éthanol et corrections de carburant déjà décodés en lecture : valeur initiale/finale, minimum/maximum, évolution si au moins deux mesures valides, nombre de mesures absentes/non supportées.
- Liaison stricte des échantillons à un VIN, une session et une source uniques, horodatages croissants, historique borné ; le 0 % déclaré n'est pas une absence de réponse.
- Aucun PID supplémentaire, aucune commande ECU, aucune activation E85 ni affirmation de capteur/firmware FlexFuel réellement validé.
- Régressions écrites dans `Lfx2012FuelSessionHistoryTest.kt`. **Pas de tests exécutés sur le nouveau HEAD et pas d'intégration écran annoncée.**

## C8 — inventaire non-protocole pendant le chantier parallèle (23/11/2026)
- Revue documentaire du diff de la PR #95 (253 fichiers lors de l'inventaire) et séparation **provisoire** entre C1/C2/C4/C6, protocoles standard gérés par l'autre chat, évolutions C3/C5/C7 à reporter et modifications à justifier avant fusion.
- Résultat dans `docs/C8_PARALLEL_NON_PROTOCOL_REVIEW.md` ; les choix de report nécessitent encore examen au niveau des fichiers/commits, sans suppression ni revert aveugle.
- **Pas de CI du HEAD courant** et aucune qualification matérielle revendiquée. La validation finale C8 attend la stabilisation des chantiers et le contrôle de tous les jobs sur une révision figée.

## C1 — intégrité de l'archive brute CAN pendant une capture longue (suite)
- `CanConsoleRawArchiveWriter` conserve les octets reçus tels quels et distingue les erreurs de stockage des déconnexions du transport.
- Une erreur d'écriture ou de vidage disque marque l'archive comme incomplète ; l'enregistrement ne peut plus reprendre silencieusement dans ce même objet. Une archive n'est annoncée complète qu'après fermeture sans erreur.
- La fermeture tente un vidage final puis ferme systématiquement le flux, même si ce vidage échoue. Tests ajoutés : archive intacte, écriture refusée après fermeture, échec persistant, échec de vidage, libération des ressources.
- **Chantier C1 non encore validé sur ce HEAD** : nouveaux tests commitées mais non exécutés ; intégration de l'indicateur `isComplete` au parcours d'export et essais matériels à contrôler avant annonce de disponibilité.

## C4 — export des historiques carburant sans modification ECU
- Ajout de `Lfx2012FuelHistoryCsv.kt` : export CSV des échantillons d'une session préalablement validée, avec VIN, session, source, horodatage, PID, statut réel de disponibilité, pourcentage et octet brut.
- Les cellules numériques absentes restent vides (pas de substitution par 0 %) ; les identifiants de session sont échappés pour le format CSV et les préfixes de formules tableur neutralisés.
- Tests ajoutés pour 0 % déclaré, mesure absente, CSV et rejet de sessions mélangées avant écriture dans le fichier.
- Module de génération hors ligne **non encore relié à un bouton d'export utilisateur** ; aucun protocole ni commande ECU modifié. Nouveaux tests **non exécutés** avant la campagne du chantier.

## C4 — raccordement Android des relevés carburant et export CSV
- L'écran LFX 2012 propose maintenant l'export CSV des relevés accumulés lorsqu'un historique est présent ; les libellés et notifications sont en ressources **français/anglais**.
- `MainViewModel` conserve une fenêtre bornée à 512 relevés au plus, liée au VIN sélectionné, à la génération de compte/connexion et au transport observé ; tout changement de contexte ou chronologie incompatible remet le jeu de mesures à zéro.
- L'export utilise le dépôt privé `CamaroDiagExportStorage`, une vérification du contexte avant publication et les données réellement issues des lectures précédentes. Aucune commande ECU supplémentaire ni programmation FlexFuel.
- Les identifiants de session refusent les caractères de contrôle et les tailles excessives ; le CSV protège aussi les préfixes de formules précédés d'espaces. Nouveaux tests écrits.
- **Statut : code et IHM raccordés, compilation / tests du HEAD actuel non exécutés, aucune recette matérielle.** Ne pas confondre cet export de mesures avec une preuve d'installation d'un capteur FlexFuel ou d'activation E85.

## C1 — validation de l'archive au point de publication
- La fin d'une capture CAN dans `MainViewModel` n'annonce le succès qu'après la fermeture et le vidage effectif du flux brut et une vérification explicite de `CanConsoleRawArchiveWriter.isComplete`. Une archive déclarée incomplète passe par le chemin d'échec au lieu d'être présentée comme un enregistrement terminé.
- Modification de l'intégration applicative uniquement, sans changement de protocole ni de trames.
- **Validation de ce correctif sur le HEAD : non encore exécutée**, chantier C1 non clôturé ; ne pas annoncer d'essai de capture physique.

## C4 — synthèse visible des mesures éthanol
- L'écran LFX 2012 affiche le nombre de mesures éthanol **réellement rapportées** et les valeurs min/max extraites de l'historique de la session, sans transformer l'absence de réponse en 0 %.
- `MainViewModel` recalcule la synthèse à chaque relevé accepté et l'efface lors de la remise à zéro du contexte véhicule. Les chaînes IHM utilisent les ressources FR/EN ; la synthèse n'atteste ni capteur physique ni activation d'une calibration E85.
- Le raccordement C1 impose désormais `CanConsoleRawArchiveWriter.isComplete` avant de publier le succès de la capture CAN.
- Une modification concurrente a été détectée sur la branche pendant l'ajout des ressources ; le fichier français a été relu puis modifié sans réécrire les changements de l'autre chat.
- **Aucune nouvelle compilation ou exécution des tests sur ce HEAD** : vérification C1/C4 et intégration protocoles restent à valider après stabilisation.

## C4 — extraction du tampon d'historique et documentation utilisateur
- La gestion des 512 relevés conservés, de la réinitialisation par véhicule/session/source et des horodatages non croissants est isolée dans `Lfx2012FuelHistoryWindow.kt`, indépendamment du ViewModel et des protocoles ECU. `MainViewModel` utilise ce composant pour la synthèse de l'atelier et l'export CSV.
- Tests unitaires dédiés : fenêtre circulaire après 513 relevés, réinitialisation de session, retour arrière d'horodatage, absence de réponse préservée et copie indépendante du jeu d'observations.
- Documentation d'utilisation des relevés et de l'export CSV mise à jour en français et anglais. L'historique est **en mémoire**, non un enregistreur en continu ; aucune commande ECU ajoutée.
- **Qualification :** code et tests écrits sur la branche ; aucun build ni nouvelle exécution des tests du HEAD par cette intervention, ni validation physique.

## SAE J1939-21 — fenêtres RTS/CTS (23 novembre 2026)
- Réception multipaquets : les CTS sont limités à la fenêtre maximale déclarée dans le RTS distant, même si la fenêtre locale est plus grande ; BAM reste indépendant de cette limite.
- Autotest ajouté avec RTS limité à un paquet, trois fenêtres CTS successives, reconstruction des octets et acquittement EOM_ACK.
- Commits `c849df3`, `071b4c0`. Tests ajoutés **non exécutés** à ce stade ; aucune qualification matérielle ou véhicule revendiquée.
- Filtrage des abandons `TP.CM Abort` pendant la réception `TP.DT` : une interruption ne s'applique qu'à la destination et au PGN de la session active. Tests ajoutés pour abort d'un autre PGN et abort correspondant (`f8529cb`, `adc71f9`), non encore exécutés.

## ISO-TP étendu — tranche logicielle (23 novembre 2026)
- Trames SF, FF, CF et FlowControl avec octets d'adressage logique TX/RX pour CAN et CAN-FD ; réduction correcte des charges utiles par trame et filtrage des réponses par adresse.
- CLI `--tx-address` / `--rx-address` distincte de `--extended` qui règle l'identifiant CAN 29 bits.
- Tests logiciels de fragmentation, relecture et corrélation ajoutés ; **non exécutés**. Pas de preuve de prise en charge de l'adressage mixte, de matériel réel ni de programmation ECU.

## C1/C4 — tests de robustesse et validation regroupée (session suivante)
- **C1 :** arrêt manuel de la capture CAN conditionné par la fermeture réussie de son archive. Une erreur de vidage/fichier éventuellement masquée par l'annulation de coroutine produit un événement d'échec et interdit de présenter la sauvegarde comme complète. Tests pour Stop propre/incomplet et échecs simultanés de vidage/fermeture.
- **C4 :** correction des tests d'identifiants CSV, qui vérifient maintenant de véritables caractères de contrôle au lieu des deux caractères littéraux `\\n` / `\\t`. Tests supplémentaires pour changement d'interface et horodatage identique dans l'historique de 512 relevés.
- **C4 validation :** demande d'un **unique contrôle groupé Debug** déposée via `.github/c4-flexfuel-readonly-gate-request.txt` ; résultat du workflow non confirmé dans cette intervention. Il est interdit de conclure à des tests réussis sans consulter les étapes et le rapport du run.
- Aucun fichier des transports K-Line/DoIP/CAN-FD/SAE n'a été édité pour ces correctifs. Aucun essai ECU réel et aucune fusion de la PR #95.

## Renforcement sessions ISO-TP et J1939 — 23/11/2026
- ISO-TP : les transferts partageant un même identifiant CAN restent distinguables par des adresses logiques TX/RX différentes ; un identifiant et des adresses identiques pour les deux directions sont refusés comme ambigus. Scénarios ajoutés pour CAN-FD, FlowControl et identifiant partagé.
- J1939-21 : messages RTS/BAM/CTS/EOM de PGN concurrents ignorés pendant une autre session ; corrélation stricte de l'abandon en cours, sans rejeter tout le bus partagé.
- Tests de non-régression ajoutés et enregistrés, **non exécutés** à ce stade ; validation du compilateur et essais hardware en attente. Aucun support J1939-22 revendiqué.

## C1 / C4 — correction des états périmés et finalisation des exports (suite)
- **C1 :** sur arrêt manuel, archive brute non fermée correctement = erreur visible, export interdit. L'export déjà lancé revérifie le contexte de console et la santé de l'archive juste avant publication, afin de refuser une capture interrompue entre-temps.
- **C4 :** un changement de compte ou de connexion efface immédiatement les mesures carburant et leur synthèse ; l'enregistrement de relevés valides se fait sur le dispatcher UI après contrôle de la génération active. Plages min/max des corrections STFT/LTFT des deux bancs ajoutées à l'écran en FR/EN, sans convertir les données manquantes en valeurs.
- **Régressions :** correction des véritables caractères de contrôle dans le test CSV ; tests de Stop, fermeture d'archive, génération de session et horodatages étendus.
- **C8 :** demande groupée C4 soumise ; **résultat non confirmé**, ne pas déduire un build vert de la demande de CI. Le HEAD stable et les composants physiques restent à qualifier.

## ISO-TP — DLC de réception indépendant (23/11/2026)
- Alignement avec ISO 15765-2:2024 : la taille `RX_DL` est établie depuis la première trame reçue du pair ; la taille d'émission locale ne sert pas à valider les trames CF reçues.
- Tests ajoutés : émetteur local FD 64 octets, pair à 12 octets, et CF non terminal tronqué rejeté.
- CLI ISO-TP : mode `receive` sans requête diagnostique sortante, avec FlowControl uniquement si une trame FF exige un échange multipaquet. Contrôle du mode/payload complété.
- Tests écrits et commités, **validation CI non exécutée** sur ces changements ; pas de preuve sur adaptateur ou ECU réel.

## ISO-TP — tolérance FlowControl WAIT (23/11/2026)
- Ajout de `MaxWaitFrames` paramétrable (CLI `--wftmax`, 0 à 255, 4 par défaut) pour les réponses FC/WAIT avant CTS. Le dépassement arrête les nouvelles CF.
- Tests de limites et de reprise après deux WAIT ajoutés, non encore exécutés ; pas de validation ECU physique.

## DoIP — identification UDP unicast (23/11/2026)
- Découverte ciblée d'une passerelle connue avec message 0x0001 et réponse 0x0004, extraction VIN/adresse logique/EID/GID et statut facultatif.
- Commande `--doip-discover --host <IPv4>` ; aucune émission UDS ni basculement vers une autre fonction. Tests codec ajoutés, non exécutés ; broadcast, TLS et banc physique restent ouverts.

- DoIP UDP : ajout des requêtes d'identification par VIN (0x0003) ou EID (0x0002) et du traitement explicite des annonces sans VIN disponible. Tests de codecs et de longueurs ajoutés ; non exécutés.

- DoIP : diffusion UDP explicite vers l'adresse indiquée via `--broadcast-ip`, réception bornée à 32 identités, séparation des réponses par adresse source et logique. Aucun UDS envoyé. Tests matériels et intégration réseau non exécutés.

## C1 / C4 — compléments de la session parallèle
- **C1 :** exports CAN explicites sur leur couverture : fenêtre de 300 derniers événements TX/RX/SYSTEM, archive brute RX intégrale si disponible, sinon extrait mémoire. Affichage d'une notice de limite dans la console et textes FR/EN ; tests de conservation des fragments d'une capture dépassant 128 Kio.
- **C4 :** l'historique carburant est effacé immédiatement lors des changements de compte, de connexion, de pont J2534 ou de simulation. Les synthèses STFT/LTFT des deux bancs sont affichées lorsqu'il existe des réponses valides. Une réponse PID physique absente ou vide ne peut plus être transformée en mesure par des octets de décodage isolés.
- **C8 :** tests de parité FR/EN des nouvelles notices et contrôles d'intégrité ajoutés. Le journal persistant complet des événements TX/RX/SYSTEM n'existe pas encore ; il reste une condition de clôture de #59.
- Le déclenchement d'une campagne C4 Debug ciblée a été demandé une fois sur une révision intermédiaire ; son résultat n'est pas vérifié ici et ne qualifie pas les changements ultérieurs. Aucun essai sur matériel physique ni merge.

## KWP2000 / J2534 — réceptions vérifiées et modes (23/11/2026)
- Parseur de trame physique ISO 14230 : source, destination, longueur 1–255, checksum additif ; tests pour payloads 1/63/64/255, altération et troncature.
- Vérification RX KWP optionnelle côté Windows quand le fabricant fournit des trames brutes complètes. Mode `--mode receive` sans émission diagnostique pour les canaux J2534 (K-Line/J1850 inclus), distinct du mode `exchange` avec double consentement.
- Tests de CLI ajoutés, aucune exécution/qualification matérielle à ce stade.

- DoIP : une réponse d'identification ciblée par VIN ou EID doit maintenant correspondre exactement à l'identifiant demandé, en découverte unicast comme broadcast. Tests de rejet des autres identifiants ajoutés (non exécutés).

## C8 — revue statique des derniers correctifs C1/C4
- Ressources FR/EN des notices d'export et de l'historique CAN vérifiées présentes chacune une seule fois ; correction de l'espacement du marqueur d'échec à l'export, sans compter sur les espaces initiaux des traductions Android.
- Tests C1 révisés pour importer les assertions nécessaires et utiliser des retours CRLF réels dans la simulation de capture longue.
- **Statut de validation :** un déclenchement C4 ciblé a été demandé, mais l'accès courant ne confirme ni l'exécution ni sa réussite. Les derniers commits ne sont donc pas qualifiés sur un build Debug/CI final, et la restitution du chantier reste partielle tant que l'historique TX/RX persistant et la recette matérielle manquent.

## SAE J1939-21 — fermeture des transferts CMDT en erreur (23/11/2026)
- Construction des trames TP.CM Conn_Abort avec PGN et adresse pair ; émission de reason 3 en cas de timeout CTS/DT et reason 7 après erreur de séquence DT. Aucun abandon sur BAM ni après annulation de l'opérateur.
- Tests de régression ajoutés pour expiration CTS, expiration après CTS, mauvaise séquence DT et format des octets d'abandon.
- Limite : aucune retransmission TP.DT facultative ni gestion multi-session complète, et **tests non exécutés / matériel non qualifié**.

## J1939-73 — DM1/DM2 (23/11/2026)
- Décodeur DTC/état des voyants pour les PGN FECA/FECB, extraction SPN 19 bits, FMI, occurrences et bit CM après assemblage J1939-21. Affichage CLI en réception, avec trame brute conservée.
- Tests sur trame directe rembourrée, deux DTC multipaquets et absence de codes ajoutés ; **non exécutés**.

- Correction J1939 DM : lorsque le bit CM indique une méthode ancienne, conserver le DTC brut (4 octets) et afficher SPN « undecoded » au lieu de calculer un SPN selon la méthode moderne ; test de régression ajouté. La méthode ancienne doit encore être qualifiée selon l'ECU et la documentation SAE.

## C1 — journal persistant unifié (travail en cours, 23/11/2026)

- Ajout du composant `CanConsoleEventJournalWriter` pour consigner dans l'ordre TX/RX/SYSTEM, sans limite mémoire de 300 événements, avec en-tête CSV, numéro de séquence, horodatage et état de réponse.
- Tests ajoutés pour 600 événements, échappement des contenus multilignes, refus après erreur disque et échec de fermeture.
- Commits : `f493b97`, `03daf32` ; **tests non exécutés** à ce stade. Composant **non raccordé à l'interface ni à la sauvegarde persistante** : ne pas présenter l'archivage intégral TX/RX/SYSTEM comme livré. Les protocoles restent pris en charge par l'autre chantier.

## DoIP — réponse Alive Check (23/11/2026)
- Réponse standard `0x0008` aux requêtes passerelle `0x0007` pendant l'activation de routage et pendant les échanges UDS, avec adresse du testeur et contrôle de format/version.
- Régressions codec ajoutées, non exécutées ; session réelle non qualifiée.

## ISO-TP : adressage mixte explicite (23/11/2026)
- Mode N_AE partagé entre TX et RX avec identifiants CAN 29 bits distincts, `--extended true --mixed-address` ; tests de cohérence, réception et refus des paramètres incohérents ajoutés. Pas de qualification physique ni de CI sur ce commit.

## J1939-81 — identification passive (23/11/2026)
- Ajout du décodeur des annonces `Address Claimed` (PGN EE00), extraction NAME 64 bits et de ses champs d'identité ; affichage dans le mode réception J1939. Aucun `Address Claim` actif ni conflit d'adresses implémenté. Tests unitaires ajoutés, non exécutés.

## DoIP — connexion TLS certifiée (23/11/2026)
- Option CLI `--tls true` ; transport TCP 3496 sous TLS 1.2/1.3, validation système du certificat, nom d'hôte et révocation ; pas de mode « accepter tout » ou de repli implicite sur TCP 13400.
- Tests de parsing CLI ajoutés (non exécutés) ; certificats clients et autorités OEM privées non intégrés, essais matériels absents.

## C1 — journal complet de la console Android (23/11/2026, suite)

- Le journal CSV chronologique TX/RX/SYSTEM est maintenant raccordé aux événements de la console Android : capture monitor avec trames RX, requêtes TX OBD et CAN, réponses physiques, erreurs et arrêts.
- Les 300 entrées restent uniquement une fenêtre d'affichage ; l'export lit désormais les événements dans le fichier privé complet puis ajoute la capture RX brute lorsqu'elle existe. L'export contrôle la génération du journal pour ne pas publier une capture périmée et refuse les archives en erreur.
- Les fichiers sont isolés par réinitialisation du contexte/compte/véhicule, nettoyage explicite et destruction du ViewModel ; les caractères multilignes et les formules CSV injectées sont neutralisés.
- Tests ajoutés : chronologie sur 600/620 événements, séparation des sessions, invalidation après nouvelle écriture, erreurs disque/fermeture et neutralisation de formule.
- Commits de cette suite : `5b0d909`, `e26931f`, `bd07e6d`, `a2299dd`, `0455d68`, `ca997eb`, `ac5dcc4`, `56bf77b`, `01ece43`, `a70c250`, `347e6e5`, `0cbbb66`.
- **Validation à réaliser** : aucune campagne CI lancée pour ces commits ; l'exact HEAD est susceptible de changer avec l'autre chantier protocoles. Exiger une campagne C1 Android ciblée sur le code figé et ensuite la validation C8 au HEAD final. Les acquittements TX sont journalisés à l'instant de leur émission (état initial pending) ; la suite RX/SYSTEM donne leur résultat, sans inventer de réponse ECU. Aucun essai matériel.

## J1939 — requête standard de PGN (23/11/2026)
- Codec PGN Request 0xEA00, sérialisation little-endian sur trois octets ; commande `--mode request` pour transmettre la requête dirigée sous double confirmation, puis attendre le PGN demandé. Tests codec/CLI ajoutés, non exécutés.
- Réponses ACK/NACK PGN 0xE800 et revendication d'adresse physique J1939-81 restent non traitées/qualifiées.

- J1939-21 : traitement des réponses ACKM PGN E800 en mode requête, y compris NACK, accès refusé et corrélation au PGN attendu. Tests de sessions concurrentes ajoutés, non exécutés ; un ACK ne certifie pas l'exécution d'une commande ECU.

## DoIP — réception passive des annonces (23/11/2026)
- Option `--doip-discover --listen true` : écoute UDP 13400, collecte bornée des annonces sans émission. Port disponible et pare-feu nécessaires ; aucun essai réel confirmé ni CI exécutée.

- Correctif de réception strictement passive J2534 : `--mode receive` refuse désormais `--kline-init`, car une initialisation 5 bauds/rapide émet physiquement sur le bus. Test de garde ajouté ; non exécuté.

- Correction de conformité ISO-TP : l'adressage mixte N_AE n'est pas limité aux CAN 29 bits ; la CLI autorise désormais aussi les identifiants 11 bits. Tests de mode 11 bits et refus de mélange avec l'adressage logique étendu ajoutés, non exécutés.

## DoIP — états d'entité et d'alimentation (23/11/2026)
- Ajout des requêtes UDP ISO 13400 `0x4001/0x4002` (sockets, type d'entité et taille maximale) et `0x4003/0x4004` (état d'alimentation diagnostique), par la CLI `--doip-status`.
- Tests de décodage normal, optionnel et malformé ajoutés, non exécutés. Aucun diagnostic actif ni autorisation de flash impliqués.

### C1 — contrôle d'intégrité du journal, suite

- L'export de la console refuse désormais l'archive en cas d'échec de persistance détecté dans le magasin, de journal absent alors que des événements sont affichés ou de fichier supprimé.
- L'initialisation qui échoue supprime le fichier temporaire, plutôt que de laisser une archive orpheline présentée comme disponible.
- Le contrôle du bouton Export n'effectue plus de flush disque à chaque recomposition UI ; la vérification complète du journal reste effectuée au lancement de l'export.
- Régressions ajoutées : fichier supprimé, réinitialisation après erreur et répertoire de destination non valide.
- Commits : `cf4230e`, `1356519`, `9b01a3b`, `0421ace`, `7efc7e3`.
- **Tests non vérifiés au dernier HEAD.** La campagne C1 précédente ne prouve pas ces correctifs. Pas de déclaration de compatibilité physique.


## J1979-2 et contrôle UDS homogène (23/11/2026)
- Helper `ObdOnUdsServices` : construction des requêtes par DID d'émissions ou masque DTC, extraction des réponses UDS DID et DTC 24 bits + statut, sans interprétation constructeur inventée.
- Options `--obd-did` et `--obd-dtc-mask` pour les commandes ISO-TP/DoIP existantes ; exclusivité des sources TX et interdiction de TX en réception passive. Tests de parseur, requêtes et réponses ajoutés.
- DoIP : même validation UDS que les autres transports, au lieu de contrôles de SID/DID dupliqués. Les NRC 0x78 / Alive Check / ACK sont limités par le délai de l'opération plutôt que par 12 messages arbitraires.
- Correctifs CAN : ignorer les trames J1939 non corrélées malformées avant le parseur, refuser les trames ISO-TP CF plus grandes que le RX_DL du FF et les FF sous-dimensionnées. Tests de régression ajoutés.
- J1939-81 : construction de la trame Address Claimed et comparaison NAME prioritaire hors ligne, sans revendication réelle sur le bus.
- **Tests ajoutés mais non exécutés** ; CI du lot et qualification matérielle toujours en attente.


## SAE J1939-22 — FEFF Multi-PG (23/11/2026)
- Nouveau codec CAN-FD FEFF Multi-PG PGN 2500 : émission et réception de plusieurs C-PG avec TOS 2, TF 0, en-tête de 4 octets et DLC CAN-FD canoniques, sans prétendre décoder les autres formats d'assurance.
- Commande Windows `--j1939-fd-multipg` bidirectionnelle, canal J2534 FD_CAN brut, BRS et data baud optionnels (négociés via pilote), confirmation double en émission, filtrage de groupe dans les réponses.
- Tests offline du codec, des trames malformées, PDU1/PDU2, CLI, modes passifs et BRS enregistrés dans `J1939FdMultiPgSelfTests`, exécutables depuis `BridgeSelfTests` mais **non encore exécutés**.
- FD.TP des messages longs, FBFF, assurance data OEM, adresse réseau J1939-81 et validation physique restent incomplets.

## Corrections indépendantes des protocoles — C1 et C2 (23/11/2026)

**C1 — archivage des événements console :**
- Le magasin du journal est définitivement mis hors service à la destruction du ViewModel. Un callback tardif d'une coroutine annulée ne peut plus créer une nouvelle archive après nettoyage.
- Pendant l'enregistrement, si le fichier de journal a disparu, la capture devient incomplète et son export est refusé (y compris lorsqu'un descripteur fichier resterait ouvert).
- Tests de régression pour destruction définitive, archive disparue entre deux événements et impossibilité d'exporter une session invalide.
- Commits : `d45c76a`, `ad57d61`, `3beb0ee`, `de2586e`, `6855250`.

**C2 — construction des images de restauration hors ligne :**
- Contrôle strict par région (64 Mio) et global (256 Mio), sans addition de `Long` susceptible de déborder ; rejet avant assemblage des octets ou création du dossier de sortie.
- Tests ajoutés sur une longueur `Long.MAX_VALUE` et une carte de cinq régions de 64 Mio dépassant le budget global.
- Commits : `1a3668e`, `1240a7d`.
- **Limitation majeure inchangée** : ces corrections portent uniquement sur la préparation hors ligne d'une image ; elles ne constituent ni une cartographie OEM complète, ni un pilote de flash, ni une récupération réelle d'ECU.

**Validation** : les tests de ces correctifs ne sont pas encore confirmés par un rapport GitHub Actions au SHA final. Ne pas annoncer de compilation réussie ou de qualification véhicule pour ces changements. Aucun protocole standard n'a été modifié dans ce lot.

### C1 — statut des requêtes dans le CSV complet

Les événements TX sont enregistrés au moment de leur émission avec le statut `PENDING`, sans indiquer faussement un échec. Les événements de réception et système portent `OK` ou `FAILED` selon leur résultat observable ; le journal conserve la séquence chronologique et n'infère pas un accusé ECU de la seule confirmation d'un adaptateur. La colonne CSV `succeeded` est remplacée par `status` pour les nouveaux exports. Tests mis à jour (commits `cc74672`, `e3b085d`, `0d5ac24`). Tests et compilation à confirmer à la clôture du lot.


## SAE J1939-22 — FD.TP BAM / RTS-CTS (23/11/2026)
- Création des codecs `J1939FdTransportFrames` pour FD.TP.CM (4D00) et FD.TP.DT (4E00), numéros de session et champs 24 bits. Tests sur RTS/CTS, trames DT, DLC, formats invalides.
- Transport BAM `J1939FdBamSession` : segmentation/réassemblage, 60 octets utiles par trame CAN-FD, EOM_STATUS et tests interruption/séquence/longueur.
- Transport CMDT `J1939FdCmdtSession` : RTS, CTS par fenêtres, segments DT, EOM_STATUS/EOM_ACK et Abort sur expiration. Tests d'envoi/réception avec faux CAN et réponses contradictoires.
- CLI Windows : `--j1939-fd-bam` et `--j1939-fd-cmdt`, `--mode send|receive`, paramètres FD_CAN/BRS, session et fenêtre, fichier binaire borné, consentement avant émission.
- Suites de régression enregistrées dans `BridgeSelfTests`. **Pas de build/CI encore exécuté sur le HEAD, ni validation d'adaptateur ou d'ECU réel.** Les variantes J1939-22 non couvertes, concurrence réelle et SAE J1939-81 actif restent ouverts.

### C2 — publication atomique logique de l'index du paquet restaurable hors ligne

- L'import ZIP conserve `index.ecu-package` en mémoire (limité à 64 Kio) tant que toutes les entrées et le footer ZIP n'ont pas été vérifiés ; les régions extraites sont synchronisées sur disque. L'index n'est créé/synchronisé qu'à la fin, puis le paquet entier est relu et validé.
- En cas d'erreur après la publication de l'index, il est supprimé afin que le dossier ne soit pas reconnu comme un paquet de restauration complet. Une archive tronquée ou prolongée n'est donc pas autoritaire même si ses fichiers région avaient déjà été extraits.
- Tests : ZIP tronqué, ZIP avec octets superflus, index corrompu et index placé en première entrée.
- Commits : `05adb73`, `39882bd`, `4e7b766`.
- **Limitation inchangée :** contrôle d'intégrité du paquet hors ligne uniquement ; aucun flash/rollback matériel ni carte mémoire constructeur attestée.
- **Validation** : tests ajoutés, résultat CI du HEAD courant non encore confirmé.

## C4 — export d'historique carburant stable (23/11/2026, session parallèle hors protocoles)

- Nouveau `Lfx2012FuelHistoryExportPolicy` : refuse les historiques vides, incohérents, non chronologiques, les séries modifiées et les exports lancés pendant une acquisition physique en cours.
- Le ViewModel revérifie la série active avant l'écriture et juste avant la publication dans le stockage utilisateur, en complément de la génération de compte, de connexion et de véhicule.
- Tests unitaires ajoutés : série stable, mesure en cours, ajout d'un nouvel échantillon, changement de session, horodatage non croissant.
- Commits : `e44d3cb`, `b308cc0`, `4085821`.
- Aucune activation éthanol, écriture de calibration ni modification du protocole ; toujours aucun essai du LFX physique. Validation CI du nouveau SHA non confirmée.

## C1 — fermeture robuste du journal

- Le magasin intercepte les erreurs de fermeture sans empêcher la remise à zéro du fichier et de la session ; la destruction du ViewModel reste une désactivation définitive.
- Commit `e27343e`. Tests du correctif non exécutés à la rédaction.

- Renforcement final J1939-22 FD.TP : RTS/CTS dirigé connecté à `--j1939-fd-cmdt`, refus des combinaisons de commandes contradictoires, vérification des champs EOM_ACK/EOM_STATUS, non-régression des sessions CMDT/BAM concurrentes et fenêtres de CTS du pair. Tests écrits, **non exécutés**. Aucune CI par commit ni qualification du pilote J2534 FD_CAN.

## C1/C2 — vérifications d'intégrité supplémentaires, hors protocoles (23/11/2026)

- **C1 :** l'instantané d'export conserve aussi la longueur physique du fichier de journal et l'export est invalidé si celui-ci est tronqué ou grossit après l'instantané sans changement reconnu de génération. Test de troncature externe ajouté. Commits `c5ecc21`, `0bcbe59`.
- **C2 :** l'export ZIP d'un paquet de restauration hors ligne détecte les modifications du contenu source entre copie et relecture. La vérification finale du paquet compare l'index d'origine et les régions une par une, afin d'éviter de charger deux fois en mémoire un paquet potentiellement volumineux. Tests sur index et région modifiés pendant l'export. Commits `4fbe65a`, `d076af6`, `c31637b`, `e191eb3`.
- Ces contrôles n'apportent pas de pilote de restauration, de cartographie constructeur, de preuve de sauvegarde physique exhaustive ni de qualification ECU ; aucune CI du nouveau SHA ni essai véhicule confirmés à la rédaction.

### C1 — intégrité entre deux écritures et avant l'instantané

Le magasin garde la taille confirmée par le dernier `flush`. Un fichier tronqué ou modifié en longueur **avant** l'instantané ou **entre deux événements** est désormais refusé, plutôt que d'être exporté avec une longueur arbitraire mais une génération valide. Tests sur les deux scénarios. Commits `753e09e` et `9b04e27`. Le contrôle ne remplace pas un hash complet ni la qualification matérielle.

## Connexions application, pont PC et CamaroDiagConnectors — hors protocoles (23/11/2026)

- **USB (ELM/STN et CamaroDiagConnectors) :** les boutons ASCII/CD/1 ne sont proposés que pour une carte explicitement énumérée comme CamaroDiagConnectors ; une carte KKL est exclue. Le ViewModel valide la sélection avant de quitter un adaptateur actif, puis le gestionnaire revalide l'énumération réelle avant de lancer le probe isolé. La découverte ne produit **aucun canal ECU actif**.
- **CDJ/1 :** l'action UI de capture physique du journal de connecteur a été masquée : le lecteur physique est explicitement reporté au chantier C5, donc une exportation trompeuse n'est plus proposée.
- **Autorisation Android USB :** nouvel état typé `UsbPermissionPendingException` renvoyé lorsque la boîte de dialogue système est demandée ; l'utilisateur voit une consigne FR/EN d'autoriser puis relancer la connexion, plutôt qu'un échec générique.
- **Pont Windows J2534 :** les indicateurs de disponibilité de lecture mémoire sont effacés lors d'une perte de liaison ou d'un changement de véhicule. Le formulaire d'appairage est désormais vérifié **avant** de couper une éventuelle connexion fonctionnelle (IPv4 privée, port autorisé, code TLS avec empreinte, identifiants CAN cohérents) ; le transport effectue toujours son contrôle TLS et de session indépendant lors de l'ouverture.
- Tests ajoutés : sélection USB reconnue versus ELM/KKL, configuration J2534 privée/TLS/11 et 29 bits avec rejets, messages FR/EN de permission et d'erreur de formulaire.
- Commits de ce lot : `a86fff7`, `38a9c80`, `2c45c05`, `4bd7333`, `c00c294`, `78457c1`, `773c40d`, `7c4607e`, `339690b`, `77c4202`, `ef0d6c2`, `0335be6`, `fa903d7`, `4454797`, `5fbf50e`, `15002c4`, `5fbe181`, `ad9b2f5`, `b370b93`, `9c0cc91`, `494429a`.
- **Tests non confirmés sur le HEAD final**, aucun essai USB, TLS PC ou ECU physique déclaré. Aucun protocole ISO/SAE, firmware ou commande d'écriture ECU modifié par cette intervention.

### Connexions Wi-Fi / Bluetooth — prévalidation du choix d'interface

- Le formulaire Wi-Fi OBD refuse un hôte vide, un URI, une adresse comprenant des espaces/contrôles ou un port TCP hors de 1..65535 **avant** de réinitialiser une session fonctionnelle. Les hôtes locaux ordinaires et les noms compatibles restent autorisés ; aucune modification du protocole OBD/ELM.
- Les adresses Bluetooth sont normalisées en six octets hexadécimaux séparés par des deux-points avant toute tentative de connexion, avec message d'erreur sans basculement d'adaptateur si la saisie est invalide.
- Tests unitaires de prévalidation ajoutés ; commits `333d8bc`, `8b6a9c2`, `7da6bc8`. Pas de tests sur périphérique physique confirmés.

### Pont et connexions — compléments de prévalidation

- Les champs du pont J2534 sont contrôlés côté Android **avant** la déconnexion de l'ancien adaptateur, avec test d'une paire CAN standard 11 bits et d'une paire CAN étendue 29 bits, ainsi que les refus de PIN TLS absent, cible Internet, port et formats invalides.
- Les autres interfaces acceptent aussi un contrôle local des valeurs de saisie avant bascule de transport : adresse Wi-Fi/port et MAC Bluetooth ; tests de scénarios malformés ajoutés.
- Le travail de connexion J2534 utilise désormais le compteur global `isBusy`, avec nettoyage en `finally` pour les annulations de handshake.
- Le firmware ESP32-S3 prototype n'a toujours que la découverte publique : aucun accès physique ECU activé ou attesté ici.
- Commits complémentaires : `4454797`, `5fbf50e`, `ad9b2f5`, `c98b8bb`, `333d8bc`, `8b6a9c2`, `7da6bc8`, `7666aaf`, `f85f5be`.


## Protocoles — compléments de la session du 23/11/2026
- ISO 14230 KWP2000 : codec d'adressage fonctionnel, longueurs 1–255, checksum ; constructeurs physiques/fonctionnels CLI J2534 et parseur RX opt-in, tests négatifs.
- ISO 14229 : constructeurs standards 0x24, 0x2F, 0x28, 0x85, 0x2A et validation des échos DID. Nouveau `UdsResponseSequence` pour attendre la réponse finale malgré NRC 0x78 sous délai borné, utilisé en ISO-TP avec `--uds-validate true` ou commandes OBDonUDS.
- SAE J2534 : correction des protocoles optionnels SW_ISO15765=0x8007 et SW_CAN=0x8008 ; les filtres FLOW_CONTROL et l'initialisation K-Line sont refusés en réception passive. Les échos RX et indications J2534 sont ignorés sans clore la fenêtre de lecture.
- J1939-22 : délais cumulés de transfert et par phase, bornes 30 s par appel pilote, fenêtre globale jusqu'à 10 min, abort corrélé sur erreurs CMDT, tests de faux bus et limites.
- J1939-81 : initial claim NAME choisi explicitement (250 ms), conflit de priorité, cannot-claim et réponse aux requêtes PGN EE00 pendant la fenêtre, sans suivi persistant des adresses.
- DoIP : version 2/3 sélectionnable, plafond de message en mémoire 1 Mio, source de demande et capture binaires sans écrasement, DoIP generic NACK, TLS mutuel optionnel via certificat installé sur Windows. Tests codec/CLI et fichiers temporaires ajoutés.
- Catalogue des protocoles : capacité de transport logiciel distincte des écritures ECU physiquement qualifiées.
- **Campagne Windows Debug, auto-tests intégrés et qualification matérielle toujours à lancer une seule fois à la fin du chantier.** Aucun succès supposé.
