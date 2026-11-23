# PROMPT DE REPRISE — CamaroDiag

> Fichier de continuité du projet. À lire en premier dans tout nouveau chat consacré à CamaroDiag. Mettre ce fichier à jour dès qu'une décision durable, un changement de périmètre, une modification GitHub/PR, un résultat CI ou un état technique important est confirmé. Tenir le changelog propre à chaque PR (`docs/CHANGELOG_PR<number>.md`) à jour sur la branche de cette PR.

## DÉCISIONS LES PLUS RÉCENTES — À APPLIQUER DANS TOUT NOUVEAU CHAT (23/11/2026)

**Lire impérativement [AGENTS.md](../AGENTS.md) et [l'audit de PR #95](AUDIT_PR95_C1_C2_C4_C6_C8.md).** Ces règles priment sur les anciennes indications de PR #35, branche `feat/live-can-monitor`, CI à chaque ~50 commits, verrouillage ECU globalement lecture seule et anciens blocages d'écriture décrits plus bas. Sur GitHub au 23/11/2026 : PR **#95** et branche **`chore/unified-ci`**, non fusionnée ; toujours rafraîchir ce statut en temps réel.

**Demande définitive du propriétaire :** aucun verrou ou licence d'écriture inventé par CamaroDiag. Conserver seulement les licences commerciales, les vraies exigences constructeur/ECU et les vérifications techniques de cohérence/integrité. Pour chaque opération pouvant écrire, afficher **avertissement de risque + responsabilité CamaroDiag (dans les limites légales) + OK**, puis une **deuxième confirmation Oui/Non** : Non, fermer ou perdre la session = zéro transmission. **Avant le premier effacement, faire une vraie lecture physique des octets d'origine de TOUS les secteurs à modifier, une double comparaison indépendante à l'image d'origine, une sauvegarde durable propre à chaque bloc avec adresse/longueur/région/ECU et une relecture du fichier de sauvegarde. Ne jamais se contenter d'un fichier BIN fourni par l'utilisateur ou d'un hash comme sauvegarde du bloc réel.** Vérifier avant la transmission que **l'adresse ECU, la région, le secteur d'effacement, la taille, les données et le checksum du bloc réellement envoyé** correspondent exactement au changement demandé ; contrôle pré-effacement et pré-trame ; lecture de vérification après écriture. Si sauvegarde / identité / géométrie / octets ne concordent pas, aucune commande d'effacement ou écriture. Ceci concerne tout ECU, tout protocole et tout adaptateur dès lors qu'un pilote réel existe ; pas de fausse compatibilité. Les commandes RAW sans définition mémoire cible ne peuvent pas être déclarées vérifiées bloc-à-bloc.

**Processus** : une seule branche+PR active ; ne pas fusionner sans accord ; une campagne de tests après un chantier terminé, correctifs groupés puis revalidation ; C1/C2/C4/C6/C8 à reprendre/auditer. Les validations logicielles ne prouvent pas la capacité de programmation physique.


## Conservation obligatoire de ce fichier

`docs/PROMPT_REPRISE.md` est le document de continuité demandé par l'utilisateur. Il est indispensable pour reprendre un développement complet et répondre précisément à la question « où en est le développement ? ».
- Ne jamais supprimer, renommer, déplacer, vider ou remplacer ce fichier par un autre document.
- Le maintenir sur `main` et le mettre à jour dès qu'un état durable change.
- Toute modification structurelle ou suppression de son contenu nécessite l'accord explicite de l'utilisateur.
- Lors de chaque reprise, le lire avant toute action et vérifier ensuite l'état GitHub réel.

## Instruction de démarrage pour l'assistant

Tu reprends le développement et le pilotage du dépôt **Hellorheaven/CamaroDiag** :
https://github.com/Hellorheaven/CamaroDiag

Commence par lire :
1. `docs/PROMPT_REPRISE.md` (ce fichier),
2. `docs/FUTURE_FEATURES_CAN_VEHICLE_GARAGE.md` (backlog détaillé, conservé en miroir pendant la période de vérification),
3. les GitHub Issues liées depuis ce prompt et le backlog, puis les PR associées,
4. les changelogs `docs/CHANGELOG_PR<number>.md` des PR pertinentes,
5. puis l'état GitHub réel : branche principale, PR ouvertes, branches, derniers commits, workflows, jobs, logs, tests et fichiers modifiés.

Ne traite jamais les informations d'état ci-dessous comme éternellement actuelles : elles sont le dernier état connu à la rédaction. **Rafraîchis les données GitHub avant toute affirmation sur l'état actuel.** Ne prétends jamais avoir travaillé en arrière-plan entre deux conversations. Ne dis pas qu'un fichier, une PR ou un build est terminé sans preuve de l'action ou du résultat.

## Synchronisation documentaire obligatoire

Cette règle est prioritaire et s'applique en continu :
- **Sur `main` uniquement :** maintenir `docs/PROMPT_REPRISE.md` et `docs/FUTURE_FEATURES_CAN_VEHICLE_GARAGE.md`. Le prompt de reprise conserve les décisions, contraintes et procédure de reprise ; le backlog conserve les demandes, lots, priorités et dépendances. Pendant la période de vérification demandée par l'utilisateur, les exigences sont maintenues dans le backlog Markdown ET dans les GitHub Issues correspondantes : les deux supports doivent rester synchronisés.
- **Dans chaque PR :** maintenir un changelog propre à la PR sur sa branche, nommé `docs/CHANGELOG_PR<number>.md` (par exemple `docs/CHANGELOG_PR35.md`). Il trace les changements effectivement réalisés dans cette PR, corrections, décisions propres à son périmètre, tests et résultats de validation avec les SHA/runs pertinents.
- Ne pas ajouter les entrées de changelog d'une PR directement sur `main` avant sa fusion. Le changelog entre dans `main` naturellement avec la PR fusionnée.
- Mettre à jour ces fichiers dans le même lot de travail que le changement concerné, sans attendre la fin de la conversation. Une modification de périmètre ou une décision durable met à jour prompt/backlog sur `main` ; chaque évolution de code ou validation de PR met à jour le changelog de cette PR.
- **Priorité aux fonctions natives GitHub :** utiliser les Pull Requests, Issues, GitHub Actions, checks et releases réellement disponibles. Les Issues sont le suivi opérationnel des lots ; les PR portent les changements ; Actions/checks portent les résultats CI. Le backlog Markdown reste en miroir détaillé pendant la période de vérification demandée. Ne pas annoncer GitHub Projects, labels, milestones ou automatisations comme configurés sans les avoir créés et vérifiés.
- **Synchronisation backlog ↔ PR :** lorsqu'une PR réalise une fonctionnalité inscrite au backlog, celle-ci doit être retirée du backlog des travaux à faire dès que la fonctionnalité est effectivement intégrée à `main` (PR fusionnée et contenu vérifié). Tant que la PR n'est pas fusionnée, conserver l'élément dans le backlog en indiquant qu'il est « en cours dans PR #… » afin de ne pas faire disparaître un travail non encore livré. Le changelog de la PR conserve l'historique de ce qui a été livré ; ne pas conserver la fonctionnalité terminée dans la liste des tâches futures. Si seule une partie est livrée, retirer uniquement la partie achevée et laisser le reliquat clairement décrit.
- Ne jamais inscrire un test/build comme réussi sans résultat observable ; noter explicitement `en attente`, `skipped`, `échoué` ou `non exécuté` le cas échéant.
- Si une modification du prompt ou du backlog sur `main` est nécessaire pendant une PR, effectuer ce commit documentaire séparément sur `main` et ne pas mélanger le contenu de la PR dans ces fichiers.

## Décision impérative du propriétaire — jamais de sécurité ECU artificielle (23/11/2026)

Ne JAMAIS ajouter ni réintroduire, sans demande et accord explicites de l'utilisateur, des clés de confiance, signatures de profils, certificats de programmation, listes figées de pilotes ou interrupteurs bloqués par défaut qui ne sont pas exigés par le protocole réel ECU/transport. C'est une perte de temps et empêche les essais réels. Cette interdiction s'applique aussi aux tests, à la CI et aux futures PR. Toujours expliquer toute restriction nouvelle et obtenir une validation avant de l'implémenter.

Séparer les licences Firebase de CamaroDiag (droits d'utilisation), le jeton et le TLS du pont (sécurité réseau) et le seed/key GM `0x27` (véritable échange ECU). Aucun ne justifie une clé de signature CamaroDiag pour programmer un E39. En revanche, conserver les vérifications techniques qui évitent un ECU inutilisable : identité exacte, sauvegarde complète et réellement restaurable, mémoire/firmware compatible, alimentation stable, checksums OEM, vérification après écriture, récupération réelle. La disponibilité du code ne prouve pas sa validation sur matériel.

Une seule PR et une seule branche actives ; pas de fusion sans autorisation. C2 reste INCOMPLET jusqu'à preuve d'une image entière restaurable. Respecter cette décision sur toute reprise du développement.

**Nouvelle exigence UI confirmée le 23/11/2026 : avant chaque opération utilisateur susceptible d'écrire sur un ECU ou un élément connecté**, afficher deux écrans successifs : (1) avertissement sur les risques de dommage et mention que CamaroDiag décline toute responsabilité dans les limites de la loi, à acquitter avec **OK** ; (2) confirmation explicite **Oui / Non**, avec **Non** ou fermeture entraînant immédiatement l'annulation **sans envoyer la commande**. L'OK initial ne vaut jamais autorisation d'écriture. Réafficher pour toute nouvelle opération (pas à chaque paquet interne d'une écriture continue). Appliquer à tous les futurs pilotes, écrans, protocoles et adaptateurs, y compris consoles brutes et voies J2534 ; vérifier que le contexte de connexion n'a pas changé au moment de l'envoi. **Cette double confirmation est expressément souhaitée par l'utilisateur et ne doit pas servir de prétexte pour réintroduire d'autres clés ou blocages artificiels.**

## Règles de collaboration non négociables

- Répondre en français, de façon directe, avec un état concret et honnête.
- Distinguer systématiquement : demandé / backlog / spécifié / codé / testé automatiquement / compilé / validé sur véhicule.
- Ne jamais inventer un pourcentage d'avancement. Si un pourcentage est demandé, l'établir à partir des critères d'acceptation vérifiés et expliquer le calcul ; sinon donner les éléments terminés et restants.
- Une CI verte avec des jobs de compilation ignorés (`skipped`) ne prouve pas que l'application compile.
- Ne pas confondre test unitaire, simulation, analyse de traces, build réel et essai physique.
- Ne jamais annoncer une compatibilité véhicule, ECU, adaptateur, protocole, écriture ou programmation sans preuve technique.
- Travailler par PR fonctionnelle cohérente. La PR #35 est réservée au Sniffer / Console CAN. Les autres demandes vont au backlog puis dans des PR distinctes, par lots.
- Ne jamais fusionner une PR sans autorisation explicite de l'utilisateur.
- Ne pas fermer/supprimer des branches ou modifier des noms/structures sans raison et vérification.
- Le fichier `docs/PROMPT_REPRISE.md` est essentiel à la continuité du projet : **ne jamais le supprimer, le déplacer ou le renommer sans demande explicite de l'utilisateur**. Le maintenir à jour sur `main` conformément à la synchronisation documentaire.
- Conserver `main` et au plus une branche de travail associée à l'unique PR active ; vérifier les noms dans GitHub. Au 23/11/2026 : `chore/unified-ci`, PR #95. Ne jamais créer une nouvelle branche/PR de sa propre initiative.
- Tous les documents destinés aux utilisateurs doivent expliquer les fonctions, les véhicules, le matériel compatible, l'utilisation et les limites, sans exposer les détails internes de développement. Seuls le backlog et les documents d'évolutions futures sont exemptés et peuvent rester techniques.
- Respecter le périmètre validé. Pas de dérive fonctionnelle dans une PR en cours.
- L'utilisateur travaille souvent depuis Android et GitHub Actions ; privilégier les opérations directes dans le dépôt plutôt que de lui demander de téléverser des fichiers quand le dépôt suffit.
- Fournir des fichiers complets lorsqu'il demande un fichier, pas des extraits incomplets.
- Appliquer la synchronisation documentaire obligatoire décrite ci-dessus. Enregistrer les SHA et liens utiles ; marquer les incertitudes au lieu de les transformer en faits.

## Projet et objectifs

CamaroDiag est une application Android de diagnostic et d'atelier automobile, d'abord pour Chevrolet Camaro Gen 5 et Gen 6, V6 et V8 selon compatibilité réellement démontrée. Architecture prévue extensible à d'autres plateformes sans supposer qu'elles partagent les mêmes protocoles.

Objectifs généraux :
- Diagnostic multi-calculateurs et données OBD/constructeur selon véhicule et interface.
- Garage multi-véhicules avec VIN, profils et historique isolé.
- Tableau de bord temps réel personnalisable donnant accès à toutes les données réellement exposées.
- Module unifié Sniffer / Console CAN : capture, analyse, décodage, console et émission si matériellement supportée.
- Outils de service, dont codage injecteurs uniquement si la procédure ECU le requiert et est documentée.
- Atelier de calibration avec tables colorées, édition, comparaison et import/export réellement interprétés.
- Adaptation téléphone/tablette, langues complètes et robustesse de session.
- Cahier de recette Excel complet à remettre à l'utilisateur lorsque le périmètre sera stabilisé.

## Véhicule et matériel utilisateur connus

Profil de test déclaré :
- Chevrolet Camaro 2012, moteur LFX 3.6L V6, boîte manuelle.
- Interface actuellement déclarée pour ce véhicule : ELM327 Bluetooth.
- Android cible : Android 10 à 16.
- L'utilisateur avait indiqué ne pas avoir accès à sa Camaro pendant une période et devait la récupérer environ une semaine après les échanges du 18 novembre 2026. **Vérifier la disponibilité actuelle avant de planifier des essais physiques.**

Autres interfaces possédées/déclarées :
- ELM327 Bluetooth ; variantes USB/Wi-Fi envisagées, modèles exacts à identifier.
- VXDIAG VCX Nano utilisé pour Ford/Mazda ; variante, firmware, licences et capacités exactes à vérifier. Ne pas présumer compatible GM.
- Interface Autocom pour PSA, logiciel estimé 2021.24 ; référence matérielle exacte à vérifier.
- Câble KKL USB destiné à des motos (Aprilia/KTM/Triumph/Moto Guzzi/Ducati) ; chipset et capacités exacts à vérifier. Le fait qu'Android affiche BHELI32 dans un sélecteur USB ne démontre aucune compatibilité automobile ; BHELI32 est associé à un outil de firmware ESC drone.
- L'utilisateur a évoqué TuneECU comme source d'inspiration fonctionnelle pour les calibrations moto ; étudier les concepts utiles, sans copier ni déclarer une compatibilité non vérifiée.

Ne pas supposer que le VCX Nano Ford/Mazda peut devenir un outil GM par simple changement de logiciel ou de licence. Séparer matériel, firmware, licence, transceiver, protocole et droits. Ne pas contourner ni forger des licences/authentifications fournisseur.

## Plateformes envisagées

- Priorité : Camaro Gen 5 et Gen 6, V6/V8.
- Corvette : extension GM potentielle à étudier séparément.
- Dodge Challenger, Charger et RAM : véhicules non-GM, architecture/profils indépendants.
- Peugeot 207 CC phase 1, moteur 1.6 VTi, année estimée 2008 ou 2010 : plateforme PSA séparée ; année exacte à confirmer.
- Aucune prise en charge ne doit être inférée de la marque, du moteur ou de la proximité de génération.

## Décisions fonctionnelles déjà établies

### Sniffer / Console CAN — PR #35 seulement
- L'utilisateur considère sniffer et console comme **un seul module fonctionnel**, pas deux modules indépendants.
- Le module ne doit pas être arbitrairement limité à la lecture seule : si l'interface et le bus permettent réellement l'émission, l'utilisateur veut pouvoir préparer et envoyer des trames/requêtes.
- Montrer les trames brutes, décodages, filtres, recherche, historique, capture, export et console.
- Les capacités réelles de chaque interface doivent être détectées/qualifiées. Ne jamais faire croire qu'un ELM327 courant peut capturer ou émettre toutes les trames CAN.
- PR #35 reste strictement limitée à ce module. Tableau de bord télémétrie, garage, calibration, injecteurs, déconnexion globale, matrice complète des interfaces, multi-marque et adaptation tablette/langues restent hors de cette PR.
- Ne pas fusionner sans autorisation et sans tests/builds réels requis.

### Déconnexion
Comportement volontairement simple, à appliquer à tous les écrans : déconnexion véhicule/interface/Bluetooth => arrêt immédiat de l'opération ou de la session, message bref précisant la cause et demande de relancer. Pas de reconnexion automatique, reprise silencieuse ou logique de récupération inutile.

### Télémétrie temps réel
L'utilisateur veut pouvoir sélectionner **toutes les données réellement disponibles** sur les calculateurs accessibles, pas uniquement quelques PIDs moteur. Inclure, selon disponibilité réelle, sondes lambda/O2 amont/aval par banc, trims, températures, pressions, MAF, ratés, transmission, ABS, vitesses de roues, ESP, TPMS, carrosserie, portes, vitres, ceintures et éclairage. Distinguer non supporté, non exposé, inaccessible avec l'interface et définition inconnue. Ne jamais inventer de valeurs.

### Calibration
- Interface atelier avec tableaux de calibration et cellules colorées, axes/unités, comparaison et édition.
- Enregistrement partiel ou complet **de la zone de calibration uniquement** ; ne pas confondre avec une sauvegarde complète de la mémoire ECU.
- Gérer plusieurs cartographies/configurations, notamment flexfuel.
- Import/export HP Tuners .hpt demandé ; étudier EFILive également, mais ne revendiquer que les formats réellement interprétés.
- Les écritures ne sont permises que si la zone mémoire et la compatibilité ECU/interface sont documentées. Pas d'écriture aveugle, ni de reprise automatique en cas de coupure.
- L'utilisateur souhaite un niveau fonctionnel comparable aux ateliers EFI/HP Tuners, sans prétendre à une équivalence avant réalisation et validation.

### Injecteurs
- Étudier le codage uniquement pour les moteurs/ECU qui l'exigent effectivement.
- Aide visuelle pour localiser le code sur l'injecteur et l'associer au cylindre.
- Représenter sans ambiguïté l'avant du moteur et l'orientation vue par une personne face au moteur ; clarifier gauche/droite côté véhicule et observateur.

### Langues
- Couvrir les 24 langues officielles de l'UE.
- Conserver aussi hébreu, russe, chinois simplifié, japonais, coréen et arabe.
- Premier lancement : langue système si intégralement supportée, sinon anglais.
- Sélecteur manuel indépendant de la langue du téléphone, avec option explicite « Suivre la langue du téléphone » ; choix mémorisé.
- Android 10–16, RTL, formats régionaux, aucune langue annoncée si traductions incomplètes.
- État de code antérieurement observé : ressources pour plusieurs langues et sélecteur manuel déjà présents ; la liste exacte et la complétude doivent être réauditées. L'option « suivre le téléphone » n'était pas clairement établie. Ne pas prétendre que les 24 langues sont déjà développées.

### Tablettes
- Profils de recette demandés : **8 pouces, 10 pouces, 12 pouces**.
- 10 pouces comme référence équilibrée ; 8 pouces compact ; 12 pouces grand format.
- Tester portrait/paysage, tactile, clavier/souris, redimensionnement et multi-fenêtre.
- Implémenter une interface réellement responsive selon largeur de fenêtre en dp et classes de taille, jamais selon la seule diagonale physique.
- L'adaptation tablette n'était pas confirmée dans l'audit antérieur.

## Backlog canonique

Le document détaillé et prioritaire pour les lots différés est :
`docs/FUTURE_FEATURES_CAN_VEHICLE_GARAGE.md`

Il contient le périmètre de la PR #35, les principes transverses et les lots fonctionnels :
1. Couche d'adaptation et matrice des capacités matérielles.
2. Garage et profils véhicules.
3. Robustesse commune des sessions/déconnexions.
4. Tableau de bord télémétrie multi-calculateurs.
5. Outils d'entretien et procédures de service / injecteurs.
6. Atelier de calibration professionnel.
7. Extension plateformes et multi-marques.
8. Cahier de tests utilisateur Excel.
9. Internationalisation complète.
10. Adaptation tablette et grands écrans, avec profils 8/10/12 pouces.

Respecter les dépendances précisées dans ce document et ouvrir une PR distincte par lot cohérent.

## Règles CI / livraison demandées

- Releases uniquement depuis `main`.
- PR : validations Debug et tests adaptés.
- Déclenchement d'un build complet périodique tous les 50 commits de PR depuis le dernier build complet réussi (seuil configurable). Si le commit du seuil est intermédiaire/incomplet, différer au premier commit cohérent suivant, puis réévaluer chaque commit tant qu'un build complet n'a pas réussi.
- Build complet périodique : tests unitaires, lint, Android Debug compilation/package, Windows bridge x64 et x86 compilation/package lorsque ces modules sont concernés.
- Attacher journaux et artefacts au SHA précis.
- Un job skipped ne compte jamais comme un succès.
- Passage Ready for review : validations complètes réelles ; les commits ultérieurs doivent relancer les validations appropriées.

## État GitHub connu — photographie du 20 novembre 2026, à rafraîchir à chaque reprise

- Dépôt : https://github.com/Hellorheaven/CamaroDiag
- PR unique en cours : [#35 — Sniffer / Console CAN](https://github.com/Hellorheaven/CamaroDiag/pull/35).
- État observé : **Open, Draft, non fusionnée**. Ne pas fusionner sans autorisation explicite de l'utilisateur.
- Branche de travail : `feat/live-can-monitor`.
- Branches existantes vérifiées : uniquement `main` et `feat/live-can-monitor`. Conserver strictement ces deux branches et une seule PR ; ne créer ni branche ni PR supplémentaire sans accord explicite.
- Dernière photographie GitHub vérifiée le 20 novembre 2026, avant la présente synchronisation : PR head `0993e41263e322cfc7210f3327977572840a6abe`, 609 commits d'avance sur `main`, 0 en retard, 67 fichiers modifiés. Le SHA changera lors des prochains commits ; toujours rafraîchir ces données au démarrage d'un nouveau chat. Ces chiffres sont une photographie et doivent être rafraîchis au démarrage de tout nouveau chat.
- PR : https://github.com/Hellorheaven/CamaroDiag/pull/35
- Le périmètre reste le module unifié Sniffer / Console CAN. L'envoi de commandes/trames est autorisé dans l'interface uniquement lorsque les capacités réelles du matériel et du réseau le permettent.
- Aucun merge n'a été effectué.

### Avancement concret récent dans la PR #35

**Transport BLE**
- Écritures GATT sérialisées et invalidation de la session lorsqu'une écriture échoue, expire ou est annulée avec un résultat ambigu.
- Protection contre les callbacks tardifs et contre la fermeture d'une nouvelle session par une ancienne coroutine.
- Les lectures BLE en attente disposent désormais d'un signal de fermeture dédié ; les lectures sont sérialisées et associées à l'instance GATT qui les a commencées.
- Un contrôle supplémentaire de l'identité GATT après l'enregistrement du lecteur ferme la course où `close()` s'exécute juste avant l'inscription du lecteur et où le signal one-shot pourrait être manqué. Code : commit `5cd3ad5d8463d4f6fc0878732ad0548f5b9543b8` ; changelog PR35 mis à jour au commit `5fb56de19ac5c36780bbfb439577256544058b4c`.
- Test unitaire ajouté : `communication/src/test/java/com/camarodiag/gen5/data/obd/ObdReadWakeupTest.kt`.
- Ces changements sont **codés mais pas encore validés par exécution**. Le test ciblé, la suite des tests, lint et builds Android/Windows doivent encore être exécutés sur un SHA précis. Aucun essai physique BLE n'a été possible.

**Connexions et écrans : jalon de travail du 20 novembre**
- La PR #35 comprend maintenant des corrections additionnelles sur les reconnexions USB/Wi-Fi et Bluetooth classique, le suivi du pont PC J2534 et la fermeture immédiate des lectures/écritures après coupure.
- Le ViewModel propage les pertes physiques détectées aux lectures UDS/OBDb, scans DTC/OBD, identification ECU, audit kilométrique et télémétrie. Le flux/son enregistrement s'arrêtent à la coupure, sans reprise silencieuse.
- La console CAN conserve l'historique RX/TX après une coupure ou changement d'adaptateur.
- Les messages de déconnexion distinguent Bluetooth, USB, Wi-Fi et pont PC, avec chaînes FR/EN.
- Le suivi de l'issue #43 et le changelog de la PR ont été mis à jour. **Toutes ces évolutions restent non validées par compilation, tests, lint ou essais physiques sur le dernier head.**

**Protection des reconnexions rapides — 20 novembre 2026**
- L'identité de la session OBD est désormais contrôlée par un compteur atomique invalidé à chaque déconnexion. Les commandes en cours vérifient cette identité pendant et après les lectures USB, BLE, Wi-Fi et Bluetooth classique.
- Les défaillances tardives d'une session remplacée sont classées séparément afin qu'une ancienne lecture ne déconnecte pas le nouvel adaptateur. Tests unitaires ajoutés mais non exécutés : `ObdTransportConnectionStateTest` et `ObdTransportFailurePolicyTest`.
- L'ouverture avortée de la connexion TCP du pont PC J2534 ferme le socket créé.
- Les instructions README Android/Windows renvoient à présent vers les APK et installateurs des Releases, plutôt que vers les artefacts Debug et les archives de développement.
- Les travaux sont présents sur PR #35 mais **ne doivent pas être annoncés comme validés** : aucun build, test unitaire, lint ou essai véhicule n'a été exécuté sur le SHA courant depuis ces corrections.

**Authentification et isolation des sessions — 20 novembre 2026**
- Vérification GitHub : l'issue #53 concerne bien Google et e-mail/mot de passe ; `CamaroAuthGate.kt`, `MainActivity.kt`, les configurations Firebase Debug/Release et les clients OAuth sont sur PR #35. Un accès « Mon compte » de base, la vérification de l'e-mail après rafraîchissement et les confirmations d'envoi ont été ajoutés. Restent à traiter : isolation des véhicules/historiques locaux par compte avec migration explicite, liaison Google/e-mail, suppression de compte et données associées, recette et validations Android ; l'issue reste ouverte.
- Le développement de la console CAN a repris en évitant les fichiers d'authentification simultanément modifiés dans l'autre conversation.
- Les commandes OBD en attente, les opérations multicommandes, le monitor CAN, l'envoi CAN brut et leur nettoyage sont liés à l'identité de la session initiale ; une reconnexion ne doit pas déclencher de commande tardive sur l'adaptateur suivant.
- Tests de classification d'anciennes sessions ajoutés, **mais non exécutés**. Aucun build/lint de validation confirmé sur le head actuel. PR #35 toujours Draft, non fusionnée.

- Synchronisation : `main` a volontairement supprimé le workflow temporaire d'empreintes Firebase (`6c3cf1aabc12`) ; cette suppression est maintenant aussi intégrée à la branche PR #35. Le fichier `google-services.json` et les clients OAuth restent présents. La branche a été synchronisée depuis `main` par merge sans fusionner la PR.
- Aucun test Android, lint, APK ou essai matériel ne doit être déclaré réussi sur la base des seuls commits ; lancement manuel du workflow Android CI nécessaire sur le SHA final.

**Suite des travaux d'authentification (session de développement unique, 20 novembre 2026)**
- L'autre conversation a été supprimée par l'utilisateur : ne plus supposer de développeur/conversation parallèle.
- `main` est désormais intégré dans la branche PR #35, sans fusionner la PR vers `main`. Le workflow temporaire de récupération des empreintes Firebase a été supprimé de la branche, conformément au commit de sécurité de `main`.
- Ajouts sur PR #35 : correction de la reprise après vérification d'e-mail, retour exact de l'envoi de vérification, boîte « Mon compte » de base avec adresse/fournisseur et déconnexion ; arrêt des captures, connexions et données diagnostiques transitoires lors du changement de compte ; réinitialisation de la sélection Google Credential Manager à la déconnexion.
- **Point non résolu sur l'issue #53 :** les véhicules et historiques locaux ne sont PAS encore isolés par UID Firebase. Ne pas prétendre que les données sont protégées entre utilisateurs du même appareil. Avant toute livraison finalisée : concevoir une migration explicite des données existantes, lier les identités Google/e-mail, gérer la suppression du compte et les données locales, valider les sessions hors ligne/révoquées.
- Les validations Android CI, lint, tests d'authentification et de transport, packaging et essais physiques restent à exécuter sur un SHA final. La PR demeure Draft et ne doit pas être fusionnée avant validation.

**Jalon PR #35 — estimation et reprise du développement, 20/11/2026**
- Estimation indicative donnée à l'utilisateur : environ 80 % d'avancement global, non mesurée automatiquement. Le code des principales fonctions est avancé mais tests et validations restent largement à faire ; ne pas la transformer en pourcentage certifié.
- Après les changements de l'issue #53, le garage et l'historique Room sont séparés par UID ; import volontaire des données antérieures sans attribution silencieuse ; suppression de compte et liaison Google/e-mail codées, validations Android encore requises.
- Correctif de reprise des imports interrompus : pas de doublons de fiches lors d'un nouvel essai (`53647f18`). Les métadonnées de validation hors ligne sont supprimées lors de la suppression du compte (`a507e679`).
- Les sessions Firebase sont revérifiées régulièrement même lorsque l'application reste ouverte ; une validation réussie ne démonte pas l'écran diagnostic. Reprise après retour d'une validation valide et rejet des callbacks anciens : `c08b43d9`, `b37ffd28`, `3550b6c4`, `720c4a48`.
- Les enregistrements de sessions, scans OBD, DTC, identification ECU et audits kilométriques sont protégés contre les réponses tardives après changement de compte : `3f9a87a3`, `bb446d9f`, `432c5e19`, `4efc0ae7`, `fbbee40b`.
- Les issues #41, #43 et #53 demeurent ouvertes. Tests Gradle, lint, APK, pont Windows et essais matériels non validés sur le SHA actuel ; PR Draft non fusionnée. Continuer l'audit des opérations asynchrones puis finaliser les fonctions avant le checkpoint de validation demandé par l'utilisateur.

**Documentation utilisateur**
- Guides réécrits : analyse de traces CAN/UDS, utilisation ELM327, disponibilité des signaux Camaro et matrice de compatibilité Camaro/adaptateurs.
- README complété avec des liens vers les guides.
- Changelog général rendu orienté utilisateur.
- Les documents de backlog et d'évolutions restent des documents de planification et peuvent conserver leurs détails techniques.
- Le guide `pc-bridge/README.md` et le README principal doivent encore être revus pour vérifier qu'ils respectent entièrement la règle documentaire utilisateur.

**Suivi des issues**
- Issue #41 — matrice des capacités matérielles : toujours ouverte. Les capacités exactes de chaque interface, leurs limites et leurs preuves restent à établir.
- Issue #43 — robustesse des sessions/déconnexions : toujours ouverte. Un commentaire d'avancement a été ajouté le 20 novembre (commentaire ID `7034719506`) pour le progrès BLE. Les transports USB, Wi-Fi, pont PC et l'application globale à tous les écrans restent à traiter.
- Ne fermer aucune issue avant intégration à `main`, tests/builds réels, documentation et accord utilisateur.

**Validation**
- Sur le head `59f850e6960b2bdbc0b8cb240d52cf02fb809842`, aucun workflow PR n'a été retourné par la vérification GitHub effectuée le 20 novembre.
- Pas de preuve d'exécution du nouveau test unitaire, de la suite complète, de lint, d'un APK Debug/Release ou des packages Windows sur ce head.
- Aucun test sur une Camaro ou un adaptateur physique n'a été réalisé. Le véhicule et l'adaptateur ne doivent pas être déclarés validés sur cette base.

## Points techniques antérieurement signalés — vérifier s'ils sont encore présents

- Gradle JVM target mismatch Java 1.8 vs Kotlin 17 dans `:core:compileDebugKotlin`.
- `Divider` déprécié à remplacer par `HorizontalDivider` dans `CamaroDiagApp.kt`.
- Condition toujours vraie dans `CamaroDiagApp.kt`.
- `OBDLinkBleTransport.kt` : API `value: ByteArray` dépréciée.
- `UdsClient.kt` : avertissement visibilité copy de data class.
- `CalibrationWorkspaceScreen.kt` : symboles `CalibrationAxisDefinition`, `fileOffset`, `count`, `unit` non résolus, puis problème `String?` vs `Any`.
- Échecs de dépendances de tests unitaires.
Ces alertes peuvent être corrigées ou obsolètes : consulter le code et les derniers workflows avant de les présenter comme anomalies actuelles.

## Procédure de reprise à chaque nouveau chat

1. Confirmer le dépôt et lire ce fichier, le changelog et le backlog.
2. Interroger GitHub pour l'état réel de `main`, PR ouvertes, branches, commits, derniers workflows/checks et logs.
3. Pour la PR #35, inspecter le diff réel et rapprocher chaque critère de la section 1 du backlog du code et des tests présents.
4. Vérifier si les workflows exécutent réellement compilation, tests, lint et packaging ; distinguer explicitement skipped/success/failure.
5. Donner un état synthétique factuel : réalisé, en cours, bloqué, non commencé, preuves/liens, prochaine action.
6. Continuer uniquement dans le périmètre autorisé. Les autres idées restent dans le backlog jusqu'à leur PR dédiée.
7. Après chaque changement effectif, mettre à jour le prompt et le backlog sur `main` si les décisions/exigences changent, synchroniser l'Issue GitHub concernée, et mettre à jour `docs/CHANGELOG_PR<number>.md` sur la branche de la PR avec date, description, liens et SHA.
8. Ne jamais fusionner sans autorisation explicite.

## Première action recommandée à la reprise

Auditer à nouveau la PR #35 et la CI sur son **head SHA actuel**, comparer le code au périmètre Sniffer / Console CAN (capture + analyse + console + émission si supportée), identifier précisément les critères non implémentés et les tests/builds réellement exécutés. Ne pas ouvrir de PR de backlog tant que l'utilisateur ne le demande pas.


## Décisions de périmètre et reprise — 20 novembre 2026

- **PR #35 est l'unique PR de développement active** sur `feat/live-can-monitor` ; conserver seulement `main` et cette branche. Ne pas créer de PR ni de branche concurrente. Terminer toutes les fonctionnalités du périmètre, puis exécuter Android et Windows (build, lint, tests), corriger erreurs et avertissements, fusionner dans `main` seulement une fois la PR complètement achevée et validée, et supprimer la branche.
- **Issue #53** : terminer les comptes Google/e-mail, les validations de session et l'isolation des garages/historiques dans PR #35. La recette Firebase réelle exige une configuration signée et des comptes de test ; ne pas confondre tests automatiques et validation sur un compte réel.
- **Issue #43** : priorité aux déconnexions de la console CAN, capture et émission ; conserver les protections communes existantes. Les autres parcours sont à inclure s'ils sont presque achevés ou nécessaires à la sécurité.
- **Issue #41** : ne jamais cacher les modules ECU en raison d'une incompatibilité de l'interface ; les laisser visibles, grisés et non utilisables lorsque non disponibles ou non vérifiés, avec motif visible. Matrice matérielle exhaustive reportable ; maintenir les protections essentielles.
- **Issue #52 FlexFuel** : hors PR #35 ; ouvrir une nouvelle PR dédiée immédiatement après la fusion de #35, avec priorité à la Camaro Gen 5 2012 LFX (production août 2011), sans prétendre valider une calibration ou un flash non testés sur véhicule.
- **CI** : éviter les déclenchements à chaque commit ; points de contrôle manuels avec `.github/workflows/pr35-validation-kickoff.yml`. Le checkpoint 5 sur `aed247d` a été déclenché ; consulter les résultats avant toute conclusion. Les commits ultérieurs (annulation J2534, grisage modules) nécessiteront un nouveau contrôle final.
- **Développement récent** : les tentatives de connexion sont invalidées lors d'un changement de compte ou de simulation, et lors d'une perte de transport ; les anciennes tentatives attendent leur nettoyage avant la suivante. Les annulations Bluetooth/Wi-Fi/USB et J2534 sont propagées après fermeture du transport. Les modules ECU restent visibles et grisés si indisponibles. Ces modifications doivent être validées par CI.
- Les consignes anciennes de ce fichier demandant une autorisation explicite pour chaque fusion ou une PR par issue sont remplacées par ces décisions plus récentes : **une seule PR active**, fusion seulement lorsqu'elle est complètement terminée et vérifiée.

### Reprise agent — 20/11/2026, correctifs après checkpoint 6

- La PR #35 reste l'unique PR ouverte et la branche de développement est `feat/live-can-monitor` ; branches constatées : `main` et `feat/live-can-monitor`.
- Le checkpoint 6 sur `1eeeb188` est **réussi** pour Android (tests, lint, builds, émulateurs API 29/33/36) et Windows (pont J2534), mais **ne couvre pas** les commits plus récents.
- Correctifs récents : simulation et pont J2534 coordonnés avec les tentatives de connexion précédentes (`2870c25`, `aab8b7d`, `94aca55`) ; conservation du job de connexion annulé pour attendre son nettoyage lors du changement de compte ou d'une perte de transport (`052bf41`) ; déconnexion explicite du pont J2534 qui annule et attend une connexion en cours (`5bb82e1`) ; annulation des requêtes TX CAN avant changement d'adaptateur, simulation ou pont PC (`d11a587`).
- **Action prioritaire :** déclencher le prochain checkpoint Android/Windows sur le SHA final après ces modifications, inspecter chaque job et corriger toute erreur/avertissement. Ne pas confondre réussite du checkpoint 6 et validation des nouveaux commits.
- Les scénarios Firebase Google/e-mail avec comptes réels et APK signés, ainsi que les essais CAN sur matériel et véhicule, ne sont pas validés. Ne pas fusionner tant que les validations requises ne sont pas satisfaites.

### Suite de l'audit CAN — 20/11/2026

- Correctif supplémentaire : lors d'un changement de véhicule/compte ou d'une déconnexion, les jobs de capture CAN et de requête TX détachés de l'interface restent suivis jusqu'à la fin de leur nettoyage transport (`12e3fe5`, `a73bc75`, `baaf633`). La console refuse une nouvelle capture ou émission tant que l'ancien nettoyage est actif ; les changements de transport attendent ces jobs et la destruction du ViewModel les prend en compte.
- Le checkpoint 7 `7fabba2` a été lancé et les workflows Android et Windows sont en cours. Les correctifs CAN ci-dessus sont **postérieurs** au checkpoint 7 : ils nécessitent un nouveau checkpoint sur un SHA final avant fusion.
- Les tests réels sur véhicule et l'authentification Firebase de bout en bout restent non validés.

### Diagnostic CI checkpoint 7 — 20/11/2026

- Windows J2534 : succès sur `7fabba2`.
- Android : compilation/tests unitaires et émulateurs API 29 et 33 réussis. Le job API 36 a échoué sur `:app:checkDebugAarMetadata` : plusieurs dépendances Maven introuvables (Material, kotlin-bom, kotlinx-coroutines, etc.) pendant la résolution des artefacts ; les logs ne montrent pas d'échec d'assertion ni de compilation Kotlin. Incident de résolution de dépendances/accès aux dépôts, non attribuable à un changement de code sans preuve.
- Nouvelle tentative ciblée du seul job API 36 demandée via GitHub Actions. Ne pas déclarer Android vert avant son résultat.
- Les commits `12e3fe5`, `a73bc75`, `baaf633` et les mises à jour documentaires sont postérieurs au SHA du checkpoint 7 ; validation finale Android/Windows encore requise sur le head stabilisé.


### Audit PR #35 — 20/11/2026, après checkpoint 8

- HEAD vérifié : `f02cc1f6d583a7184a9326c3c7004d6a96aa4d86` ; PR #35 ouverte et fusionnable au moment du contrôle.
- Checkpoint 8 : Android CI #44747268947 **success**, y compris tests instrumentés API 29/33/36 et build debug ; Windows J2534 CI #44747273129 **success**. Le précédent échec API 36 n'est plus un blocage CI.
- Lecture ciblée du code : `CamaroAuthGate.kt` contient un contrôle de session Firebase périodique et une politique hors ligne bornée à 7 jours ; `OfflineSessionPolicyTest.kt` couvre première validation, expiration et horodatage futur ; `CanConsoleRecordParser.kt` distingue RX et SYSTEM. Ces lectures ne prouvent pas à elles seules tous les critères d'acceptation.
- **À auditer avant fusion** : inscription/connexion Google et e-mail avec configuration Firebase et comptes réels, liaison/suppression des identités et données, migration/isolation des véhicules, déconnexion pendant capture et émission CAN, absence de secrets dans exports/logs, avertissements éventuels des jobs CI. Ne jamais déclarer ces essais réalisés sans preuves.
- Issues #41/#43/#53 restent ouvertes ; leurs critères de clôture nécessitent une vérification explicite. Les exigences non incluses dans le périmètre réduit de #35 doivent rester suivies, sans création d'une autre PR avant la fermeture de #35.
- La réussite CI sur `f02cc1f6` ne couvre **pas** les commits postérieurs, notamment cette mise à jour documentaire. Refaire le checkpoint final si des modifications de code suivent.


### Audit ciblé 20/11/2026 — correction migration compte

- Correctif réel `ed6044b` dans `MainViewModel.importLegacyVehicles` : vérifie UID et génération de session après lecture Room et juste avant la revendication des historiques non attribués. Évite qu'un import lancé pour A continue à revendiquer les données après une bascule vers B.
- Vérifié dans le code : `CamaroAuthGate` propose liaison Google/e-mail, suppression Firebase et callback de nettoyage local ; `MainViewModel` sépare les garages par UID et `DiagnosticDatabase` filtre les historiques par propriétaire ; tests instrumentés d'isolation et de migration présents. Les parcours Firebase réels restent non vérifiés.
- CAN : les jobs de capture et d'émission sont annulés/détachés lors d'un changement de contexte, avec protection contre les résultats tardifs ; pas de test sur adaptateur/voiture.
- CI checkpoint 8 sur `f02cc1f6` : Android API 29/33/36 et Windows réussis ; log Android Gradle : BUILD SUCCESSFUL ; log Windows : 0 Warning(s). Le correctif `ed6044b` est **postérieur** à ce checkpoint et exige validation finale.
- **À suivre** : vérifier la sûreté de l'import en cas d'interruption entre écriture du garage et revendication Room, tests réels Firebase (liaison, suppression, révocation), audits des avertissements restants, puis checkpoint final sur le nouveau HEAD. PR #35 non fusionnée.

### Audit complémentaire CAN — 20/11/2026
- Correctif `92a6f92` : émission CAN brute bloquée lorsque l'adaptateur ne confirme pas la gestion des en-têtes CAN, même si le canal OBD est ouvert. Les issues #41, #43 et #53 ont reçu des commentaires d'audit. Checkpoint 8 antérieur : Android et Windows réussis, mais ne couvre pas ce correctif ; refaire une validation finale du nouveau HEAD. Essais Firebase réels et matériels non effectués.


### État confirmé — 20/11/2026 16:50 CET (nouvelle PR #54)
- PR #35 fusionnée dans main (merge `b926dd596b7229d16841a0c1c3f11d6338a75acf`), fermée ; ancienne branche supprimée par workflow automatique. Ne pas reprendre l'ancienne PR ni créer une branche supplémentaire.
- Validation préalable PR #35 Release #44763196432 : **success** ; Android CI #44761425078 et Windows #44761431268 : **success**. Workflow officiel Release sur main #44764529134 : **success**. Aucun essai physique véhicule ni test de connexion Firebase réelle n'est revendiqué.
- Issues #41, #43 et #53 restent ouvertes pour critères nécessitant une recette réelle ou travaux complémentaires ; ne pas les fermer par assimilation au merge.
- Une seule branche active en plus de main : `feat/flexfuel-lfx-2012` ; PR **#54** (draft) pour l'issue #52. Premier développement : `EthanolContentPidDecoder` avec décodage strict SAE Mode 01 PID 52 (A×100/255), détection de disponibilité via bitmap PID 40 et tests unitaires. Ne pas confondre lecture OBD avec capteur physique présent, activation FlexFuel, ou droit d'écriture ECU.
- **À faire en priorité :** exécuter les tests du module communication et une validation Android/Windows au prochain checkpoint ; identifier les variantes ECU/firmware du LFX millésime 2012 avant tout accès de calibration ; intégrer la lecture éthanol à l'interface uniquement si PID 52 annoncé et réponse valide ; garder les opérations de calibration désactivées sans preuves d'identification et de sécurité.
- Documentation publique destinée aux utilisateurs, en français/anglais seulement. Ne pas déclencher une CI à chaque commit ; conserver une seule PR active. Ne pas déclarer de validation véhicule.


### Exécution agent — 20/11/2026, après fusion PR #35
- L'instruction historique de travailler sur `feat/live-can-monitor` est dépassée : PR #35 **fusionnée et fermée** ; branche supprimée. Ne jamais recréer cette branche. Issues #41, #43 et #53 restent ouvertes pour leurs recettes et suites.
- Seule PR active : **#54**, branche `feat/flexfuel-lfx-2012`, priorité issue #52 ; issue #55 réservée à une future PR secondaire Gen 5/Gen 6 tous moteurs.
- Checkpoint PR #54 #44767493774 : jobs Android Release et Windows Release **success** sur `8388c742` ; ne couvre pas les commits suivants.
- Optimisation APK Release R8 + ressources : commit `5088000`. Précontrôle LFX 2012 : `4ef6177`, renforcé par `1979487` pour afficher explicitement `WRITE_NOT_VALIDATED` et interdire l'écriture ECU même avec les autres prérequis ; tests ajoutés `19b1575` mais **non exécutés** conformément aux checkpoints espacés.
- Ne pas lancer CI à chaque commit ; grouper les validations après un lot important (environ 50 commits), puis build final Android/Windows sans erreurs ni avertissements avant fusion. Aucune validation sur véhicule.

### Périmètre P0 ajouté à la PR #54 — 20/11/2026
- L'utilisateur demande d'intégrer toutes les issues P0 actuellement ouvertes à la PR active. Recherche GitHub : #56 (zones système Android), #57 (sélection de capteurs par cases à cocher), #58 (fenêtre d'aide DTC). Les trois sont explicitement incluses dans la PR #54 et leurs issues commentées. Ne pas créer de PR supplémentaire.
- Critères d'acceptation complets dans les issues ; les trois restent **à implémenter et valider** avant fusion. Le FlexFuel LFX 2012 #52 demeure prioritaire ; extension autres moteurs #55 ultérieure.

### Agent PR54 — correctif P0 #57
- Commit `41cd8e9` : la sélection des PID affichés par l'écran de scan OBD est désormais conservée dans les préférences locales **par VIN valide**, y compris sélection vide, et le filtre cherche aussi dans les libellés localisés des capteurs. En l'absence de VIN, le choix reste temporaire pour éviter d'associer les données à un mauvais véhicule.
- Limite explicite : la fenêtre n'affiche pour l'instant que les PID retournés par le dernier scan, pas encore le catalogue complet ni les signaux indisponibles. Le P0 #57 reste ouvert ; intégrer la liste des capacités réelles, les tests et la validation Android. Ce commit n'a pas encore été compilé par CI.
- P0 #56 : premier correctif de zone système `6ba65cc` non validé sur appareils. P0 #58 : fenêtre DTC `959b41f` avec repli sans fiche, reste à relier au catalogue d'explications véhicule et à tester. Ne pas fermer ces issues prématurément.

### Exécution agent — 20/11/2026, P0 #57/#58
- Commits `210f84d` : la liste principale des DTC ouvre désormais une modale d'aide liée au véhicule ; explications, causes et contrôles du catalogue existant sont affichés uniquement si l'identité véhicule/ECU est suffisamment établie ; sinon repli explicite. Les détails historiques restent dans la carte, nettoyage UI à poursuivre.
- Commits `9c67ae5`, `a1123a2`, `738355e` : sélecteur de capteurs enrichi avec PID connus et PID annoncés par l'ECU, libellés FR/EN d'indisponibilité et cases désactivées pour les PID sans mesure dans le dernier scan. La disponibilité signifie **mesuré pendant le scan**, pas une capacité future garantie.
- `41cd8e9` conserve la sélection par VIN localement. Restent à faire : catalogue de télémétrie complet au-delà de l'écran de scan, tests UI/insets Android 10–16, contrôles de compilation, DTC par calibration validée. Aucun essai sur véhicule, aucune écriture ECU. CI volontairement non relancée avant checkpoint.


### Session de développement PR #54 — 20/11/2026 (correctifs interface/CAN)
- **Branche unique** : `feat/flexfuel-lfx-2012` ; **PR unique** : #54. PR #35 déjà fusionnée ; ne pas recréer son ancienne branche.
- #59 — Capture CAN : boutons Play/Stop visibles ; durée par défaut désormais sans plafond temporel (arrêt explicite, fin de flux matériel ou déconnexion). Le moniteur ELM/STN utilise un tampon mémoire borné et un fichier brut temporaire sur stockage privé pour enregistrer l'intégralité des flux reçus ; export complet depuis ce fichier après Stop, mémoire UI limitée au récent ; effacement interdit pendant capture, isolation des fichiers entre véhicules. Tests unitaires `CanCaptureTextBufferTest` **écrits mais non exécutés**. Vérifier la fermeture du mode monitor (ESC), transport USB/BLE/Bluetooth/Wi-Fi, stockage et volume prolongé avant clôture #59.
- #57 — La fenêtre de choix à cases à cocher avec recherche et OK/Annuler est maintenant ajoutée à **Données en temps réel** (en complément de l'écran scan OBD) ; préférences par identifiant de garage ou VIN valide, jamais partagées durablement entre profils inconnus. `Tout afficher` supprime la préférence. Nom MAP/éthanol visible dans les options du scan OBD, liste scrollable limitée en hauteur. Le catalogue multi-ECU complet et la recette sur Android restent à faire.
- #61 — `À propos` réutilise désormais `AboutUpdateCard()` depuis une entrée du menu Plus/Actions et n'est plus dans Réglages. Installation automatique jusqu'à la confirmation Android inchangée ; parcours complet à vérifier.
- #58 — La modal DTC existante reste à vérifier sur profils réellement identifiés ; aucun renseignement non vérifié ne doit être présenté comme un diagnostic certain.
- #52 — Priorité FlexFuel Camaro Gen 5 LFX 2012 inchangée ; décodage PID éthanol et garde-fous sans autoriser écriture ECU. Identification versions ECU/firmware, sauvegarde et récupération réelles nécessaires.
- **État de validation :** commits de cette session poussés sur #54, mais les builds Android/Windows, lint et tests ne couvrent pas encore le HEAD après ces changements. Ne déclarer aucune issue « terminée » ni aucun essai véhicule. Prochain checkpoint pertinent aux environs de 50 commits, puis corriger toute erreur/avertissement. Maintenir FR/EN pour les nouveautés et docs utilisateurs non techniques hors backlog/prompt.

### Complément P0 #56 — contrôle de l'action du compte
- L'action `Mon compte` est superposée en haut de `CamaroAuthGate`, hors TopAppBar. Commit `2b539a1` : `statusBarsPadding()` appliqué à cette action pour Android 15/16 (edge-to-edge forcé), tout en conservant la disposition précédente sur Android 10–14. Correctif plausible mais **pas de recette appareil** : vérifier status bar, découpe écran, barre basse et interaction compte sur tous les OS supportés.
- Après ce lot, checkpoint Android/Windows et tests unitaires à exécuter sur le **HEAD courant**, sans déclarer de validation véhicule. Corriger les erreurs de compilation/lint révélées avant tout merge ; vérifier notamment le nouveau sélecteur LiveDataScreen et la capture CAN file-backed.

### PR54 — poursuite des développements, checkpoint et audit (20/11/2026)
- Ne travailler que sur `feat/flexfuel-lfx-2012` / PR #54, sans autre branche/PR ; #35 déjà fusionnée. Ne pas utiliser les anciens prompts qui demandent de reprendre #35.
- DTC #58 : `6a0dd57` — aide cliquable au sein d'une modale avec conditions d'applicabilité véhicule/ECU, explications, causes, contrôles, moniteurs GM si documentés ; détails longs retirés de la liste.
- Live #57 : `7486611`, `7de46d0`, `45db289` puis `46aaed3` — catalogue FR/EN des signaux SAE effectivement traités, autres capteurs natifs et OBDb filtrés par véhicule disponibles en liste. Seules les mesures déjà reçues sont cochables ; rechercher et mémoriser les sélections par profil VIN ; tests unitaires de catalogue ajoutés. Ne pas supposer que les signaux ECU/OBDb sont accessibles sans interface/ECU vérifiés.
- FlexFuel #52 : `963bf25`, `469abfc`, `69fbd0f` et ressources FR/EN — références de service possibles E39 12651994 (supersède 12643248), mention de main boot 12632286, source https://www.gmpartsgiant.com/parts-list/2012-chevrolet-camaro-coupe/fuel_system_exhaust_emission_system/e_c_m_module_wiring_harness.html . **Ce ne sont pas des identifiants de firmware** et la compatibilité de l'ECU du véhicule ne peut pas être supposée. Fiche utilisateur en lecture seule dans l'atelier LFX 2012, affichant les conditions de précontrôle : VIN ECU, variante réelle, firmware, sauvegarde, récupération, validation écriture. Toutes les écritures restent interdites.
- Pilotage #60 : `528aa83`, `a750fa7` et localisations + `5b4a9be` — huit commandes sont descriptives uniquement, aucune commande GM de pilotage exécutable existante. Leur état est maintenant « NON IMPLÉMENTÉ » (et non « non validé ») avec test de non-disponibilité. **Ne jamais dire que le pilotage fonctionne.**
- Checkpoint ~50 commits lancé par modification unique de `.github/workflows/pr35-validation-kickoff.yml` (nom interne PR54) : `06dc794` envoie `android-ci.yml` #44782818969 et `pc-bridge-ci.yml` #44782822972 sur le même SHA, sans CI par commit. Windows #44782822972 réussi (Debug + self-tests + installateurs), Android #44782818969 tests unitaires et contrôle ressources FR/EN réussis, lint/build encore en cours à l'instant de cette note. **Ne pas confondre ce checkpoint antérieur avec validation des commits ultérieurs.** Relancer validation finale après stabilisation, sans aucune erreur ni avertissement, avant fusion.
- Issues #52 #56 #57 #58 #59 #60 #61 restent ouvertes tant qu'elles ne sont pas entièrement terminées/validées. Dernières priorités : résolution CI Android, contrôles mise en page Android 10–16, couverture/télémétrie réelle par ECU, identification et sauvegarde sûres LFX 2012. Aucune validation physique véhicule possible actuellement.

### PR54 — complément après le troisième checkpoint (20/11/2026)
- La PR #54 est la seule PR active, branche `feat/flexfuel-lfx-2012`. Ne pas ouvrir d'autre branche ou PR. Aucune fusion sans build propre et sans les critères fonctionnels.
- DTC P0 #58 : amélioration modale en onglet Défauts `6a0dd57` ; scan OBD complet `864cc4d` + `19045d2` + `432f050` + `9d50063` + `0c7504b` + `f550234`. Les informations GM spécifiques ne sont montrées qu'avec VIN concordant et réponse unique ECM 7E8 ; génériques disponibles autrement. Tests de refus simulation, identité incertaine et VIN différent ajoutés. Aucune interprétation non validée n'est présentée comme sûre.
- Capteurs P0 #57 : `LiveSensorCatalog` catalogue FR/EN, signaux Camaro natifs et OBDb supplémentaires affichés désactivés si non mesurés, `LiveSignalRecency` borne la fraîcheur (120 s par défaut, pour les transports ELM lents) ; `SensorSelectionScope` protège le choix enregistré par **compte Firebase et véhicule** (pas de partage entre comptes / inconnus). Nouveaux tests `LiveSensorCatalogTest`, `LiveSignalRecencyTest`, `SensorSelectionScopeTest`. Ne pas confondre catalogue et signaux effectivement lus ; ne pas clore sans recette multi-ECU.
- Affichage P0 #56 : `CamaroAuthGate` n'affiche plus le compte dans un overlay au bord supérieur ; action compte déplacée dans la `TopAppBar` de `CamaroDiagApp`, FR/EN. Recette visuelle Android 10–16 encore nécessaire.
- FlexFuel P0 #52 : `Lfx2012ControllerReferences` (E39 pièces de service, pas firmware), précontrôles de sécurité de calibration en lecture seule ; bouton export de la fiche d'identification du calculateur (VIN ECU, CAL IDs, CVNs, adresses répondantes) en JSON explicitement **PAS une sauvegarde ECU** et **AUCUNE écriture**. Vérifier sur véhicule et matériel, déterminer famille/firmware spécifiques, sauvegarde et procédure de récupération avant d'entreprendre tout flash.
- Pilotage P1 #60 : commandes présentes dans la liste mais aucune exécution ; état « non implémenté » au lieu de « non validé ». Aucun pilotage physique ni validation véhicule.
- CI checkpoint 1 sur `06dc794` : Windows succès #44782822972, Android #44782818969 échoué pour 3 avertissements lint UseKtx. Corrigés.
- CI checkpoint 2 sur `e7cb84b` : Windows succès #44784445082, Android #44784441206 échoué faute d'import SharedPreferences KTX. Corrigé `bde503f`.
- CI checkpoint 3 sur `d93c67c` : Windows **succès** #44785134365 ; Android #44785130169 tests unitaires, lint, compilation et build Debug **succès**, tests d'instrumentation Android 29/33/36 encore en cours à l'instant du suivi. Attention : ce commit est antérieur aux nouveautés DTC/FlexFuel/compte et plusieurs correctifs ; ne couvre pas le HEAD courant.
- Prochaines actions : checkpoint final Android/Windows sur HEAD après les nouveaux commits ; remédier aux erreurs/tests qui se présentent ; ne pas relancer à chaque commit ; maintenir les issues de suivi à jour. Ne pas prétendre avoir testé sur véhicule. Changelog public restant concis et orienté utilisateur.

### PR54 — sélection par compte / stabilité calibration (20/11/2026)
- Correctifs post-checkpoint `4b667c6` : nouveau helper `SensorSelectionScope` `5fcb5e3` et tests `f85e1ec`, `MainViewModel.activeAccountUid` lecture seule `393be62`, passage de l'UID aux écrans `0bff687`, `1862f4c`. Les préférences de capteurs ne se partagent plus entre comptes utilisateurs, même pour le même VIN et un garage identique ; pas de préférence durable en l'absence d'UID/profil.
- Télémétrie : TTL d'un PID observé augmenté à 120 s pour tenir compte de la latence de 4 lots de requêtes sur ELM327 lent (`68788d7`) ; les valeurs anciennes finissent par disparaître des données « actuelles ». Tests avec délais explicitement paramétrés conservés.
- Écran ECU/Cartographie : `5403c08` remplace l'axe X et les cellules de cartographie larges composées intégralement par des listes horizontales paresseuses (LazyRow). Réduit le volume de composables visibles simultanément. Issues #38/#39 maintenues ouvertes : **ne pas affirmer avoir reproduit ni corrigé le crash ASUS ROG Phone 8 Android 16 sans Logcat et recette**.
- Validation à rejouer sur un nouveau SHA après ces modifications ; le checkpoint `4b667c6` est antérieur à `5403c08` et n'en tient pas compte. Windows de ce checkpoint #44786616504 réussi. Android #44786612153 en cours à la dernière interrogation.

### Exécution agent — 20/11/2026, sécurité identification ECU
- Commits `7e806e2` et `2528de1` : le précontrôle FlexFuel LFX 2012 distingue désormais une identité ECU/firmware réellement renseignée des valeurs de démonstration ou « unknown ». Ajout du statut `ECU_IDENTITY_UNVERIFIED` et de tests unitaires (identifiants fictifs, identifiants renseignés, verrouillage d'écriture permanent). Ne jamais considérer un identifiant non vide comme une preuve de compatibilité.
- Ces tests sont **écrits mais non exécutés** ; aucun test sur véhicule ni aucune écriture ECU. La PR reste en brouillon.
- Derniers workflows constatés : Windows #44786616504 **success** sur `4b667c6` ; Android #44786612153 **in_progress** sur le même commit, donc antérieur aux changements récents. Ne pas déclarer le HEAD validé. Un nouveau checkpoint Android/Windows sur HEAD est requis.

### Agent PR54 — contrôle des identifiants ECU (20/11/2026)
- Commits `93d5384`, `afeaf12` : le précontrôle FlexFuel rejette maintenant aussi les identifiants ECU/calibration remplis de zéros, de F, les valeurs `TBD`, `pending`, `not supported` et `test`, qui ne constituent pas une identification réelle. Tests unitaires de non-régression ajoutés.
- Tests ajoutés **non exécutés sur ce HEAD** ; aucune validation de firmware sur ECU réel. Écriture de calibration toujours interdite par `WRITE_NOT_VALIDATED`.
- Le dernier checkpoint connu (Android/Windows) est antérieur à ces commits. Prochain travail : compilation/test sur le HEAD, validation Android 10–16, capteurs multi-ECU, aide DTC et preuve d'identification/récupération LFX 2012. Ne pas fusionner.

### Session suivante PR54 — identité calculateur et sécurité FlexFuel (20/11/2026)
- La PR #54 comptait 116 commits à l'ouverture de cette session ; branche unique `feat/flexfuel-lfx-2012`, PR toujours ouverte. Ne pas rouvrir la PR #35.
- Renforcement `Lfx2012ControllerReferences.hasCompleteReadOnlyFingerprint` : un nom ECU, un CAL ID et un CVN doivent contenir chacun une valeur significative ; rejet des champs de démonstration, placeholders, N/A, identifiants remplis de zéros/F. Tests ajoutés pour les combinaisons mixtes. Une empreinte « complète » est **strictement informative et n'autorise aucune écriture**.
- Correction d'une incohérence bloquante possible dans `CalibrationWorkspaceScreen` : enum `ECU_IDENTITY_UNVERIFIED` désormais traité explicitement par le `when` exhaustif et message FR/EN dédié. Sans cette branche, le build peut échouer après les ajouts antérieurs.
- **À valider sur HEAD après ces commits** : Android unit tests, lint, Debug/Release, Windows. Aucun test véhicule ni compatibilité firmware/flash établie. Conserver toutes les opérations de calibration ECU désactivées ; ne pas fusionner sans tests/recette requise.

### PR54 — identification lecture seule multi-lignes et export (20/11/2026)
- Correctifs `53e4b20`, `081af18`, `dbd1f79`, `9f41760`, `3acbef2` : validation des champs ECU, CAL ID, CVN en lecture seule ; les valeurs fictives/vides ou composées uniquement de zéros/F ne suffisent plus à déclarer un relevé complet. Les listes multi-lignes (identification de plusieurs réponses) sont contrôlées entrée par entrée. Tests unitaires de non-régression ajoutés, **pas encore exécutés sur ce HEAD**.
- Correctifs `5a45e2c`, `f6eb55c`, `31a7ec2` : branche `ECU_IDENTITY_UNVERIFIED` ajoutée à l'affichage exhaustif du précontrôle de calibration et textes FR/EN pour éviter le défaut de compilation et conserver le blocage d'écriture.
- Correctifs `6ed19d6`, `e246cdf`, `ac344bf` : alerte visible dans l'atelier LFX 2012 lorsque le relevé ECU/CAL-ID/CVN est incomplet ou fictif.
- Commit `0267d6d` : export JSON d'identification **lecture seule** enrichi d'un booléen `observedFingerprintComplete` indiquant seulement la présence de données significatives, jamais la validation du firmware ni une sauvegarde mémoire.
- Les derniers checkpoints Android/Windows consultés `44786612153` et `44786616504` sont **success** (Android API 29/33/36 compris), mais portent sur un ancien SHA. Relancer le checkpoint de compilation/tests/lint + Windows au prochain lot, puis vérifier le HEAD final. Ne pas fusionner en cas d'erreur/avertissement ; aucune recette matérielle.

### PR54 — distinction E39/E39A (20/11/2026)
- `Lfx2012ControllerReferences.hasCandidateE39FamilyLabel` (`92f379e`) : reconnaissance prudente des familles E39 et E39A sous forme de jetons distincts ; écarte les sous-chaînes ambiguës et identifiants de démonstration. Tests correspondants `44b9665`.
- `CalibrationWorkspaceScreen` (`5badf16`) utilise cette vérification de famille au lieu de `contains("E39")`. Reconnaissance **indicative seulement** ; elle ne prouve ni l'ECU installé, ni sa calibration ni la possibilité d'écriture.
- **CI du HEAD en attente** : ne pas déclarer la compilation ni les tests validés sur ces nouveaux commits. PR ouverte, pas de merge, aucun test véhicule.

### PR54 — lot suivant après checkpoint vert (20/11/2026)
- Checkpoint `85f592d` : Android Debug `44801446648` succès (unit tests, lint, build, émulateurs API 29/33/36), Windows Debug `44801451064` succès, Release Android signé / Windows `44801455804` succès. Ces validations concernent précisément `85f592d`, **pas** les commits ultérieurs.
- À partir de `d1718aa`, Release utilise désormais `actions/upload-artifact@v7` (comme le reste du projet) pour corriger le warning de dépréciation Node 20 de la version v4 ; cet ajustement CI doit être validé au prochain checkpoint.
- FlexFuel #52 : `Lfx2012ControllerReferences.classifyResponderAttribution` différencie réponses CAN absentes/invalides, uniques et multiples ; tests couvrant CAN 11/29 bits et doublons. `CalibrationWorkspaceScreen` avertit l'utilisateur si les réponses Mode 09 viennent potentiellement de plusieurs ECU ; nouveaux messages FR/EN. L'export JSON indique `responderAttribution` et `calibrationIdCvnAttributionVerified=false`. Même une adresse unique **ne prouve pas** le firmware ECM.
- Préparation atelier hors véhicule #52 : `EcuBenchExpertPlan.verifyCandidateImage` compare le fichier RAW réel à la taille et au SHA-256 annoncés, tests de cas altérés/mal étiquetés. **Ne constitue ni une sauvegarde mémoire ECU ni un contrôle de checksum constructeur, et ne permet aucune écriture**. BOOT/JTAG sans pilote, sauvegarde réelle et récupération non validées.
- Correctif Kotlin : remplacer `ByteArray?.isNullOrEmpty()` non supporté par garde de null puis `ByteArray.isEmpty()` dans cette nouvelle API.
- **État : commits poussés sur l'unique branche/PR #54, non fusionnée.** Tests ajoutés mais nouveau HEAD non couvert par une CI complète ; respecter checkpoint espacé et revalider Android 10–16 / Windows sans erreur/avertissement avant fusion. Aucun test véhicule.


### Décision de suivi — Mode Expert ECU et restauration (20/11/2026)
- **Issues créées : #62 (P0 mode Expert sur banc), #63 (P0 sauvegardes multirégions vérifiables), #64 (P1 pilotes d'écriture/lecture/verification OBD-BENCH-BOOT-Nexus), #65 (P1 récupération après échec) et #66 (P1 contrôles après programmation LFX 2012 manuel).** Les cinq sont liées à #52 ; l'atelier général #46 reste le lot parent.
- **À intégrer immédiatement à PR #54** : terminer l'expérience utilisateur FR/EN et les précontrôles du mode Expert #62 ; concevoir l'inventaire des zones et les sauvegardes typées, manifestes, double lecture et vérification hors ligne #63 ; pour #64/#65 poser seulement les interfaces pilotes, simulations et états de reprise utiles au LFX ; pour #66 préparer la checklist/rapport en lecture seule.
- **Lots matériels ultérieurs** : pilotes d'écriture réels, récupération BOOT/Nexus sur ECU démonté, vérification post-écriture et procédures d'apprentissage actives — aucun matériel ni firmware de la Camaro cible n'a encore été validé. Ne pas ouvrir de nouvelle branche/PR tant que #54 est active ; ne pas prétendre que la programmation matérielle fonctionne.
- **Objectif permanent** : lecture, sauvegarde, calibration, programmation et récupération ECU ; la gestion du risque résiduel ne signifie pas ignorer les contrôles de compatibilité ou autoriser une écriture non implémentée.
- La recherche et les notes de banc détaillées demeurent **dans un dossier privé extérieur au dépôt**. Les guides publics décrivent les fonctions sans publier les notes de reverse engineering ; ce backlog et docs/PROMPT_REPRISE.md restent les exceptions techniques de suivi.
- **Recette** : tests unitaires, Android/Windows sans erreur ni avertissement, puis tests distincts sur banc et véhicule. Ne fermer les issues qu'après preuves des critères correspondants.

### PR54 — provenance des relevés simulés (20/11/2026)
- `CalibrationWorkspaceState.ecuIdentityFromSimulation` mémorise désormais l'origine d'une lecture ECU (simulation/démo ou commande matérielle). Champ vidé avec les données au changement de véhicule, session, interface et lors des erreurs. Les lectures réussies l'initialisent lors de `readCalibrationIdentification` et `refreshTopologyAndDefaults`.
- Écran ECU LFX 2012 : avertissement FR/EN spécifique si les données viennent d'une simulation. Export d'identification JSON : champ `observationFromSimulation` ; `observedFingerprintComplete` devient **false** si la lecture était simulée, même si les chaînes ECU/CAL-ID/CVN sont plausibles.
- Validation pure Kotlin : `Lfx2012ControllerReferences.ObservedIdentity(simulated=true)` doit être refusée par `hasCompleteReadOnlyFingerprint`. Test de non-régression ajouté.
- Cette vérification n'accorde aucune permission d'écriture, récupération ni de validation réelle de firmware. **Aucun test véhicule**. Les modifications de ce lot ne sont pas comprises dans le checkpoint Android/Windows réussi sur `85f592d` ; réexécuter la CI lors d'un prochain jalon raisonnable (~50 commits) et avant tout merge.


### 20/11/2026 — #63 couverture de sauvegarde ECU (nouveau lot)
- Ajout de `EcuBackupCoverage` : inventaire de régions CALIBRATION / PROGRAM_FLASH / DATA_FLASH / EXTERNAL_EEPROM / OTHER ; statut `COMPLETE`, `PARTIAL` ou `INCONSISTENT` selon les longueurs, le chevauchement, la cohérence des adresses, la forme SHA-256 et la confirmation d'une lecture indépendante.
- Tests `EcuBackupCoverageTest` : couverture complète, zones manquantes, lecture non vérifiée, chevauchements, offsets invalides, séparation des régions, hash mal formé. Code publié sur l'unique branche PR #54 ; **aucune lecture matérielle réelle ni build du HEAD encore attesté**.
- Attention : ce modèle ne prouve pas la provenance des données ni la compatibilité d'une image avec l'ECU. Il ne débloque aucune écriture. #62/#63 dans PR #54, #64/#65 fondations uniquement, #66 checklist ; #55 après PR #54.


### PR #54 — Écriture ECU ciblée, issue #67 (20/11/2026)
- **P0 #67 créée** : programmation partielle des calculateurs : codage d'options par service de diagnostic, modification NVM/EEPROM/DFLASH, patch de calibration PFLASH ; ne pas confondre écriture d'un paramètre et effacement physique d'un secteur.
- **À développer maintenant dans PR #54** : modèle des régions et unités d'effacement/programmation, diff des octets, regroupement des secteurs impactés, préservation des octets voisins, plan d'écriture hors ligne, tests de limites/secteurs protégés et aperçu FR/EN. Relier #52 (LFX 2012), #62 (mode Expert), #63 (sauvegardes).
- **Validation ultérieure dépendante de #64/#65** : pilotes matériels, lecture réelle des secteurs, effacement/écriture et relecture vérifiée, restauration ; aucune compatibilité ECU ni écriture effective ne peut être déduite d'un plan hors ligne. Ne pas autoriser l'écriture sur matériel non validé.
- Les informations détaillées de recherche et de brochage restent hors du dépôt public. Une seule branche de développement et PR #54, #55 après fusion validée.


### 20/11/2026 — Recherche multi-modules et sauvegarde avant TOUTE modification (#68)
- Issue **#68** créée pour recenser et développer les capacités de programmation de tous les modules présents sur Camaro Gen 5 et Gen 6, avec priorité ECM LFX 2012, BCM/TPMS, EBCM/ABS, SDM/SRS, HVAC, FPCM, IPC, puis TCM uniquement pour boîtes automatiques et autres équipements par VIN/RPO. **Ne pas supposer que ces fonctions sont déjà implémentées.**
- Règle produit obligatoire : **toujours proposer avant une modification la meilleure sauvegarde réellement disponible** : S0 état diagnostic/configuration, S1 paramètres, S2 secteurs, S3 calibration, S4 flash complète, S5 clonage vérifié. Présenter les limites, les régions non lisibles et la différence entre sauvegarde et restauration. Ne pas annoncer un dump complet ou une récupération si non prouvés.
- #67 : écriture ciblée des options, enregistrements NVM et secteurs flash (préservation des octets voisins, diff, vérification), distincte d'une reprogrammation intégrale ; #63 : manifestes et preuves de couverture ; #64/#65 : pilotes matériels et récupération.
- **PR #54 maintenant** : avancer les fondations génériques et l'ECM LFX 2012, sans détourner les priorités P0 #56/#57/#58. Autres modules et Gen 6 en lots ultérieurs après validation/fusion de #54, sans branche/PR supplémentaire pour l'instant.
- Recherche détaillée avec matrice des modules, sources et solutions dans le document Word privé `CamaroDiag_Dossier_Technique_Privé_ECU_2026-11-20_MAJ_modules_Gen5_Gen6.docx`, enregistré dans la bibliothèque ChatGPT, **pas dans le dépôt public**. Aucun essai matériel ni écriture ECU prouvé.


### ARBITRAGE PRIORITAIRE DU PROPRIÉTAIRE — livraison rapide PR #54 (20/11/2026)
- **La programmation multi-modules et les développements étendus de sauvegarde/écriture partielle sont REPORTÉS À LA PROCHAINE PR**, à créer seulement après fusion/fermeture de #54 : issues **#68** (catalogue multi-modules et sauvegarde universelle) et **#67** (codage d'options/écriture ciblée), ainsi que l'activation matérielle des pilotes #64 et récupération physique #65. Cette décision **remplace toute mention antérieure** demandant de développer ces fonctionnalités dans #54.
- **Ne pas prolonger #54** avec de nouvelles interfaces ou fonctionnalités de programmation multi-modules. Conserver les fondations déjà commitées (#62/#63/#64/#65/#66) si elles compilent et ne bloquent pas la livraison, mais ne pas les utiliser comme critère de complétude des fonctionnalités matérielles.
- **#54 : priorité immédiate** aux correctifs P0 #56 (insets Android 10–16), #57 (sélection des capteurs), #58 (aide DTC), à l'état LFX 2012 #52 réellement pris en charge et aux corrections de tests/builds Android et Windows. Ne jamais annoncer une écriture ECU, une sauvegarde physique ou un essai sur véhicule sans preuve.
- **Prochaine PR, après #54** : #67/#68, sauvegarde partielle/complète systématiquement proposée avant chaque modification, avec preuves de couverture et d'intégrité, puis programmation par modules et validation matérielle. #55 reste également postérieure à #54, avec priorité à arbitrer.
- Conserver une seule branche/PR active, aucun merge avant validation finale sans erreur ni avertissement. Ne pas créer la PR suivante maintenant.

### Finalisation du lot logiciel PR #54 — 20/11/2026
- Checkpoint de référence : `85f592d` Android Debug/unit/lint/émulateurs API 29, 33, 36 = SUCCESS, Android Release signé = SUCCESS, Windows Debug/Release = SUCCESS. Les commits de cette section sont POSTÉRIEURS et pas encore validés.
- #62 Mode Expert : ajouts méthodes distinctes OBD/BENCH aux méthodes BOOT/BAM, Nexus/JTAG et CHIP-OFF ; parcours FR/EN et tests `EcuBenchExpertPlanTest` mis à jour. Toujours aucun pilote matériel enregistré.
- #63 Sauvegardes : `EcuBackupCoverage` ne déclare plus COMPLETE si une région requise manque ; `EcuBackupManifest` construit un inventaire SHA-256/longueurs à partir d'octets fournis, compare un second buffer et reste explicite sur absence de provenance physique. Tests `EcuBackupCoverageTest` et `EcuBackupManifestTest` ajoutés. Export/réimport matériel/paquet complet non disponible.
- #64 Contrats : `EcuProgrammingDriverContract` isole ELM/J2534/BENCH/BOOT/NEXUS/CHIP-OFF, identification, lecture, erase, write, readback et reset. Registre de pilotes vide, écriture matérielle non autorisée ; test de non-activation ajouté.
- #65 Reprise : `EcuRecoveryAssessment` classe les coupures sans réessai automatique, selon présence ECU, tension, identité, sauvegarde et pilote de reprise. Tests de scénarios ajoutés.
- #66 Audit avant/après LFX 2012 : `Lfx2012ProgrammingAudit` distingue observé, indisponible, non réalisé, à revoir ; tests sur VIN et horodatage / communication ; résumé de l'état avant et « après non réalisé » dans l'écran ECU. Sans donnée de flash physique.
- #67 Planification ciblée : `EcuPartialWritePlanner` calcule hors ligne les octets et secteurs modifiés, refuse géométrie invalide et zones protégées ; tests des frontières de secteur et préservation des octets voisins. L'écran Expert FR/EN accepte les dimensions mémoire saisies par l'utilisateur et affiche un aperçu de l'impact pour deux BIN. Géométrie et compatibilité matérielles **non vérifiées**, aucune écriture.
- **Étape immédiate** : lancer une CI de checkpoint sur HEAD via le workflow de lancement dédié, lire les journaux Android/Windows/Release, corriger toute erreur et avertissement de code. Pas de fusion si tests non terminés. La validation matériel/firmware/ECU reste impossible sans équipement.
- Maintenir les issues matérielles #52/#63/#64/#65/#67 ouvertes ; éviter d'étendre la PR à de nouveaux pilotes sans matériel vérifié. Le périmètre livrable immédiat est le logiciel préparatoire et le diagnostic lecture seule.


## FEUILLE DE ROUTE DES FUTURES PR — classement validé le 20/11/2026
**Référence de planification : 29 issues ouvertes examinées.** Les lots ci-dessous sont des **lots prévisionnels**, pas des numéros de PR déjà créées. Une seule PR de développement active : #54 sur feat/flexfuel-lfx-2012 ; ne pas créer les autres branches/PR avant sa fusion, fermeture et suppression de branche.

| Ordre | Lot futur | Priorité | Issues | Dépendances et objectif |
|---|---|---|---|---|
| 1 | **A — Sauvegarde et programmation ECU ciblée** | P0 | #63 #67 #64 #65 | Conserver les fondations déjà commitées ; construire sauvegarde vérifiée partielle/complète, diff secteurs, pilotes, readback et récupération ; banc réel requis pour annoncer l'écriture |
| 2 | **B — Programmation multi-modules** | P1 | #68 #66 #46 | Après A ; BCM/TPMS, ABS, SDM, HVAC, FPCM, IPC et atelier de calibration, inventaire selon VIN/RPO |
| 3 | **C — Interfaces et robustesse** | P1 | #41 #43 #50 | Matrice VCX/OBDLink/ELM/J2534, déconnexions et CI ; peut être avancé avant A si blocage technique |
| 4 | **D — Garage, comptes et télémétrie** | P1 | #42 #53 #44 | Isolation véhicule/compte et temps réel multi-ECU |
| 5 | **E — FlexFuel autres moteurs** | P2 | #55 #37 | Gen 5/Gen 6 V6/V8, matrice ECU/firmware distincte, après base LFX |
| 6 | **F — Service et actionneurs** | P2 | #45 #60 | Fonctions de service documentées et commandes sécurisées réellement supportées |
| 7 | **G — Ergonomie, recette, extensions** | P2/P3 | #40 #48 #49 #47 | Tablettes, cahier Excel, langues futures, plateformes étendues ; FR/EN uniquement tant que décidé |

**PR #54 à finir sans extension** : P0 #56/#57/#58, FlexFuel LFX 2012 #52 en lecture seule/contrôles vérifiés, correctifs déjà engagés #38/#59/#61, tests et build. #62/#63/#64/#65/#66/#67 ont déjà du code préparatoire dans #54 : conserver s'il est stable mais **ne pas en déduire que les fonctionnalités physiques sont terminées**. #68 et fonctionnalités complètes de #67 passent après #54. #53 et les autres lots ne doivent pas bloquer la livraison de #54.

**Règle universelle** : toujours proposer la meilleure sauvegarde effectivement accessible avant une modification ; afficher explicitement sauvegarde partielle, régions non lisibles, restauration non démontrée. Aucun flash ECU non validé. Pour chaque lot, créer sa branche/PR **seulement après clôture du lot précédent**, associer les issues, tests Android/Windows sans erreurs ni avertissements, puis merge et suppression de branche après validation. Ce tableau remplace les priorités historiques contradictoires sans effacer leur historique.

### PR54 — audit final des secteurs et matrice Android (20/11/2026)
- Correctif `012130b` : un secteur d'effacement ne peut plus être planifié s'il contient **n'importe quel octet protégé**, même quand l'octet que l'on souhaite modifier est en dehors de la zone protégée. Test de voisin protégé `ee62790` ajouté ; aucune écriture matérielle.
- Matrice instrumentée Android `0d5f0f1` étendue à **API 29, 30, 31, 32, 33, 34, 35, 36** (Android 10 à 16 et leurs niveaux intermédiaires). Tests sur matériel réel, encoches, tablette et paysage restent distincts.
- Premier checkpoint lancé sur `b22cee0` : Windows Debug/Release déjà verts ; Android tests unitaires et lint en cours lors du dernier constat. Le correctif secteurs et la matrice étendue sont **postérieurs à ce SHA**, nécessitant un NOUVEAU checkpoint avant tout merge.
- Ne pas prétendre une compatibilité ECU ou une sauvegarde/écriture physique sans validation matérielle. Les issues #52, #63, #64, #65 et #67 conservent leurs travaux matériels à venir. Ne pas étendre le périmètre de cette PR après validation finale sans nécessité de corriger un défaut.


### Matériel futur #69 — interface J2534 ET programmateur EEPROM sur banc (20/11/2026)
- Le propriétaire demande que l'interface matérielle CamaroDiag (ESP32-S3 / STM32 ou architecture à évaluer) **programme aussi les EEPROM et mémoires sur banc**, et ne soit pas limitée au connecteur OBD/J2534.
- **Issue #69 complétée** : port de programmation séparé, SPI EEPROM 25xx, I²C EEPROM 24xx, Microwire 93xx, NOR SPI via pilotes distincts ; supports SOIC/ZIF, 1,8/3,3/5 V avec protections et identification exacte. Programmer en circuit uniquement lorsque l'absence de contention est prouvée ; sinon dépose. La mémoire interne MCU exige BOOT/BAM, Nexus/JTAG ou autre interface selon référence et n'est pas une EEPROM externe.
- Procédure obligatoire : lecture initiale, proposition sauvegarde partielle/intégrale, double lecture concordante quand possible, dump brut + manifeste/hash, aperçu diff et granularité des pages/secteurs, écriture ciblée uniquement après précontrôles et **relecture indépendante de vérification**. Aucun clonage complet ni récupération garantis sans preuves.
- Planification : #69 en **lot matériel C après clôture de #54**. Dépendances logicielles #63 (sauvegarde), #67 (patch), #64 (pilotes) et #65 (récupération). Ne pas étendre ni retarder PR #54 ; ne créer aucune branche/PR supplémentaire à présent. Toute écriture doit être validée d'abord sur puce de test ou banc.


### 21/11/2026 — poursuite PR #70, lecture Windows et localisation
- Conserver `feat/ecu-backup-integrity` comme unique branche de travail ; ne pas fusionner avant validation complète. Licences désactivées sur Android et côté backend ; pas de déploiement Firebase Billing. Nom du produit immuable **CamaroDiag**.
- Cette passe : 589 chaînes traduites (28 clés véhicule/VIN/réglages sur 16 langues + 141 clés restantes en croate). 868 clés de référence ; 15 locales complètes en nombre de clés (EN FR DE IT ES HE RU ZH-CN JA KO-KR AR PT NL EL HR), 15 incomplètes, 6 333 occurrences de traduction encore manquantes. Audit statique de toutes les locales : aucune clé inconnue/dupliquée ni différence de paramètres dans les chaînes présentes. Cela ne remplace pas CI, lint et recette linguistique. Continuer les traductions directement dans `strings.xml`, ne pas recopier l'anglais ni ajouter de moteur de traduction.
- Correctifs Android : erreurs de Task Firebase et navigateur traitées sans exception non gérée ; expiration future obligatoire pour abonnement. Tests dédiés des droits. Licence toujours désactivée.
- Correctifs Windows #63 : VIN lu via F190 avant chaque passe mémoire, tous les 32 blocs et en fin de lecture ; refus en cas de changement ou réponse tronquée, annulation avant export et contrôle SHA-256 du binaire relu sur disque. Retour à la session de diagnostic par défaut si l'ECU est toujours identifié et si non annulé. Tests simulés ajoutés. Aucune validation physique revendiquée.
- Dernier commit de code/ressources de cette passe : `714cbaac`. CI de ce SHA : lancée, non encore acquise lors du constat. Kernel flash RAM original et validation sur banc restent nécessaires ; aucun flash matériel déclaré validé.


### 21/11/2026 — session suivante PR #70 (checkpoint ac06bf3)
- Travailler uniquement sur CamaroDiag, branche `feat/ecu-backup-integrity` ; CamaroDiagConnectors est hors périmètre pour le moment. Aucune fusion avant validation de la PR et aucun nouveau lot/branche concurrent.
- 63 commits de code et ressources depuis `d1c5d01`, vérifiés par GitHub compare. Correctif Compose de l'identité du compte ; identification physique GMLAN VIN/OSID dans Android via J2534, affichage et export distincts du Mode 09, validation du CAN ID, invalidation lors du changement de contexte. Lecture seule : ne pas confondre observation et qualification de flash.
- Traductions : 150 nouvelles chaînes GMLAN (5 × 30), 130 anciennes chaînes CS/DA, 314 chaînes de commandes et interverrouillages (22 × 12 + 25 × 2). Parité intégrale des anciennes ressources non atteinte ; conserver le test strict.
- Noyau C + C# : tests d'intégration réels du code de transaction contre modèle RAM de la flash MPC5566, échec avant effacement sur original incorrect, récupération après échec de pulse, blocs intermédiaires/hauts et préservation des voisins. Vérifications supplémentaires des registres, erreurs ECC et intégrité du plan de transfert RAM. Compilation croisée PowerPC réussie sur checkpoints antérieurs ; pas de binaire ECU déployable ni essai matériel.
- CI native verte à `3caf199` (44933594826), C# Windows verte à `b0b2281` (44932646651) ; le dernier HEAD nécessite validation renouvelée. Android précédemment bloqué par la parité et le lint Compose ; ne pas annoncer la réussite finale en attente des rapports.
- Prochaines actions : lire les rapports CI du dernier SHA, corriger les défauts, compléter les traductions restantes sans copier l'anglais, poursuivre le raccordement matériel isolé, démarrage/horloge/watchdogs/CAN et qualification sur ECU de banc réellement disponible, avant activation de l'écriture E39/E39A. Aucun succès physique revendiqué.


### 21/11/2026 — reprise PR #70, checkpoint f41bffd
- Continuer exclusivement `feat/ecu-backup-integrity` de CamaroDiag ; aucune nouvelle PR/branche, aucun merge avant développement et validation complète. CamaroDiagConnectors non modifié. Conserver CamaroDiag comme nom propre et la commercialisation des licences désactivée.
- 30 commits de code/ressources depuis `9d755ad` : correction du test de flux tronqué et des six avertissements Android de typographie/URI ; pin TLS défensif et test du trust manager. 358 traductions d'audit kilométrique réparties sur 14 locales ; parité des anciens écrans encore incomplète.
- Noyau MPC5566 : statut flash vérifié à chaque lecture et au handshake, lecture des mots non alignés optimisée. Mesures de tension avant transitions de programmation et transfert RAM. Tests de refus/coupure.
- Nouvelle orchestration locale : identification répétée, sauvegarde complète concordante, digest du kernel, séquence GMLAN, relecture intégrale avant effacement et après écriture avant statut Completed. Tests logiciels incluent modification hors secteurs ciblés. Raccordement physique interne à PassThru J2534 : canal dédié, réponses GMLAN corrélées, purge RX, aucun accès via serveur réseau, verrou de fichier du pilote et empreinte SHA-256 liée au canal qualifié. Ne jamais créer de profil qui déclare une qualification physique non mesurée.
- CI native confirmée sur `031256a` (44935844992), Windows réussie sur `2967b68` (44935309899). Dernier code `f41bffd` : résultats Windows+Android à vérifier ; tests matériels jamais exécutés. Aucun binaire RAM E39 déployable et aucune variante ECU/driver physiquement qualifiée fournie.
- Reprendre les CI, corriger erreurs/avertissements, compléter traductions réelles et qualification du noyau RAM (démarrage carte, horloge, watchdog, CAN, récupération, artefact signé/contrôlé), poursuivre tests E39/E39A et autres variantes. Aucun flash réel ni activation FlexFuel revendiqués. Garder #64, #63, #71/#72, #52 ouvertes jusqu'à preuve.


### 21/11/2026 — reprise diagnostic J2534, checkpoint c8e3971
- 51 commits depuis `2386e17` (avant ce checkpoint documentaire). Toujours uniquement CamaroDiag / PR #70 / branche `feat/ecu-backup-integrity`, aucun changement CamaroDiagConnectors et aucune fusion. Objectif cumulé demandé 200 commits, ne compter que les commits GitHub réels.
- Nouvelle voie OBD physique primaire ISO 15765-4 via J2534, sans émulation ELM : découverte des PID, télémétrie et CSV, DTC confirmés/en attente/permanents avec compte CAN exact, scan complet d'un répondant (freeze frame, monitor support, Mode 09), audit kilométrique. Le scan physique n'est pas une découverte fonctionnelle des modules. Les modes de DTC explicitement refusés restent indisponibles et les anciennes données correspondantes ne sont pas supprimées.
- Effacement standard antipollution activable localement dans le pont Windows (case à cocher/option CLI), avec confirmation Android, mesures fraîches vitesse/régime zéro et réponse positive exacte. Pas d'effacement de codes permanents ni d'ouverture de programmation, actuation, SecurityAccess ou effacement UDS. Vérification stricte d'accusé Mode 04 ELM aussi.
- Deux nouveaux libellés traduits dans les 30 langues ; ancienne parité de ressources encore incomplète. Ne jamais affaiblir le test de parité. Guide utilisateur mis à jour. Tests logiciels du scan/compteurs/réponses partielles et effacement ajoutés.
- CI Windows confirmée à `7eeeb1c` (44937811130, jobs build et validation réussis), noyau natif vert à `fbd93d0` (44938032182). Dernier Android à reprendre après CI ; au checkpoint antérieur `2386e17`, 187/188 tests app réussis, seul test de parité échoué, lint sans avertissement. Ne pas confondre les tests logiciels avec un essai sur véhicule. Aucun flash réel ni qualification matérielle J2534/ECU.
- Priorités : lire les résultats récents CI, corriger toute régression, compléter les anciennes traductions, développer le noyau RAM E39 réellement déployable avec qualification startup, watchdog, alimentation et récupération, et étendre aux variantes exactes. Conserver les limitations ELM/PassThru documentées, les licences non commercialisées et l'accès debug propriétaire uniquement.


### 21/11/2026 — checkpoint recette et intégrité native 4f14500
- Objectif de 200 commits cumulés depuis d1c5d01 : 199 avant ce checkpoint documentaire, 200 après publication. Cette reprise compte 52 commits de code/ressources et 1 checkpoint. PR #70, branche feat/ecu-backup-integrity uniquement ; pas de fusion, CamaroDiagConnectors inchangé.
- Recette propriétaire : contrôles physiques J2534 des PID, DTC et readiness ; contexte VIN/adaptateur/transport capturé, export conditionné à une recette terminée avec provenance, résultats tardifs rejetés après changement de connexion ou de véhicule. L'audit kilométrique J2534 lit les deux PID dans une transaction ; scan OBD protégé contre résultats périmés et lancements multiples.
- Natif : vérification de la géométrie et de l'état flash après impulsion, relecture des sélections remises à zéro, ACK exact, journal de récupération prioritaire après historique corrompu/ancien, lecture et CRC interdits pendant secteur incomplet, fragments non liés à l'écriture refusés. Tests ajoutés sans qualification sur ECU réel.
- 646 chaînes effectivement ajoutées dans les locales incomplètes (238 profils/DTC, 180 connexion/scan, 228 module/telemetrie/avertissements). La parité intégrale reste à terminer ; ne pas désactiver le contrôle.
- CI précédente Android e080322 : 189/190 tests, seul échec parité locale. Noyau natif vert à f93b7f3 (44939072504), Windows vert au checkpoint 7eeeb1c de la session précédente. Résultats du dernier code à vérifier. Aucun noyau ECU déployable qualifié physiquement ni flash réel.
- Suite : corriger toute régression CI, compléter les chaînes restantes, intégrer un démarrage RAM qualifié par carte et procédure de récupération vérifiée, tester les interfaces et variantes exactes sur matériel. Conserver les droits/licences en attente de commercialisation, les tests privés et l'absence de dépendance PCM Hammer. 


### 22/11/2026 — reprise PR #70 (checkpoint f2c91de)
- 17 commits de code/tests/ressources depuis 6b31a2f ; uniquement CamaroDiag, branche feat/ecu-backup-integrity, aucune fusion. À l'entrée : 194/195 tests Android réussis, parité locale en échec ; Windows et noyau natif verts. Douze avertissements TypographyDashes sur le nom normalisé J2534-2 corrigés localement, sans désactiver le lint global.
- Audit direct de strings.xml : 870 clés de référence ; 2 989 manquantes dans ET/FI/HU/GA/LV/LT/MT/PL/RO/SK/SL/SV. BG, CS, DA complets en nombre de clés. Test de parité désormais exhaustif dans son rapport (toutes les locales et toutes les clés manquantes), reste bloquant.
- Sauvegarde J2534 : export lié à l'empreinte originale vérifiée, copies figées, rejet si les deux buffers ont été modifiés ensemble, annulation respectée après retour en session par défaut. Deux tests de régression.
- Mise à jour APK : refus d'un même ID DownloadManager associé à des versions divergentes ; les entrées ambiguës ne permettent ni reprise ni suppression. Tests de régression ajoutés.
- Dernier SHA de code/tests f2c91de : CI à confirmer ; aucune validation physique ECU, aucun nouveau profil qualifié, ne pas activer le flash sur un appareil non qualifié. Poursuivre développement fonctionnel restant, puis traductions réelles fichier par fichier, vraie CI Android/Windows/native complète, et recette matérielle.


### 22/11/2026 — session suivante PR #70 (checkpoint 29aa0e2)
- 13 commits de code, tests et ressources depuis 30ce459, uniquement sur feat/ecu-backup-integrity. Aucun merge, aucun changement CamaroDiagConnectors.
- 510 chaînes traduites (255 SV + 255 RO). Les trois fichiers de chaînes agrégés contiennent 881/881 clés dans ces deux locales, aucune clé inconnue ou dupliquée et aucun écart de paramètres détecté. Reste 2 479 chaînes de strings.xml réparties dans 10 langues. Conserver le test bloquant de parité et prévoir validation linguistique et lint.
- Sauvegarde ECU : vérification renforcée des acquisitions partageant un même buffer, segments vides et VIN mal formé ; refus d'export avant toute écriture ZIP si les buffers sont aliasés ; 4 tests supplémentaires. Une paire de buffers distincts ne prouve pas une acquisition physique indépendante.
- CI à l'entrée 30ce459 : 196/197 tests Android, seul échec de parité, noyau natif vert. CI du nouveau HEAD à confirmer. L'écriture physique reste non qualifiée ; ne pas activer le flash sur un ECU non validé.


### 22/11/2026 — grande session PR #70 (checkpoint 6c5e400)
- 48 commits GitHub réels supplémentaires depuis le point de départ à 1423 commits, avant ce checkpoint documentaire. Branche feat/ecu-backup-integrity uniquement, aucune fusion, aucun changement CamaroDiagConnectors.
- Exports Android : dossier public /CamaroDiag/ avec permission SAF limitée et persistante, vérification de présence et réautorisation si supprimé, contexte compte/véhicule vérifié, fichier temporaire .partial, relecture indépendante et contrôle SHA-256 avant publication. Erreurs de stockage séparées de la liaison ECU. APK toujours dans Download.
- ECU : relecture indépendante de chaque secteur immédiatement avant effacement sur Kotlin et C#, surveillance tension renforcée, contrôle d'identité Kotlin après journalisation et avant effacement. Tests de régression sur perturbation du secteur suivant et tension. Windows conserve les deux acquisitions physiques séparées et peut créer un ZIP vérifiable par Android, ou un BIN brut sans preuve d'archive. Nouveau panneau de récupération hors ligne et rapport de progression du journal.
- Entrée locale E39/E39A conditionnée par un profil exact signé ECDSA P-256 (VIN, OS, DLL, canal, noyau, preuves de carte/récupération, limites). Registre de confiance de production volontairement vide ; aucune clé, carte ou signature matériellement qualifiée. Aucun flash physique possible dans cette configuration. Aucun accès réseau à ces opérations. Métadonnées et journal forcés sur disque avant ouverture ECU, consentement local explicite, aucun retry automatique. Les clés de test éphémères restent uniquement utilisées en simulation.
- 755 chaînes supplémentaires réellement traduites : SK 255, SL 255, PL 245. 873/873 clés strings.xml pour chacune, aucune inconnue/dupliquée ni écart de paramètres détecté. Reste 1724 clés réparties sur ET/FI/HU/MT (245 chacune), GA/LV/LT (248 chacune). Contrôle de parité maintenu bloquant.
- CI Windows compilation, tests sans matériel et installation x86/x64 réussis au SHA 24ccfd5 (run 44988059612). Noyau natif vert à 6c14bfe (44988379080). Ces résultats ne couvrent pas tous les commits ultérieurs. Contrôles Android et Windows ultérieurs à confirmer. CI renforcée pour imposer une compilation après modifications de programmation et d'export non encore validées. Ne pas fusionner tant que l'intégralité de la CI n'est pas verte.
- Suite : compléter les 7 locales réellement, contrôler les warnings et tests du HEAD, corriger tout défaut logiciel, puis qualifier noyau RAM/révision de carte/démarrage/transport et récupération sur matériel réel avant activation. Licences non commercialisées, aucun essai physique réalisé.


### 22/11/2026 — reprise PR #70, parité des langues (checkpoint ba9c351)
- La PR est passée de 1472 à 1506 commits avant ce checkpoint. Contributions de cette reprise et changements parallèles du dépôt conservés, sans écrasement ni nouvelle branche.
- Audit des 30 fichiers strings.xml au SHA ba9c351 : 873 clés chacun, aucune clé manquante, supplémentaire ou dupliquée, aucun écart de paramètres de format détecté. Les 1724 clés manquantes du checkpoint précédent sont désormais présentes. FI/HU/ET/LV/LT/GA terminés dans cette reprise (1479 chaînes), MT complété par les commits parallèles. La qualité linguistique et le test JVM complet restent distincts de cet audit statique.
- Correction ciblée des deux warnings TypographyDashes polonais sur les références exactes des bulletins GM, sans désactiver le lint global.
- Noyau natif : interdiction de traiter des trames après un échec d'initialisation du runtime ou de liaison CAN ; arrêt définitif et récupération requise après rejet d'une commande sur un secteur partiellement réécrit. Tests de non-régression ajoutés, suite native verte à dbe5086 (run 44989413490).
- CI Windows complète verte à edbd2be, Android alors 204/205 tests (seule parité linguistique en échec), lint avec deux avertissements polonais. Les contrôles du nouveau HEAD doivent être confirmés, notamment lint, compilation APK et tests JVM. Aucune programmation physique réalisée ni qualifiée ; ne pas fusionner avant validation complète et respect des exigences du lot.


### 22/11/2026 — reprise PR #70, checkpoint 7d61b31
- PR ouverte, branche feat/ecu-backup-integrity uniquement. 1515 commits au point de contrôle, contre 1493 au démarrage de cette reprise (22 commits supplémentaires sur la branche). Aucune fusion, aucune modification CamaroDiagConnectors.
- Les 30 locales contiennent les 873 clés de strings.xml : audit à ba9c351, aucune clé manquante, inconnue ou dupliquée, aucun écart de paramètres. Maltais : 245 traductions supplémentaires. Les autres locales restantes ont été complétées sur la branche. Contrôles de chaînes non vides et messages localisés du dossier /CamaroDiag/ ajoutés. Ne pas annoncer une recette linguistique humaine ni un build final avant résultats.
- Stockage Android : empreinte stable après fermeture réussie, relecture bornée à la taille écrite, rejet du contenu supplémentaire et de la fermeture en erreur ; trois tests. Exports de calibration, CAN et télémétrie liés au compte et au profil sélectionnés au lancement ; mise à jour des écrans seulement si contexte toujours courant.
- Journal Windows : refus des nouvelles écritures après append interrompu, fermeture fiable du flux et tests annulation/fermeture. Noyau MPC5566 : contrôle de chaque unité ECC avant impulsion, contrôle de toute la plage après programmation, modèle de perturbation d'unités adjacentes et intégration C/C# avec journal de récupération. CI native verte à 80e2df8 (44990046652) et 022934e (44990100782).
- Le dernier build Windows complet confirmé est celui de edbd2be (44988584266). Les builds Android/Windows du nouveau code doivent être confirmés. Les anciens résultats Android (204/205, parité + 2 warnings polonais) précèdent la complétion des langues et les corrections. Ne pas fusionner avant tests et lint verts.
- Qualification physique E39/E39A, carte/initialisation RAM, firmware exact, transport et récupération banc restent à réaliser. Aucun faux profil validé, aucune activation réelle de la programmation ni vente de licence.


### 22/11/2026 — périmètre matériel séparé, PR70 / Connectors #7
- Décision utilisateur : CamaroDiagConnectors devient la couche commune de communication et programmation matérielle Android/Windows, développée à part. Issue chapeau https://github.com/Hellorheaven/CamaroDiag/issues/86 créée, couvre OBD, CAN direct, BENCH, BOOT/BAM, BDM, JTAG/Nexus, SWD, EEPROM/NOR ISP et chip-off, et fédère les issues 1 à 5. Aucun fichier du dépôt Connectors modifié.
- CamaroDiag #79 et PR70 : contrat de pilotes distincts, qualification par cible/firmware/région/route exacte, sélecteur Expert huit modes, sauvegarde ZIP des deux BIN d'origine identiques importés depuis un programmateur externe (15 Mio par lecture max, /CamaroDiag/, provenance importée, couverture partielle). EEPROM externe exclue de l'effacement flash par secteurs. Tests de non-interchangeabilité et d'intégrité ajoutés. Import des deux BIN protégé contre changements de compte/véhicule et annulation. CI Android renforcée pour ces fichiers.
- Le socle J2534 et noyau E39/E39A existant reste conservé ; aucune connexion physique universelle ni écriture validée n'est revendiquée. L'intégration du futur module Connectors et sa qualification matérielle seront traitées séparément. Activation FlexFuel réelle toujours tributaire des définitions de cartographie exactes et de la qualification du pilote.
- Au checkpoint 115fc7b : 1539 commits, 30 locales complètes. Ancien HEAD 926933b : tests unitaires, noyau natif et Windows réussis ; build Android réussi mais instrumentation annulée. Ne pas annoncer dernière CI verte sans vérifier tous les jobs attendus au nouveau HEAD. Ne pas fusionner en cas d'erreur, warning ou validation requise incomplète.


### 22/11/2026 — Reprise PR70, Android natif CamaroDiagConnectors
- Exigence utilisateur : CamaroDiagConnectors fonctionne directement avec Android via USB-C/OTG, sans pont PC, Windows/J2534 facultatif. Exigence inscrite dans CamaroDiagConnectors #7 et #4. Le dépôt Connectors reste développé séparément, aucun fichier de ce dépôt modifié.
- Vérification de Connectors/main : firmware ESP32-S3 0.1.0 fournit uniquement PING, CAPS, STATUS en ASCII à 115200 ; fonctions CAN, EEPROM, flash et J2534 désactivées. Aucun protocole binaire opérationnel ou qualification physique. PR70 : découverte USB native dans Adaptateurs à partir de l'interface USB existante, strictement séparée des commandes ELM et du pont PC. Les trois réponses sont vérifiées avec taille/délai bornés et protocole/version stricts. Le rapport est historique, la liaison est refermée et aucun scan ECU ni profil de connexion opérationnel n'est activé. Action explicite même si la carte se présente sous un nom générique. Cinq tests unitaires et guide utilisateur ajoutés.
- Correction du test EcuFamilyCatalogCompatibilityTest (qualification exacte de la région/route dans son fixture). PR unit tests run 44991165861 au SHA 0a6245d réussi, après la correction et l'intégration de la découverte USB. Native ECU kernel tests au même SHA réussi. PC J2534 Bridge CI checkpoint vert, build Windows ignoré car non modifié (précédent checkpoint 277b7cb vert sans nouveau build Windows ; build complet confirmé à edbd2be). Validation Android complète déclenchée, résultat final à vérifier.
- Sauvegarde ciblée J2534 Android : couverture PARTIAL en absence de carte complète qualifiée, cohérente avec Windows/import externe ; origine et double acquisition conservées. Test mis à jour. Journal Android : l'écriture en cours refuse le retour à un préfixe valide antérieur et toute nouvelle tentative après erreur d'I/O/flush ; lecture bornée et test de régression. Ces derniers changements sont postérieurs au dernier résultat unitaire confirmé.
- Checkpoint 7005bed : 1552 commits avant mise à jour du document, 30 langues. Le registre des pilotes physiques reste vide faute de qualification ; le futur pilote Connectors devra respecter les routes, régions et cibles exactes et utiliser le protocole natif versionné réellement fourni. Aucun pilote universel fictif, aucun faux flash ni activation FlexFuel. Ne pas fusionner tant que CI complète du périmètre final et critères restants ne sont pas remplis. Le firmware et sa qualification, ainsi que l'intégration de ses commandes matérielles, relèvent des travaux indépendants liés aux issues Connectors #4/#7.


### 22/11/2026 — PR70, intégration des exigences Connectors P0 sécurité / anti-brick
- Sources : https://github.com/Hellorheaven/CamaroDiag/issues/86, https://github.com/Hellorheaven/CamaroDiag/issues/87 et https://github.com/Hellorheaven/CamaroDiag/issues/88. Aucun développement firmware Connectors effectué dans CamaroDiag.
- Le rapport ASCII 0.1.0 est explicitement non authentifié et ne donne aucune capacité de trafic ECU. Le gestionnaire USB refuse de sonder un connecteur nommé comme ELM, y compris en appel direct (nom USB = indice de routage, jamais preuve cryptographique). Tests ajoutés en 15d18e7.
- Séquenceur ECU : comparaison indépendante des secteurs non modifiés de la région qualifiée avant le premier effacement et après les écritures ; divergence = arrêt sans succès, sans retry ni reset automatique. Trois nouveaux tests simulés en 1574a0f. Aucune qualification sur banc revendiquée.
- Futur transport natif : appairage hors ligne et authentification mutuelle PC/Android ↔ boîtier, session anti-rejeu, isolation de la découverte, qualification et autorisation exacte par route/cible/firmware/région. Android sans PC. USB en priorité ; Wi-Fi/BLE non destructifs jusqu'à qualification. Ne pas traiter le nom USB, une capacité déclarée ou un checksum comme preuve d'authentification ou de restaurabilité.
- Couvrir OBD, CAN HS/SWCAN/FD, BENCH, BOOT/BAM, BDM, JTAG/Nexus, SWD, EEPROM/NOR ISP/chip-off (SWD et distinction sous-routes restent à intégrer explicitement). Les pilotes existants ne sont pas universels. Les opérations critiques et le journal de récupération doivent être locaux au futur boîtier ; ne pas prolonger aveuglément une opération interrompue depuis Android.
- Signature/secure boot du boîtier uniquement ; image ECU au format natif constructeur, checksum/signature OEM seulement si requis. Pas de chiffrement ou d'en-tête arbitraire sur CAN/EEPROM. Vérification complète, protections électriques, procédure de récupération qualifiée par profil, et sauvegardes /CamaroDiag/. Ne pas activer la programmation physique tant que les preuves et le firmware correspondant ne sont pas disponibles.
- HEAD initial d5a14d4 : quatre workflows GitHub terminés avec succès ; vérifier les jobs réellement exécutés (un checkpoint vert ne garantit pas un build complet). Dernier code 1574a0f : CI à confirmer. PR #70 maintenue en brouillon et unique branche de travail.


### 22/11/2026 — PR70, raccordements et SWD (Connectors #10)
- Nouvelle exigence : https://github.com/Hellorheaven/CamaroDiag/issues/89. Documentation du dépôt Connectors consultée à 04883775d7f97de4cd89da0de75c46bf076f3332 ; fiches LFX 2012 E39 candidat, E38 LS3/L99 2012 et E39A indépendante, toutes non qualifiées. Ne pas réutiliser les pins E39 pour E39A, ni inventer une séquence d'alimentation. USB 5 V logique, 12 V externe protégé dédié cible, VCC mémoire selon composant ; continuité de l'alimentation logique indépendante des données exigée pour opérations critiques.
- dd8a1cb : SWD ajouté au modèle, au sélecteur Expert et aux contraintes de routes/régions ; tests d'étanchéité entre SWD, BDM, JTAG/Nexus, CAN et mémoires externes. Aucun support matériel déclaré.
- cd6f181 : accès au guide et fiches documentaires dans Expert, sélection stricte année/RPO/famille saisie, repli catalogue sans rapprochement approximatif. Trois tests du catalogue. Liens figés sur la révision Connectors examinée, non autorisants.
- 8a60674 : indication non vérifiée, gestion de l'échec d'ouverture, CI Android complète requise pour tout changement de ce catalogue. Aucun nouveau texte non traduit (réutilisation des ressources).
- CI du checkpoint cbafdf7 : tests unitaires et noyau natif réussis, PC J2534 contrôle réussi mais vérifier si compilation effective, Android à vérifier. Nouveau code : CI en cours. Ne pas fusionner avant contrôle final et qualification du périmètre matériel revendiqué.
- Prochain lot : transport authentifié Android direct quand le contrat natif est établi, politique d'admission électrique liée au firmware et à une qualification réelle ; distinction des sous-routes EEPROM/NOR et des variantes CAN, journal local et récupération. Aucune branche supplémentaire, aucun fichier Connectors modifié.


### 22/11/2026 — PR70, finalisation logicielle et nouveau prototype binaire
- Point de départ 88f0114 : 1564 commits, 305 fichiers. Tests unitaires et noyau natif verts ; Windows checkpoint vert (compilation ignorée) ; Android en cours. Pour cbafdf7, tests Android, lint et rejet des warnings, compilation APK instrumenté réussis ; build APK alors en cours. Recontrôler le vrai dernier HEAD.
- Connectors PR #6 au SHA e8593d29 : nouveau mode expérimental USB binaire CD/1, hôte Python de découverte et TLS 1.3 de simulation ; pas de TLS USB embarqué ni de protocole ECU opérationnel. ef2c2e3 : intégration Android de la découverte CD/1, choix explicite du mode, aucune rétrogradation automatique, aucune capacité véhicule. 7c67738 et 61ec3ac : paquets complets et fragmentés, CRC32, longueur, correlation, refus de concaténation, tests de non-régression. 9c60c33 : journal ECU mono-région et test.
- Pour finaliser PR70, vérifier tests unitaires, build Debug et lint Android, instrumentation, build Windows x86/x64 et autotests ainsi que les critères utilisateur encore ouverts (#63,#64,#71,#73 à #79 et lot pertinent). Ne pas confondre le succès d'un checkpoint avec compilation complète. Maintenir licence commerciale désactivée, exports /CamaroDiag/ et fonctions physiques non qualifiées bloquées.
- Conseil de découpage : finaliser le socle logiciel de PR70 sans attendre la fin du développement indépendant du boîtier, mais ne pas retirer un critère obligatoire ni fusionner avant validation et arbitrage si un essai matériel reste requis. Les routes physiques, authentification USB embarquée, qualification électrique, reprise après coupure et FlexFuel sur ECU exact ne sont pas terminées. Aucun faux statut. Aucun changement dans Connectors.


### 22/11/2026 — Synchronisation PR70 avec Connectors PR #6 (77d9590)
- Connectors : 116 commits au moment de la consultation, clients Java Android CDC et .NET Windows, modèle de journal NVS embarqué CDJ/1, pas de commande USB permettant de le lire ni d'accès physique aux ECU. Aucun changement effectué dans ce dépôt.
- 9683d32 : alignement CD/1 sur les réponses canoniques des clients de référence, port CDC unique pour binaire ; refus d'une sélection ambiguë, handle de découverte séparé du canal partagé ELM, contrôle de l'époque de transport et de l'annulation ; une ancienne découverte ne ferme pas un nouveau canal OBD. Compatibilité réelle USB CDC/JTAG Espressif à vérifier, aucun repli ASCII automatique. Le firmware isolé doit être redémarré entre sessions.
- 0888671 : inspection hors ligne du checkpoint CDJ/1 (64 octets, CRC32, identifiant transaction, digest du manifeste, étape/sequence et transitions atteignables). Aucun accès au NVS depuis l'application ; aucun nouveau protocole, aucune autorisation de reprise ni preuve d'authenticité. Quatre tests ajoutés ; test de réponse CD/1 canonique supplémentaire. Aucune écriture physique activée.
- Statut initial : 1569 commits, 307 fichiers, branche unique de travail feat/ecu-backup-integrity. CI du nouveau code à confirmer. Conserver les pilotes et fonctions existants, ne pas fusionner tant que les vérifications complètes ne sont pas réussies. Le véritable contrat de transport authentifié reste une dépendance de Connectors #8 ; la liaison avec son journal embarqué dépend du protocole et de la recette de #9.


### 22/11/2026 — Développement synchronisé des deux PR
- Décision utilisateur : développer CamaroDiag #70 et CamaroDiagConnectors #6 ensemble, une seule branche active par dépôt ; maintenir les critères de fusion indépendants et des tests de compatibilité communs.
- Connectors 410a356 : session CD/1 canonique unique, firmware et Python ; 4d04244 : cohérence étapes/séquences CDJ/1 et verrouillage du writer après persistance incertaine. Révision du contrat et du vecteur partagé : 790d212aba2d8b7aae5d0fac2d274ed78402bda9.
- CamaroDiag : test d'interopérabilité du même vecteur synthétique CDJ/1, récupération requise et écriture bloquée. Les deux dépôts ont le test du même vecteur fixe, non authentifiant. Nouveau code à valider par CI. Le transport authentifié et les tests physiques restent bloquants pour toute programmation réelle.


### 22/11/2026 — Extension multimarque et TuneECU
- Décision : Connectors #11, #12 ; VCI automobile/moto multimarque et logiciels tiers autorisés. Android direct, pont PC facultatif, DLL/API standard et mode natif restent distincts. Ne pas confondre capacités J2534, CDC ou FTDI KKL ; les noms USB ne sont que des indices. Référence Connectors 1d4de23 : politique de séparation des modes et sécurité, schéma candidat FTDI K/L-Line et Bluetooth Classic additionnel (S3 BLE uniquement). Aucun accès moto physique qualifié.
- PR70 : protéger les ports explicitement nommés KKL/K-Line des sondes ELM/CDC dans le gestionnaire et l'UI, préserver les adaptateurs FTDI non identifiés comme KKL. Test de routage ajouté. Ne pas déduire un type de câble du seul VID FTDI ; un câble générique non identifié reste à sélectionner avec prudence. TuneECU doit pouvoir fonctionner indépendamment de CamaroDiag, sans imitation d'identité ni contournement des restrictions de l'éditeur. Aucun firmware ou mode reprogrammation tiers activé.


### 22/11/2026 — Socle commun K-Line passif
- Connectors ee9927f : analyse hors ligne ISO 14230-2 adressage physique, longueur courte/étendue, checksum, adresses exactes ; tests C++ et CI. Aucun TX, initialisation K-Line, ISO 9141 ou accès ECU.
- Côté CamaroDiag : KwpFrameInspector et tests Kotlin, mêmes vecteurs synthétiques que le firmware (82 F1 12 61 01 E7 et 80 F1 12 02 61 01 E7). Aucun support TuneECU réel déduit, aucune capacité K-Line active. Maintenir les tests physiques #12 séparés.


### 22/11/2026 — Session coordonnée pont Windows et protections
- PR70 a026fbf : découverte locale CD/1 dans le pont Windows, CLI explicite COM, test simulé et package System.IO.Ports ; pas de backend ECU ni relais réseau. 13445d8 / Connectors c5349e3 : délai Windows global par échange, test de réponse lente et réutilisation refusée.
- Connectors ad92a68 : arbitre de sessions exclusives de lecture, générations locales, refus de callback obsolète et verrou de récupération ; modèle isolé sans I/O. Le précédent lot ee9927f a ses quatre workflows verts.
- PR70 : snapshot privé unique avant contrôle/planification et protections pendant la double lecture baseline ; tests de coupure et mutation des images. Aucune activation de driver ECU. Attendre les CI complètes, pas de fusion avant build sans avertissements et validation des critères existants.

- Connectors 6c95fac : délais de découverte Android Java et Python, vérification des octets USB encore en tampon, tests de fragmentation lente. PR70 : onglet graphique local de découverte CD/1, sélection COM explicite, confirmation carte isolée, aucun sondage automatique, blocage si pont/console occupés ; même implémentation que CLI. CI du nouveau HEAD à vérifier.

- Pont Windows : inspection hors ligne CDJ/1 et test du vecteur commun avec firmware/Android ; UI récupération et CLI explicite. Toute étape reste bloquante pour les opérations physiques ; pas de commande embarquée d'export. Vérifier CI avant fusion.


### 22/11/2026 — Autorisation mutuelle TLS de référence
- Connectors 4d897e4 : CA + empreinte exacte des clients autorisés et du boîtier, révocation à la prochaine connexion, rejet reprise TLS ; tests socket Python. PR70 : fondation Android IP TLS1.3 dédiée (CA provisionnée, client KeyManager, vérification nom/ALPN/empreintes locales et distantes). Tests Kotlin de la politique post-handshake. Ne pas confondre avec CD/1 USB ou appairage du pont actuel ; non connecté à l'UI ni à une route ECU. Provisionnement sécurisé et tests croisés réels restent bloquants.


### 22/11/2026 — Finalisation PR70 : parité de récupération Android et intégrité export
- Ajout d'une analyse locale CDJ/1 en mode Expert, choix SAF explicite, lecture plafonnée à 65 octets (record 64), rejet des données surnuméraires, aucun accès ECU ni reprise automatique, UI reprend les ressources localisées existantes. Tests de lecture bornée. Le firmware ne fournit pas encore d'export de checkpoint.
- Exports /CamaroDiag : flux empoisonné après toute écriture/flush échoué, aucune empreinte de succès même si appelant intercepte l'erreur et fermeture réussie. Deux tests.
- CI 21cc16a : tests unitaires, Windows et natifs verts, build APK et lint Android sans warnings confirmés ; instrumentation encore en cours au moment du changement. Revalider le dernier SHA. Les issues Connectors #11 à #14 (interop OEM, TuneECU et packs) sont des dépendances séparées ; aucune implémentation firmware dans PR70. Ne pas fusionner avant recette requise et CI du dernier HEAD.


### 22/11/2026 — Décision de centralisation logiciel (#80)
- Remplace la politique antérieure de deux dépôts logiciels : tout firmware, code hôte, pilotes, tests, CI et backlog logiciel CamaroDiagConnectors sont centralisés dans CamaroDiag, PR #70 / branche feat/ecu-backup-integrity. Ne plus développer de code dans l'ancien dépôt. Celui-ci conserve le matériel (PCB, BOM, schémas, câblages, alimentation et validation électrique).
- Import de l'inventaire Git source PR6 81b578571a56c8d96b46932f24c55f34aacd1a65, y compris les fichiers déjà sur main. Module `connectors/`, quatre workflows `.github/workflows/connectors-*`. Inventaire : `connectors/IMPORT_MANIFEST.json`. Les documents matériels sont des liens vers la révision de référence, non des copies. Distinguer l'import d'un snapshot et l'intégration effective aux transports ; dédupliquer progressivement les clients de référence sous tests. L'historique Git reste dans l'ancien dépôt, aucune suppression avant contrôle.
- Issues source #1–5 et #7–14 à centraliser avec leurs liens et discussions ; tableau de correspondance dans #80. Toutes exigences, notamment #11 multimarque, #12 TuneECU, #13 GDS2/Tech2Win/DiagBox lecture/écriture, #14 packs firmware, restent en vigueur. Aucune branche/PR concurrente. Après validation du nouvel emplacement, fermer PR6 sans merger de nouveaux développements logiciels dans l'ancien dépôt et retirer le code de celui-ci de manière contrôlée en préservant les documents matériels. Ne pas annoncer les CI vertes avant résultats réels. Aucun nouveau pilote ECU physique activé.


### 22/11/2026 — Décision finale utilisateur (remplace les règles de répartition précédentes)
- Un seul dépôt actif : CamaroDiag. Intégrer intégralement les deux main et développements PR70/PR6, logiciel ET matériel, schémas/PCB/BOM/documentation et futurs développements dans `connectors/`. Aucun dépôt matériel indépendant à maintenir désormais. Toutes les sources utiles et les exigences sont suivies directement dans CamaroDiag (issues #80 à #93). Les 13 issues migrées #81–#93 sont canoniques, #80 pilote la migration.
- Les neuf références matérielles de la première importation sont remplacées par les fichiers complets d'origine. Les 84 chemins de l'instantané source sont présents ; quatre workflows relocalisés. Demande explicite de fusion de la consolidation dans main maintenant même si la qualification fonctionnelle/physique reste incomplète. Ne pas confondre consolidation Git et livraison/validation fonctionnelle ; inscrire les blocages restants dans les issues et maintenir les écritures non qualifiées bloquées. Aucune nouvelle branche de développement parallèle.

- Liens de câblage de l'application repointés vers l'instantané complet CamaroDiag 84c21a5, toujours non qualifié. Les références matérielles et PCB futurs sont maintenus dans le même dépôt.


### 22/11/2026 — Rationalisation CI post-consolidation (#94)
- Toutes les évolutions sont dans CamaroDiag, logiciel ET matériel sous connectors/. PR70 et ancienne PR6 fusionnées. Ne pas reprendre anciens refs/PR70/PR6 ni ouvrir deux PR de développement.
- 12 workflows identifiés, cible 8 : Android CI, PC J2534 Bridge CI, PR unit tests (y compris Firebase/licences), Native ECU kernel tests, Connectors CI (ESP32/STM32, C++, Java USB, Python 3.11/3.12/3.13, Windows), Release validation sans publication, Release CamaroDiag, nettoyage des branches fusionnées. Supprimer kickoff PR35/70 et remplacer quatre anciennes CI Connectors par une seule. Aucune perte volontaire de tests ; préserver cadence draft 50 commits et build complet ready-for-review.
- Android et Windows : lancement manuel sur main ou ref explicite avec SHA résolu pour artefacts ; supprimer référence à l'ancienne branche. Préflight signé sans publication uniquement main ; publication automatique conservée pour changements produit, pas les seuls documents/PCB. Nettoyage uniquement sur PR fusionnée, branche locale et SHA inchangé, aucune PR ouverte réutilisant le nom. Contrôle de CI avant fusion.
- Catalogue Android Debug existant #76 reste lié à ancienne PR70 ; adapter explicitement dans un lot avec tests de provenance GitHub, ne jamais ouvrir aux artefacts de forks ou prétendre que télécharger est anonyme.


### Règle impérative de gestion Git — issue #96
- Un seul dépôt actif : CamaroDiag. Une seule PR ouverte à la fois (brouillon inclus), ciblant main depuis sa branche locale unique. Il ne peut exister que main et cette branche. Sans PR, main seule.
- Avant toute création, interroger GitHub pour les PR ouvertes et toutes les branches. Si une PR existe, reprendre exclusivement celle-ci. Ne pas créer de branche ni de PR en parallèle, même pour un autre composant. En l'absence de PR, vérifier que seule main subsiste avant de créer la prochaine branche et sa PR.
- Après acceptation et CI obligatoires réussies sur le HEAD exact, fusionner la PR vers main ; vérifier sa fermeture et la suppression de sa branche. Ne travailler sur le prochain lot qu'après contrôle de main seule et zéro PR ouverte. Ne jamais supprimer main, une branche ayant avancé ou du travail non fusionné. Une CI signale les violations mais ne bloque pas atomiquement les créations GitHub : ces préconditions restent obligatoires pour chaque session et agent. En cas de violation, arrêter toute nouvelle création, examiner le travail existant et corriger sans perte.


### Reprise des développements PR6/PR70 après consolidation
- Continuer sur l'unique PR ouverte #95. CDJ/USB/1 : variante firmware de service isolée, lecture réelle du checkpoint NVS, export borné en hex ASCII, un ordre exact par boot, aucune écriture, aucun clear, aucune activation de route. Client Python `--port COM5 checkpoint --isolated --output checkpoint.cdj` refuse corruption, suffixes, troncatures, CRC/états invalides et écrasement. Fichier 64 octets compatible lecteurs Android/Windows existants. Ne pas effacer NVS, ne pas reflasher un boîtier en transaction ou récupération ; variante pour validation isolée uniquement, pas une procédure de restauration validée. Vérifier compilation/CI et tester sur vrai boîtier avant qualification. Durcissement de l'inspection NVS si le record possédé disparaît/change.

- PR95 : 1ccd8eb ajoute la capture USB CDJ/1 dans l'onglet Connectors Windows et CLI, tests hors ligne et conservation exclusive de preuve. Le contrôle Android des secteurs inchangés vérifie identité et alimentation entre acquisitions et après lecture ; test de coupure supplémentaire. Qualifier sur matériel réel, les protections restent actives. Une seule PR ouverte ; pas de fusion sans CI requises.

- Reprise PR95 : capture CDJ/USB/1 directement depuis Android, port CDC unique explicite, confirmation carte isolée, propriétaire/licence et profil contrôlés, réutilise l'arbitrage USB de découverte (pas d'ELM ni de session ECU), export via stockage vérifié `/CamaroDiag/`. Tests parseur/lecture fragmentée et refus données supplémentaires ; aucune autorisation d'écriture, aucune qualification physique.

- Organisation utilisateur (#78) : classification Android sous `/CamaroDiag/` (Backups/ECU, Backups/Diagnostics, Calibrations, Reports, Logs/Console, Logs/Recovery, Telemetry, Tests, Exports). Parité Windows dans Documents/CamaroDiag avec les mêmes catégories et Profiles ; dialogues de sauvegarde prépositionnés, noms uniques, chemin alternatif uniquement au choix explicite, refus des conflits/répertoires redirigés. Préserver fichiers existants et stockage privé. Tests Windows ajoutés ; vérifier CI.

- Stockage Android : catégorie obligatoire à chaque appel d'export (10 appels existants), vérification d'identité des documents temporaires et publiés ; le chemin complet est renvoyé dans chaque message de succès. Tests supplémentaires (#78). Les applications d'installation utilisent toujours DownloadManager et vérification de signature, hors du classement des exports.

- Demande #97 : sous-répertoire `/CamaroDiag/APK/`, téléchargement updater en transit privé puis signature/package/version et copie SAF vérifiée avant lancement installateur. Rétention cinq fichiers archivés gérés (versionCode puis ID), nettoyage après publication et démarrage, indépendamment de la suppression du transit après installation. Aucun fichier hors APK, aucun APK navigateur externe ni ancienne sauvegarde supprimés. Autorisation SAF explicite ; retenter si accès perdu. Tests et CI nécessaires.

- Complément utilisateur #97 : afficher le chemin exact `/storage/emulated/0/CamaroDiag/APK/` dans la modale de téléchargement et la notification, conserver la progression jusqu'à publication vérifiée, possibilité de masquer/réouvrir la modale sans annuler le transfert. Les libellés existants sont réutilisés pour les langues ; rétention cinq APK complets gérés. Continuer sur PR95, ne pas créer une autre branche.


- Hotfix #98 (main et PR95, demande explicite) : logo Google 18dp, vérification fondée sur le claim Firebase signé email_verified après renouvellement, relecture au retour du navigateur, feedback erreurs, actualisation après ajout d'un fournisseur, grâce hors ligne seulement sur même adresse précédemment confirmée, sans privilèges propriétaire offline. L'objet des mails nécessite la configuration Firebase Authentication (nom public CamaroDiag et modèle de vérification). Ne pas modifier l'ID projet/OAuth ni annoncer une modification serveur sans confirmation.


### UX Auth #99 — compte Google et mots de passe
- Google devient la connexion par défaut ; aucun champ e-mail/mot de passe n'est affiché tant que l'utilisateur n'a pas explicitement choisi « Connexion avec e-mail et mot de passe ». Lors du changement de mode, nettoyer les saisies.
- Aucun champ « ajouter/créer mot de passe » sur compte lié Google, même si `password` est présent parmi les fournisseurs ; aucun changement de mot de passe sur compte Google. Seuls les comptes e-mail purs (`password` et non `google.com`) ont dans « Mon compte » une action Changer mon mot de passe, avec ancien mot de passe + double saisie du nouveau. Vérifier ≥8 caractères, égalité, différence de l'ancien ; appeler `reauthenticate` puis `updatePassword` Firebase ; ne pas toucher aux UID, e-mails ou claims. Tests de la politique.
