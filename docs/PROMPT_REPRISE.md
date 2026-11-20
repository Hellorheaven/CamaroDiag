# PROMPT DE REPRISE — CamaroDiag

> Fichier de continuité du projet. À lire en premier dans tout nouveau chat consacré à CamaroDiag. Mettre ce fichier à jour dès qu'une décision durable, un changement de périmètre, une modification GitHub/PR, un résultat CI ou un état technique important est confirmé. Tenir le changelog propre à chaque PR (`docs/CHANGELOG_PR<number>.md`) à jour sur la branche de cette PR.

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
- Conserver exactement deux branches (`main` et `feat/live-can-monitor`) et une seule PR ouverte (#35), sauf autorisation explicite contraire. Ne jamais créer une nouvelle branche ou PR de sa propre initiative.
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
