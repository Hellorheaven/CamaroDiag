# Plan d'exécution multi-agents — CamaroDiag

Statut : **missions préparées, NON lancées en autonomie**. Ce fichier n'est pas un ordonnanceur : l'exécution nécessite de confier chaque mission à un agent de développement disposant d'un environnement Git, d'un exécuteur de tests et de l'accès requis. Ne jamais prétendre qu'un agent travaille sans preuve d'exécution.

Cible unique : `Hellorheaven/CamaroDiag`, **PR #95** (`chore/unified-ci` vers `main`). Ne créer ni branche ni PR supplémentaires. Ne jamais fusionner automatiquement. **Issue d'audit #106 non modifiée.**

## Contrat commun
- Chaque mission commence sur le SHA courant de `chore/unified-ci` après synchronisation, relève ses fichiers, ses tests et les dépendances.
- Lecture et analyse parallèles possibles ; **écriture séquentielle intégrée par un seul coordinateur** sur `chore/unified-ci` pour empêcher conflits et courses de commits. Les agents doivent proposer patchs/commits révisables au coordinateur, pas pousser simultanément.
- Éviter de modifier les mêmes fichiers partagés : `MainViewModel.kt`, `CamaroDiagApp.kt`, `README.md`, Gradle, workflows, manifest, intégration Firebase. Le coordinateur attribue ces conflits.
- Pas de secrets, identités privées, données sensibles, dumps ECU ni codes de sécurité dans le dépôt public ou dans les logs. Tests sur données fictives.
- Pas de nouvelle fonctionnalité hors périmètre de la #95. Aucun hardware/ECU déclaré validé sans test banc/véhicule. Écriture ECU et commandes actives interdites par défaut sans profil matériel validé.
- CI lourde au checkpoint défini et avant fusion, pas à chaque commit. Tests ciblés pendant les développements.
- Remise obligatoire par mission : chemins modifiés, SHA de commit, tests exacts et résultats, problèmes encore ouverts, références aux issues, état **fait / partiel / non vérifié matériel**.

## Vague A : terminer la PR #95 — exécution possible maintenant une fois les agents disponibles

**A0 — Coordinateur / intégration, seul responsable des pushes**
Périmètre : #95 et issues associées #80–#105 selon contenu réel ; attribuer les chemins, détecter conflits et dépendances, contrôler l'unicité branche/PR, intégrer les patchs, suivre les critères de recette. Vérifier 132 fichiers déclarés changés au relevé initial, puis recalculer. Aucun merge sans accord utilisateur.

**A1 — Android, navigation et expérience**
Chemins principaux : `app/`, `core/`, `diagnostic/`. Vérifier flux connexion/compte, affichage licence, reprises APK et mises à jour, `/CamaroDiag` avec SAF, UI, fonctionnalités non factices, messages d'erreur et permissions. Revoir les modifications touchant `CamaroDiagApp.kt`, `MainViewModel.kt`, `UpdateManager.kt` en coordination A0. Régressions Android pertinentes et tests unitaires. S'assurer que fonctions non qualifiées restent désactivées.

**A2 — Firebase / identités / licences / confidentialité**
Chemins : `functions/`, `tools/admin/`, `firestore.rules`, configuration Firebase et classes d'auth sous `app/` en coordination A1. Confirmer séparation identité/licence vs données véhicule, absence de diagnostic dans Firestore, autorisations serveur sur toutes Cloud Functions, Google/email, fraîcheur de session, révocation, droits propriétaires, absence de stockage de mots de passe ou de droits falsifiables. Scénarios négatifs et logs sans données personnelles.

**A3 — Windows / pont J2534 / diagnostics**
Chemins : `pc-bridge/`, intégration `communication/` partagée via A0. Valider échanges Android↔Windows, appairage TLS, refus du clair, ISO-TP, CAN/UDS autorisés, gestion timeout/reconnexion, rapport d'erreurs et autodétection. Builds Windows x86/x64, autotests. Aucun flash véhicule revendiqué.

**A4 — Connecteurs et firmware**
Chemins : `connectors/`, `ecu-native/`. Vérifier firmware ESP32-S3/STM32, framing USB, protection anti-rejeu et sécurité transport si implémentées, ségrégation lecture/écriture, compatibilité Android/J2534 et matrice d'interopérabilité prévue dans #81–#93. Compiler et exécuter tests existants. Marquer la validation physique comme bloquée sans banc.

**A5 — CI, releases, GitHub Actions et assurance qualité**
Chemins : `.github/workflows/`, tests et configuration de build en coordination A0. Supprimer uniquement les workflows effectivement remplacés, vérifier triggers, permissions minimales, absence de références PR70/branches périmées, production d'artefacts vérifiés, signature APK et provenance, zéro avertissement bloquant. Faire un bilan cohérent Android/Windows/Connectors à SHA identique au checkpoint final.

### Barrière de fin A
- Périmètre #95 vérifié et documenté ; aucune fonction faussement annoncée comme opérationnelle.
- Tests unitaires et d'intégration pertinents, lint, Android Debug/Release selon politique, Windows x64/x86 et Connectors sans erreur/avertissement bloquant sur SHA unique ; limites matérielles listées.
- Diff et secrets revus, conformité au modèle de PR unique ; approbation utilisateur avant fusion.
- **Ne pas déclencher la vague B simplement sur un commit ou un statut vert partiel.**

## Vague B : audit #106 — missions PRÉPARÉES MAIS BLOQUÉES

**Condition d'ouverture** : #95 terminée, contrôles complets validés, fusion explicitement approuvée puis effective sur `main`. L'audit est d'abord une activité de lecture : ne pas modifier l'issue #106 ni lancer de correctifs avant restitution et priorisation. Démarrer sur le SHA de `main` post-fusion.

**B0 — Consolidation des preuves** : crée une matrice de constats, sévérité, reproductibilité, fichiers/lignes, état réel, propriétaire du lot de correction #108, doublons et dépendances. Ne publier aucune donnée privée.

**B1 — Sécurité et surface d'attaque** : secrets, historique, permissions, dépendances, TLS, pont, transports ECU, sûreté de l'écriture, revues des mécanismes de récupération et des workflows.

**B2 — Android** : architecture, performances, permissions, stockage, navigation, erreurs, obsolescence, tests et fonctions réelles vs simulées.

**B3 — Windows/J2534** : interopérabilité, trames, timeouts, erreurs, sécurité et tests de diagnostic/programming.

**B4 — Matériel/Connectors** : firmwares, protocoles USB/BLE/Wi-Fi/CAN, séparation des privilèges, risques électriques, claims matériels non testés.

**B5 — Firebase/cloud** : authentification, contrôles d'accès, Firestore, Cloud Functions, comptes/licences, vie privée et rétentions.

**B6 — Qualité et traçabilité** : test coverage, GitHub Actions, releases, doublons de workflows, documentation, matrice fonctionnelle et couverture du manuel Camaro 2012 par module.

### Barrière de fin B
Chaque constat : référence vérifiable, sévérité et justification, scénario de reproduction sans secrets, correction recommandée, test de non-régression, lot #108 cible. Déclarer explicitement les points impossibles à vérifier sans matériel ou privilèges. Ouvrir des tickets de correction seulement après consolidation et sans modifier #106 sauf demande explicite.

## Commandes d'orchestration suggérées
1. Affecter les missions A1–A5 en lecture/patch séparés sur le SHA de la PR #95.
2. A0 contrôle et intègre **séquentiellement** les correctifs sur la branche existante ; vérifier le nouveau SHA entre chaque livraison.
3. Lancer les tests ciblés, puis le checkpoint complet commun.
4. Après accord de fusion, A0 constate la fusion et libère B1–B6 sur `main`.
5. B0 centralise l'audit #106 et redistribue les anomalies aux lots de la roadmap #108.

Ce protocole décrit **quoi lancer et sous quelles conditions** ; il ne démontre pas qu'une exécution d'agent a eu lieu.
