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
