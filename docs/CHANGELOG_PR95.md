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
