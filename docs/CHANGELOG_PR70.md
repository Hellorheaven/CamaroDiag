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
