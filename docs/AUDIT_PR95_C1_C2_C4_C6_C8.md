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

### Complément physique demandé par le propriétaire (23/11/2026)

Les premières modifications comparaient les images et les trames préparées, **mais ne créaient pas encore de sauvegarde indépendante des blocs réellement lus**. Le code a donc été renforcé sur les deux moteurs existants :
- **Android** : `EcuWriteTransaction` double lit chaque secteur live ; `PersistentEcuPhysicalBlockBackup` capture les octets de la première lecture physique dans un fichier **create-new**, synchronisé sur disque, relu et comparé, associé au fichier journal et à l'identité ECU. La construction du magasin est imposée par l'entrée matérielle publique ; aucun effacement si la sauvegarde échoue. Les tests internes utilisent seulement un magasin isolé en mémoire pour les faux pilotes.
- **Windows E39/E39A** : `NativeE39WriteSequence` exige maintenant `IDurableJournal.PreserveOriginalBlockAsync` sur les octets de l'ECU à la suite des vérifications live, avant le moindre erase. `NativeE39FileWriteJournal` archive séparément chaque secteur avec adresse, longueur, SHA, fsync et relecture ; le `CompletionGuard` relaie le dépôt durable. Les tests de banc simulent aussi ces sauvegardes.
- **Important** : la capture du bloc ne démontre pas que l'ECU réel est récupérable, que le firmware constructeur accepte la programmation, ni que la carte mémoire est exacte. Exiger une preuve sur banc avant toute affirmation matérielle.

## Matrice des chantiers

| Chantier | Code et chemins confirmés | Campagne déjà réussie avant les derniers commits | Restant et conditions de clôture |
|---|---|---|---|
| **C1 — console CAN/OBD** | `CanConsoleRecordParser.kt`, `CanConsoleDisplayFilter.kt`, `BluetoothObdManager.kt`, `CamaroDiagApp.kt`, `BridgeDesktopForm.Console.cs` : TX/RX, trames brutes 11/29 bits, contrôle de session et confirmation OK puis Oui/Non. | [C1 #45071294652](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45071294652). | **Logiciel présent**, pas de preuve d'émission/capture CAN sur véhicule et matériel réel. Vérifier à nouveau au HEAD final. |
| **C2 — sauvegarde ECU** | `J2534LiveMemoryBackup.kt`, `NativeScopedMemoryReader.cs`, `EcuBackupArchive.kt`, `NativeScopedBackupImport.cs` : périmètre VIN/région, double lecture, archive/intégrité et réimport ; vérificateurs de bloc ajoutés pour les voies d'écriture projetées. | [C2 #45115280714](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45115280714). | **INCOMPLET pour le but physique** : capture durable par secteur ajouté au moteur d'écriture, mais aucune preuve de sauvegarde complète, image intégralement restaurable, géométrie exacte ni programmation de récupération validées sur ECU réel. Ne pas déclarer « sauvegarde complète fonctionnelle ». |
| **C4 — FlexFuel LFX en lecture** | `Lfx2012FuelReadOnly.kt`, `Lfx2012FlexFuelPreflight.kt`, UI et textes FR/EN : PID éthanol et trims seulement si réponse réellement obtenue. | [C4 #45116744258](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45116744258). | **Lecture seulement** ; PID 52, capteur et compatibilité physique non attestés. Activation E85 / calibration / flash non développés : suivre les tickets après PR et tests réels. |
| **C6 — sûreté du pont et de la programmation** | `BridgeServer.cs`, `J2534BridgeTls.kt`, `UdsClient.kt`, `EcuWriteConsentFlow.kt`, `EcuWriteOperatorConsent.cs`, `NativeE39WriteSequence.cs` : TLS/appairage, vraies exigences OEM, suppression des verrous artificiels, avertissement OK puis Oui/Non, vérification du bloc de flash. | [C6 #45129685889](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45129685889) (avant renforcement des blocs). | Sauvegarde préalable des secteurs live et correspondance exacte des paquets de programmation ajoutées ; nouvelles vérifications à tester au HEAD final ; pas d'essai physique ni de restauration démontrée. |
| **C8 — contrôle final / intégration** | Workflows `.github/workflows/pr-unit-tests.yml`, `connectors-ci.yml`, `native-ecu-kernel.yml` ; tests Android, Windows, ECU natif, connecteurs et Cloud Functions. | Campagnes ciblées ci-dessus, **mais aucune campagne C8 intégrale récente au dernier HEAD** constatée lors de l'audit. | **EN COURS :** campagne globale [#45133010915](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45133010915) a confirmé Android, backend, Windows et connecteurs, mais échoué sur la compilation de l'intégration managed du noyau pour un fichier C# de vérification non référencé. Référence corrigée dans `NativeKernelIntegration.csproj` (commit `a598d86`). Revalider **au HEAD final**, vérifier conflits/secrets/périmètre avant toute clôture ; ne pas faire de Release ni fusionner sans accord. |

### Décision propriétaire — C6 sans verrou artificiel d'écriture

C6 doit **permettre les écritures effectivement implémentées et compatibles**, pas réintroduire un mode global lecture seule. Aucun certificat de programmation CamaroDiag inventé, whitelist ECU/pilotes, interrupteur d'écriture désactivé par défaut ou autorisation supplémentaire ne doit être ajouté. Les anciennes restrictions génériques figurant dans l'historique documentaire sont obsolètes.

Le consentement utilisateur OK puis Oui/Non, la cohérence VIN/ECU, la sauvegarde préalable des octets des secteurs modifiés, les vérifications mémoire/checksum et les échanges OEM réels sont maintenus. Une capacité physique absente est un développement restant à accomplir, **pas** un motif pour bloquer artificiellement toutes les fonctions d'écriture. C2 et C6 doivent avancer sans toucher aux protocoles développés dans l'autre chat.

## C6 — reprise explicite des contrôles de session et d'autorisation (23/11/2026)

**C6 reste un chantier actif et non clos.** La couche de sûreté logicielle ne constitue pas une preuve d'écriture ni de récupération physique.

- La confirmation de suppression des DTC normalisés lie désormais l'avertissement initial et le « Oui » à la **même génération d'adaptateur**. Une déconnexion, reconnexion, perte de liaison ou modification du compte invalide la confirmation, même si une autre interface est déjà active.
- Le ViewModel vérifie à nouveau cette génération avant d'émettre la commande de suppression ; le protocole de suppression n'a pas été modifié.
- Une déconnexion générale depuis l'écran Adaptateur s'applique aux liaisons USB, Wi-Fi, BLE/Bluetooth, pont J2534 et mode simulé, avec arrêt et jonction des opérations de capture pour éviter d'utiliser un transport obsolète.
- Tests de régression ajoutés dans `EcuWriteConsentFlowTest` : avertissement seul, refus, absence de contexte et changement de génération.
- Restent bloquants pour déclarer C6 terminé : validation du consentement sur l'ensemble des commandes potentiellement modifiantes, contrôles réels des octets/blocs programmés, sauvegarde restaurable avant effacement, test du rollback sur ECU, compilation/tests complets au HEAD stabilisé. **Aucune clé ou licence de programmation artificielle ajoutée.**

### Complément C6 : refus/déconnexion et changement de VIN

- La boîte de suppression DTC efface la génération de connexion et l'identité véhicule mémorisées sur **Non**, fermeture et changement de contexte. Même si l'écran tarde à se rafraîchir, le ViewModel compare les deux valeurs à la cible courante avant d'autoriser une demande physique.
- `EcuWriteConsentFlow.approvalForContext` refuse les changements de session ou de véhicule. Tests unitaires de ces cas ajoutés.
- Le formulaire J2534 n'expose plus les éléments du code d'appairage TLS lors de sa conversion en texte ; le pont est affiché comme appairé au PC, ce qui n'implique pas automatiquement une capacité ECU.
- Ne pas considérer ces validations applicatives comme la preuve de sauvegarde, effacement, réécriture des blocs, tests post-flash ou récupération physique : **C6 reste en cours**.

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
