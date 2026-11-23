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
