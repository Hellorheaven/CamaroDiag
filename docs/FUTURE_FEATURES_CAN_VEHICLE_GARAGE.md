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

## 7. Exigence transverse : parité fonctionnelle sur tous les modèles compatibles

Principe obligatoire pour toutes les prochaines versions et toutes les évolutions de CamaroDiag, y compris les fonctions déjà présentes dans l'application :

- Une fonctionnalité doit être disponible et opérationnelle sur **tous les modèles Camaro, millésimes, motorisations/RPO, transmissions, calculateurs et marchés** pour lesquels les capacités techniques nécessaires existent et sont documentées.
- Ne jamais limiter une fonction au seul véhicule de développement ou de validation (notamment une Camaro 2012 LFX), ni coder en dur un millésime ou un RPO lorsque la fonction peut être généralisée.
- Déterminer la disponibilité à partir d'une matrice de capacités explicite : génération, année, RPO, transmission, équipement réellement installé, calculateur identifié, protocole, réseau, interface/firmware et version logicielle/calibration lorsque ces éléments sont requis.
- Séparer la disponibilité de la fonction de la disponibilité de ses données : une fonction générique (par exemple lecture, capture, export ou affichage) doit rester utilisable sur les véhicules compatibles même si certaines interprétations GM spécifiques ne sont pas encore documentées. Dans ce cas, afficher les données brutes ou génériques et signaler précisément ce qui manque.
- Adapter automatiquement le parcours et les commandes aux calculateurs et équipements présents. Un équipement optionnel absent ne doit pas bloquer les fonctions utilisables sur les autres calculateurs.
- Si une limitation matérielle, protocolaire, logicielle, de sécurité ou de documentation est réelle, désactiver uniquement l'opération concernée, expliquer la raison et conserver accessibles les fonctions compatibles. Ne jamais afficher une fonction comme opérationnelle si l'interface ne sait pas l'exécuter.
- Toute nouvelle fonction doit être accompagnée d'une matrice de compatibilité et de tests couvrant les profils concernés, y compris les frontières de millésime et les variantes moteur/transmission. Les profils non couverts doivent être explicitement identifiés, sans extrapolation.
- Les critères de livraison doivent vérifier la parité sur l'ensemble des profils compatibles, et pas uniquement sur le véhicule de référence. Les essais simulés, les tests sur traces et les validations physiques doivent rester distingués.

Critère d'acceptation : pour chaque fonctionnalité, démontrer qu'elle fonctionne sur chaque configuration déclarée compatible ; lorsqu'une configuration ne la supporte pas, démontrer que seule cette fonction est indisponible et que le reste de l'application continue de fonctionner normalement.

## 8. Règle de construction périodique pendant le développement

Objectif : détecter les régressions de compilation régulièrement sans imposer un build sur chaque commit intermédiaire potentiellement incomplet.

- Intervalle nominal : **50 commits de la PR depuis le dernier build complet réussi**.
- L'intervalle doit être configurable (valeur par défaut 50), sans nécessiter de modifier le code applicatif.
- Le seuil est un déclencheur minimal, pas une obligation de construire un commit manifestement intermédiaire. Si le cinquantième commit laisse volontairement un travail incomplet ou un état non constructible, le build est différé jusqu'au premier commit cohérent suivant.
- Après le seuil, le contrôle de seuil est réévalué à chaque nouveau commit. Tant qu'aucun build complet ne réussit, le besoin de build reste en attente ; il ne repart pas à zéro et ne disparaît pas.
- Dès qu'un build complet réussit, ce commit devient le nouveau point de départ du compteur.
- Un build périodique de PR Draft doit au minimum exécuter les tests unitaires, lint et compilation/package Debug Android ainsi que compilation et packages Debug Windows x64/x86. Les artefacts et journaux doivent être conservés et rattachés au SHA exact.
- Les builds périodiques Draft ne constituent pas une validation de livraison : le passage en Ready for review conserve une validation complète obligatoire, et chaque commit ultérieur en Ready relance cette validation.
- Les workflows de release restent exclusivement liés à la branche principale et ne doivent jamais être déclenchés par ces builds de PR.
- Afficher explicitement les états « en attente du seuil », « différé car commit intermédiaire », « build en cours », « réussi » et « échoué ». Un job ignoré/skipped ne vaut jamais build réussi.

Critère d'acceptation : aucun dépassement du seuil nominal ne doit être oublié ; un build échoué ou différé reste dû jusqu'à un build réussi sur un commit cohérent. L'intervalle nominal peut être augmenté ou réduit par configuration selon la durée des builds et la taille du chantier.

## 9. Lots différés — hors périmètre de la PR Console CAN

**Règle de gestion :** les éléments ci-dessous restent au backlog. Ils ne doivent pas être ajoutés à la PR #35. Chaque lot fera l'objet d'une PR dédiée, avec son propre périmètre, ses tests, ses critères d'acceptation et son état CI. Les lots pourront être réordonnés selon les dépendances et la priorité produit.

### Lot A — Tableau de bord télémétrie temps réel multi-calculateurs
- Permettre à l'utilisateur de choisir toutes les données réellement disponibles, plutôt que d'imposer une courte liste fixe.
- Catalogue dynamique par calculateur, service/PID/DID, bus, unité, fréquence de rafraîchissement et compatibilité véhicule/interface.
- Inclure les sondes lambda/O2 amont et aval, par banc, corrections carburant STFT/LTFT, températures, pressions, débit d'air, ratés, données moteur et transmission, ainsi que les données châssis, ABS, TPMS, carrosserie, portes, vitres, ceintures et éclairages lorsqu'elles sont réellement accessibles.
- Recherche, sélection, retrait, organisation et sauvegarde de plusieurs tableaux de bord personnalisés ; affichages numériques, états, jauges et courbes temporelles.
- Distinguer explicitement « non supporté par le véhicule », « non exposé par le calculateur », « inaccessible avec l'interface actuelle » et « définition inconnue ». Ne jamais inventer une valeur.
- Gérer les fréquences et groupes de requêtes pour ne pas saturer le bus ni surcharger les calculateurs ; enregistrer et exporter les mesures.

### Lot B — Atelier de calibration professionnel
- Faire évoluer l'éditeur de calibration vers des vues tableaux/cartographies avec cellules colorées, axes, unités, sélection, comparaison, recherche, graphiques 2D/3D et historique des modifications.
- Ajouter les opérations de masse pertinentes (interpolation, lissage, incréments, normalisation) avec aperçu et annulation/rétablissement.
- Gérer plusieurs calibrations/cartographies d'un même véhicule, notamment les configurations flexfuel, et l'import/export avec préservation des données.
- Étudier les formats HP Tuners (.hpt) et EFILive (.coz/.ctz) : ne déclarer un format pris en charge que si sa structure est réellement interprétée ; signaler les pertes et incompatibilités. Importer un fichier ne signifie pas qu'il est flashable.
- Distinguer sauvegarde partielle d'une zone de calibration et sauvegarde complète de la zone de calibration. Ne jamais inclure firmware, OS, bootloader ou mémoire non-calibration dans ces opérations.
- Toute écriture partielle ou complète doit être fondée sur une cartographie mémoire documentée et une compatibilité calculateur/outil vérifiée ; si la zone ne peut pas être isolée de façon fiable, désactiver l'écriture.
- Prévoir validation statique, comparaison, simulation hors véhicule et procédures de récupération documentées. Aucune simulation ne garantit à elle seule la sécurité mécanique du moteur.

### Lot C — Outils d'entretien et codage injecteurs
- Étudier les fonctions de remplacement et codage d'injecteurs pour chaque moteur/ECU concerné, sans présumer qu'un moteur essence utilise le même mécanisme qu'un diesel.
- N'activer le codage que si le calculateur et la procédure applicables le confirment.
- Si un code injecteur est requis, fournir une aide visuelle pour localiser le code sur l'injecteur et l'associer au cylindre correct.
- Illustrer clairement l'orientation du moteur vue par une personne placée face au compartiment moteur, et différencier gauche/droite côté véhicule de gauche/droite côté observateur.
- Ajouter contrôles de cohérence, confirmation des valeurs et journal d'intervention.

### Lot D — Matrice d'interfaces et capacités matérielles
- Décrire les capacités réellement détectées de chaque adaptateur : ELM327, OBDLink, VXDIAG/VCX, Autocom, KKL USB et pont PC/J2534, selon modèle, firmware, transport et licences.
- Séparer transport USB/Bluetooth/Wi-Fi, protocole véhicule, transceiver physique, fonctions exposées par le firmware et droits/licences.
- Ne jamais déduire une capacité CAN/K-Line/J2534 ou une compatibilité GM du seul nom commercial ou de la présence d'un pilote USB.
- Déclarer séparément les opérations possibles : lecture OBD, diagnostic constructeur, capture passive, émission, programmation, etc. Une capacité non vérifiée reste inconnue ou indisponible.
- Traiter l'installation et les mises à jour du pont PC dans un parcours cohérent avec les mises à jour de l'application.

### Lot E — Gestion uniforme des déconnexions et robustesse des écrans
- Appliquer à tous les écrans et toutes les sessions la règle simple : en cas de déconnexion véhicule ou adaptateur Bluetooth/USB, arrêter immédiatement l'opération en cours.
- Afficher un message court et explicite selon la cause (« véhicule déconnecté » ou « Bluetooth déconnecté » / interface déconnectée) et demander de relancer la session.
- Ne pas ajouter de reprise automatique, de reconnexion silencieuse ou de logique de récupération complexe non demandée.
- Couvrir les états de déconnexion par des tests unitaires et d'intégration.

### Lot F — Cahier de tests utilisateur complet
- Produire un classeur Excel de validation fonctionnelle couvrant l'application complète, les parcours, les calculateurs, les interfaces, les variantes véhicule, les erreurs, les exports/imports et les régressions.
- Prévoir pour chaque test : identifiant, prérequis, matériel, véhicule/profil, étapes, résultat attendu, résultat constaté, statut, preuve/log et commentaire.
- Distinguer tests hors véhicule, tests simulés, tests sur traces et essais physiques.
- Le classeur sera préparé lorsque le périmètre fonctionnel sera suffisamment stabilisé, puis fourni à l'utilisateur pour exécution avec son véhicule et ses interfaces.

### Lot G — Extension des profils véhicules et couverture multi-marques
- Étendre progressivement la couverture au-delà des Camaro Gen 5/Gen 6, d'abord aux véhicules GM apparentés retenus (Corvette, Challenger/Charger/RAM uniquement après étude de leur architecture et de leur constructeur réel), puis aux véhicules Peugeot ciblés.
- Ne pas regrouper sous « GM » des marques qui n'appartiennent pas à GM ; chaque plateforme doit avoir ses propres profils, protocoles et matrices de compatibilité.
- Conserver la compatibilité des fonctions génériques et désactiver uniquement les fonctions spécifiques non prises en charge.

### Organisation des PR
- PR #35 : exclusivement le module unifié **Sniffer / Console CAN** (capture, analyse, décodage documenté, journalisation, console et émission manuelle/prédéfinie dans les limites réellement supportées par l'interface, avec garde-fous et tests). Aucun chantier télémétrie, calibration, garage, codage injecteur, gestion globale des déconnexions ou extension multi-marques ne doit y être ajouté.
- Lots A à G : PR séparées, une par lot cohérent ou sous-lot techniquement indépendant ; ne pas mélanger plusieurs domaines sans dépendance explicite.
- Le backlog est une feuille de route, pas une déclaration d'implémentation. Chaque lot passe en « en cours » seulement lorsqu'une PR dédiée est effectivement ouverte.
