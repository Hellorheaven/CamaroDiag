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

## Revue de corrections indépendantes — 23 novembre 2026, session C1/C4

**C1 :** vérification explicite de l'archive à la fin d'une capture et au moment d'un Stop. Les erreurs de vidage et fermeture ne doivent pas être masquées par l'annulation de coroutine : elles sont signalées et interdisent l'export. L'export vérifie également que le contexte d'utilisation, la génération de console, le besoin d'une archive brute et son état d'erreur n'ont pas changé pendant l'écriture du rapport. Régressions ciblées ajoutées au vérificateur d'archive et à la politique d'export.

**C4 :** invalidation de l'historique temporaire au changement de compte, d'adaptateur, de session de pont et après déconnexion ; publication des nouvelles mesures sur le dispatcher UI après recontrôle de génération. Les valeurs d'éthanol et de trims sont affichées en plages min/max uniquement pour les PID réellement répondants ; traductions FR/EN. Le test des caractères de contrôle CSV a été corrigé pour utiliser des tabulations et retours à la ligne réels, avec cas complémentaires pour changement de source et répétition d'horodatage.

**Validation :** une demande unique de campagne C4 Debug ciblée a été commitée sur le chemin prévu par GitHub Actions ; **aucun rapport d'exécution n'a pu être confirmé ici**. Le HEAD a encore changé depuis cette demande du fait des corrections et des travaux parallèles. Ces commits ne sont donc pas considérés comme validés par le simple dépôt de la demande. Une validation de l'exact HEAD stabilisé reste obligatoire avant fusion.

**Dépendances non résolues :** la restauration physique C2, les transports standard de l'autre chat et la recette véhicule restent séparés de ces correctifs. Aucun merge, nouvelle branche ou écriture ECU physique.

## C1 — audit d'exhaustivité de l'historique de console

- La console **affiche actuellement les 300 derniers événements TX/RX/SYSTEM**, tandis que la capture brute RX peut être conservée intégralement dans un fichier distinct. L'ancien historique de transmission TX dépassant 300 entrées **n'est pas encore archivé**.
- Les exports et l'écran l'indiquent désormais explicitement en FR/EN ; le type d'en-tête RX distingue un flux archivé intégral d'un simple extrait de la fenêtre mémoire. Contrôle spécifique des chaînes ajouté à `LocaleResourcesIntegrityTest`.
- Test C1 supplémentaire : 6 000 lignes de capture divisées arbitrairement en fragments, soit plus que 128 Kio, restent intégralement écrites dans l'archive.
- **Point bloquant de clôture de #59 :** journal chronologique persistant de tous les événements TX/RX/SYSTEM (sans dépendance aux protocoles standard), puis export de l'intégralité de ce journal avec le flux RX brut, avec contrôles d'erreurs d'E/S, tests longs et qualification physique.
- Aucun test nouvellement commité n'est déclaré réussi à ce stade. La campagne C4 demandée auparavant ne prouve pas la validation des modifications suivantes du HEAD.

## Contrôle statique final de cette session C1/C4
- Test de capture CAN longue : import JUnit manquant corrigé, et test ajusté pour émettre de **vrais enregistrements CRLF** fragmentés (`CanConsoleRawArchiveWriterTest.kt`), toujours sans exécution annoncée.
- Ressources d'interface et d'export C1 : présence unique en français et en anglais des huit clés contrôlées ; écran et ViewModel référencent bien les nouvelles clés / vérifications de contexte.
- C4 : le moteur rejette les mesures dont la réponse physique brute est absente/vide, indépendamment de la valeur d'un tampon déjà décodé. Les deux tests concernés ont été ajoutés.
- Le contrôle ici est **statique**, non une compilation Android ni une preuve de réussite de GitHub Actions. Les validations sur un SHA stabilisé et la clôture de #59 restent à mener.

## C1 — suivi après intégration du journal complet (23/11/2026)

- Le blocage « historique TX au-delà de 300 lignes absent » a reçu une implémentation : `CanConsoleEventJournalWriter` (journal CSV complet), `CanConsoleEventJournalStore` (fichier privé par contexte) et branchements `MainViewModel` pour les événements monitor RX, émission TX, réponse RX/SYSTEM et erreurs.
- L'écran continue à afficher 300 événements récents, mais l'export peut désormais inclure tous les événements conservés dans le journal disque et le flux RX brut complet. La génération du journal est vérifiée à l'export ; les erreurs de stockage rendent l'archive indisponible.
- Couverture unitaire écrite : 600/620 événements, ordre, échappement, échec de stockage et nettoyage inter-session. Chaînes d'export FR/EN ajoutées.
- Une demande de campagne C1 regroupée a été déclenchée par la modification de `.github/c1-console-gate-request.txt` (commit `9bdc221`). **Résultat de CI non encore vérifié : ne pas déclarer C1 validé au nouveau HEAD.**
- Toujours à qualifier : endurance sur matériel réel, correspondance fiable TX/ACK/réponse ECU, comportement en déconnexion et volumétrie, puis recette de fermeture de #59. Aucun transport ISO/SAE supplémentaire modifié par cette intervention.

## C8 — contrôles indépendants des protocoles, session du 23 novembre 2026

- **C1 :** CSV d'événements séquentiel non limité à la fenêtre UI de 300 événements ; `TX/PENDING` séparé des résultats `RX/SYSTEM`. Le magasin de fichier est définitivement désactivé à la fermeture du ViewModel et refuse la perte du fichier entre deux événements.
- **C2 :** les régions du paquet de restauration hors ligne sont limitées à 64 Mio chacune / 256 Mio au total sans dépassement numérique ; l'index de l'archive portable n'est publié qu'après validation des membres et du footer ZIP, puis relecture du paquet. Cela ne démontre ni programmation ni restauration de l'ECU.
- **C4 :** l'export de l'historique de mesures carburant exige une série complète et inchangée, hors acquisition, sans interpréter les PID absents comme 0 % ni présumer de l'activation éthanol.
- **Tests :** suites unitaires dédiées ajoutées ; requêtes C1/C2/C4 déclenchées via les fichiers de contrôle des workflows. Aucune conclusion sur la réussite des jobs pour le nouveau HEAD n'est permise sans consultation explicite de GitHub Actions ; la compilation globale C8 reste à faire après fin de l'autre intervention protocoles.
- **Périmètre :** aucune modification des transports ISO/SAE, J2534, DoIP, K-Line, CAN-FD, J1939 ni des firmwares connecteurs par cette intervention. PR #95 reste Draft ; aucun merge/Release.

## C8 — revue supplémentaire des archives C1/C2 (23/11/2026)

- **C1 :** le contrôle d'export vérifie la génération, le nombre d'événements et désormais la longueur du journal persistant ; un fichier tronqué pendant l'export devient invalide. La seule égalité de taille n'est pas un hash d'intégrité ni une preuve de réception physique.
- **C2 :** l'export d'un paquet de restauration contrôlé hors ligne ne peut pas annoncer une réussite lorsque des fichiers sources changent pendant la copie. Il compare les digests lors de la copie puis le contenu des images vérifiées, par région, avec un pic mémoire borné plutôt que de rouvrir le paquet entier en une deuxième collection d'images.
- **Limite :** tous ces contrôles sont sur fichiers locaux ; aucun write/erase, protocole constructeur, plan mémoire exhaustif ou récupération d'ECU physique n'a été validé.
- **Tests** : tests de troncature du journal et de mutation de l'index/région pendant export ajoutés. Résultats GitHub Actions sur le dernier HEAD toujours à vérifier. Les travaux standards transport restent à l'autre chat.

## C7/C8 — intégration des liaisons Android, USB et pont Windows hors protocoles (23/11/2026)

- **Routage USB :** CamaroDiagConnectors est reconnu en sélection uniquement à partir du nom de périphérique énuméré. La liste UI est contrôlée avant de remplacer une session fonctionnelle, puis le périphérique est revérifié par le gestionnaire USB. Les équipements KKL et USB génériques ne reçoivent pas les probes ASCII ou CD/1. La découverte est publique et non authentifiée ; elle ne confère aucun droit ECU.
- **UI :** les boutons de découverte ne sont plus affichés pour chaque adaptateur USB. L'action CDJ/1 physique non implémentée a été retirée plutôt que d'afficher un export trompeur.
- **Autorisations Android :** la demande d'autorisation USB est distinguée des vrais échecs de transport avec message FR/EN et nécessité de confirmer le dialogue Android avant reconnexion.
- **J2534 :** configuration IPv4 LAN, TCP, appairage TLS avec empreinte et paires CAN 11/29 bits validée avant de déconnecter l'ancien adaptateur ; le transport refait ses vérifications sécurisées. Les indicateurs de lecture mémoire se réinitialisent à la déconnexion ; le handshake est intégré au compteur global busy.
- **Autres transports :** saisies Wi-Fi et MAC Bluetooth refusées en amont si elles sont malformées.
- **Validation attendue :** compilation et suites unitaires Android/communication/Windows au HEAD stable ; essai matériel USB permission/discovery sur matériel identifié, PC J2534 TLS réels et transitions déconnexion/reconnexion ; aucun succès matériel n'est revendiqué. L'autre chat conserve les protocoles et firmwares.
