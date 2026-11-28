# Nouveautés — CamaroDiag

## En développement — PR #117 (non publié) / In development (unreleased)

### Français
- Connexion ELM327 : la recherche d'adaptateurs et la connexion sélectionnée ne se chevauchent plus. Initialisation bornée, traitement d'un ancien flux de surveillance CAN et arrêt de la session en cas d'écriture Bluetooth bloquée.
- Données temps réel : reprise de la découverte à la nouvelle connexion, sélection limitée aux capteurs effectivement lus, case et ligne sélectionnables, préférences temporaires isolées lorsque le VIN n'est pas connu.
- Diagnostic OBD : réassemblage des réponses DTC CAN multitrames par calculateur et affichage de la couverture PID observée par calculateur.
- Modifications non encore compilées ni testées sur véhicule ; validation finale du chantier C6 à venir.

### English
- ELM327 connection: adapter discovery no longer overlaps a selected connection. Bounded initialization, previous CAN-monitor stream handling and session retirement on blocked Bluetooth writes.
- Live data: discovery resumes on a newly selected connection, selection is limited to actually measured signals, checkbox and row are selectable, and temporary selections are isolated when the VIN is unknown.
- OBD diagnostics: multi-frame CAN DTC replies are reassembled per ECU and observed PID coverage is displayed per ECU.
- Changes have not yet been built or tested on a vehicle; C6 completion validation is pending.

## En préparation — PR #54 (non publié)

- Capture CAN commandée par Play/Stop, avec export de la capture complète.
- Choix des capteurs en temps réel par cases à cocher, recherche et sélection par véhicule.
- Aide au dépannage affichée au clic sur un code défaut, lorsque des informations fiables existent.
- Accès au compte depuis l’en-tête ; « À propos » disponible depuis le menu Actions.
- Camaro LFX 2012 : lecture et export des identifiants ECU. **Aucune modification ou programmation du calculateur.**
- Le pilotage des équipements reste non implémenté ; les commandes concernées sont signalées comme telles.

## 20 novembre 2026

### Console CAN
- Regroupement de la capture, de l’historique des échanges et de la console de commande dans un même espace.
- Consultation chronologique des échanges et des trames reçues.
- Filtres de lecture, recherche dans l’historique et export des captures.
- Possibilité de saisir et d’envoyer des commandes ou des trames lorsque l’adaptateur et le réseau les prennent réellement en charge.
- Amélioration de la stabilité des échanges BLE : après une écriture interrompue ou expirée, la session concernée est abandonnée avant toute nouvelle tentative.

### À propos et mises à jour
- Affichage du nom de l’application, de la version installée et du numéro de build.
- Vérification manuelle des nouvelles versions.
- Proposition de télécharger et d’installer la version correspondant au canal déjà installé.
- Vérification de l’identité et de la signature de l’application avant de proposer son installation.

## 19 novembre 2026

### Diagnostic OBD-II
- Identification du véhicule et lecture des informations disponibles auprès du calculateur.
- Lecture des paramètres moteur standard, des codes défauts et des informations de disponibilité des systèmes antipollution, selon les réponses du véhicule.
- Amélioration de la prise en charge des adaptateurs ELM327 Bluetooth classique.
- Ajout d’un mode de démonstration sans véhicule ; les données de démonstration sont signalées comme artificielles.

### Données Camaro et fichiers
- Intégration d’un catalogue communautaire de signaux Chevrolet Camaro, avec des limites de compatibilité clairement indiquées.
- Analyse locale de captures CAN/UDS déjà enregistrées.
- Import et inspection de fichiers ECU, comparaison et analyse locale sans écriture dans le calculateur.

Les fonctions avancées dépendent toujours de l’année, du moteur, du calculateur, de la calibration et de l’adaptateur connecté. Une fonction listée dans l’application n’est pas nécessairement disponible sur toutes les Camaro.
