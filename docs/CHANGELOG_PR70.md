# Changelog PR #70 — Intégrité des sauvegardes ECU

Branche : `feat/ecu-backup-integrity` — base `main` après fusion de #54.

## Effectivement développé
- `EcuBackupIntegrity` vérifie la cohérence entre une liste de captures brutes et le manifeste attendu.
- Contrôles : nombre d'entrées, identité région/adresse/taille/origine, SHA-256 des octets, concordance de la lecture indépendante, bilan de couverture recalculé.
- Refus explicite de la provenance matérielle non attestée.
- Tests unitaires pour les données modifiées, la couverture altérée, les métadonnées et l'attestation invalide.
- Refus des segments mémoire superposés ou des plages invalides (couverture incohérente).
- Tests supplémentaires : mutation du tableau d'octets après manifeste, région requise absente et chevauchement.

## Commits
- `4efbad77d0ca86db063153f2c363d45faa593ac8` : vérificateur.
- `a113aec4c006032e83cf71db7fb8e6faa3300e04` : tests.

## État de validation
- Code et tests committés, exécution Gradle et lint **non encore confirmée**.
- PR Draft : **ne pas fusionner avant build Android/Windows approprié sans erreur ou avertissement bloquant**.
- Aucun essai véhicule, aucune sauvegarde physique, aucune écriture ECU réalisés.

## Suite du lot A
- Exiger la carte mémoire cible documentée avant déclaration de complétude.
- Valider matériel, double lecture, manifestes persistés et procédures de récupération sur banc avant toute écriture.


## 20 novembre 2026 — sauvegardes et internationalisation (travaux en cours)
- Les contrôles d'intégrité refusent désormais une sauvegarde sans seconde lecture, même si le manifeste indique lui aussi l'absence de comparaison. Test de non-régression ajouté (`9a7b33f`, `0ef99a4`).
- Le sélecteur de capteurs utilise les ressources Android déjà existantes pour 12 capteurs usuels ; la recherche prend en compte leur libellé dans la langue active (`32536c5`). Les identifiants PID restent inchangés.
- **Non terminé :** extraction des autres libellés codés en dur, audit des écrans et ressources de toutes les locales, tests de placeholders/changement de langue/RTL, vérification des builds Android et Windows. Ne pas fusionner #70 ni fermer #63/#71.
- **CI :** aucun workflow sur la branche #70 n'a été trouvé dans la liste des exécutions interrogée pendant ce passage. Aucun build ou lint réussi n'est revendiqué.

## Renforcement supplémentaire — double lecture et export
- Refus explicite des lectures indépendantes divergentes, même si un manifeste les signale toutes deux comme non identiques (`4f9f7c9`, `6891b87`).
- Validation préalable à l'export d'archive, avant d'écrire le moindre octet : absence de seconde lecture et divergences provoquent un rejet (`71cc462`, `29a7685`).
- Tests d'export adaptés pour confirmer le rejet et la sortie vide.
- Builds/tests CI du dernier HEAD toujours à confirmer avant toute fusion.

## Renforcement du 20 novembre — couverture navigation et archives
- Ajout de 12 traductions de navigation pour chacune des 15 locales initialement limitées à 7 clés : croate, tchèque, danois, estonien, finnois, hongrois, irlandais, letton, lituanien, maltais, polonais, roumain, slovaque, slovène et suédois.
- Test de présence/non-vacuité des 12 libellés principaux dans toutes les 30 locales (`e60cb12`).
- Tests ECU supplémentaires : chevauchement d'adresses refusé à l'export, corruption des octets primaires dans le ZIP refusée à l'import (`464ec1e`).
- Audit partiel : 821 ressources EN et FR ; certaines locales historiques comptent 664 ressources, plusieurs locales nouvelles n'en avaient que 7 avant cette passe. **La couverture complète de 821 traductions dans toutes les locales n'est pas achevée.**
- Validation CI/Android/Windows du dernier HEAD **non confirmée**. Conserver la PR Draft. Ne pas fusionner avant validation complète de #63/#71 et des builds.

## Validation préliminaire supplémentaire — archive hors ligne
- `808c45c` : refuse les archives vides et les propriétés de manifeste inconnues, afin d'éviter les fausses sauvegardes et les métadonnées silencieusement ignorées.
- `7149284` : tests unitaires correspondants.
- Vérification des statuts GitHub et workflows sur `7149284` : aucun statut ni workflow PR retourné. **Aucun build exécuté ou réussi n'est confirmé**.
- La PR reste ouverte et non fusionnée ; internationalisation exhaustive et validation Android/Windows encore incomplètes.

## Nouvelle passe — durcissement du manifeste
- `75dccef` : import ZIP refuse les clés dupliquées dans `manifest.properties` au lieu d'accepter silencieusement la dernière valeur.
- `0031586` : test de régression sur un manifeste modifié contenant une deuxième propriété `count`.
- Vérification locale isolée avec le compilateur Kotlin : la surcharge de `Properties.put` compile et rejette effectivement une clé dupliquée. Cela **n'est pas** un build Android/Windows ni une exécution de la suite Gradle.
- Périmètre non terminé : parité et traduction de l'ensemble des chaînes UI dans les 30 langues, builds complets et recette matérielle.

## Contrôle bloquant de couverture linguistique
- `74959a5` : test de parité des clés de `strings.xml` sur les 30 locales déclarées. Toute clé anglaise manquante provoque un échec explicite avec les 20 premières clés absentes.
- Le test est volontairement **non satisfait actuellement** : plusieurs locales sont incomplètes. Ne pas maquiller cette situation en recopiant le texte anglais comme traduction.
- Priorité : traduire réellement les valeurs manquantes et valider la cohérence des placeholders et le rendu RTL avant fusion.

## Nouvelle passe : cohérence des données, traductions et CI
- `da7eaa4` : copie figée des tableaux d'octets avant le calcul du manifeste et l'export ZIP, pour empêcher une mutation du tampon source de produire un fichier corrompu.
- `a2314d7` : test d'injection d'une modification du tampon source pendant l'écriture de l'archive ; l'archive doit préserver les octets initiaux.
- Ajout de **150 traductions de libellés de connexion/diagnostic** (10 clés × 15 locales) dans les langues de l'UE précédemment réduites au strict minimum. Elles disposent désormais de 29 clés traduites (sur 821 anglaises).
- `6e85d10` : test de présence des 10 libellés de diagnostic dans toutes les 30 locales.
- `8a575ec` : workflow `.github/workflows/pr-unit-tests.yml` pour lancer les tests unitaires Android lors des mises à jour de PR, en gardant le workflow de validation complète séparé. Résultat d'exécution non confirmé lors de la création.
- **Reste bloquant** : traductions complètes (dont locales historiques), tests réels Android 10–16, lint, builds Android/Windows et vérification des résultats. Aucune fusion tant que les critères ne sont pas remplis.

## Poursuite i18n et garantie de non-régression
- Traduction de 10 libellés supplémentaires de diagnostic/identification ECU dans les 15 locales à couverture minimale (150 chaînes au total) : nom ECU, identification absente, données indisponibles, données temps réel, états DTC, freeze frame, journal brut, chaîne cinématique ECM.
- Ces 15 fichiers comportent maintenant **39 chaînes localisées** chacun, contre 7 avant la série d'ajouts. Cela ne représente toujours qu'une petite partie des 821 clés de référence.
- `73bd143` : contrôle de couverture de ces nouveaux libellés sur l'ensemble des 30 locales.
- `f275efd` : alignement des versions GitHub Actions/SDK du nouveau workflow de tests sur celles déjà utilisées par la CI du projet.
- **Pas de succès CI revendiqué** tant qu'une exécution complète n'est pas effectivement retournée pour le SHA visé.

## Nouvelle séquence — contrôle archive, configuration véhicule, langues
- `e6f3c32`, `7b6f71c`, `f0f1df5` : refuse à l'import les captures primaires vides et les lectures secondaires manquantes ; ajoute deux tests de ZIP altérés ; élimine une condition Kotlin désormais redondante.
- 150 nouvelles traductions (`vehicle_setup_header`, génération, millésime, moteur, transmission, région, VIN, scan, readiness et mode 06) réparties sur les 15 langues encore peu couvertes.
- Ces 15 langues disposent désormais de **49 clés** chacune sur 821 clés anglaises ; la traduction exhaustive reste très incomplète.
- `d66f3cf` : test de ressources obligatoire pour ces 10 clés dans les 30 langues.
- Tests Android/Windows/lint à faire réellement sur HEAD. CI non confirmée et PR non fusionnable à ce stade.
