# Feuille de route — Sniffer CAN, émission encadrée et garage multi-véhicules

> Statut : fonctionnalités planifiées. Ce document décrit les exigences produit ; il ne signifie pas que ces fonctions sont déjà disponibles dans l'application.

## 1. Sniffer CAN passif et traduction en langage humain

Créer un écran dédié de capture CAN, utilisable lorsque l'interface et le réseau sélectionnés permettent réellement ce mode.

Fonctions attendues :
- Démarrer / mettre en pause / arrêter la capture, avec horodatage précis.
- Afficher les trames brutes : bus, identifiant CAN, format 11 ou 29 bits, DLC, octets, direction si elle est connue et compteur de répétition.
- Filtrer par bus, identifiant, plage d'identifiants, période et changements de valeur ; rechercher et épingler des trames.
- Décoder les signaux connus en valeurs et libellés compréhensibles (par exemple vitesse véhicule, régime moteur, température), avec unité et état de validité.
- Expliquer en langage humain les échanges de diagnostic connus : requête, réponse, calculateur répondant, service, DID ou DTC, et résultat.
- Conserver côte à côte la trame brute et son interprétation afin que le décodage reste vérifiable.
- Enregistrer, relire et exporter les captures (CSV et format de trace adapté, par exemple ASC lorsque son implémentation est validée).
- Permettre une analyse hors véhicule d'un fichier de trace déjà enregistré.

Règles de fiabilité :
- La capture doit être réellement passive (« listen-only ») lorsque le matériel le permet ; ne pas présenter une lecture périodique active comme un sniffer passif.
- Indiquer clairement les limites de chaque adaptateur. Un ELM327 Bluetooth courant ne garantit pas la capture brute de toutes les trames du bus.
- Ne traduire une trame que si son réseau, son protocole, son identifiant, son année/RPO et sa définition sont suffisamment documentés.
- Afficher « trame inconnue » ou les octets bruts lorsqu'aucune définition fiable ne correspond. Ne jamais inventer une signification.
- Afficher la provenance, la couverture véhicule et le niveau de confiance de chaque décodage.
- Prévoir la gestion du débit, des pertes de trames, des débordements de tampon et de la charge CPU.

## 2. Émetteur CAN manuel

Ajouter une zone de préparation et d'envoi de trame comprenant :
- Sélection explicite du bus (HS-CAN / SW-CAN uniquement si le matériel et le câblage le supportent).
- Identifiant CAN, format 11/29 bits, DLC et octets de données.
- Validation stricte des champs, affichage de la trame complète avant envoi et retour du résultat réel de l'interface.
- Confirmation explicite avant chaque émission et journal horodaté des trames envoyées.
- Indication permanente du véhicule, du réseau et de l'adaptateur ciblés.

Garde-fous :
- Ne jamais envoyer automatiquement une trame construite à partir d'une supposition ou d'une capture non vérifiée.
- Refuser par défaut les identifiants, longueurs et formats incohérents, ainsi que les commandes de programmation, d'accès sécurité, d'effacement non autorisé, d'actionneur ou de fonction antivol.
- Pas d'émission sur un véhicule en mouvement ; afficher un avertissement et exiger un véhicule immobilisé, contact et conditions de test appropriés.
- Séparer strictement les requêtes de diagnostic documentées des commandes qui modifient l'état du véhicule.
- Les actions critiques restent indisponibles tant qu'une procédure constructeur applicable au VIN/calculateur et une validation matérielle n'existent pas.

## 3. Boutons de commandes prédéfinies

Ajouter une bibliothèque de boutons pour les opérations courantes et documentées, avec un libellé humain, une description de l'effet, les prérequis, le calculateur ciblé et la source technique.

Exemples de catégories à étudier :
- Requêtes de lecture standardisées et non destructives (identification, lecture de données ou lecture de défauts) lorsque le protocole et l'adressage sont compatibles.
- Commandes de diagnostic de session ou de maintien de session uniquement lorsque leur usage et leur portée sont connus.
- Filtres/raccourcis de capture pour afficher rapidement les familles de trames utiles.

Chaque bouton doit :
- Montrer la trame exacte qui sera envoyée avant validation.
- Vérifier la compatibilité année, RPO, transmission, calculateur, bus et interface.
- Expliquer ce que la commande fait et ce qu'elle ne fait pas.
- Demander confirmation pour toute émission.
- Être désactivé si sa définition ou sa compatibilité n'est pas confirmée.

Aucune trame GM propriétaire ne doit être qualifiée de « standard » sans référence vérifiable. Les trames d'action carrosserie, d'actionneur, de sécurité, d'antidémarrage et de programmation ne doivent pas être ajoutées comme raccourcis génériques.

## 4. Garage : enregistrer et recharger les véhicules depuis Réglages

Ajouter dans l'onglet **Réglages** une section « Véhicules enregistrés » avec des actions visibles :
- **Enregistrer le véhicule actuel** : sauvegarder le profil sélectionné.
- **Recharger un véhicule enregistré** : afficher la liste des profils, leur année, moteur/RPO, transmission, marché, VIN et nom personnalisé éventuel, puis charger le profil choisi.
- Modifier le nom ou les caractéristiques d'un profil, supprimer un profil avec confirmation et gérer les doublons.
- Proposer l'enregistrement après la création ou la modification d'un véhicule, sans écraser silencieusement un profil existant.

Données minimales d'un profil :
- VIN lorsqu'il est connu et validé, année, génération calculée, moteur/RPO, transmission et marché.
- Nom personnalisé facultatif, date de création et date de dernière utilisation.
- Provenance et date de lecture du VIN, lorsqu'il vient d'un calculateur ; distinguer cette valeur d'un VIN saisi manuellement.

Comportement :
- Conserver plusieurs véhicules localement, après fermeture et redémarrage de l'application.
- Au chargement, revalider le VIN lu sur l'ECU à chaque nouvelle connexion OBD ; ne jamais supposer que le profil sélectionné correspond encore au véhicule branché.
- Si le VIN lu diffère du profil, demander s'il s'agit d'un autre véhicule ou d'un ECU remplacé / VIN incohérent avant de basculer.
- Isoler par véhicule les DTC, rapports, captures CAN, télémétries, sessions et interventions ; ne pas mélanger les historiques.
- Le rechargement d'un profil ne doit pas réutiliser des données de diagnostic anciennes comme si elles venaient de la connexion actuelle.
- Prévoir export/import de profils pour sauvegarde et transfert, avec validation des données importées.

## 5. Ordre de réalisation proposé

1. Persistance locale des profils et boutons Enregistrer / Recharger dans Réglages, avec tests de cycle de vie et d'isolation des historiques.
2. Modèle de capture CAN, stockage horodaté et visualisation brute ; prise en charge passive uniquement sur les interfaces qui l'exposent réellement.
3. Décodeur de trames fondé sur les catalogues documentés et enrichissement progressif en langage humain.
4. Bibliothèque de requêtes prédéfinies strictement en lecture seule, après validation de chaque définition.
5. Émetteur manuel sous garde-fous, d'abord limité aux requêtes de diagnostic documentées et compatibles ; aucune commande d'action non vérifiée.

## 6. Statut et critères d'acceptation

Ces fonctions sont **planifiées et non implémentées** tant qu'elles ne sont pas présentes dans l'application, couvertes par des tests et validées sur les interfaces concernées.

Une fonctionnalité de capture ou d'émission ne peut être déclarée « validée sur véhicule » qu'après un essai réel documenté avec véhicule, année/RPO, calculateur, interface, réseau, firmware et résultat. Les tests simulés et les traces importées doivent être identifiés comme tels.
