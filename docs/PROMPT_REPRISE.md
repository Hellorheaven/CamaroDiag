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
