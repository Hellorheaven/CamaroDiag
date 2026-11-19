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

- 2026-11-18 : décision de conserver CamaroDiag comme priorité immédiate, tout en concevant progressivement une architecture extensible.
- 2026-11-18 : extensions GM (Corvette, Cadillac, GMC, Buick) et Stellantis (Dodge Challenger/Charger, RAM) classées en priorité basse.
- 2026-11-18 : ajout de la Peugeot 207 CC Phase 1 1.6 VTi 120 (EP6 probable, année 2008 ou 2010 à confirmer) au backlog priorité basse.
- 2026-11-18 : demande utilisateur de centraliser les évolutions futures dans ce fichier à la racine du dépôt afin de ne pas perdre les demandes.

---

**Consigne de maintenance :** à chaque nouvelle demande d'évolution durable, ajouter une entrée dans la bonne section, indiquer sa priorité et son statut, puis conserver ce fichier à jour au fil des PR. Avant de commencer un nouveau chantier, relire ce backlog et vérifier ce qui est déjà réalisé afin d'éviter les doublons.
