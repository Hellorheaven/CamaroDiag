# FUTURE_EVOLUTIONS — CamaroDiag

> Backlog de référence pour les évolutions à venir. Ce fichier doit être mis à jour au fil des décisions et des développements afin de ne pas perdre les demandes.
>
> **Règle de priorité :** les extensions à d'autres marques et modèles sont en **priorité basse**. Elles ne doivent pas ralentir le périmètre Camaro Gen 5/Gen 6 ni les correctifs de fiabilité en cours.
>
> **État de départ :** PR #35 `feat/live-can-monitor` est le chantier actif au moment de la création de ce document. Une seule PR active à la fois ; la PR reste Draft jusqu'à la fin des développements et validations. Aucun merge sans accord explicite.

## 1. Règles de pilotage

- [ ] Maintenir une seule PR de développement active à la fois.
- [ ] Garder la PR en Draft pendant le développement ; demander la review uniquement lorsque le périmètre est terminé.
- [ ] Ne jamais merger sans accord explicite de l'utilisateur.
- [ ] Intégrer les correctifs cohérents dans la PR active plutôt que créer des PR concurrentes.
- [ ] Ne pas renommer, supprimer ou modifier le comportement d'éléments existants sans justification et accord lorsque l'impact est significatif.
- [ ] Distinguer clairement compilation, tests, lint, contrôles différés et validations matérielles. Un workflow vert qui a ignoré le build ne vaut pas validation de compilation.
- [ ] Viser des builds Debug sans avertissements, tests automatisés et contrôles de régression avant Ready for review.
- [ ] Les builds Release sont déclenchés uniquement depuis `main`, selon le workflow de publication convenu.
- [ ] Regrouper les évolutions par composants pour réduire les redites, sans créer de gros changements non testables.
- [ ] Ne jamais présenter une simulation ou un test logiciel comme une validation sur véhicule réel.
- [ ] Ne jamais inventer de DID, identifiant CAN, DTC constructeur, adresse mémoire, seuil, procédure ou donnée de calibration.
- [ ] Maintenir les fonctions d'écriture, programmation, flash, recovery, Seed/Key et antidémarrage désactivées tant que leur sécurité, leur exactitude et leur validation ne sont pas établies.
- [ ] Conserver les attributions et obligations de licence des données tierces, notamment le catalogue OBDb Camaro sous CC BY-SA 4.0.

## 2. Priorité haute — fiabilité et périmètre Camaro existant

Ces points sont à traiter avant l'élargissement multimarque. Leur statut doit être vérifié dans le code et les PR avant de les considérer comme terminés.

### 2.1 PR #35 — CAN Sniffer (chantier actif à la création)
- [ ] Terminer l'onglet CAN Sniffer et sa console de requêtes strictement en lecture seule.
- [ ] Vérifier la capture passive, les limites de durée et de taille, l'arrêt explicite/automatique et la libération de session.
- [ ] Vérifier le comportement lors du changement d'adaptateur, de véhicule, de déconnexion et de fermeture de l'application.
- [ ] Vérifier la liste blanche OBD et les rejets des services non autorisés, des identifiants CAN arbitraires et des entrées malformées.
- [ ] Vérifier les traductions dans les 14 langues.
- [ ] Renforcer les tests sur les transports, les erreurs, les délais, les flux concurrents et le cycle de vie.
- [ ] Obtenir une compilation Android réellement exécutée, tests et lint sans avertissements bloquants.
- [ ] Documenter les limites matérielles : HS-CAN 11 bits / 500 kbit/s sur le chemin actuel ; pas de compatibilité revendiquée pour J2534/VCX ou bus non pris en charge sans implémentation et validation.
- [ ] Prévoir une validation physique ultérieure sur véhicule et adaptateurs réels.

### 2.2 Diagnostic Camaro Gen 5 / Gen 6
- [ ] Continuer l'enrichissement des DTC spécifiques par véhicule, moteur, année et calculateur avec sources et niveau de confiance traçables.
- [ ] Compléter progressivement les profils V6 et V8 Gen 5 et Gen 6 sans généraliser une donnée non vérifiée à toutes les variantes.
- [ ] Poursuivre l'inventaire des calculateurs : ECM/PCM, TCM, EBCM/ABS, BCM, IPC, SDM, HVAC, PSCM, RCDLR, RADIO/AMP, DDM/PDM, FPCM, Park Assist, HMI et autres modules réellement documentés.
- [ ] Compléter l'identification véhicule/calculateur et le rapprochement VIN, CAL ID et CVN lorsque les données sont disponibles.
- [ ] Enrichir la provenance des données : source, millésime, RPO, moteur, transmission, module, matériel, CAL ID/CVN et niveau de validation.
- [ ] Étendre les interprétations UDS et DTC propriétaires uniquement avec des données vérifiables ; conserver les identités provisoires explicitement signalées.
- [ ] Garder distincts les mécanismes d'effacement SAE et les éventuelles fonctions constructeur, sans ajouter de commande non validée.

### 2.3 Connexion et interfaces OBD
- [ ] Stabiliser ELM327 Bluetooth SPP, USB et Wi-Fi, en identifiant les capacités réelles des adaptateurs plutôt que leur seule appellation commerciale.
- [ ] Étendre et valider le support OBDLink MX+ et CX, puis les autres modèles OBDLink pertinents.
- [ ] Préparer le support VCX Nano et VCX SE via pont PC/J2534, sans déclarer une compatibilité avant validation.
- [ ] Améliorer la détection des capacités, les diagnostics de connexion, les délais, la reprise après erreur et la gestion des sessions exclusives.
- [ ] Maintenir une matrice de compatibilité adaptateur / protocole / fonction / OS / niveau de validation.

### 2.4 Véhicules, paramètres et interface
- [ ] Finaliser un onglet Paramètres distinct de l'action/menu de navigation.
- [ ] Permettre le choix manuel de la langue et la désactivation de la sélection automatique par langue système ; anglais par défaut si langue système non prise en charge.
- [ ] Finaliser la gestion de plusieurs véhicules enregistrés, avec sauvegarde, sélection, modification et rechargement.
- [ ] À la connexion OBD, lire le VIN ECU lorsqu'il est disponible, le comparer au véhicule sélectionné et avertir en cas de différence.
- [ ] En cas de VIN différent, proposer la création d'un nouveau profil véhicule avec les informations moteur/transmission disponibles ; encadrer toute synchronisation de VIN par confirmation explicite et avertissements.
- [ ] Améliorer l'ergonomie tablette avec une mise en page adaptative, densité et tailles de panneaux adaptées, sans dégrader le téléphone.
- [ ] Vérifier l'accessibilité, les états de chargement/erreur et les traductions sur les écrans principaux.

### 2.5 Pont PC et distribution
- [ ] Traiter l'installation et la mise à jour du pont Windows comme un composant distribué avec l'application Android.
- [ ] Documenter les prérequis Windows, pilotes, architectures x64/x86 et adaptateurs compatibles.
- [ ] Fiabiliser la CI Windows/J2534 : distinguer build exécuté, build différé, build non applicable et échec.
- [ ] Garder des builds ciblés plus précoces pour les fichiers critiques transport/communication, même si un contrôle périodique général existe.
- [ ] Publier des artefacts clairement versionnés et traçables.

### 2.6 Atelier ECU et fonctions avancées
- [ ] Continuer l'amélioration de l'espace atelier pour l'import, l'identification, l'inspection et la comparaison de fichiers BIN/ORI/MOD, Intel HEX, Motorola S-record et archives prises en charge.
- [ ] Améliorer les tables, axes, unités, offsets, dimensions et affichage tablette en corrigeant les défauts connus avant d'étendre les fonctions.
- [ ] Ajouter des garde-fous, provenance et rapports pour chaque opération sur fichier.
- [ ] Ne pas activer l'écriture ECU, flash, recovery, correction de checksum GM, accès mémoire direct ou Seed/Key sans spécifications fiables, tests matériels et validation dédiée.

#### Évolution future — courbes de puissance, couple et gains avant/après
- [ ] Ajouter dans l'atelier Cartographie un écran « Puissance & Couple » présentant les courbes en fonction du régime moteur (RPM), avec puissance et couple sur des axes clairement identifiés et unités sélectionnables (ch, kW, hp ; Nm, lb-ft).
- [ ] Afficher sur le même graphique les courbes de référence/originale et modifiée, avec légende, couleurs distinctes, curseur de régime et lecture des valeurs au point sélectionné.
- [ ] Calculer et afficher les écarts absolus et relatifs de puissance et de couple par régime, ainsi que les gains/pertes maximums, leur régime correspondant et les valeurs de puissance/couple de crête.
- [ ] Permettre de comparer plusieurs versions de calibration et plusieurs sessions de mesure, en conservant véhicule, moteur, transmission, carburant, conditions d'essai, source et date.
- [ ] Distinguer sans ambiguïté les courbes mesurées sur banc, les courbes estimées par un modèle et les simples courbes théoriques calculées à partir de données partielles ; afficher un badge/avertissement et la méthode/source dans le graphique et le rapport.
- [ ] Ne jamais présenter une estimation issue des seules tables de calibration comme une mesure réelle ou comme une promesse de gain. Une cartographie seule ne suffit généralement pas à calculer avec fiabilité la puissance et le couple moteur.
- [ ] Pour les courbes mesurées, permettre l'import de fichiers de banc et de logs compatibles, avec identification des canaux, unités, fréquence, régime, couple, puissance, corrections atmosphériques si disponibles et méthode de calcul ; rejeter ou signaler les données insuffisantes/incohérentes.
- [ ] Pour les courbes calculées, dériver la puissance du couple et du régime uniquement avec les unités et conventions correctes (par exemple kW = Nm × RPM / 9549 ; hp mécaniques = lb-ft × RPM / 5252) et documenter l'origine du couple utilisé.
- [ ] Pour les estimations avant/après, exiger un modèle moteur et des données d'entrée validés pour la variante concernée (moteur, carburant, air, charge, rendement, limites et conditions) ; si le modèle n'est pas disponible, désactiver l'estimation de puissance et afficher « données insuffisantes » plutôt qu'inventer une courbe.
- [ ] Prévoir un rapport exportable comparant les courbes, les gains/pertes par plage de régime, les pics, les conditions de mesure et le niveau de confiance ; conserver les fichiers sources sans les modifier.
- [ ] Ajouter des tests sur conversions d'unités, calcul puissance/couple, interpolation des courbes, synchronisation RPM, données manquantes, comparaison avant/après et distinction mesure/estimation.

#### Chantier prioritaire après clôture de la PR #35 — atelier Cartographie professionnel
**Objectif :** transformer l'espace Cartographie existant en atelier hors ligne complet, inspiré des capacités fonctionnelles de HP Tuners/EFILive, sans reproduire leurs formats ou interfaces propriétaires et sans autoriser une écriture ECU non validée.

- [ ] Planifier ce chantier comme prochaine évolution fonctionnelle prioritaire après finalisation de la PR #35 ; ne pas ouvrir de PR concurrente et conserver la règle d'une seule PR active.
- [ ] Phase 1 — fiabiliser le socle fichiers : import/export, conservation de l'original, inspection, comparaison, métadonnées, validation du format et identification exacte véhicule/ECU/CAL ID/CVN.
- [ ] Phase 2 — construire l'éditeur graphique : tables colorées, axes/unités, vues 2D/3D, sélection de cellules, opérations mathématiques, interpolation, lissage, comparaison et historique undo/redo.
- [ ] Phase 3 — construire la validation et simulation hors ligne : contrôles statiques, vérifications de cohérence documentées, simulation des tables/interpolations et rejeu de logs.
- [ ] Phase 4 — construire l'analyse Puissance & Couple : courbes origine/modifiée, gains/pertes par régime, pics, unités et rapports ; distinguer mesures banc, calculs dérivés de mesures, estimations issues d'un modèle validé et données théoriques.
- [ ] Phase 5 — ajouter les projets multi-cartographies, profils FlexFuel, import/export HP Tuners et EFILive dans la limite des formats réellement interprétables et des droits/licences applicables.
- [ ] Phase 6 — préparer la matrice ECU et les modes d'écriture partielle/complète calibration-only ainsi que l'assistant de récupération ; ces fonctions restent désactivées tant que les zones de calibration, protocoles et procédures ne sont pas validés pour chaque ECU.
- [ ] À chaque phase, livrer des écrans utilisables, tests automatisés et documentation ; ne pas marquer une fonction terminée sur la seule base d'une maquette ou d'une définition dans le backlog.
- [ ] Maintenir une séparation technique stricte entre outils hors ligne (inspection, édition, comparaison, simulation, export) et toute communication ECU ; le mode simulation ne doit pas pouvoir appeler de chemin d'écriture.
- [ ] Pour les courbes de puissance/couple, ne jamais déduire un gain réel de la seule modification des tables : afficher « données insuffisantes » si aucune mesure exploitable ni modèle validé n'est disponible.

#### Évolution future — validation et simulation de cartographie sans écriture ECU
- [ ] Ajouter un mode « Vérifier / Simuler » accessible depuis l'atelier Cartographie, strictement hors ligne et sans envoyer de commande d'écriture au véhicule.
- [ ] Prévoir une validation statique du fichier : format et structure, taille, intégrité, sommes de contrôle lorsqu'elles sont documentées, cohérence des sections et présence des métadonnées attendues.
- [ ] Vérifier la compatibilité déclarée avec le véhicule et le calculateur identifiés : génération, année, moteur, transmission, référence matérielle, identifiants logiciel/calibration et version de définition ; signaler les éléments inconnus et bloquer toute conclusion de compatibilité non étayée.
- [ ] Contrôler les tables décodées : dimensions, axes, unités, facteur/offset, plages et limites documentées, valeurs hors plage, valeurs constantes/anormales, cellules manquantes et incohérences entre tables liées. Ne pas inventer de seuils constructeur.
- [ ] Comparer la cartographie d'origine et la cartographie modifiée : écarts absolus et relatifs, cellules modifiées, tables touchées, changements de limites et rapport détaillé exportable.
- [ ] Ajouter des contrôles de cohérence croisée entre tables lorsqu'une relation technique est documentée (par exemple axes, limites de couple, demande conducteur, air/carburant ou allumage) ; présenter les alertes comme indicateurs et non comme preuve de sécurité moteur.
- [ ] Fournir une simulation numérique des tables et de leurs interpolations pour des points d'entrée choisis (régime, charge, température, etc.) uniquement lorsque les axes, unités et règles de calcul sont connus ; afficher les valeurs interpolées et la provenance des données.
- [ ] Prévoir des scénarios de simulation reproductibles avec jeux d'entrées modifiables, résultats avant/après, hypothèses explicites et export des rapports.
- [ ] Distinguer visuellement les résultats : valide selon les contrôles disponibles, avertissement, erreur bloquante, ou non vérifiable faute de définition/données. Une absence d'alerte ne doit jamais être présentée comme une garantie de fonctionnement sûr.
- [ ] Étudier ultérieurement une simulation moteur/plant simplifiée ou un modèle dynamique, uniquement si des modèles physiques et paramètres validés sont disponibles ; documenter les hypothèses, limites et incertitudes, sans prétendre remplacer un banc moteur.
- [ ] Prévoir l'import de logs moteur enregistrés (CSV et formats documentés) pour rejouer les points de fonctionnement et comparer les valeurs mesurées aux tables/calibrations, sans modifier le calculateur.
- [ ] Prévoir une analyse de logs en lecture seule après essai sur véhicule ou banc : synchronisation temporelle, unités, canaux manquants, valeurs hors plage documentée et comparaison avant/après.
- [ ] Garantir techniquement que ce mode n'ouvre aucune session de programmation et n'appelle aucune API d'écriture ECU ; aucun test de simulation ne doit déclencher une écriture, un flash ou une adaptation véhicule.
- [ ] Ajouter des tests automatisés avec cartographies de référence documentées, fichiers volontairement corrompus, calibrations incompatibles, tables limites et scénarios de simulation reproductibles.
- [ ] Dans l'interface et les rapports, séparer explicitement « validation du fichier », « simulation mathématique des tables », « rejeu de logs » et « validation moteur sur banc/véhicule » : ces niveaux ne sont pas interchangeables.

#### Évolution future — éditeur graphique de tables et cartes de calibration (style HP Tuners / EFILive)
- [ ] Faire évoluer la grille actuelle en véritable éditeur graphique de calibration : cellules numériques colorées selon une échelle de valeurs configurable, avec légende, unités, précision et indication des valeurs modifiées.
- [ ] Proposer les vues tableau, graphique 2D et surface 3D pour les tables compatibles ; permettre de basculer entre les vues et d'afficher le tableau et le graphique simultanément lorsque l'écran le permet.
- [ ] Ajouter une navigation structurée par calculateur, catégorie et paramètre, avec recherche, favoris et ouverture de plusieurs tables.
- [ ] Permettre la sélection d'une cellule, d'une plage, d'une ligne, d'une colonne ou de toute la table, puis les opérations courantes : remplacer, ajouter, soustraire, multiplier, copier/coller et annuler/rétablir.
- [ ] Ajouter l'interpolation et le lissage horizontal, vertical et global, avec aperçu du résultat et possibilité d'annuler ; ne jamais appliquer silencieusement une transformation.
- [ ] Ajouter la comparaison de deux calibrations compatibles : vue principale, vue de comparaison et vue des différences, avec couleurs et légende distinctes des couleurs de modification locale non enregistrée.
- [ ] Afficher clairement la description du paramètre, les axes X/Y, unités, limites connues, résolution, type de donnée, facteur d'échelle, offset et provenance de la définition.
- [ ] Permettre l'édition directe des cellules et, si le modèle de données le permet sans ambiguïté, l'ajustement des points sur les graphiques 2D/3D ; synchroniser immédiatement les représentations.
- [ ] Adapter l'éditeur aux téléphones et tablettes : grille défilante et zoom lisible, commandes tactiles, sélection précise, panneaux redimensionnables et orientation paysage privilégiée pour les grandes tables.
- [ ] Conserver un historique des changements par fichier/table, avec comparaison avant/après, restauration et export individuel des tables lorsque le format est pris en charge.
- [ ] Ne présenter que les tables dont la définition est documentée pour le véhicule et le calculateur identifiés. Les définitions génériques ou non vérifiées doivent être signalées et ne doivent pas être éditables par défaut.
- [ ] Garder les modifications hors ligne tant que l'utilisateur n'a pas explicitement exporté le fichier ; l'application des changements à un fichier ne déclenche jamais une écriture ECU.
- [ ] Respecter strictement la règle calibration-only : aucune vue, transformation, importation ou exportation ne doit toucher le firmware, l'OS, le bootloader ou une région mémoire hors calibration.

- [ ] Ajouter un navigateur de cartographies par calculateur et famille de fonctions (moteur, air/carburant, allumage, couple, transmission, limites, etc.) uniquement lorsque les définitions exactes existent pour le matériel/logiciel identifié.
- [ ] Fournir une vue de calibration de type éditeur professionnel : tableau numérique avec cellules en grille, couleurs en heatmap paramétrable, légende, palette, seuils, surbrillance des cellules modifiées et distinction entre valeurs d'origine et valeurs courantes.
- [ ] Afficher pour chaque table le nom et la description, les dimensions, les axes X/Y, les unités, la précision, le type de donnée, l'endianess, le facteur, l'offset, les limites et la plage valide lorsqu'ils sont connus.
- [ ] Permettre le zoom, le défilement, le redimensionnement des colonnes, la sélection tactile précise et le basculement portrait/paysage ; adapter l'interface aux écrans de tablette sans supprimer les fonctions disponibles sur téléphone.
- [ ] Ajouter des vues graphiques 2D et 3D synchronisées avec la grille : courbes, surface colorée, rotation, zoom et sélection de points/cellules ; désactiver l'édition graphique si la correspondance point/table n'est pas parfaitement déterminée.
- [ ] Proposer les opérations de sélection et d'édition en lot : cellule, plage, ligne, colonne, table entière ; saisie directe, copier/coller, remplissage, ajout, soustraction, multiplication, division et remise à une valeur choisie.
- [ ] Ajouter interpolation selon les axes, lissage horizontal/vertical et lissage global, avec aperçu avant application, contrôle des limites et annulation immédiate.
- [ ] Fournir undo/redo, historique horodaté des modifications, comparaison avant/après et possibilité de revenir à une version précédente sans altérer le fichier source original.
- [ ] Permettre l'ouverture simultanée de plusieurs tables et la comparaison de calibrations : valeurs d'origine, valeurs de destination, delta numérique et représentation colorée dédiée aux différences.
- [ ] Ajouter recherche de paramètres, filtres par calculateur/catégorie, favoris et navigation rapide entre tables ; afficher clairement les tables indisponibles ou non documentées plutôt que fabriquer des définitions.
- [ ] Mettre à jour simultanément la grille, les vues 2D/3D, les deltas et l'état non enregistré après chaque modification ; toute transformation doit rester explicite et réversible.
- [ ] Permettre l'export d'une table isolée et d'un rapport de comparaison, en plus de l'export de la cartographie complète ; conserver unités, axes, précision et provenance dans les formats qui le permettent.
- [ ] Prévoir une ergonomie de type outil professionnel sans recopier les éléments propriétaires d'interface ou de marque de HP Tuners/EFILive.
- [ ] Ajouter des tests unitaires sur décodage/encodage, dimensions, axes, facteurs, offsets, arrondis, bornes, transformations, interpolation, lissage, undo/redo et synchronisation des vues ; utiliser des fichiers de référence documentés.

#### Évolution future — import/export de cartographies et compatibilité HP Tuners
- [ ] Permettre l'export d'une cartographie seule ou d'un ensemble de cartographies liées à un même véhicule/projet.
- [ ] Gérer les projets multi-cartographies, notamment les configurations FlexFuel lorsqu'elles nécessitent plusieurs calibrations (par exemple cartographie essence, cartographie éthanol/flexfuel ou variantes associées) ; conserver explicitement le rôle, les dépendances et les conditions d'utilisation de chaque fichier, sans supposer qu'un véhicule utilise toujours deux fichiers.
- [ ] Permettre l'import d'un fichier unique ou d'un paquet multi-cartographies, avec détection des fichiers manquants, doublons, versions incompatibles et associations incohérentes.
- [ ] Ajouter la prise en charge des formats HP Tuners, notamment le format natif de calibration/projet `.hpt` lorsque sa structure peut être lue et interprétée de façon fiable ; identifier séparément les autres formats HP Tuners réellement rencontrés et ne pas les assimiler automatiquement à un fichier BIN.
- [ ] Distinguer clairement l'ouverture native, l'import avec conversion et l'export compatible HP Tuners. Ne jamais annoncer une compatibilité aller-retour parfaite si certains champs, métadonnées ou données propriétaires ne peuvent pas être préservés.
- [ ] Préserver les fichiers sources originaux et produire un rapport d'import/export indiquant format, version, véhicule/ECU ciblé, identifiants de calibration, conversions réalisées, éléments ignorés et éventuelles pertes d'information.
- [ ] Avant toute comparaison ou utilisation, vérifier la cohérence véhicule, génération, moteur, transmission, référence matérielle ECU, identifiants logiciel/calibration et contenu des fichiers ; signaler ou bloquer les incompatibilités.
- [ ] Pour un ensemble multi-cartographies, exporter les fichiers avec un manifeste décrivant leurs noms, rôles, liens, empreintes d'intégrité et métadonnées nécessaires à leur réimportation.
- [ ] L'import/export et la conversion de fichiers restent des opérations hors ligne sur les données de calibration ; ils ne doivent jamais introduire ni modifier firmware, OS ECU, bootloader ou zones non-calibration.
- [ ] Relier ces fonctions aux deux modes d'écriture calibration-only définis ci-dessous, sans confondre le format de fichier HP Tuners avec une autorisation ou une capacité d'écriture ECU.

#### Évolution future — modes d'écriture de cartographie et récupération ECU (Camaro Gen 5 / Gen 6)
- [ ] Ajouter au module Cartographie deux modes distincts, sur le modèle de HP Tuners, **tous deux limités aux données de calibration** :
  - **Write Calibration Only** : écrire uniquement la partie de cartographie sélectionnée/modifiée, lorsque le calculateur et le format le permettent.
  - **Write Completely** : réécrire l'intégralité de la zone de calibration, même si seule une partie de la cartographie a été modifiée.
- [ ] **Exclusion stricte : aucun des deux modes ne doit écrire, remplacer ou mettre à jour le firmware, le système d'exploitation du calculateur, le bootloader ou les autres zones logicielles hors calibration.** Le terme « Completely » désigne uniquement la totalité de la calibration, jamais le firmware complet.
- [ ] Si un calculateur ou un outil ne permet pas de distinguer de façon fiable les zones de calibration des zones firmware, désactiver l'écriture pour ce calculateur.
- [ ] Maintenir les deux modes désactivés tant que la compatibilité exacte du calculateur, la méthode d'accès, les contrôles d'intégrité, l'alimentation requise et les procédures de récupération ne sont pas documentés et validés.
- [ ] Créer un référentiel versionné des calculateurs Camaro Gen 5 et Gen 6, par année, motorisation, transmission, référence matérielle, identifiant logiciel/calibration et variante de calculateur.
- [ ] Pour chaque ECU/TCM, documenter séparément : lecture, écriture partielle de calibration, écriture complète de calibration, zones mémoire de calibration concernées, outil/interface requis, conditions d'alimentation, contrôles avant/après écriture et niveau de validation.
- [ ] Référencer les procédures officielles GM et les documentations fabricants autorisées relatives aux interruptions d'écriture de calibration ; conserver la source, le numéro de bulletin/document, son applicabilité exacte et sa version.
- [ ] Ajouter un assistant de récupération intégré au module Cartographie : identifier le calculateur et l'étape interrompue, afficher la procédure applicable, expliquer les actions à effectuer et indiquer les situations nécessitant un spécialiste.
- [ ] Après perte de connexion pendant une écriture : arrêter l'opération, ne pas reprendre automatiquement, ne pas renvoyer aveuglément le dernier bloc, marquer l'état de la calibration comme inconnu et imposer une nouvelle identification/lecture de contrôle avant toute tentative.
- [ ] Ne jamais déclencher automatiquement une écriture complète de calibration pour récupérer un échec d'écriture partielle ; aucune procédure générique ne doit être appliquée à tous les ECU.
- [ ] Si la procédure de récupération applicable n'est pas connue ou ne correspond pas précisément à la référence matérielle/logicielle détectée, bloquer la programmation et expliquer pourquoi.
- [ ] Commencer par établir la matrice de compatibilité Gen 5 / Gen 6 (ECM/PCM et TCM), sans extrapoler les capacités d'une famille de calculateur à une autre.
- [ ] Préparer des tests automatisés des états et scénarios d'interruption, puis des validations sur banc et sur véhicule avec alimentation stabilisée et matériel compatible avant toute activation réelle.

### 2.8 Boîte à outils atelier — outils communs et outils dépendants du véhicule

**Décision utilisateur — 2026-11-19 :** ajouter au backlog l'ensemble des outils proposés pour compléter CamaroDiag. La boîte à outils doit être commune aux marques lorsque la fonction est générique, et afficher automatiquement les fonctions spécifiques uniquement lorsque le véhicule, le moteur, la transmission et les calculateurs sélectionnés sont identifiés et pris en charge. Ces fonctions sont à concevoir après les priorités de fiabilité et le chantier actif ; elles ne sont pas déclarées implémentées.

#### Diagnostic, analyse et acquisition
- [ ] Ajouter un analyseur de fichiers de logs/captures : ouverture des formats documentés, affichage de plusieurs courbes simultanées, sélection des canaux, synchronisation temporelle, zoom, curseurs, unités, filtres et repérage des valeurs atypiques.
- [ ] Permettre de comparer plusieurs sessions de capture (avant/après intervention), avec alignement temporel ou par régime lorsque les données le permettent, résumé des écarts et export d'un rapport.
- [ ] Ajouter un analyseur CAN hors ligne pour décoder et filtrer les trames à partir de définitions vérifiées, rechercher les variations entre captures et relier les signaux à leur source ; conserver les fonctions de capture et de diagnostic en lecture seule dans leurs rubriques normales.
- [ ] Ajouter une vue consolidée des DTC et de leurs contextes : calculateur, statut, fréquence si disponible, freeze frame, données associées, conditions d'apparition et historique ; ne pas inventer de définition constructeur.
- [ ] Constituer une bibliothèque de formes d'onde de référence (vilebrequin, arbres à cames, sondes lambda, injecteurs et autres capteurs/actionneurs) avec moteur/variante, conditions de mesure, unités, source technique et niveau de confiance. Ne jamais présenter une forme d'onde générique comme référence universelle.
- [ ] Permettre l'import et l'archivage de mesures issues d'oscilloscopes ou d'outils externes uniquement lorsque le format est documenté ; distinguer les données mesurées des illustrations pédagogiques.

#### Outils moteur et mécanique — priorité élevée après le socle atelier
- [ ] Ajouter un assistant de tests moteur pour consigner les mesures de compression, pression, température et autres contrôles pertinents, avec comparaison entre cylindres, conditions de mesure, unités, date et historique.
- [ ] Ajouter un carnet de suivi des injecteurs : référence, cylindre, date de remplacement, kilométrage, opérations réalisées et codes de correction/calibration lorsque le moteur/ECU les utilise effectivement.
- [ ] Relier le carnet injecteurs à l'assistant visuel déjà demandé : schéma spécifique au moteur, emplacement des injecteurs, marquage du code, numérotation des cylindres et orientation explicitement montrée du point de vue d'une personne debout à l'avant du véhicule et face au moteur. Distinguer gauche/droite du véhicule et gauche/droite de l'observateur ; afficher côté distribution, côté boîte, bancs et source documentaire. Bloquer toute affectation ou programmation si l'orientation et la numérotation exactes ne sont pas confirmées.
- [ ] Ajouter une aide à la distribution moteur, particulièrement prioritaire : schémas de calage adaptés au code moteur exact, repères vilebrequin et arbres à cames, chaîne/courroie, tendeurs, guides, ordre et étapes de contrôle/remontage, couples de serrage et angles lorsque des données fiables sont disponibles.
- [ ] Pour l'aide à la distribution, fournir des vues clairement orientées et légendées : avant du véhicule, personne face au moteur, côté distribution, côté boîte, bancs et numérotation des cylindres. Éviter toute ambiguïté entre gauche/droite du véhicule et gauche/droite de l'observateur.
- [ ] Associer chaque procédure de distribution à la variante exacte (marque, modèle, année, code moteur, évolution technique) et afficher la source, la révision et les conditions d'applicabilité. Ne jamais extrapoler un schéma ou un couple de serrage d'un moteur voisin.
- [ ] Ajouter des contrôles préalables et avertissements pour les moteurs interférentiels et les opérations présentant un risque de contact soupapes/pistons ; les avertissements ne remplacent pas la procédure constructeur.
- [ ] Si les informations exactes de calage, couples, angles ou outils de maintien ne sont pas disponibles et vérifiables, afficher « procédure non documentée » plutôt que compléter par supposition. Aucune commande d'actionneur ou programmation ECU ne doit être déclenchée par ces assistants mécaniques.

#### Préparation, calibration et comparaison
- [ ] Ajouter un comparateur de cartographies dédié, distinct de l'éditeur : comparaison de deux ou plusieurs fichiers compatibles, affichage des tables modifiées, écarts absolus/relatifs et heatmaps, avec identification des données non décodées.
- [ ] Relier le comparateur aux projets de calibration et à leur historique : fichier original préservé, versions successives, commentaires, date, véhicule/ECU/CAL ID/CVN et rapport des modifications.
- [ ] Ajouter un gestionnaire de projets multi-calibrations, notamment pour les configurations FlexFuel : associer les fichiers, leur rôle, le carburant/configuration visés et les métadonnées. Ne jamais mélanger des calibrations incompatibles.
- [ ] Ajouter un analyseur de logs avant/après calibration pour confronter les paramètres réellement enregistrés ; distinguer corrélation, mesure et causalité et ne pas promettre un gain de puissance sur la seule base des différences de tables.
- [ ] Relier les outils de puissance/couple déjà prévus au comparateur et à l'analyseur de logs, sans dupliquer les calculs ni les rapports existants.

#### Calculatrices et utilitaires d'atelier
- [ ] Ajouter un calculateur de rapports de boîte : rapports de transmission, rapport de pont, régime moteur, vitesse véhicule et rapport engagé, avec unités configurables et paramètres explicitement saisis ou issus d'une source vérifiée.
- [ ] Ajouter un calculateur de pneus : dimensions, diamètre, circonférence, différence de développement, écart de vitesse affichée/réelle et comparaison de montes.
- [ ] Ajouter un calculateur de démultiplication combinant pneus, boîte et pont afin d'estimer le régime à une vitesse donnée ou la vitesse à un régime donné.
- [ ] Ajouter un convertisseur d'unités automobiles (pression, température, débit, couple, puissance, vitesse, volume et consommation) avec conversions exactes et unités clairement affichées.
- [ ] Ajouter un calculateur de débit carburant/consommation avec hypothèses et unités explicites ; ne pas présenter une estimation comme une mesure réelle.
- [ ] Prévoir une architecture de calculateurs extensible, testée sur les conversions, arrondis, valeurs limites et unités incompatibles.

#### Historique, pièces et rapports
- [ ] Ajouter un carnet d'entretien/intervention par véhicule : date, kilométrage, description, symptômes, diagnostic, opérations, pièces, références, photos/fichiers joints et intervenant si renseigné.
- [ ] Ajouter un suivi des pièces remplacées, notamment injecteurs, capteurs, bobines, bougies, modules et éléments de distribution, avec référence, emplacement, date et historique.
- [ ] Permettre de rattacher les logs, captures, DTC, mesures, fichiers de calibration et rapports à une intervention, sans altérer les fichiers sources.
- [ ] Ajouter un générateur de rapports PDF professionnels : identité du véhicule et VIN selon les données disponibles, calculateurs identifiés, défauts, freeze frames, mesures, captures, pièces et opérations effectuées, date, version de l'application et limites de validation.
- [ ] Permettre l'export et l'archivage des rapports et données d'intervention ; protéger les données personnelles et permettre de masquer les identifiants avant partage.
- [ ] Distinguer clairement dans les rapports les mesures réelles, données calculées, informations issues d'une base documentaire, résultats simulés et éléments non vérifiés.

#### Architecture et règles d'affichage
- [ ] Concevoir la boîte à outils comme un espace commun aux véhicules et constructeurs, sans dupliquer les calculs génériques dans chaque profil constructeur.
- [ ] Afficher ou masquer les outils spécifiques selon le véhicule, le moteur, la transmission, les calculateurs, les capteurs disponibles et le niveau réel de prise en charge ; ne pas afficher une compatibilité supposée.
- [ ] Conserver les outils génériques (convertisseurs, calculateurs de pneus/rapports, gestionnaire de rapports et d'interventions) indépendamment de la marque.
- [ ] Relier les outils spécifiques aux données techniques exactes et à leurs sources ; indiquer « non disponible pour ce véhicule » lorsque les données ou procédures sont absentes.
- [ ] Adapter les écrans à l'utilisation sur téléphone et tablette, avec navigation tactile, unités lisibles et conservation de l'état des projets.
- [ ] Ajouter des tests unitaires pour les calculs, tests de parsing pour les fichiers pris en charge et tests d'interface pour l'affichage conditionnel selon le profil véhicule.
- [ ] Maintenir les assistants mécaniques et calculatrices hors ligne et sans écriture ECU ; toute fonction de diagnostic actif ou de programmation doit rester dans son module dédié avec ses propres prérequis et verrous.

### 3.1 Architecture multimarque future
- [ ] Étudier une architecture commune de diagnostic avec modules/adaptateurs spécifiques à chaque constructeur.
- [ ] Séparer le socle partagé (transport, sessions, journalisation, VIN, rapports, télémétrie, gestion des adaptateurs) des définitions constructeur (calculateurs, protocoles, DTC, DID, procédures et profils).
- [ ] Prévoir une sélection du véhicule et un chargement du profil correspondant ; ne pas mélanger les données propriétaires GM, Stellantis/PSA ou d'autres groupes.
- [ ] Prévoir une matrice de compatibilité par marque, modèle, génération, année, motorisation, calculateur, interface et fonction.
- [ ] N'afficher comme pris en charge que les fonctions réellement implémentées et validées.
- [ ] Conserver provisoirement le nom CamaroDiag et le périmètre Camaro ; toute décision de renommage en plateforme multimarque devra être discutée séparément.

### 3.2 General Motors — priorité basse
- [ ] Chevrolet Corvette : générations et motorisations à définir, puis profils ECU/DTC/protocoles spécifiques.
- [ ] Cadillac : sélectionner les modèles pertinents (par exemple CTS-V, ATS-V) avant toute étude technique.
- [ ] GMC : sélectionner les modèles pertinents (par exemple Sierra, Yukon).
- [ ] Buick : modèles à définir selon la demande et la disponibilité de données fiables.
- [ ] Réutiliser le socle GM uniquement lorsque les architectures et procédures sont effectivement communes ; ne pas supposer qu'une donnée Camaro s'applique à une Corvette ou Cadillac.

### 3.3 Stellantis — Dodge et RAM — priorité basse
- [ ] Dodge Challenger : générations, moteurs et calculateurs à définir.
- [ ] Dodge Charger : générations, moteurs et calculateurs à définir.
- [ ] RAM : sélectionner les générations et motorisations pertinentes (1500/2500/3500 à confirmer).
- [ ] Étudier séparément les protocoles, passerelles, calculateurs et interfaces nécessaires aux véhicules Stellantis/FCA.
- [ ] Ne pas réutiliser les définitions GM sans preuve technique.

### 3.4 Stellantis — Peugeot 207 CC — priorité basse
- [ ] Ajouter au backlog le véhicule personnel : **Peugeot 207 CC Phase 1, essence 1.6 VTi 120 ch, moteur EP6 probable**.
- [ ] Année exacte incertaine à ce stade : 2008 ou 2010 ; ne pas figer l'année sans vérification documentaire/VIN.
- [ ] Ne pas inscrire ni exploiter la plaque d'immatriculation dans le code ou les données du projet.
- [ ] Confirmer la variante exacte, le code moteur, la boîte et les calculateurs via VIN et/ou carte grise (données personnelles masquées).
- [ ] Étudier le diagnostic PSA : calculateur moteur, BSI, ABS/ESP, airbags, combiné, climatisation et autres calculateurs présents selon finition.
- [ ] Identifier les protocoles et interfaces adaptés à cette génération PSA, ainsi que les limites de l'ELM327.
- [ ] Distinguer diagnostic OBD générique et diagnostic constructeur PSA ; ne pas promettre le diagnostic BSI ou les fonctions spéciales sans interface/protocole adaptés.

### 3.5 Autres marques éventuelles
- [ ] Toute nouvelle marque/modèle proposé ultérieurement doit être ajouté ici avec son groupe constructeur, génération, moteur et priorité.
- [ ] Évaluer la faisabilité à partir des protocoles, interfaces, données vérifiables et possibilités de validation avant d'accepter un nouveau périmètre.

### 2.7 Mode avancé — fonctions à risque élevé et opérations sensibles

**Précision utilisateur — 2026-11-19 :** le Mode avancé ne doit pas devenir un simple regroupement de tous les outils techniques. Le CAN Sniffer, les logs, l'identification ECU et les diagnostics approfondis ne mettent pas, à eux seuls, le véhicule en péril lorsqu'ils restent en lecture seule. Ils restent dans les écrans de diagnostic adaptés. Le Mode avancé doit surtout regrouper les opérations qui peuvent modifier durablement le comportement, l'identité, la sécurité ou le logiciel du véhicule.

- [ ] Créer une zone dédiée « Mode avancé », distincte du diagnostic courant et accessible par activation explicite.
- [ ] À la première entrée, afficher un avertissement et demander une confirmation. L'avertissement doit rester consultable depuis le mode.
- [ ] Utiliser le texte suivant comme base de l'avertissement :

  > **Mode avancé — réservé aux utilisateurs avertis**
  >
  > Les fonctions de cette rubrique peuvent modifier des données essentielles du véhicule, ses systèmes de sécurité ou le logiciel de ses calculateurs. Une opération incorrecte, un fichier incompatible, une alimentation instable ou une interruption de communication peut rendre un calculateur inutilisable, empêcher le démarrage du véhicule ou provoquer le dysfonctionnement de certains équipements.
  >
  > Certaines modifications peuvent également être interdites ou soumises à des obligations réglementaires selon le pays, notamment lorsqu'elles concernent l'antidémarrage, l'identification du véhicule, les émissions polluantes, les dispositifs de sécurité ou l'homologation.
  >
  > Réservez ces fonctions aux interventions légitimes sur un véhicule que vous êtes autorisé à réparer ou configurer. Vérifiez la compatibilité, la documentation et la réglementation applicables avant toute opération. Une sauvegarde ne garantit pas qu'une opération interrompue puisse être récupérée.
  >
  > **Vous êtes responsable de vérifier que l'intervention est autorisée et adaptée au véhicule concerné.**

- [ ] Placer dans ce mode l'atelier Cartographie professionnel et ses opérations de modification de calibration. Maintenir les règles strictes calibration-only : aucune écriture de firmware, d'OS ECU, de bootloader ou de zone hors calibration via les fonctions de cartographie.
- [ ] Étudier un module Antidémarrage / Immobilizer pour les opérations légitimes et documentées : apprentissage ou remplacement de clés, ajout de clés, réinitialisation/reconfiguration après remplacement d'un module et diagnostic de synchronisation entre modules.
- [ ] Étudier la gestion des clés et télécommandes, y compris les procédures de réapprentissage après remplacement ou perte, uniquement pour les véhicules et variantes où les procédures et prérequis sont documentés.
- [ ] Ne pas proposer de fonction générique de désactivation ou de contournement de l'antidémarrage. Toute opération doit suivre une procédure constructeur autorisée, avec contrôle de légitimité/autorisation, compatibilité exacte et journalisation. Si les prérequis ne sont pas réunis, la fonction doit rester indisponible.
- [ ] Étudier les procédures de configuration et d'appairage des systèmes radio/infodivertissement après remplacement, selon le module et le véhicule : identification, configuration, apprentissage et restauration des paramètres documentés.
- [ ] Ajouter une fonction de programmation / apprentissage des codes de correction ou de calibration des injecteurs après remplacement, lorsque le moteur et le calculateur utilisent effectivement des codes injecteurs (par exemple codes de compensation individuels). Lire et afficher les codes existants si cette lecture est prise en charge, permettre la saisie/import des nouveaux codes avec validation du format, de la longueur et de l'affectation au bon cylindre, puis lancer uniquement la procédure constructeur documentée et vérifier le résultat. Ne jamais inventer de code ni appliquer une procédure générique à tous les moteurs ; afficher clairement « non pris en charge » si le moteur/ECU ne nécessite pas ou ne permet pas cette opération.
- [ ] Ajouter une aide visuelle guidée pour localiser physiquement les injecteurs et leurs codes : schéma moteur adapté au véhicule, à la motorisation et à l'orientation (vue de dessus/avant si utile), numérotation des cylindres clairement indiquée, repérage des rangées/bancs et correspondance cylindre ↔ injecteur ↔ emplacement du code. Afficher où chercher le code sur l'injecteur (gravure, étiquette ou zone de marquage) avec illustrations ou photos de référence lorsqu'elles sont disponibles et documentées.
- [ ] Dans l'assistant, permettre de sélectionner un cylindre sur le schéma pour afficher l'injecteur correspondant, son code lu/saisi et son état ; proposer une vérification visuelle de l'ordre des cylindres avant validation afin d'éviter une inversion entre bancs ou cylindres.
- [ ] Adapter les schémas à chaque moteur : ne jamais réutiliser une numérotation générique. Afficher la convention de référence (vue depuis l'avant du véhicule, côté distribution, banc 1/2, etc.) et la source technique. Si l'orientation ou l'ordre des cylindres n'est pas confirmé pour la variante identifiée, demander confirmation et ne pas autoriser la programmation.
- [ ] Prévoir une aide à la lecture du marquage : exemples de formats connus pour le moteur concerné, distinction entre référence de pièce et code de calibration, zoom/cadrage de photo si l'utilisateur fournit une image, et saisie manuelle assistée. Ne jamais déduire ou fabriquer un code à partir d'une photo illisible.
- [ ] Étudier les changements d'identifiants véhicule (VIN) uniquement pour les cas de réparation/remplacement de module où une procédure constructeur autorisée le prévoit. Ne pas fournir de fonction de réécriture arbitraire du VIN, de clonage d'identité ou de masquage de provenance. Conserver une trace de l'identifiant initial, du motif, de la procédure et du résultat lorsque cela est légalement permis.
- [ ] Étudier un gestionnaire de mises à jour logicielles constructeur pour les calculateurs GM : inventaire des modules et versions, recherche de mises à jour applicables, vérification de compatibilité, téléchargement depuis une source autorisée, contrôle d'intégrité/signature si disponible, alimentation requise, sauvegardes possibles et procédure de programmation documentée.
- [ ] Ne jamais supposer que les firmwares GM sont librement disponibles. Rechercher d'abord les voies d'accès légitimes (portails et services de programmation GM, abonnements professionnels, fournisseurs autorisés ou fichiers fournis légalement par l'utilisateur). Ne pas intégrer de dépôts non autorisés, de fichiers d'origine incertaine ou de firmware dont la provenance/licence n'est pas établie.
- [ ] Distinguer strictement les mises à jour firmware constructeur des opérations de calibration. Un firmware peut inclure ou remplacer l'OS du calculateur : il doit donc disposer d'un module, d'un processus et de garde-fous séparés de l'atelier Cartographie.
- [ ] Avant toute programmation firmware, exiger l'identification précise du véhicule et du module, une correspondance vérifiée du matériel/logiciel, un fichier authentique et applicable, une alimentation stabilisée, une interface officiellement compatible et une procédure de récupération documentée. À défaut, afficher que la programmation n'est pas prise en charge.
- [ ] Maintenir les outils en lecture seule (CAN Sniffer, visualisation des données, logs, identification ECU et diagnostics approfondis) dans leurs rubriques de diagnostic normales ; ils ne doivent pas être déplacés artificiellement dans le Mode avancé.
- [ ] Ne jamais considérer l'activation du Mode avancé comme une autorisation technique : chaque fonction sensible conserve ses propres contrôles d'accès, conditions de compatibilité et verrous de sécurité.
- [ ] Ajouter des tests d'interface et de sécurité pour vérifier que les opérations sensibles restent indisponibles sans leurs prérequis et qu'aucune fonction de diagnostic en lecture seule ne déclenche une écriture.


## 4. Qualité, traçabilité et éventuelle commercialisation — à préparer progressivement

- [ ] Maintenir une architecture modulaire et testable sans introduire prématurément une couche commerciale.
- [ ] Documenter les licences, attributions et origines de toutes les bases de données et définitions intégrées.
- [ ] Distinguer dans l'application et la documentation : fonction validée, fonction expérimentale, fonction simulée et fonction non disponible.
- [ ] Maintenir une matrice de compatibilité honnête et vérifiable.
- [ ] Préparer progressivement documentation utilisateur, diagnostic des problèmes et rapports exportables.
- [ ] Assurer la traçabilité des versions, builds, changements et résultats de tests.
- [ ] Étudier licences/éditions et distribution commerciale uniquement lorsque le produit est suffisamment mûr ; ne pas retarder le développement fonctionnel pour cela.
- [ ] Ne faire aucune promesse commerciale concernant une fonction non validée.

## 5. Informations véhicule de référence actuellement connues

### Camaro de développement/test
- Année : 2012.
- Moteur : LFX 3.6L V6.
- Transmission : manuelle.
- Interface actuellement disponible : ELM327 uniquement.
- Pas de véhicule réel disponible pour les validations physiques au moment de la rédaction.
- Cible Android : versions 10 à 16.

### Peugeot 207 CC personnelle — extension future
- Modèle : 207 CC Phase 1.
- Motorisation annoncée : 1.6 VTi essence, 120 ch ; code EP6 probable.
- Année : hésitation entre 2008 et 2010, à confirmer.
- Aucune plaque d'immatriculation ni donnée personnelle ne doit être conservée dans ce backlog.

## 6. Historique des décisions

- 2026-11-19 : structurer l'atelier Cartographie professionnel en chantier prioritaire après PR #35, avec phases socle fichiers, éditeur graphique, simulation hors ligne, analyse puissance/couple, multi-cartographies/formats externes et programmation calibration-only strictement désactivée jusqu'à validation.
- 2026-11-19 : ajouter les courbes de puissance et de couple avant/après cartographie, avec gains par régime, pics et rapports ; distinguer strictement mesures banc/logs, estimations fondées sur un modèle validé et calculs théoriques, sans déduire de gains fiables des seules tables.
- 2026-11-19 : ajout d'un mode de validation et simulation hors ligne des cartographies, comparaison avant/après, contrôles statiques, simulation des tables lorsque les définitions sont connues et rejeu de logs ; aucune écriture ECU, et aucune prétention à remplacer un essai moteur sur banc/véhicule.
- 2026-11-19 : préciser l'éditeur graphique de calibration de niveau professionnel (grilles colorées, vues 2D/3D, opérations sur cellules, interpolation/lissage, comparaison, historique, ergonomie tablette, définitions vérifiées et tests) ainsi que les exigences d'import/export multi-cartographies et HP Tuners ; ces éléments restent au backlog et ne sont pas déclarés implémentés.
- 2026-11-18 : décision de conserver CamaroDiag comme priorité immédiate, tout en concevant progressivement une architecture extensible.
- 2026-11-18 : extensions GM (Corvette, Cadillac, GMC, Buick) et Stellantis (Dodge Challenger/Charger, RAM) classées en priorité basse.
- 2026-11-18 : ajout de la Peugeot 207 CC Phase 1 1.6 VTi 120 (EP6 probable, année 2008 ou 2010 à confirmer) au backlog priorité basse.
- 2026-11-18 : demande utilisateur de centraliser les évolutions futures dans ce fichier à la racine du dépôt afin de ne pas perdre les demandes.

---

**Consigne de maintenance :** à chaque nouvelle demande d'évolution durable, ajouter une entrée dans la bonne section, indiquer sa priorité et son statut, puis conserver ce fichier à jour au fil des PR. Avant de commencer un nouveau chantier, relire ce backlog et vérifier ce qui est déjà réalisé afin d'éviter les doublons.
