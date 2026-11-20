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
