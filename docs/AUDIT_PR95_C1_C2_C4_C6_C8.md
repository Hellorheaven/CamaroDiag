# PR #95 — Audit de reprise des chantiers C1 / C2 / C4 / C6 / C8

**Révision de l'audit : 23/11/2026, après les dernières décisions du propriétaire.**
**Ce document reflète une revue du code, du périmètre GitHub et des anciennes campagnes de tests.** Aucune qualification véhicule physique ne résulte d'un test automatisé. Avant toute conclusion de livraison, exécuter la campagne globale Debug au **HEAD final** et consulter ses jobs (sans se fier aux seuls anciens SHAs). PR unique : [#95](https://github.com/Hellorheaven/CamaroDiag/pull/95), branche `chore/unified-ci`, **non fusionnée**.

## Vérification transversale de l'écriture ECU — exigence non négociable

- Contrôler l'image d'origine, l'image cible, la cible physique ECU/VIN/OSID, la cartographie et les secteurs exacts.
- **Avant chaque effacement** : secteur/région/adresse/taille et octets de référence liés à l'image cible ; comparer au contenu réellement relu sur ECU.
- **Avant chaque bloc envoyé** : adresses et tailles **encodées dans la trame** correctes, octets strictement égaux aux modifications demandées, checksum correct, couverture du secteur sans trou, chevauchement ni modification des voisins. Réaliser une lecture indépendante après écriture.
- Android : `EcuWriteBlockVerifier.kt` + `EcuWriteTransaction.kt`, contrôles sur le secteur et chaque unité de programme ; tests `EcuWriteBlockVerifierTest.kt`.
- Windows E39 : `NativeE39WriteBlockVerifier.cs` + `NativeE39WriteSequence.cs`, vérification des vraies trames `0x36` avant transmission ; autotests `NativeE39WriteSequenceSelfTests.cs`.
- Ceci n'invente **ni une clé fabricant ni une permission CamaroDiag**. Les véritables exigences OEM et les limites physiques restent en vigueur.
- Limite majeure : une commande RAW CAN/UDS libre n'a pas nécessairement de région mémoire documentée. **On ne peut pas vérifier adresse/secteur du bloc cible pour toute commande arbitraire à partir du seul SID** ; la voie de programmation planifiée doit posséder la vraie cartographie ECU. Ne jamais revendiquer ce contrôle pour une trame libre non interprétée.

## Matrice des chantiers

| Chantier | Code et chemins confirmés | Campagne déjà réussie avant les derniers commits | Restant et conditions de clôture |
|---|---|---|---|
| **C1 — console CAN/OBD** | `CanConsoleRecordParser.kt`, `CanConsoleDisplayFilter.kt`, `BluetoothObdManager.kt`, `CamaroDiagApp.kt`, `BridgeDesktopForm.Console.cs` : TX/RX, trames brutes 11/29 bits, contrôle de session et confirmation OK puis Oui/Non. | [C1 #45071294652](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45071294652). | **Logiciel présent**, pas de preuve d'émission/capture CAN sur véhicule et matériel réel. Vérifier à nouveau au HEAD final. |
| **C2 — sauvegarde ECU** | `J2534LiveMemoryBackup.kt`, `NativeScopedMemoryReader.cs`, `EcuBackupArchive.kt`, `NativeScopedBackupImport.cs` : périmètre VIN/région, double lecture, archive/intégrité et réimport ; vérificateurs de bloc ajoutés pour les voies d'écriture projetées. | [C2 #45115280714](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45115280714). | **INCOMPLET pour le but physique** : pas de preuve de sauvegarde complète, image intégralement restaurable, géométrie exacte ni programmation de récupération validées sur ECU réel. Ne pas déclarer « sauvegarde complète fonctionnelle ». |
| **C4 — FlexFuel LFX en lecture** | `Lfx2012FuelReadOnly.kt`, `Lfx2012FlexFuelPreflight.kt`, UI et textes FR/EN : PID éthanol et trims seulement si réponse réellement obtenue. | [C4 #45116744258](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45116744258). | **Lecture seulement** ; PID 52, capteur et compatibilité physique non attestés. Activation E85 / calibration / flash non développés : suivre les tickets après PR et tests réels. |
| **C6 — sûreté du pont et de la programmation** | `BridgeServer.cs`, `J2534BridgeTls.kt`, `UdsClient.kt`, `EcuWriteConsentFlow.kt`, `EcuWriteOperatorConsent.cs`, `NativeE39WriteSequence.cs` : TLS/appairage, vraies exigences OEM, suppression des verrous artificiels, avertissement OK puis Oui/Non, vérification du bloc de flash. | [C6 #45129685889](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45129685889) (avant renforcement des blocs). | Nouvelles vérifications à tester au HEAD final ; pas d'essai physique ni de restauration démontrée. |
| **C8 — contrôle final / intégration** | Workflows `.github/workflows/pr-unit-tests.yml`, `connectors-ci.yml`, `native-ecu-kernel.yml` ; tests Android, Windows, ECU natif, connecteurs et Cloud Functions. | Campagnes ciblées ci-dessus, **mais aucune campagne C8 intégrale récente au dernier HEAD** constatée lors de l'audit. | **À FAIRE :** une campagne finale Debug sur le HEAD gelé, examiner tous les jobs (y compris lint et connecteurs), code/portée de la PR, secrets, avertissements et conflits ; ne pas faire de Release ni fusionner sans accord. |

## Anomalies / travaux restant ouverts

1. **Pilotes physiques absents :** `EcuProgrammingDriverContract.registeredHardwareDrivers = emptyList()` ; les voies de programmation Android ne peuvent pas physiquement écrire. Les connecteurs ESP32-S3/STM32 et les chemins autres que banc E39 ne disposent pas de support flash complet démontré. [Issue #109](https://github.com/Hellorheaven/CamaroDiag/issues/109).
2. **C2 / restauration :** l'import d'une archive et une double lecture ne démontrent ni l'exhaustivité des mémoires ECU ni une restauration réelle. Banc physique avec échantillons connus indispensable.
3. **Protection de bloc :** les vérificateurs ajoutés vérifient le contrat d'écriture des moteurs planifiés ; **pas** une garantie de la bonne cible pour toute trame RAW non décodée. Développer les pilotes et profils constructeur avant de présenter cette garantie dans l'interface utilisateur.
4. **Écrans de calibration :** le bouton de flash complet reste inactif tant qu'aucune vraie opération n'existe. L'activer sans moteur matériel rendrait l'interface trompeuse.
5. **Périmètre de PR étendu :** le diff inclut les connecteurs, le backend Firebase, les releases et les ressources UI ; distinguer fondations réellement nécessaires à C1/C2/C4/C6, travaux C3/C5/C7 reportés et modifications hors périmètre. L'inventaire/isolement final de ce diff est un point de C8 non résolu par la simple réussite des unitaires.
6. **Internationalisation :** règle propriétaire de textes UI en ressources FR/EN actuellement sur Android/Windows/Connectors ; audit du code existant sur littéraux visibles et parité FR/EN encore nécessaire avant déclaration de conformité globale.
7. **Consentement :** sur les voies déjà accessibles, avertissement OK puis Oui/Non avant toute opération utilisateur potentiellement modifiante ; les futurs pilotes et transports doivent le respecter. L'utilisateur doit choisir Oui ; un autre choix n'autorise rien.

## Gouvernance et état de livraison

Une seule branche/PR, **tests regroupés après fin du chantier**, pas de CI à chaque commit. Ne pas annoncer C1–C8 comme « entièrement validés sur voiture » sans preuves. Les tests C1/C2/C4/C6 antérieurs sont des preuves **logicielle** de ces états, pas des validations matérielles ni du HEAD final. Aucun merge sans demande explicite du propriétaire. Mise à jour des règles transversales : [AGENTS.md](../AGENTS.md), [instructions Copilot](../.github/copilot-instructions.md), [PROMPT_REPRISE](PROMPT_REPRISE.md).
