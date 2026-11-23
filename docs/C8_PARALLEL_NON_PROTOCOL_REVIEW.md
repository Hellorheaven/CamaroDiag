# PR #95 — revue C8 des travaux indépendants des protocoles (23/11/2026)

## Objet et état observé

- PR : [#95](https://github.com/Hellorheaven/CamaroDiag/pull/95), `chore/unified-ci` vers `main` ; toujours **draft et non fusionnée** lors de cette revue.
- Inventaire de **253 fichiers modifiés** dans le diff à la date de ce contrôle. Les chiffres sont des comptes de chemins, pas une certification de qualité ni une séparation du code.
- Un second chantier prend en charge l'implémentation des protocoles standard K-Line, DoIP, CAN-FD et SAE. **Ce document ne valide pas leur implémentation et ne demande aucune modification simultanée de ces chemins.**

## Classification initiale du diff, à revoir fichier par fichier avant fusion

| Lot | Chemins représentatifs | Décision provisoire |
| --- | --- | --- |
| C1 | `CanConsoleRawArchiveWriter.kt`, console Android/Windows | Garder le socle TX/RX et la persistance de captures, sous réserve des tests finaux |
| C2 | `EcuBackupArchive.kt`, `J2534LiveMemoryBackup.kt`, `EcuRestoreImage*.kt`, `EcuRestorePackage.kt`, `EcuRestorePortableBundle.kt` | Garder les lectures/images **hors ligne** ; ne pas annoncer une restauration réelle avant preuve |
| C4 | `Lfx2012FuelReadOnly.kt`, `Lfx2012FuelSessionHistory.kt` | Garder la lecture seule et les tests ; les fonctions d'écriture FlexFuel demeurent hors périmètre |
| C6 | `EcuWriteConsentFlow.kt`, contrôles d'intégrité avant opérations et liaison Android/Windows | Garder le consentement double et les véritables exigences OEM, sans verrou artificiel |
| Protocoles parallèles | Transport K-Line, DoIP, CAN-FD, SAE, adaptation J2534 | **Réserver à l'autre intervention** ; vérifier l'intégration seulement quand son chantier sera terminé |
| C3/C5/C7 à reporter | Pilotes de programmation physiques non qualifiés, firmwares/connecteurs étendus, i18n complète | Analyser les dépendances communes et isoler sans revert aveugle |
| Hors périmètre potentiel | `.github/workflows/release-apk.yml`, `.github/workflows/release-validation.yml`, `CamaroDiagReleaseAssetPolicy.kt`, backend comptes/licences | Revue explicite d'utilité pour PR #95 ; conserver les fondations nécessaires, reporter les fonctions sans lien prouvé |

## Points de contrôle C8 non résolus

1. **C2** : les ZIP et images produits représentent les régions réellement capturées ; aucune preuve de lecture de *toutes* les zones OEM, ni de restauration physique, de reprise BOOT/BAM/BDM ou de compatibilité par firmware.
2. **C4** : l'historique des observations reste un module logiciel. Aucun affichage utilisateur de cet historique ni essai de capteur/ECU réel attesté. Une lecture de PID 52 ne prouve pas l'activation E85.
3. **C1** : contrôler réception réelle, émission et historisation sur interface physique ; vérifier l'association TX/RX sans confondre accusé du pilote et réponse ECU.
4. **C6** : vérifier une fois le pilote réel disponible que le premier écran « risque / OK » est suivi d'une confirmation distincte « Oui / Non » ; aucune émission sur Non, fermeture, annulation ou changement de session. Les vérifications techniques liées au format, à l'intégrité et au contrôleur ne sont pas des licences.
5. **Diff/CI** : campagne Debug complète à l'exact HEAD **après** la fin des chantiers, inspection des warnings et jobs skipped, revue des changements hors lot, secrets, licences et dépendances. Pas de compilation Release ni fusion sans accord.
6. **Synchronisation** : documenter les commits réellement testés et leur SHA ; ne jamais convertir une réussite ancienne en preuve pour le HEAD actuel.

## Travaux pouvant continuer sans protocole

- Tester et compléter les traitements **hors ligne** des images et archives, dont annulation/fichiers tronqués et rapports de couverture.
- Développer les calculs de tendances des PID carburant **déjà lus**, conserver les mesures indisponibles comme indisponibles, et éviter toute inférence sur FlexFuel activé.
- Revoir les tests UI/documentation FR/EN sur les chantiers C1/C4 et le diff C8, sans toucher aux transports ni rendre opérationnelle une écriture non qualifiée.

**Statut de cette revue** : inventaire et priorisation documentés ; aucune compilation ou campagne CI du nouveau HEAD exécutée au titre de cette revue. Aucune fusion autorisée.
