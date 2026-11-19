# Feuille de route — CamaroDiag

> **Règle générale :** ce document décrit le backlog et les critères attendus. Une fonctionnalité n'est pas considérée comme développée parce qu'elle est décrite ici. Elle passe « en cours » uniquement lorsqu'une PR dédiée est ouverte, et « terminée » après validation du code, des tests et des builds requis.

## 1. Sniffer / Console CAN — périmètre de la PR #35

La PR #35 est exclusivement consacrée à un seul module utilisateur **Sniffer / Console CAN**. Il regroupe capture entrante, analyse, décodage, journalisation, console de requêtes/réponses et émission lorsque l'interface le permet réellement.

### Capture et analyse
- Démarrer, mettre en pause et arrêter une capture avec horodatage précis.
- Afficher les trames brutes : bus, identifiant, format 11/29 bits, DLC, octets, direction si connue et compteur de répétition.
- Filtrer par bus, identifiant/plage, période et changement de valeur ; rechercher et épingler des trames.
- Décoder les signaux et échanges connus en langage humain, en conservant toujours la trame brute à côté de l'interprétation.
- Enregistrer, relire et exporter les traces (CSV et formats spécialisés comme ASC uniquement lorsque leur prise en charge est réelle).
- Permettre l'analyse hors véhicule de traces enregistrées.

### Console et émission
- Préparer et envoyer des trames ou requêtes avec sélection explicite du bus, identifiant, format, DLC et données.
- Proposer les commandes prédéfinies documentées, avec explication, prérequis, calculateur ciblé et source technique.
- Montrer exactement ce qui sera envoyé, demander confirmation et journaliser l'émission et le résultat réel de l'interface.
- Permettre les émissions manuelles et répétées uniquement si l'adaptateur et son firmware exposent réellement ces capacités.
- Ne pas limiter arbitrairement le module à la lecture seule, mais ne jamais annoncer une capacité non supportée ou non vérifiée.

### Fiabilité et garde-fous
- Distinguer une vraie capture passive d'une lecture périodique active. Un ELM327 courant ne garantit pas la capture brute de toutes les trames.
- Ne jamais inventer le sens d'une trame. Afficher les octets bruts et « trame inconnue » en l'absence de définition fiable.
- Afficher provenance, couverture véhicule et niveau de confiance des décodages.
- Contrôler débit, pertes, débordements et charge CPU.
- Valider les champs et refuser les formats incohérents ; ne jamais émettre automatiquement une trame supposée.
- Distinguer requêtes diagnostiques documentées et commandes modifiant l'état du véhicule. Les opérations sensibles (programmation, accès sécurité, antivol, actionneurs, etc.) ne deviennent pas des raccourcis génériques.
- Pas d'émission sur véhicule en mouvement ; afficher les prérequis et conditions de test appropriés.
- Adapter les fonctions au bus, au véhicule, à l'adaptateur, au firmware et aux capacités réellement détectées.

**Hors périmètre de la PR #35 :** tableau de bord télémétrie, garage, calibration, codage injecteurs, gestion globale des déconnexions, matrice générale des interfaces et extension multi-marques. Ces sujets restent dans les lots ci-dessous.

## 2. Principes transverses applicables à tous les lots

### Compatibilité et parité véhicule
- Ne pas limiter une fonction au seul véhicule de développement ou de validation, notamment la Camaro 2012 LFX.
- Déterminer la disponibilité à partir d'une matrice explicite : génération, année, RPO/moteur, transmission, marché, équipements installés, calculateur, protocole, réseau, interface/firmware et version logicielle/calibration lorsque nécessaire.
- Séparer la disponibilité d'une fonction de la disponibilité de ses définitions. Une fonction générique doit rester utilisable avec données brutes si le décodage constructeur manque.
- Adapter automatiquement les fonctions aux calculateurs et équipements présents. L'absence d'un équipement ne doit pas bloquer les autres fonctions compatibles.
- Désactiver uniquement l'opération réellement incompatible et expliquer pourquoi ; ne jamais présenter une fonction comme opérationnelle si le matériel ne sait pas l'exécuter.
- Toute fonction doit préciser les configurations compatibles et les configurations non encore couvertes. Les essais simulés, sur traces et physiques doivent rester distincts.

### Déconnexions
La règle fonctionnelle demandée est simple : en cas de déconnexion véhicule ou interface, arrêter immédiatement l'opération en cours, afficher un message court correspondant à la cause (« véhicule déconnecté », « Bluetooth déconnecté » ou « interface déconnectée ») et demander de relancer la session. Pas de reprise automatique ou de mécanisme de récupération complexe non demandé.

### Qualité et CI
- Un job marqué `skipped` ne constitue jamais un build réussi.
- Les validations de livraison doivent exécuter réellement tests, lint, compilation et packaging concernés, et être rattachées au SHA testé.
- Les workflows de release restent exclusivement liés à la branche principale.

## 3. Lots de développement différés — une PR par lot

Les lots sont regroupés par responsabilité fonctionnelle. Leur ordre proposé tient compte des dépendances ; il pourra être ajusté si une dépendance technique réelle l'impose.

### Lot 1 — Couche d'adaptation et matrice des capacités matérielles

**Objectif :** disposer d'une description fiable et commune de ce que chaque interface peut réellement faire. Ce lot sert de fondation aux fonctions qui dépendent du matériel.

- Gérer les familles ELM327, OBDLink, VXDIAG/VCX, Autocom, KKL USB et pont PC/J2534 selon modèle exact, firmware, transport et licences.
- Séparer clairement transport USB/Bluetooth/Wi-Fi, pilote, protocole véhicule, transceiver physique, fonctions firmware et droits/licences.
- Détecter ou déclarer explicitement les capacités : lecture OBD, diagnostic constructeur, CAN brut, capture passive, émission, programmation, etc.
- Ne jamais déduire une capacité CAN/K-Line/J2534 ou une compatibilité GM du seul nom commercial ou de la présence d'un pilote USB.
- Distinguer « pris en charge », « non pris en charge » et « inconnu/non vérifié ».
- Intégrer l'installation, la configuration et les mises à jour du pont PC dans un parcours cohérent avec les mises à jour de l'application.

**Livrables :** modèle de capacités, matrice adaptateur/protocole/opération, détection et tests unitaires sur les profils d'interfaces. Aucune compatibilité physique ne doit être déclarée sans preuve correspondante.

### Lot 2 — Garage et gestion des profils véhicules

**Objectif :** gérer plusieurs véhicules sans mélanger leurs données ni leurs historiques.

- Ajouter dans Réglages une section « Véhicules enregistrés » avec **Enregistrer le véhicule actuel** et **Recharger un véhicule enregistré**.
- Gérer plusieurs profils persistants : VIN, année, génération, moteur/RPO, transmission, marché, nom personnalisé, dates de création et dernière utilisation.
- Distinguer VIN lu sur un ECU et VIN saisi manuellement, avec provenance et date de lecture.
- Modifier, renommer, supprimer avec confirmation, détecter les doublons et éviter tout écrasement silencieux.
- Exporter/importer les profils avec validation des données.
- À chaque nouvelle connexion, relire le VIN disponible et le comparer au profil sélectionné.
- En cas d'écart, demander si un autre véhicule est connecté ou si un ECU a été remplacé / présente un VIN incohérent ; ne pas changer de profil silencieusement.
- Isoler par véhicule DTC, rapports, captures, télémétries, sessions et interventions. Un profil rechargé ne doit jamais faire passer des données anciennes pour des données de la session actuelle.

**Dépendance :** s'appuyer sur la couche d'adaptation du Lot 1 pour qualifier les capacités de lecture du VIN.

### Lot 3 — Robustesse commune des sessions et déconnexions

**Objectif :** appliquer la même règle de fin de session dans toute l'application.

- Centraliser la détection des déconnexions véhicule, Bluetooth, USB, Wi-Fi ou pont PC selon le transport utilisé.
- Arrêter immédiatement la capture, la requête ou l'opération en cours.
- Afficher un message bref identifiant la cause et demander à l'utilisateur de relancer.
- Appliquer le comportement à tous les écrans, pas uniquement au Sniffer CAN.
- Ne pas introduire de reconnexion silencieuse, reprise automatique ou procédure complexe.
- Tester les déconnexions pendant lecture, capture, export, diagnostic et autres opérations longues.

**Dépendance :** couche d'adaptation du Lot 1 ; intégration avec les profils du Lot 2 lorsque l'identité du véhicule est concernée.

### Lot 4 — Tableau de bord télémétrie temps réel personnalisable et multi-calculateurs

**Objectif :** laisser l'utilisateur choisir toutes les données réellement accessibles, au lieu d'une courte liste fixe.

- Créer un catalogue dynamique par calculateur, service/PID/DID, bus, unité, fréquence de rafraîchissement, protocole et compatibilité véhicule/interface.
- Inclure les sondes lambda/O2 amont et aval par banc, STFT/LTFT, températures, pressions, débit d'air, ratés, paramètres moteur et transmission.
- Ajouter les données châssis et carrosserie lorsqu'elles sont exposées : ABS, vitesses de roues, ESP/traction, TPMS par roue, portes, coffre, capot, vitres, ceintures et états d'éclairage.
- Permettre recherche, sélection, ajout/retrait, organisation et sauvegarde de plusieurs tableaux de bord.
- Proposer des rendus adaptés : valeur numérique, état, jauge et courbe temporelle ; afficher unité, source, calculateur, fréquence et validité.
- Distinguer précisément « non supporté par le véhicule », « non exposé par le calculateur », « inaccessible avec l'interface actuelle » et « définition inconnue ».
- Ne jamais inventer une donnée. Les valeurs propriétaires doivent avoir une définition et une provenance fiables.
- Gérer les groupes et fréquences de requêtes pour éviter de saturer le bus ou de surcharger les calculateurs.
- Enregistrer et exporter les mesures ; permettre leur consultation hors connexion.

**Dépendances :** Lots 1, 2 et 3. Les données disponibles varieront selon véhicule, ECU et interface.

### Lot 5 — Outils d'entretien et procédures de service

**Objectif :** regrouper les opérations d'entretien et de remplacement qui ne relèvent ni du diagnostic courant ni de la calibration.

- Étudier les fonctions de remplacement et de codage d'injecteurs pour chaque moteur et calculateur concerné.
- Ne pas présumer qu'un moteur essence utilise le même mécanisme de codage qu'un diesel ; vérifier si le calculateur concerné exige réellement un code.
- N'activer une procédure que si l'ECU, la motorisation et la procédure applicable sont identifiés et documentés.
- Si un code injecteur est requis, fournir une aide visuelle pour localiser le code sur l'injecteur et l'associer au bon cylindre.
- Montrer clairement l'orientation du moteur comme vue par une personne face au compartiment moteur ; distinguer gauche/droite côté véhicule et côté observateur.
- Ajouter contrôles de cohérence, confirmation des valeurs, prérequis et journal d'intervention.
- Étendre ensuite ce lot à d'autres procédures de service uniquement lorsqu'elles sont documentées et validées, sans transformer l'application en catalogue de commandes non vérifiées.

**Dépendances :** Lots 1 et 2 ; matrice de compatibilité moteur/ECU obligatoire.

### Lot 6 — Atelier de calibration professionnel

**Objectif :** faire évoluer l'espace calibration vers un véritable atelier d'analyse et d'édition de cartographies, distinct du diagnostic standard.

- Présenter les cartographies sous forme de tableaux avec cellules colorées, axes, unités, sélection et navigation cohérentes.
- Ajouter comparaison de calibrations, recherche, graphiques 2D/3D et historique des modifications.
- Ajouter les opérations de masse pertinentes : incréments, interpolation, lissage et normalisation, avec aperçu et annulation/rétablissement.
- Gérer plusieurs calibrations/cartographies d'un même véhicule, notamment les configurations flexfuel.
- Fiabiliser import/export et préserver les données non modifiées.
- Étudier HP Tuners (.hpt) et EFILive (.coz/.ctz) ; ne déclarer un format pris en charge que si sa structure est réellement interprétée. Signaler pertes et incompatibilités. Importer un fichier ne signifie pas qu'il est flashable.
- Distinguer sauvegarde partielle d'une zone de calibration et sauvegarde complète de la zone de calibration.
- Exclure strictement firmware, OS, bootloader et mémoire non-calibration de ces opérations.
- Toute écriture doit reposer sur une cartographie mémoire documentée et une compatibilité ECU/interface vérifiée. Si la zone de calibration ne peut pas être isolée avec certitude, désactiver l'écriture.
- Prévoir validation statique, comparaison, simulation hors véhicule et procédure de récupération documentée. Une simulation ne garantit jamais à elle seule la sécurité mécanique du moteur.
- Une interruption de communication pendant une programmation ne doit jamais déclencher de reprise ou de renvoi aveugle ; l'état doit être considéré comme inconnu jusqu'à identification et vérification adaptées.

**Dépendances :** Lots 1, 2 et 3. Ce lot doit rester isolé des fonctions de diagnostic courant et suivre ses propres critères de sécurité et de validation.

### Lot 7 — Extension des plateformes et couverture multi-marques

**Objectif :** étendre progressivement l'application au-delà des Camaro, sans supposer que des marques différentes partagent la même architecture.

- Consolider d'abord la couverture Camaro Gen 5 et Gen 6, moteurs et transmissions réellement documentés.
- Étudier ensuite les véhicules GM apparentés retenus, par exemple Corvette, selon leurs calculateurs et architectures propres.
- Étudier séparément Dodge Challenger, Charger et RAM : ce ne sont pas des véhicules GM et ils nécessitent des profils, protocoles et sources techniques propres.
- Ajouter les Peugeot ciblées, notamment la 207 CC 1.6 VTi, dans un périmètre PSA distinct.
- Réutiliser les fonctions génériques lorsque c'est pertinent, mais maintenir des profils, catalogues, procédures et matrices de compatibilité propres à chaque plateforme.
- Ne déclarer aucune couverture véhicule sur la seule base d'une proximité de marque ou de génération.

**Dépendances :** Lots 1 et 2, puis validation spécifique de chaque plateforme. Ce lot sera découpé en PR indépendantes par famille/plateforme si son volume le nécessite.

### Lot 8 — Cahier de tests utilisateur complet

**Objectif :** fournir un classeur Excel de recette que l'utilisateur pourra remplir avec ses véhicules et interfaces lorsque le périmètre sera suffisamment stabilisé.

- Couvrir les parcours fonctionnels, calculateurs, interfaces, variantes véhicule, erreurs, exports/imports et régressions.
- Pour chaque test : identifiant, lot/fonction, prérequis, matériel, véhicule/profil, étapes, résultat attendu, résultat constaté, statut, preuve/log et commentaire.
- Distinguer tests unitaires/automatisés, tests hors véhicule, tests simulés, tests sur traces et essais physiques.
- Prévoir des matrices de couverture par génération, année, moteur/RPO, transmission, calculateur et interface.
- Inclure des tests négatifs : capacité non supportée, ECU absent, définition inconnue, mauvaise sélection de véhicule et déconnexion.
- Préparer le classeur complet après stabilisation des fonctions ; le fournir à l'utilisateur pour exécution physique, puis intégrer les résultats et anomalies dans des PR de correction dédiées.

**Dépendances :** construction progressive à partir des critères d'acceptation des Lots 1 à 7. La préparation du modèle de classeur peut commencer avant la fin, mais la recette complète ne peut être figée qu'une fois le périmètre stabilisé.

## 4. Règle de construction périodique pendant le développement

- Intervalle nominal : **50 commits de la PR depuis le dernier build complet réussi**, configurable avec 50 comme valeur par défaut.
- Le seuil est un déclencheur minimal, pas une obligation de construire un commit manifestement intermédiaire. Si le cinquantième commit est volontairement incomplet, différer jusqu'au premier commit cohérent suivant.
- Après le seuil, réévaluer à chaque commit. Tant qu'aucun build complet ne réussit, le besoin de build reste en attente et le compteur ne repart pas à zéro.
- Dès qu'un build complet réussit, ce commit devient le nouveau point de départ.
- Un build périodique de PR Draft doit exécuter au minimum tests unitaires, lint et compilation/package Debug Android, ainsi que compilation et packages Debug Windows x64/x86.
- Conserver artefacts et journaux, rattachés au SHA exact.
- Le passage en Ready for review exige une validation complète ; chaque commit ultérieur en Ready relance cette validation.
- Les releases restent exclusivement déclenchées depuis la branche principale.
- Afficher explicitement les états « en attente du seuil », « différé car commit intermédiaire », « build en cours », « réussi » et « échoué ». Un job ignoré/skipped ne vaut jamais build réussi.

## 5. Organisation des PR et suivi

- **PR #35 :** uniquement le module unifié Sniffer / Console CAN décrit en section 1.
- **Lots 1 à 8 :** PR distinctes, chacune limitée à son domaine fonctionnel. Un lot volumineux peut être divisé en sous-PR techniquement cohérentes, sans mélanger des domaines indépendants.
- Respecter les dépendances indiquées ; ne pas ouvrir une PR d'implémentation avant que ses prérequis soient disponibles ou explicitement traités.
- Une fonction ne passe en « terminée » qu'après code livré, tests pertinents réussis, builds réels réussis et limites de compatibilité documentées.
- Ne pas confondre backlog, code présent, build CI, test simulé et validation sur véhicule.
