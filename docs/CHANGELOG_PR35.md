# Nouveautés — Sniffer et console CAN

## 2026-11-19
- Ajout de l'analyse des captures CAN et de l'historique des échanges OBD-II / UDS.
- Amélioration du décodage des trames et des messages de diagnostic, y compris les défauts moteur (DTC).
- Ajout des filtres RX/TX, de la recherche dans les trames et de l'export des captures.
- Amélioration de la console de diagnostic et des commandes de lecture disponibles.
- Amélioration de la prise en charge des adaptateurs Bluetooth, BLE, USB et Wi-Fi.

## 2026-11-20
- Regroupement de la capture et des échanges dans une console CAN avec historique chronologique.
- Ajout de l'envoi de commandes et de trames depuis la console, dans les limites prises en charge par l'adaptateur.
- Amélioration de l'affichage des réponses de l'adaptateur et des trames reçues.
- Amélioration de l'export de l'historique, y compris lorsque l'analyse ne détecte pas encore de trames.
- Amélioration de la stabilité des connexions et des échanges avec les adaptateurs BLE.
- Après une écriture BLE interrompue ou expirée, la connexion concernée est abandonnée avant toute nouvelle tentative ; une ancienne réponse tardive ne peut plus valider une commande suivante.
- Amélioration de la gestion des captures interrompues et des reconnexions.
- Lorsqu’une session BLE est fermée ou interrompue, une lecture en attente est réveillée immédiatement au lieu d’attendre l’expiration de son délai.
