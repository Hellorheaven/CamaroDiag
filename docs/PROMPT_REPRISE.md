## PR117 — clôture logicielle DoIP / sûreté C6 (26/11/2026, reprise de 11 h 37 CET)

- PR unique #117 sur `chore/unified-ci`. Campagne de clôture logicielle DoIP en cours : [GitHub Actions #45805147429](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45805147429), **SHA exact testé `e3b5f2a5a0ef704da7a5d622ef8b11116f337d96`**. Dernière observation : Debug Windows **succès** (y compris nouvelle régression de concurrence E39), Python 3.11/3.12/3.13 **succès**, tests ECU natifs/parseurs/USB et firmware ESP32/STM32 **succès** ; Android `unit-tests` encore **en cours** à ce point de reprise. Ne jamais marquer DoIP clôturé avant le verdict complet du job Android.
- Côté diagnostic Android : correction du simulateur LFX pour attacher les données Mode 01, le DTC Mode 02 et ses données freeze-frame au même répondant simulé CAN `7E8`, sans assouplir l'attribution physique des vrais véhicules. Ajout du test `freezeFrameDtcAndValuesRemainPinnedToSyntheticPhysicalEcu`. Commits `e7720e4`, `f07bb76`, correction syntaxique `520750d`.
- Côté app Android : compilation corrigée pour le vrai namespace de `UdsNegativeResponseException` (conservation des NRC) et l'import `androidx.compose.foundation.clickable` de la carte DTC. Commit `e0730ea`.
- C6 Windows : `NativeE39WriteSequence` acquiert un verrou non bloquant **par instance de liaison IKernelLink**, empêchant deux écritures concurrentes sur la même session ECU, sans bloquer les liaisons indépendantes. Un `using` libère le verrou même après annulation et erreur ; les nouvelles régressions utilisent un journal suspendu et une annulation de préflight. Commit `7b6c904`. Les autotests Windows du gate ci-dessus sont **verts en simulation** ; aucune qualification ECU physique ou pilote flash n'est revendiquée.
- Les passages antérieurs de clôture DoIP ont mis au jour puis fait corriger : Python fixtures inventaire/monitoring, doublon de ressources FR/EN, six décodeurs C# `ReadOnlySpan`/await, test de commande UDS, packages Kotlin réconciliés, ancien constructeur LFX et absence de provenance freeze-frame. Ne pas réappliquer les variantes historiques. La correction du chemin absolu `--rx-file` est `fd642d3`.
- **Reprise suivante :** lire l'état de la campagne #45805147429. Si le job Android échoue, récupérer ses logs et, si nécessaire, l'archive JUnit, corriger les erreurs de manière groupée avant **une unique nouvelle campagne de clôture**. Si tous les jobs réussissent, enregistrer le périmètre logiciel DoIP comme validé **uniquement sur le SHA de la campagne** ; les tests matériels restent pour la Release. C6 reste actif : compléter les vrais pilotes et l'intégration des capacités ECU/transport selon [#109](https://github.com/Hellorheaven/CamaroDiag/issues/109) et les exigences [#110](https://github.com/Hellorheaven/CamaroDiag/issues/110) avant clôture du chantier. C8 reste final, ni merge ni Release sans accord.
- Aucune nouvelle PR, branche, fusion ou Release ; tout reste sur `chore/unified-ci`. La modification de ce document de reprise après le SHA du gate n'affecte pas les sources testées.

## PR117 — DoIP Python : refus corrélés, inventaire progressif et verrou VIN (26/11/2026)

- C6/DoIP : correction fonctionnelle du transport Python. `Session.exchange_sequence` était resté en appels `exchange` séparément verrouillés malgré les déclarations antérieures ; il partage désormais réellement `_exchange_owned` avec l'échange simple et garde un verrou unique pour tout le lot, y compris les callbacks. `expected_vin` impose une relecture ECU F190 sous ce verrou avant toute commande, avec fermeture sans émission mutante en cas de divergence ou réponse invalide. `doip_cli` transmet ce VIN ; annulation, échec ou callbacks incertains retirent la session. Tests mock et socketpair/loopback ajoutés mais non exécutés.
- Refus réseau Python corrigés : `TransportRefusal(layer, code)` dissocie NACK d'en-tête validé, routage refusé, NACK diagnostique et `NegativeResponse` UDS (SID/NRC). Validation de version avant classification ; ACK de mauvaise adresse/écho ou code positif invalide reste une erreur de protocole. Un NACK idle n'est jamais imputé à l'échange suivant. L'inventaire et `main()` émettent `failure_code`, `reason`, et les champs UDS le cas échéant, sans retour réussi fictif.
- `inventory_ecus` émet `on_progress` uniquement après clôture du socket ECU et nouvelle vérification de l'identité de passerelle ; copie détachée des lignes et conservation des codes d'erreur/timeout, arrêt en cas d'échec de callback, trace ou changement de passerelle. La CLI sérialise le statut `inventory_progress` en JSON partiel sur stderr avant de rendre le résultat final seulement après fermeture du journal. Les régressions précédemment rédigées ont été réconciliées et complétées ; aucune exécution de test, compilation ni CI n'a été déclenchée.
- C6/DoIP toujours **en cours**. Aucun protocole nouveau, fusion ni Release. Poursuivre les fonctionnalités applicables et réservations de sessions ; réserver la campagne de validation au seul critère de clôture défini dans AGENTS.md.
- Commits de ce lot : 829af5bcb680646886e8b66be4ad841dee229e11, 6a98e555c4d9de141a4b7910d2f4bd8ce905ff92, 14081b0dc638aa270cf7b45d57304b4e8e6b92dd, b7ae7c59292fbf9e5f68755c603d8cf4ecb52bc7, c525ccca86328bce703736aab8d88e5087cae61c, fec6c8ca00420da0b0307b84e30dfc93c560b867, 6298bebc2d2ac7fbfc893d3ae73c88dc8f8b8eb4, 3d3e848a23f577c26ac7d0b623c1d3c39119310a, 53d9d5e6121d5a251d0a1c721f0ee09a33dc3a23, 728bff045cd5eb3938f7cc4c0cee2c21f5beadf7, 7e56382c30af539e13dd030f0d9cdfe63f6fa08d, 78ed1abf4f0f681a2e5c151c6d89f019f3f9a1cc.

## PR117 — consolidation des sources Android et progression DoIP (26/11/2026)

- C6 / DoIP : les répertoires fautifs `com/camaradiag/` créés lors de reprises concurrentes coexistaient avec les sources `com/camarodiag/`, avec le **même package Kotlin**. Réconciliation atomique, blob/SHA vérifiés : huit sources `communication/main` (DoIP, sauvegardes/ZIP, ELM, Bluetooth, PID et échecs transport), cinq tests `communication/test`, quatre sources `app/main` (ViewModel, UI, scan et calibration), les moteurs DTC multitrames `diagnostic/main`, et leurs suites de test. Les deux fichiers orphelins `ObdCanMonitorRecords` (source et test) sont également dans le chemin canonique ; aucune copie `com/camaradiag/` ne reste dans l'arbre Git. La version `SafeObdRequestPolicy.kt` conservée est celle qui préserve les validations multiréponses `parseAll` et DTC numérotés (la copie raccourcie a été écartée).
- Android : `MainViewModel.readDirectDoIp` préserve les réponses déjà vérifiées dans l'écran et distingue le résultat final de l'état partiel ou annulé. Le message de complétude est émis seulement après retour de `DoIpDirectRead.read` (socket fermé) avec tous les retours attendus ; la version FR/EN indique `X/Y réponses vérifiées`. `DoIpCommandProgress` refuse index hors ordre, duplication, réponse vide ou complétude prématurée, avec deux tests JVM écrits. Les consentements existants et contrôles de génération de session sont préservés.
- Vérification statique de l'arbre Git réalisée : zéro fichier sous un chemin `/camaradiag/` dans le HEAD après consolidation ; aucune compilation, test JVM, CI ou qualification matérielle exécuté. Refaire les vérifications de compilation Android/Windows/Connectors **uniquement à la clôture du protocole ou chantier**. C6 et DoIP restent ouverts. Les anciennes copies restent accessibles dans l'historique Git mais ne font plus partie des sources du HEAD.
- Commits : 121c4379120c6e4621f47e18a5adcd9045cb28bc, cc6e9c4fd18c47477e3b5735c7594c8fc71e5d6e, 2cc99c61f8f55d7c4b3e68cc41eb14d5baebcd70, f60226e0374471ace016e3b9edfa0bb262b61274, 0f2d21450665b2cd61dd6d741a4dee99b32b57a3, f8fd457173ed3100bf9f3045340e48f6c2aa81dc, a7974fcde21dd0c76df4a67dd56bdc7bdf75e341, 8eb366041b4866d4d848319025a920cb5f2ec8a9, a5f6443c1bd5e4b8d65e4f7feae475bc8bf01447, 31b7072c2bb6fa016990467035d0bf625c3265a3.

## PR117 — séquences DoIP et publication après fermeture, Windows/Python (26/11/2026)

- C6 / DoIP : Python CLI `sequence` émet désormais chaque réponse validée sur stderr comme `sequence_progress` avec `partial:true`, `complete:false`, numéro, réponse hexadécimale, VIN, ECU, testeur, passerelle, transport et compteur. La réussite `sequence_complete:true` n'est publiée qu'après fermeture réussie de la session et des traces ; en cas d'échec de commande ou de fermeture, `sequence_summary` conserve le nombre de réponses publiées et `complete:false`. Aucune réémission automatique.
- Windows : `DoIpDiagnosticTransport` ajoute une réservation de session exclusive partagée entre l'échange simple et la séquence, couvrant le contrôle du VIN UDS F190 et toutes les commandes + callbacks. `DoIpVinReader.ExchangeSequenceVerifiedAsync` utilise cette opération atomique. Les appels concurrents sont refusés sans interrompre le propriétaire ; échec de callback = session retirée. `DoIpSequenceProgress` publie désormais aussi des événements JSON `sequence_progress` et `sequence_summary` sur stderr, avec cible/route, compteurs, réponse brute et indication de complétude. La CLI Windows n'affiche ni `sequence_complete:true` ni le message localisé `SequenceComplete` avant la fermeture du transport et du journal ; même si toutes les réponses sont reçues, une fermeture en erreur produit un résumé incomplet.
- Tests écrits, non exécutés : Python progression, échec second paquet et fermeture en erreur ; Windows conflit de requête depuis callback et session retirée sur callback en erreur ; validité et ordre des événements JSON Windows, impossibilité de déclarer un succès prématuré, échec de fermeture malgré toutes les réponses reçues. Documentation de qualification FR/EN révisée. C6/DoIP restent ouverts ; aucune compilation, CI ni essai matériel.
- Note historique désormais résolue par la consolidation Android ci-dessus : les anciennes copies `com/camaradiag/` ont été réconciliées et retirées du HEAD. L'arbre canonique est `com/camarodiag/`. Vérifier la compilation de l'ensemble à la clôture, sans recréer les doublons.
- Commits : 05fe12a3e0d1a9bc3369d7ab8857192bdb7b9dfd, 4ebcac459b3e326ffc63f9c3c5cf25181892127a, 2e28fc1ea45d952837eb482235b1018b508d1d52, 587ea3d5370614eea0264dfc1bbe334aa082a3c4, 7c7855418981ddb281d20b93c912078b23406799, 4797d62c26fac7c3d4cdb99ad28b788877c1bd9f, ec9551bfb6d3297740e2ef41aafc33a68876598b, f507ded7f4c0266f4a24952ac71f5165a379b226, f0d06e28c3845d1e9b35fee2668f2e030f07db0f, 0d14fdf04d6a44a34e511fca223b92b30be31284, 06d3e60e7adbcc9191ec853bf33d02ae030d774c, 6baf226e9e70e22ff84449e5f2cb807a971ffcc0, 4f7087a943966e8805637296d80c9ea879aa9494, b40d8b265ec615fcba6133bb882b2f18a4541b90.

## PR117 — séquences diagnostiques DoIP atomiques, Connectors Python (26/11/2026)

- C6/DoIP : `Session.exchange_sequence` garde désormais un seul verrou d'opération pour tous les échanges et callbacks de progression d'une séquence. Un échange, une attente ou une seconde séquence concurrente est refusé sans fermer le propriétaire actif. `Session.exchange` et la séquence partagent le même moteur de réponse `_exchange_owned`, qui n'acquiert pas le verrou de façon réentrante. Les échecs d'échange ou de callback retirent la session, avec libération du verrou même en cas d'exception. Aucun retry.
- Quatre régressions écrites : verrou pendant les callbacks, refus de concurrence depuis le callback, vrai thread concurrent, fermeture/libération après erreur d'échange ou de callback. Documentation FR/EN mise à jour. Tests et compilations **non exécutés** selon la décision du propriétaire ; C6 et DoIP restent ouverts, sans qualification matérielle.
- Commits : ed000189441068e0588d5c1ebc4bcce1b4efe7d5, 564bc4d0dced5d6e94e474dfd0f42bc27e2788ef, 68abf559821c5e60e404657baf427a8a907f1a72, e3a8d07a9653d9428328aca60c3474cf211ba1c3, 91d15de2ca4b337e70f979cf5169da78c20b5000.

## PR117 — qualification des refus DoIP et NRC UDS (26/11/2026)

- C6/DoIP : les trois moteurs conservent séparément les refus d'en-tête DoIP (NACK générique valide de longueur 1), le refus d'activation de routage, le NACK diagnostique et le NRC UDS. Les erreurs sont typées, avec code brut et, pour UDS, SID demandé lorsque disponible. La version, les adresses et l'écho éventuel de la requête sont contrôlés avant attribution ; ACK positif non nul, mauvais écho et mauvaise adresse restent des erreurs de protocole, pas des refus corrélés. Android/Python n'attribuent pas un NACK reçu à l'inactivité à la prochaine requête non émise. Windows vérifie la version avant attribution d'un NACK d'en-tête. Aucun retry automatique et aucune extension des autorisations ECU. Le parseur Windows d'état de passerelle refuse le nombre de sockets actifs supérieur au maximum, comme les deux autres moteurs.
- Les inventaires Android/Windows affichent le type et le code hexadécimal en FR/EN ; Android expose aussi ces motifs dans les parcours passerelle, lecture/échange et sauvegarde. Les rapports CLI Python et Windows incluent failure_code et, pour UDS, nrc/requested_service ; les refus terminaux sont des objets diagnostic_error sur stderr avec complete:false, sans succès stdout ni changement du code de sortie. Le journal brut reste disponible. Un code inconnu n'est pas interprété comme un code constructeur connu.
- Régressions écrites sur sockets loopback/pair (Android/Python) : refus de routage/diagnostic, mauvais écho/adresse, ACK positif invalide, NACK d'en-tête, fermeture sans retry et isolation au repos ; Windows : codec, NRC UDS, inventaire et compteur de sockets ; Python : sortie CLI d'échec et classification. Documentation FR/EN actualisée. Tests et compilation non exécutés selon directive du propriétaire. C6 et DoIP restent ouverts ; la recette physique reste pour Release.
- Base du lot : 9b8e150a1c1fcbea7cd40a1442d6026543e7379c. Dernier commit fonctionnel de ce lot : 3639a36f737a7bdf9edffaf06a8f30a0519c3ff9 ; documentation de qualification : 911ce6858aec0e930b9e4e1ff28120b53315ff10.

## PR117 — inventaire ECU DoIP progressif (26/11/2026)

- C6/DoIP : Android, Windows et Connectors Python publient des observations détachées au fur et à mesure des interrogations d'adresses logiques explicites UDS F190 ou OBDonUDS F802/F810. Chaque ligne, y compris échec, est exposée seulement après fermeture du socket de l'ECU et revalidation de la passerelle. Les erreurs de trace, d'observateur, de TLS et de changement de passerelle restent fatales ; aucune nouvelle cible après ces erreurs. Les erreurs de communication ne prouvent jamais l'absence d'ECU. Android distingue VIN, protocole, NRC, timeout et communication ; Windows distingue timeout, réponse/identité invalide et communication ; Python conserve notamment le NRC numérique et le timeout.
- Interface Android : résultat partiel mis à jour par callback, cause localisée FR/EN, erreur finale ajoutée aux observations déjà présentées tant que le contexte reste courant. Windows : console mise à jour par ECU, compteur de cibles interrogées et cause FR/EN, vérification du journal et du contexte avant affichage. Les deux CLI émettent des événements JSON inventory_progress sur stderr avec complete:false ; seul le résultat final après fermeture du journal est complet sur stdout. Pas de nouvelle autorisation de programmation.
- Régressions écrites : Android loopback UDP/TCP, NRC, fermeture avant publication et changement EID ; Windows orchestration réelle via délégués, ordre de fermeture et revalidation, timeout et arrêt si passerelle modifiée ; Python ordre de revalidation, snapshots détachés, timeout, changement de passerelle et échec de callback. Aucun test, compilation ou checkpoint exécuté. Qualification FR/EN actualisée. C6 et DoIP restent ouverts.
- Commits du lot : 05169e684205c421cf4721f2e165dd9c7a5d425a, 0add88959d52595b01d9d01607615604311d46fd, df0f0c5f6b788724ce430a5cd20555308dcf1387, 775f318369f412c3ba45a0646b4dde05bac1e55e, a7459b75bbdd5d8a5b849f9267d944c35d22c868, 0234c13c84dfe0e362b8916c2de21c1a92017bd7, 4063822d34e10b33d9e9b6b223200f342e1d85d9, e7ca79c8a0dc084d90f6981631f3752c8bd4d586, 17d2b9a327a739deb709bb55e03c3b743339c5fb, e4b5a82a9182f3fab5856d7f4b0ae0acd55e1520, 7f2b953ff38796f5474913696a7afa9a51552953, 09c0608bb1a6a8bff90fac7dbd88583a23d84228, e5b77bd180aa42b371e7d985af2c7f8c8a9128d1, 00ed4119c5cdb5f7209f51ad0421072d9d727fb9, 2937b25f90f5f125e42600f00eb92e75bf9807c7, e937ca03ae472bf13c8f13b591cb53b48a9b3c55, d4d12836ab7ceb9f41ed7909e318b272303395f8, 53d87aef572ffa8d1a417bdee251cbfc3ed7c18a.

## PR117 — intégrité interplateforme des sauvegardes DoIP (26/11/2026)

- C6/DoIP : Android réutilise EcuZipCompletionGuard pour le contrôle de complétude des archives de mémoire DoIP à l'import et avant publication. Le garde contrôle les en-têtes locaux, le nom, la méthode, les flags, les tailles et CRC après décompression, les descripteurs éventuels avec ou sans signature, les offsets exacts du répertoire central et sa fin. Les trois entrées sont contiguës, sans préfixe, chevauchement, entrée cachée ni suffixe hors commentaire ZIP déclaré. Archive classique stockée/DEFLATE, taille compressée bornée à 33 Mio. Le garde conserve une fenêtre circulaire de 512 Kio ; l'ancien appel des autres archives reste compatible. Android conserve une seule région de référence à l'import et compare l'autre par blocs de 8 Kio ; la vérification temporaire compare directement par blocs aux captures. Syntaxe horodatage explicite harmonisée.
- Windows : contrôle indépendant de la structure ZIP (répertoire, CRC et tailles, en-têtes locaux et descripteurs), CRC-32 calculé sur les données, SHA-256 incrémental et comparaison des deux régions de 64 Kio en 64 Kio. La comparaison de chaînes JSON accepte des échappements équivalents, pas une modification du périmètre. Profil lu sur un seul handle borné à 16 Kio, adresses hexadécimales de longueur explicite et identité canonique ; la publication impose aussi le budget d'acquisition.
- Python : même validation de fin de ZIP et de disposition locale, descripteurs, absence de préfixe/suffixe/chevauchement, CRC via ZipFile et comparaison par blocs de 64 Kio avec hash incrémental. Les erreurs de décompression sont rapportées comme archives invalides. Réconciliation de doip_capture.py et doip_cli.py : réapplication conditionnée aux SHA des versions déjà publiées comportant délai global et publication après fermeture des ressources ; vérification du HEAD effectuée, tests de capture antérieurs conservés.
- Scénarios de régression ajoutés pour les trois plateformes : fin tronquée, répertoire contradictoire, CRC, tailles, offsets, et pour Python en-têtes locaux, descripteurs de flux non seekable, ordre inversé, dernier octet divergent, horodatages invalides ; pour Windows échappement JSON équivalent. Documentation de qualification FR/EN actualisée. Limites : ce contrôle atteste l'intégrité locale, pas l'origine matérielle ni une autorisation d'écriture ; les formats ZIP64 et archives à préfixe/suffixe ne font pas partie du format DoIP de sauvegarde défini. Aucun essai de véhicule ni compilation/tests exécutés ; les scénarios ajoutés et l'interopérabilité réelle des archives restent à valider à la clôture de C6/DoIP.
- Commits de ce lot : a7a080558548b29118e89f7b5795f4bcda6e6f98, 735792d575788276d277846d2f63e2ebf766c15e, 3336282caa358e1f63359679d0794256c60f6be4, 0f70d527c55e1ec142eb598e4a3d1d992db0692e, f48feb22b55533e8860155f21dcfe39d95d7f86c, e125b8da492aed78813c1eab5bbd31f8d21e5b3d, 81344b96c32d22b2969bee7f6f6c38803551cdc7, 6889e2f165710e9ff176a331870261519b14a0cd, c06d697e27c7355747d50b65634189cdebed4b9f, 668288e7447790b2898f01c25450913c9b8ddd3f, d48bacbd04d06d1859dc09e9af8b29be8fd15eba, 93a4b4d247d2266240c85a52cb62af50fd4e1fbd, dd94a4aabff3b0c85c9db5f5a7a874fa584d8731, c98c095e0350b17ee9444bb1031cbf13b741536b, eb50d01d7b55f656a187492ab5c4968b57ca8dee, 937ea09aa0a8fb780d0667b2c41a50a724ee943a, fb7ae9a9860430c00e9324ac9593ea181b771745, 195c414b3c3a63d97ec9a774c3382f28a3af4ecf. C6/DoIP restent actifs.

## PR117 — reprise Bluetooth ELM et sélection temps réel (26/11/2026)

- C6 : l'interface ne déclenche plus une recherche BLE en parallèle d'une sélection Bluetooth, y compris après autorisation Android. Une seule recherche est admise à la fois ; elle est annulée et rejointe avant une nouvelle connexion générique, ses résultats sont liés à la génération du compte et de la connexion. Les interrogations Bluetooth/USB de la liste s'exécutent sur IO ; annulation de la recherche lors du changement de compte et de la destruction du ViewModel.
- L'initialisation ELM s'effectue dans une session de commandes exclusive, avec une échéance coopérative globale de 45 s pour les sondes, un délai ATZ de 3,5 s et publication de la disponibilité seulement après la réussite du lot et vérification de génération. Les ouvertures RFCOMM/BLE/USB/Wi-Fi conservent leurs propres délais. Avant ATZ, l'ouverture RFCOMM purge le flux résiduel jusqu'à une fenêtre calme ; si un ancien monitoring produit encore des données, un seul ESC est envoyé, jamais de CR seul, et le prompt suivi du calme est exigé. Limites de synchronisation 2 s/64 Kio, hors éventuelle latence native du pilote. Échec = fermeture, pas de reconnexion automatique.
- Les écritures RFCOMM sont bornées sur un worker, avec fermeture du socket exact lors d'un timeout ou d'une annulation (au plus 4 s pour la phase d'écriture ordinaire ; 1,5 s pour ESC initial). L'autorisation d'émission est revérifiée après purge et avant l'écriture physique. Aucune réémission automatique après transmission incertaine. La fermeture RFCOMM précède celle des flux. La politique d'erreur différencie un timeout de prompt (pas de perte physique présumée) d'une fermeture explicite suite à un timeout d'écriture ; les erreurs d'une génération révolue restent ignorées. Les délais coopératifs ne garantissent pas le retour d'une API Bluetooth native défaillante, notamment close().
- Le polling n'est plus lancé pendant la transition de connexion. Après sélection explicite d'un adaptateur prenant réellement en charge OBD standard, la nouvelle session réactive la découverte temps réel (y compris après une perte de lien ou un changement de compte). Les données restent liées à la génération de la connexion. Dans le véritable écran LiveDataScreen, une seule cible toggleable accessible pour ligne et case, choix anonyme lié à la connexion et dialogue fermé si aucun signal observé. Aucun changement de protocole actif.
- Régressions ajoutées : flux résiduel, ancien moniteur, absence de prompt, socket remplacé, écriture native bloquée, timeout du pilote, distinction fermeture/timeout et erreur périmée. Aucun test ni compilation ni checkpoint. Le défaut de démarrage rapporté avec l'ELM déjà connecté au téléphone reste à reproduire sur matériel ; il n'est pas considéré comme définitivement résolu. Le code du démarrage ne demande toujours ni connexion automatique ni permission Bluetooth sans action utilisateur.
- Commits de ce lot : 7e72504934af3defeb239308b67035fcb62ecbd3, 23c717a7d744c2f3eeda726a602bea4e8dd782f6, 6eb5282e2cf1f2bbda5df0a1281a22fd8d5d3b79, 2a09afa5da6a472a4032abe7d9a126c452594f35, 70d026df00c1354f2a5c3efea71bc890d63c43b5, 102e13a0fe5afcc0fd24f2c3a918cf0047b81a26, ccb8a658be5b43349e099ebe9cb5f5956d63c316, 77fe33c0a45b9fcb5cf24f2d72379e54f7869cea, 07dfe7186406d8cffdae1b1cac0d7414c29e1c1a, 33a3c01ab232e783229a2bfffd62a448cf047751, a1ca292cfc708ff90edfa7d16aa8e75b18775f75, ee7e44f82079d02267b29252207c103fe6a3e6f7, 8b5f6907cf9ca2bc7320ceaa0a0fb1fe7e11c82e, 35ac021a12187969c9ce552a6c9e637f55a05bf5, fa499bf7ef38e96c2ad1c3b5f58b344879e63bbc. C6/DoIP restent ouverts.

## PR117 — corrections DTC multitrames et sélection temps réel (26/11/2026)

- C6 : le décodeur de modes 03/07/0A réassemble désormais les réponses CAN ISO-TP multitrames à en-têtes physiques 11/29 bits par répondant, avec validation du DLC éventuel, longueur, séquence, compteur DTC et absence de doublon contradictoire. En présence de multitrames incomplètes ou ambiguës, la lecture du mode reste indisponible plutôt que de fabriquer une liste de défauts vide. Le comportement historique des réponses simples et numérotées est conservé. Régressions réseau de trames simulées : deux ECU entremêlés, fragment manquant, mauvais répondant, mauvaise séquence, longueur incohérente, code zéro et répétition ; correction d'une fixture ELM numérotée dont la première ligne n'avait que cinq octets au lieu de six.
- Réconciliation des deux variantes de Obd2FeatureEngine/ObdScanScreen : couverture des PID par répondant, première page non résolue, attribution incomplète, budget Mode 06 existant et libellé PID 5B conservés avec la sélection limitée aux valeurs lues, la préférence fondée sur VIN physique et le refus d'étiqueter arbitrairement ECM un DTC générique. Tests Mach-E exacts et tests de bitmaps anonymes/physiques réunis.
- Données temps réel : le cache de disponibilité conserve les ensembles PID par adresse et les fige dans la génération de la connexion. Le chemin ELM du ViewModel consomme les observations filtrées dans la même session exclusive ; si les adresses sont connues, seules les réponses des ECU qui ont annoncé le PID sont admises (pas d'attribution d'une réponse anonyme à un ECU). Le chemin J2534 garde le répondant physique contrôlé par le pont. Les signaux sélectionnables restent ceux effectivement mesurés ; la case du sélecteur OBD et sa ligne partagent une seule cible toggleable, fermeture du dialogue si connexion interrompue, scan en cours ou plus aucun PID mesuré. Régressions de cache et d'attribution écrites.
- Commits : f2ba18b79987358c7f7c6f9f308769fa9a53adbc, ca8a34a1eaccfa203f68f2437da9a9b4fff463ce, 7c29d012353adbb8c130c6c1c7e0f2a04fdbd34f, 2919602fa74dec9755e75c9308ffc5dda596560e, 60987aa56f0cae81d4e34821a38b749f616c0ac7, 432955d788e07cef7f8a0514acdd43331e2bfd9e, cdae8c97df8cdb7d2b95059b5ee4fbd7346b5202, e0c2a4c2093399ea19904a820fb76e63bf74ff73, 4941aadbf6790d8e7cdfceffb65d3442aea7d094, 908c8f55aba12747cb0327de21e91586bde4bfe9, 38064f6c0e2fe26e32f3276e6492e0988d192dc7. Aucune compilation ni exécution de tests ou checkpoint. C6/DoIP restent ouverts ; comportement réel ELM327/Mach-E et interaction tactile à qualifier. Aucun changement de protocole actif.

## PR117 — isolation des échanges DoIP et inventaire Connectors (26/11/2026)

- C6/DoIP : l'inventaire Python ne publie plus de résultat positif avant fermeture réussie de la session, ce qui évite les doubles lignes pour un même ECU après une erreur de fermeture. L'accès au socket Python est sérialisé par un verrou non bloquant entre échange et attente ; les appels concurrents sont refusés sans fermer la session de l'autre propriétaire. Avant nouvelle requête, les trames disponibles sont examinées (select TCP, données TLS en attente, sonde bornée) : AliveCheck traité, diagnostic non sollicité journalisé et session fermée sans émettre la nouvelle requête. Le budget global inclut ce contrôle.
- Android : contrôle similaire avant envoi (disponibilité TCP, sonde TLS de 1 ms), réutilisation du parseur de trames au repos, journalisation de la PDU complète sans attribution UDS pour les diagnostics non sollicités, contrôle d'échéance après journalisation. La session n'est jamais réutilisée après une erreur. La détection est préventive, sans garantie contre une réponse identique tardive arrivant après l'envoi suivant ; Windows dispose déjà d'un lecteur permanent.
- Régressions écrites : fermeture inventaire en erreur sans double résultat, accès concurrent, réponse déjà en file, AliveCheck avant échange (Python), réponse spontanée ressemblant à celle du DID suivant (Android loopback). Aucun test ni compilation ni checkpoint. Documentation de qualification FR/EN complétée. C6/DoIP restent ouverts.
- Accès GitHub : les lectures Contents et arbres ont donné temporairement des SHA divergents pour plusieurs fichiers. Après vérification, les blobs Android de sauvegarde et ViewModel du lot précédent étaient toujours disponibles ; le blob Python de ce lot a été réappliqué par mise à jour conditionnée au SHA. Vérifier les empreintes au HEAD plutôt que supposer une perte ou écraser des fichiers. Commits fonctionnels 72c353798dedeff2e9b7ae403bb6916be6ee816e, 75da3cfd306923cb524c4b08f6ee966c8326ca79, c259bc007ba5d423cb077beaec44f1b0fb89ea08, 1faf2ec1485ba54202c9a7625a60f101aa05fd3b, e01e59f2b3dce69f15a1dc9e057b3aa7585c82bf, 7ba15c6aff25675b5c7e80fa5b54faf0aded3514, ac2375102ea40ce63fcc71e94b3719848ca3ee61.

## PR117 — parité Android/Windows GUI des sauvegardes DoIP (26/11/2026)

- C6/DoIP : récupération des blobs Android par l'arbre Git du HEAD malgré les erreurs Contents 404 (ne pas réécrire un fichier sans SHA vérifié). Android partage une échéance monotone pour les échanges de sauvegarde, contrôle le VIN et le DID d'identité après chaque lot de 32 blocs/fin de passe avant progression, et redécouvre la même passerelle après fermeture TCP/TLS avant de retourner la capture. Annulation et changement de contexte revérifiés à la publication. La boucle Android existante de 600 s inclut TCP/TLS/routage ; UDP initial/final et export sont hors budget.
- GUI Windows : validation du budget avant la connexion, vérification de la passerelle après acquisition, contrôle du contexte, de la trace et relecture du profil avant publication ZIP ; renommage de la variable locale de sauvegarde pour éviter son conflit avec la progression du monitoring.
- Android : séparation entre profil vérifiable hors ligne (16 Mio) et acquisition (8192 blocs par passe, au plus 2 Mio). Vérification des deux entrées ZIP octet par octet, quel que soit leur ordre, avec une seule région entière en mémoire ; refus de longueur incorrecte, entrées inattendues/dupliquées et UTF-8 invalide. Interface FR/EN : un profil hors budget reste vérifiable mais ne peut pas démarrer de capture ; contrôle également dans le ViewModel et le moteur avant le réseau. L'intégrité locale ne prouve pas la provenance matérielle. Le lecteur Android utilise les entrées ZIP locales, pas un contrôle exhaustif du répertoire central.
- Régressions réseau loopback et archive ajoutées : succès, changement d'identité, substitution de passerelle après la lecture, limite de blocs, comparaison de régions de plus de 2 Mio. Dépendance org.json de test JVM alignée sur la version déjà employée dans le dépôt. Documentation FR/EN mise à jour. Aucun test ni compilation ni checkpoint déclenché ; C6 et DoIP restent ouverts. Les limites réelles du véhicule et la recette physique restent à vérifier sur Release.

## PR117 — acquisition mémoire DoIP bornée et publication après fermeture (26/11/2026)

- C6/DoIP : Windows et Connectors Python bornent chaque passe à 8192 blocs et les deux lectures mémoire à une enveloppe de 600 s (hors découverte/routage, vérification UDP finale et écriture ZIP). Windows applique un jeton lié à la durée totale et rejette les profils trop grands avant découverte CLI ; Python propage une échéance monotone unique à chaque échange (P2/P2* inclus), y compris VIN/DID d'identité. Les deux implémentations revérifient le VIN et l'identité après chaque lot de 32 blocs ou fin de passe avant publication de progression, puis avant comparaison finale. Python fige les champs du profil pendant l'acquisition. Aucun retry ni commande modificatrice.
- Les CLI Windows et Python ne publient plus le ZIP avant la fermeture réussie de la session et du journal de traces. Windows revalide aussi l'identité UDP de la passerelle et recharge le profil avant publication ; Python conserve sa revalidation UDP et son contrôle du profil. Le contrôle d'intégrité du ZIP n'est pas une preuve indépendante de provenance matérielle.
- Régressions écrites : limite exacte du nombre de blocs, changement d'identité avant progression, même échéance par requête Python, fermeture échouée avant sauvegarde, préservation du périmètre de lecture. Commits 70fb68b49fe66071f8671be2873d5ab8f0dd1d99, 773792192f8edbf593a90222337467ba7a08e607, e62475a2be386b4a526d45ca9f52a6e1dac5dd12, 8f079359e75cd9dd86596c61bf015ab5a03828e7, 4e0efab882d06f7c831c8b674548cf23ebb96bd2, 6bdedb9587c10a8c57eaa89f5b1c2df17e5f5b84, 1a129c3d2956e15a154087cb2e2d2e494b1c5d34, 56ee606a35ba6d821d26f8771eb34db4f5571792, 54f14dac0af8139e216c2f5ccff6e230db25403e. Android DoIpMemoryBackup.kt reste inaccessible par Contents API (404), parité à vérifier sans écrasement. Récupération par blob SHA connu utilisée pour certains fichiers Python, mise à jour conditionnée au SHA. Aucun test ni compilation ni checkpoint ; C6/DoIP restent ouverts.

## PR117 — réconciliation des corrections Mach-E dans les blobs courants (26/11/2026)

- C6 : les versions effectivement retournées par GitHub de l'écran scan et du moteur OBD ne contenaient pas toutes les corrections déjà décrites ci-dessous. Réintégration ciblée sans remplacer les autres fonctions : préférence des capteurs liée au VIN physique valide, aucune persistance pour une simulation ou VIN absent, sélection locale au rapport sans identité, bouton seulement si des mesures existent en connexion stable ; suppression de l'attribution ECM non prouvée pour les DTC génériques. Union des bitmaps fonctionnels anonymes, refus d'une union ambiguë en enquête moteur seule, conservation des bitmaps physiques vides et rejet des mesures anonymes lorsque l'inventaire est attribué. Deux régressions écrites dans Obd2FeatureEngineTest. Commits e76dfc709bbf8f2382ae161d5e64908f08d59865, 5bafff2efb0091ba7425b7d5c5decc1199732df2, 3d36c1f1a7be2a6fb3a4b9c74cd61777b46788af. Plusieurs autres fichiers Kotlin restent inaccessibles par Contents API (404) ; ne pas les recréer à l'aveugle. Aucun test/compilation/checkpoint déclenché, C6/DoIP restent ouverts.

## PR117 — restauration du budget Mode 06 et preuve de réponse (26/11/2026)

- C6 : réintégration de la version bornée du Mode 06 (budget partagé 20 s, annulation, MID non interrogés et résultat partiel), absente du blob courant après réconciliation des modifications parallèles. Les nouveaux parcours PID par répondant, A6 et identité sont préservés. Le bitmap réel Mach-E 460000000001 déclenche une tentative de page 0620 ; sans réponse valide, le résultat reste partiel et ne prétend pas que les tests sont absents. Les régressions existantes ObdCanMonitorRecordsTest couvrent ces cas ; non exécutées. Aucun checkpoint.

## PR117 — A6 et libellés réels dans les données temps réel (26/11/2026)

- C6 : odomètre A6 en temps réel seulement lorsqu'annoncé et effectivement lu, décodage quatre octets non signé /10, km/miles, source stable existante. Traductions A6 et PID 5B dans le catalogue, le scan et les cartes ; 5B n'est ni SOC traction ni mesure certifiée de santé. Cartes temps réel réutilisent les libellés localisés et indiquent le répondant physique. Les préférences de capteurs ne sont persistées que pour le VIN observé et ne contaminent plus le garage Camaro lors d'un essai Ford. Régressions catalogue écrites, non exécutées. Aucun checkpoint.

## PR117 — découverte PID multiréponses et attribution Mach-E (26/11/2026)

- C6 : la découverte fonctionnelle sans en-têtes fusionne les bitmaps valides en union, sans prétendre connaître le calculateur d'origine ; elle ne valide pas une enquête limitée au groupe motopropulseur en cas de bitmaps anonymes contradictoires. Les réponses physiques conservent les ensembles par ECU, y compris les ensembles vides. Une mesure anonyme ne complète pas un catalogue attribué. Le scan affiche la couverture par répondant, l'attribution incomplète et la première page non résolue. Régression avec les six bitmaps réels Mach-E et le PID A6, non exécutée. Aucun checkpoint.

## PR117 — VIN et odomètre sur canal J2534 (26/11/2026)

- C6 : lecture Mode 09 PID 02 avant/après les PID kilométriques sur le même canal physique J2534 sous verrou de session et contrôle d'époque. Décodage VIN strict, aucune attribution si une des deux lectures est indisponible, refus de VIN contradictoires. A6/D3 gardent leur décodage et l'absence de VIN n'est pas remplacée par le profil du garage. Régressions écrites, non exécutées. Aucun checkpoint.

## PR117 — qualification des températures extrêmes OBD (26/11/2026)

- C6 : les PID température à un octet 05/0F/46/5C signalent explicitement les limites de codage 00/FF dans le scan, en conservant valeur décodée, brut et adresse du répondant. Ne pas assimiler arbitrairement ces valeurs à un capteur absent ni à une température physique confirmée. Régression Mach-E 215/-40/15 °C enrichie, non exécutée. Aucun checkpoint.

## PR117 — admission des fonctions OEM après retour Mach-E (26/11/2026)

- C6 : les lectures catalogue OBDb Camaro, enquête LFX et identification GMLAN exigent un VIN Camaro candidat observé sur connexion physique, concordant avec le profil sélectionné. Les vérifications sont répétées pendant les parcours concernés ; les diagnostics génériques restent distincts. Le VIN observé est actualisé aussi lors de l'identification initiale et manuelle, pas uniquement au scan complet. Les topologies incompatibles ne sont pas conservées. Régressions de la politique VIN écrites, non exécutées. Ce contrôle n'est pas une autorisation d'écriture ni une preuve de calibration. Aucun checkpoint.

## PR117 — identité Mode 09 multicalculateur après retour Mach-E (26/11/2026)

- C6 : lorsque les CAL ID, CVN ou noms ECU ne peuvent être décodés sans en-têtes, nouvelle interrogation bornée en conservant les en-têtes CAN restaurés. Réassemblage ISO-TP classique indépendant par adresse, refus des trames tronquées, séquences incohérentes et blocs intercalés sans provenance. Le VIN existant reste sur son parcours éprouvé. Les identités ne sont pas attribuées à un nom de module OEM sans profil. Régressions sur réponses synthétiques intercalées (format observé dans les traces Mach-E), non exécutées. Aucun checkpoint.

## PR117 — odomètre Mach-E et identité réelle du véhicule (26/11/2026)

- C6 : audit ELM lit le VIN réel avant et après A6/D3 dans une session exclusive, refuse un changement de VIN et ne substitue plus le VIN du garage à une identité non observée. J2534 conserve sa lecture kilométrique sans attribution VIN tant que la transaction ne fournit pas cette preuve. Le VIN réel non-Camaro remplace le descriptif Camaro dans le bandeau de connexion après scan/audit ; modules GM non vérifiés retirés de la liste, sans modifier le véhicule du garage. L'observation est invalidée sur changement de connexion/contexte. Le rapport conserve toutes les preuves ; l'écran odomètre masque les sources indisponibles lorsqu'une valeur est exploitable et distingue valeur déclarée d'une vérification indépendante. Ressources FR/EN. Pas de compilation/test ; la protection exhaustive de toutes les fonctions GM et le VIN J2534 atomique restent à compléter.

## PR117 — retour réel Mach-E : scan OBD multicalculateur (26/11/2026)

- C6 : le scan conserve les réponses Mode 01 par adresse CAN, les associe aux bitmaps annoncés et refuse une valeur arbitraire en cas de contradiction sans adresse. Les mesures du scan portent leur répondant, la sélection ne propose que les PID effectivement mesurés et les clés UI distinguent les réponses et unités. A6 est intégré au scan lorsque annoncé ; 31 reste la distance depuis effacement. Le relevé réel A6 41A6000BD967 (77655,1 km) est conservé en régression ; aucune modification de sa formule, refus des kilométrages contradictoires. Les réponses aplaties de PID sont séparées sans concaténation aveugle. Readiness affiche les observations distinctes. Libellés FR/EN. Tests écrits non exécutés ; aucun checkpoint. Les valeurs extrêmes réelles restent brutes, pas de suppression arbitraire des 0xFF. Le réassemblage Mode 09 intercalé, l'identité de l'audit et l'attribution des PID sur ELM sans en-têtes restent à traiter.

## PR117 — arrêt de l'inventaire Android en cas de perte de journal (26/11/2026)

- C6/DoIP : les callbacks de trace UDP/TCP/TLS d'un inventaire Android sont isolés des exceptions de transport. Un échec du journal ne devient plus une ligne ECU indisponible et interrompt les interrogations suivantes. L'annulation reste une annulation ; contrôle du contexte avant publication, testeur distinct de la passerelle. Point d'entrée interne à ports personnalisés limité au loopback TCP pour la régression écrite, non exécutée. Aucun checkpoint.

## PR117 — attribution des échecs de journal pendant inventaire Windows (26/11/2026)

- C6/DoIP : une exception du callback de trace TCP/TLS est distinguée d'une erreur réseau, y compris lorsqu'elle est de type IOException. L'inventaire n'enchaîne plus les ECU après une erreur de journal constatée durant une transaction ; un résultat vérifié n'est ajouté qu'après fermeture de sa session afin d'éviter une double ligne si la fermeture échoue. Annulation revérifiée avant publication. Régression loopback écrite, non exécutée ; aucun checkpoint.

## PR117 — parité WWH Android et Windows (26/11/2026)

- C6/DoIP : les interfaces Android et Windows affichent les masques demandés et effectifs d'état et de sévérité pour WWH 19/42, uniquement quand ces filtres ont réellement été transmis. Les rapports 19/55 et 19/56 n'inventent aucun filtre. Les octets DTC/sevérité restent bruts et les états Android sont interprétés selon les bits disponibles. Un filtre WWH indisponible ne peut pas conclure à l'absence de défauts. Ressources FR/EN et régressions écrites, non exécutées. Aucun checkpoint.

## PR117 — états WWH et filtres effectivement transmis (26/11/2026)

- C6/DoIP Connectors : les rapports WWH 19/42, 19/55 et 19/56 exposent les états normalisés selon le masque disponible, en conservant les octets bruts. 19/42 distingue les intersections effectives des filtres d'état et de sévérité ; aucun filtre artificiel n'est attribué aux requêtes 19/55 et 19/56. Documentation FR/EN et régressions écrites, non exécutées. Aucun checkpoint.

## PR117 — cohérence du comptage DTC Windows et Android (26/11/2026)

- C6/DoIP : le comptage Windows 19/01 affiche aussi le masque effectif et signale un filtre indisponible, y compris si le compteur est nul. Régression FR/EN écrite. Ajustement de compatibilité Kotlin du rendu des états Android (liste d'identifiants de ressources, concaténation explicite). Aucune compilation ni test exécuté.

## PR117 — disponibilité des filtres DTC Android (26/11/2026)

- C6/DoIP : Android affiche le masque effectif pour les résultats DTC 19/01 et 19/02, y compris les listes de mémoire et historiques filtrées. Les états DTC sont traduits FR/EN en ne décodant que les bits disponibles ; le code et l'octet bruts restent affichés. Une lecture sans bit disponible ne remplace plus l'inventaire précédent du calculateur, en DoIP et sur le parcours UDS CAN. Une requête filtrée ne remplace pas l'inventaire complet. Tests de politique écrits, non exécutés ; aucun checkpoint.

## PR117 — masques DTC structurés Connectors DoIP (26/11/2026)

- C6/DoIP : les opérations 19/01 et 19/02 UDS/OBDonUDS des Connectors retournent le masque effectif et la disponibilité du filtre ; les listes 19/02 exposent en plus les identifiants des bits d'état normalisés, sans perdre l'octet brut. Filtre vide distinct d'un résultat sans défaut sur un filtre disponible, masques invalides refusés avant émission. Documentation CLI FR/EN et régressions écrites, non exécutées. Aucun checkpoint.

## PR117 — interprétation DTC DoIP Windows (26/11/2026)

- Console Windows : interprétation des huit bits d'état ISO 14229, filtrée par le masque de disponibilité réellement retourné. Le code DTC 24 bits et l'octet d'état brut restent affichés. Les rapports 19/02 indiquent le masque effectif ; aucun bit disponible ne signifie pas absence de défaut. Inventaires supportés/permanents et WWH affichent aussi les états disponibles sans inventer les définitions OEM. Ressources FR/EN et régressions écrites, non exécutées. Aucun checkpoint.

## PR117 — disponibilité des commandes DoIP GUI Windows (26/11/2026)

- C6/DoIP : activation distincte des commandes passerelle, ECU individuel, opérations exigeant un VIN explicitement concordant et inventaire multi-ECU. Les adresses testeur/ECU doivent être valides et différentes ; l'inventaire peut fonctionner sans ECU individuel. La modification du type de routage et des octets OEM invalide le contexte sélectionné, comme les changements d'adresse et TLS. Les boutons ne constituent pas une preuve de support du service par l'ECU ; les réponses réelles restent nécessaires. Régressions de disponibilité écrites, non exécutées. Aucun checkpoint.

## PR117 — fermeture et publication DoIP Connectors Python (26/11/2026)

- Le transport vérifie que le socket reste courant après chaque callback de trace TX/RX et que le délai reste respecté après décodage final. Une fermeture déclenchée par l'observateur du dernier RX ne valide pas la réponse ni un nouvel échange ; une erreur de close ne conserve pas un socket réutilisable. Régressions TCP loopback et fermeture en erreur ajoutées, non exécutées. Aucun checkpoint.

## PR117 — frontière de fin de transaction DoIP Windows (26/11/2026)

- C6/DoIP : le transport Windows contrôle de nouveau l'annulation et le délai global sous verrou au moment de rendre le socket disponible après une réponse finale. Une réponse devenue hors délai ou annulée retire la connexion, sans réémission. Le contrôle s'applique également juste avant l'écriture après acquisition du verrou TX. Régression TCP loopback d'annulation au dernier RX ajoutée, non exécutée. Aucun checkpoint.

## PR117 — bilan de capture Connectors DoIP CLI (26/11/2026)

- Les sorties stderr partial_sample sont désormais attribuées au VIN, à la passerelle, au testeur, à l'ECU, au transport et au service réellement sélectionnés. Le bilan stdout ne devient complet qu'après fermeture de la session et du journal ; en échec un monitor_summary incomplet conserve le compteur de mesures publiées. Index vérifiés, aucun succès si fermeture échouée ; si stderr lui-même est inutilisable, la cause originale est propagée. Documentation utilisateur FR/EN corrigée, régressions écrites non exécutées. Aucun checkpoint.

## PR117 — parité monitoring DoIP Connectors Python (26/11/2026)

- C6/DoIP : les captures mono et multi-DID UDS/OBDonUDS revérifient le VIN et, pour OBDonUDS, le protocole après chaque lecture avant publication du résultat. Les callbacks ne reçoivent plus de mesure provisoire ; un échec ultérieur conserve les échantillons précédemment vérifiés et retire la session. Une expiration de pause avant le premier octet est normale, une trame incomplète reste fatale. Régressions ajustées et ajoutées, non exécutées. Les délais par échange restent inchangés, la cadence dépend des contrôles supplémentaires. Aucun checkpoint.

## PR117 — invalidation de la découverte DoIP UDP Android (25/11/2026)

- C6/DoIP : le contrôle d'activité s'applique maintenant aux datagrammes de découverte directe, broadcast, multicast, écoute et requêtes d'état/alimentation. La découverte liée à une opération ou un inventaire vérifie en plus le contexte véhicule avant transmission et après observation. Une annonce reçue après invalidation ne peut pas ouvrir une session TCP. Régression loopback ajoutée ; isolation des échecs attendus dans la coroutine du test de monitoring. Aucun test exécuté, aucun checkpoint.

## PR117 — annulation DoIP Android à la frontière de publication (25/11/2026)

- C6/DoIP : l'activité de la continuation réseau est désormais intégrée au contrôle du contexte de session TCP. Une annulation détectée empêche les nouvelles émissions et la publication tardive des mesures vérifiées, réponses de commandes et progressions de sauvegarde ; les traces TCP ne sont plus transmises à un observateur devenu obsolète. Fermeture du socket existante conservée, pas de réémission automatique. Une callback déjà commencée ne peut pas être annulée rétroactivement. Le test loopback existant couvre désormais annulation au dernier RX d'identité UDS et OBDonUDS. Tests écrits, non exécutés ; aucun checkpoint.

## PR117 — délai global des acquisitions DoIP Android (25/11/2026)

- C6/DoIP : le budget TCP global de capture tient compte de chaque lecture DID et de chaque contrôle d'identité supplémentaire (VIN UDS, VIN et F810 OBDonUDS), pour les parcours mono/multi-DID. Il inclut la fin d'une trame Alive Check reçue en bord de période d'attente. Les limites individuelles P2/P2* et le nombre maximal de mesures restent inchangés. Expiration avant le premier octet pendant une pause traitée comme fin normale ; changement de contexte détecté pendant la pause. Régressions déterministes écrites, non exécutées. Aucun checkpoint.

## PR117 — délais de lecture ELM multi-transport (25/11/2026)

- C6 Android : horloge monotone pour la phase RX USB, BLE, Wi-Fi et Bluetooth classique ; chaque attente est limitée au temps restant. Le socket Wi-Fi applique le délai demandé sur le socket courant au lieu de bloquer systématiquement 1200 ms. Les contrôles de session et de prompt restent en place ; durée d'écriture/configuration et latence système hors budget RX. Régressions ajoutées, non exécutées. Aucun checkpoint intermédiaire.

## PR117 — budget commun de lecture Mode 06 (25/11/2026)

- C6 Android : budget monotone de 20 s pour les requêtes Mode 06 (configuration/restauration ELM hors budget), délai de chaque commande limité au temps restant, aucune nouvelle émission après expiration. Les résultats vérifiés sont conservés ; les MID annoncés non interrogés sont explicitement distingués des réponses manquantes et le rapport est partiel. Un bitmap vide valide est distingué d'une absence de réponse ; continuation impossible après E0 rejetée. Le respect temporel dépend du canal sous-jacent. Régressions déterministes écrites, non exécutées ; C6/DoIP restent ouverts, aucun checkpoint ni Release.

## PR117 — alignement des régressions Windows Mode 06 (25/11/2026)

- Les assertions J2534 couvrent les lectures MID et les pages suivantes, tout en refusant les requêtes incomplètes ou trop longues. Tests non exécutés. Le budget temporel global Mode 06 reste à intégrer : récupération du moteur Android et de certains diffs à nouveau intermittente ; ne pas considérer cette limitation comme publiée. C6/DoIP ouverts, aucun checkpoint.

## PR117 — lecture des moniteurs CAN OBD Mode 06 (25/11/2026)

- C6 : parcours borné des bitmaps Mode 06, lecture des MID effectivement annoncés et décodage de leurs enregistrements CAN de 9 octets (MID/TID/UASID, valeur et seuils bruts). Aucun calcul d'unités ni verdict inventé sans table SAE J1979-DA ; résultats partiels explicitement marqués. J2534 Windows/Android autorise ces lectures normalisées (pas d'écriture). Le rapport existant présente les résultats, pas le bitmap comme mesure. Sources : SAE J1979 / ISO 15031-5, https://phprell.github.io/obd-diag/en/technik/dienste.html. Fichiers Android reconstitués depuis blobs et diffs vérifiés, lecture classique 404. Tests écrits non exécutés ; aucun checkpoint C6/DoIP.

## PR117 — cohérence des freeze frames OBD Mode 02 (25/11/2026)

- C6 : contrôle strict service/PID/numéro de trame/longueur Mode 02, avec provenance CAN 11/29 bits. Une image figée sélectionne un seul répondant pour le DTC déclencheur, ses bitmaps et ses valeurs ; un autre ECU ne remplace pas silencieusement un PID absent. Réponses contradictoires du même ECU rejetées. Régressions écrites non exécutées ; aucun checkpoint C6/DoIP.

## PR117 — identité OBD multicalculateur (25/11/2026)

- C6 : les lectures Mode 09 décodent séparément les blocs ELM numérotés et les réponses complètes anonymes, sans concaténer plusieurs ECU. VIN identiques acceptés, VIN divergents ou enregistrement tronqué refusés ; identifiants de calibration et CVN conservés par enregistrement. Console compatible avec plusieurs blocs valides. Aucun changement de droits d'écriture. Lecture des fichiers confirmée par SHA/blob avant modification. Régressions ajoutées non exécutées, aucun checkpoint C6/DoIP.

## PR117 — clôture fiable de la capture DoIP CLI (25/11/2026)

- Le bilan NDJSON complet est désormais émis après fermeture du transport et fermeture/flush du journal, jamais avant ; une erreur de fermeture conserve les échantillons déjà publiés mais laisse la capture incomplète. Documentation FR/EN du flux et de ses limites mise à jour. Aucun test ni checkpoint intermédiaire. C6/DoIP restent ouverts.

## PR117 — DoIP : distinction fermeture TCP et trame tronquée (25/11/2026)

- Windows DoIP : une fermeture propre entre deux PDU peut conserver la dernière réponse finale déjà validée ; une trame tronquée, invalide ou un débordement constaté avant retour de l'échange interdit le succès et retire le socket. Aucune réémission automatique. Scénario loopback ajouté, non exécuté ; une anomalie arrivant après le retour ne peut pas invalider rétroactivement une réponse. C6/DoIP ouverts, aucun checkpoint.

## PR117 — décodage commun des DTC ELM numérotés (25/11/2026)

- C6 : console et scan utilisent le même assemblage des blocs DTC CAN auto-formatés ELM (longueur, lignes 0..F, découpage, nombre de DTC et absence de code nul déclaré). Plusieurs réponses sont traitées séparément, sans joindre des fragments d'ECU distincts ; réponse incomplète ou compteur incohérent non validés. Les NRC finaux restent distincts d'une lecture réussie. Un bloc anonyme ne prouve pas l'adresse physique. Tests ajoutés non exécutés ; aucun checkpoint C6/DoIP.

## PR117 — intégration console OBD des formats ELM/J2534 (25/11/2026)

- C6 : console opérateur ELM conserve les lignes physiques pour PID ; Mode 09 et DTC utilisent temporairement le format sans en-tête pour l'assemblage natif, puis restaurent ATH1/ATS0 sous verrou et invalident la session en cas d'échec. La console J2534 conserve l'adresse du répondant physique déjà vérifiée avec la réponse assemblée. Tests de régression ELM et progression DoIP Windows écrits non exécutés. Aucun checkpoint C6/DoIP.

## PR117 — suivi des acquisitions DoIP interrompues Windows / CLI (25/11/2026)

- C6/DoIP : CLI NDJSON publie les échantillons vérifiés avec adresse passerelle/testeur/ECU, transport et temps écoulé ; bilan final explicite même en échec ou annulation, sans transformer l'erreur en succès. Console Windows affiche le nombre réellement vérifié en cas de capture partielle (FR/EN), contrôle le journal avant chaque publication. Suivi séquentiel partagé ; aucune mesure non vérifiée déclarée complète. Régressions de ressources écrites non exécutées ; aucun checkpoint. C6/DoIP restent actifs.

## PR117 — classification console OBD des répondants CAN secondaires (25/11/2026)

- C6 : la console accepte les réponses positives et négatives SAE provenant de répondants 29 bits secondaires, au même titre que les 11 bits ; support DLC explicite et bourrage d'une trame classique complète. Option d'adresse exacte pour vérification physique, sans accepter de réponse anonyme pour cette preuve. Aucune extension des commandes modifiant les ECU. Régressions écrites non exécutées. C6/DoIP restent actifs ; aucun checkpoint.

## PR117 — attribution des DTC et sélection télémétrie par ECU (25/11/2026)

- C6 : les DTC SAE sur canal J2534 physique sont identifiés par adresse CAN, les DTC ELM fonctionnels restent OBD sans attribution ECM inventée. Les listes standard et UDS sont distinctes ; un rescan ne remplace que les modes confirmés du répondant courant. Effacement J2534 ne retire que les défauts standard non permanents du canal confirmé ; ACK fonctionnel ELM ne prouve pas l'effacement de tous les répondants, conserve le snapshot jusqu'à rescan. La télémétrie générique accepte les ECU secondaires même sur Camaro, restriction FlexFuel primaire maintenue. Mode 03 explicitement non supporté toléré dans le scan J2534 partiel. Tests écrits non exécutés. Aucun checkpoint C6/DoIP.

## PR117 — parité Android des canaux J2534 secondaires (25/11/2026)

- C6 Android : même politique de paires physiques que le pont Windows ; lecture sur ECU secondaire explicitement sélectionné, pas d'effacement ni de FlexFuel sur ces canaux. Adresse réelle du répondant conservée en télémétrie ; longueurs strictes pour PID 5B, E0 et sondes large bande supplémentaires. Tests ajoutés non exécutés. Accès aux fichiers rétabli après correction du chemin. C6/DoIP restent ouverts, aucun checkpoint.

## PR117 — extension des canaux OBD J2534 physiques (25/11/2026)

- C6 pont Windows : lecture SAE J1979 sur paires physiques ISO 15765-4 11 bits 7E0..7E7 / 7E8..7EF et 29 bits 18DAxxF1 / 18DAF1xx corrélées strictement. Aucun broadcast ou changement d'ECU ; effacement réservé au canal primaire avec autorisations et contrôle de vitesse/régime existants. Régressions Windows écrites non exécutées. Parité Android à réaliser (accès GitHub à certains fichiers intermittent). C6/DoIP restent ouverts, aucun checkpoint.

## PR117 — découverte PID multicalculateur et télémétrie générique (25/11/2026)

- C6 : collecte des bitmaps SAE Mode 01 par répondant réel avec suivi indépendant des pages, rejet des doublons contradictoires et conservation du statut incomplet lorsqu'une page manque. Télémétrie standard générique : source stable par PID, pas de substitution silencieuse si le répondant disparaît, identifiant CAN dans les libellés des mesures/CSV ; restriction ECM conservée pour Camaro confirmé. Ajout du PID 5B lorsque réellement annoncé et lu (durée de vie batterie hybride, pas état de charge ni capacité EV propriétaire). Tests écrits non exécutés ; aucune CI. C6/DoIP restent actifs.

## PR117 — export des mesures DoIP partielles vérifiées (25/11/2026)

- C6/DoIP Android : chaque échantillon validé porte le VIN et l'adresse logique de passerelle observés ; un rapport partiel est constitué immédiatement et reste exportable après annulation ou erreur ultérieure, exclusivement pour le contexte véhicule/compte courant. CSV avec capture_complete, requested_samples et verified_samples ; statut partiel visible en FR/EN. Aucun résultat non vérifié ajouté ; changement de contexte efface toujours les données. Régressions écrites non exécutées, aucun checkpoint. C6/DoIP restent actifs.

## PR117 — parité Android / CLI des échantillons DoIP (25/11/2026)

- C6/DoIP : Android mono/multi-DID et OBDonUDS, CLI Windows DID simple : chaque mesure est vérifiée sur la même connexion avant publication. Recontrôle du contexte Android après la vérification ; résultats déjà confirmés conservés en cas d'échec ultérieur. Régression Android UDP/TCP loopback écrite pour deux mesures, VIN ou protocole divergent sur la deuxième ; aucune exécution. Cadence dépendante des temps ECU supplémentaires, pas de promesse de temps réel déterministe. Aucun checkpoint CI. C6 et DoIP restent actifs.

## PR117 — DoIP temps réel vérifié avant publication (25/11/2026)

- Windows C6/DoIP : monitoring DID simple, multi-DID et OBDonUDS vérifie désormais l'identité après chaque échantillon avant de le transmettre au callback UI/CLI/export, au lieu de publier des mesures provisoires et vérifier seulement en fin de session. Le premier échantillon vérifié reste disponible si le suivant échoue ; le nouvel échantillon incohérent n'est jamais publié. Aucun replay ou changement d'ECU. Tests loopback adaptés aux lectures d'identité supplémentaires, non exécutés. Coût réseau accru et cadence réelle dépendante du temps de réponse ECU. Aucun checkpoint CI ; C6/DoIP restent ouverts.

## PR117 — séparation des réponses ELM et restauration des en-têtes (25/11/2026)

- C6 Android : les commandes du moteur OBD conservent désormais les véritables séparations de lignes ; le scan Live emploie le décodeur strict PID au lieu de concaténer plusieurs répondants. Mode 09 et inventaire DTC passent temporairement en format ELM sans en-tête pour bénéficier de l'assemblage interne, puis restaurent ATH1/ATS0 sous verrou, même en annulation. Une restauration non confirmée invalide la session physique, jamais une connexion de remplacement. Correction des imports de régression console 29 bits. Régressions écrites non exécutées ; aucun checkpoint CI. C6 et DoIP restent ouverts.

## PR117 — arrêt CAN monitor et corrélation fonctionnelle 29 bits (25/11/2026)

- C6 : arrêt monitor ELM/STN par octet ESC sans CR supplémentaire ; la purge RX Bluetooth bornée ne retarde plus l'interruption d'un flux continu. Attente du prompt obligatoire, données résiduelles ignorées pendant l'arrêt. La console corrèle les réponses fonctionnelles 29 bits séparément par répondant, sans émission automatique sur l'adresse déduite ; conserve RX et ACK pour trames brutes non diagnostiques sans exception de corrélation. Régressions écrites non exécutées ; aucun checkpoint, C6/DoIP restent ouverts.

## PR117 — négociation ELM et restauration du protocole (25/11/2026)

- C6 Android : sélection initiale automatique ATSP0 et relevé ATDPN, preuve ECU 0100 séparée des capacités annoncées ; aucune preuve CAN issue d'un protocole non CAN, aucun ajout artificiel du protocole 6. Le profil CAN 6/7/8/9 n'est conservé que sur réponse positive ; à défaut retour automatique. Console capture/TX, ELM UDS et STN UDS restaurent le profil et l'en-tête fonctionnel correspondant, sans basculement systématique vers 11 bits/500 kbit/s. En cas de nettoyage incomplet, fermeture de la session selon politique existante. Tests écrits non exécutés. Les protocoles legacy annoncés ne constituent pas une validation des décodeurs K-Line/J1850 ; DoIP et C6 restent ouverts. Aucun checkpoint CI.

## PR117 — démarrage Android avec ELM Bluetooth déjà connecté (25/11/2026)

- Retour propriétaire : chargement en boucle si Bluetooth ELM connecté avant ouverture. Cause exacte non reproduite. C6 : accès au service Bluetooth différé ; connexion RFCOMM bornée à 12 s avec fermeture du socket exact sur annulation ; purge RX bornée à 500 ms/8192 octets. Authentification Firebase : attente bornée à 15 s, message FR/EN et retry, invalidation des callbacks tardifs, aucun privilège ni accès hors ligne sur timeout. Régressions écrites non exécutées. Aucun checkpoint CI, C6/DoIP restent actifs.

## PR117 — scan OBD complet sans changement de protocole et parité DTC J2534 (25/11/2026)

- C6 : le scan complet interroge les métadonnées du connecteur déjà configuré, sans ATZ/ATSP imposant CAN 11 bits 500 kbit/s. Les trois modes DTC conservent leur preuve indépendante ; un mode 03 non confirmé n'efface ni les défauts antérieurs ni les résultats 07/0A. J2534 accepte l'indisponibilité explicite de chacun des trois modes en inventaire, tout en rejetant les réponses malformées. Rapport propriétaire J2534 avec lignes par mode, statut partiel et bitmap vide valide. Tests de régression écrits, non exécutés. Le choix initial du protocole et les restaurations après capture restent à auditer ; aucun checkpoint CI, C6/DoIP restent actifs.

## PR117 — preuves OBD réelles dans le rapport fonctionnel (25/11/2026)

- Rapport propriétaire Android : conservation bornée des véritables commandes/réponses OBD de VIN, répondants, bitmaps, DTC et readiness, y compris réponses négatives et NO DATA. Pas de données J2534 synthétiques. Le JSON peut contenir VIN et informations ECU : partage volontaire uniquement. Régressions écrites, non exécutées. Aucun checkpoint, C6/DoIP restent actifs.

## PR117 — fiabilité découverte PID (25/11/2026)

- C6 : cache Mode 01 ELM/J2534 lié à une génération ; découverte incomplète renouvelée après 10 s même non vide, bitmap complet valide même vide conservé, horloge monotone et rejet des opérations obsolètes. Le parseur accepte le remplissage d'une trame CAN physique complète avec PCI cohérent et longueur SAE exacte, et le DLC explicite. Réponses tronquées refusées. Régressions écrites non exécutées ; aucun checkpoint. C6/DoIP restent actifs.

## PR117 — retour Mustang : sélection Live et lecture DTC (25/11/2026)

- Android C6 : le sélecteur Live ne présente que les signaux réellement décodés dans le contexte courant ; disponibilité mémorisée séparément de la fraîcheur des valeurs, cases sélectionnables, catalogue OEM non lu masqué, remise à zéro sur changement véhicule/connexion et rejet des lots obsolètes. Les préférences restent par véhicule/compte. Un PID annoncé mais jamais décodé n'est pas affiché.
- DTC OBD : messages de résultat dans l'écran DTC, zéro défaut vert uniquement après réponse complète ; absence ou réponse partielle distincte. Décodeur prend en charge réponses CAN single-frame avec padding/PCI et réponses ELM multi-lignes numérotées complètes, sans interpréter les fragments ni modifier les ECU. Régressions écrites non exécutées. La cause exacte sur la Mustang reste inconnue faute de traces brutes ; recette CAN/DTC sur Release par le propriétaire. Aucun test/build/checkpoint, C6/DoIP restent ouverts.

## PR117 — séparation lecture VIN et concordance profil (25/11/2026)

- C6 Android : lecture VIN physique et concordance du profil sont deux preuves distinctes ; un VIN Ford valide reste une lecture réussie mais une discordance avec le profil Camaro est signalée séparément. Même filtrage de topologie pour OBD et J2534, y compris VIN absent. Export : un rapport interrompu ne peut être entièrement réussi. Tests écrits, non exécutés ; aucun checkpoint CI. DoIP/C6 restent actifs, aucune qualification matérielle supplémentaire.

## PR117 — retour matériel Mustang Mach-E 2023, diagnostic OBD générique (25/11/2026)

- Rapport physique fourni par le propriétaire : Bluetooth Classic connecté, réponse OBD 7E8 à 0100 et réponse readiness ; VIN exporté vide, lecture VIN/PID/DTC déclarée échouée. Le propriétaire confirme séparément avoir vu le VIN exact de sa Ford à l'écran lors de la connexion. Aucun VIN Ford réel ni contenu brut de réponse n'a été fourni ; pas de preuve matérielle DoIP.
- Correction Android C6 : le rapport enregistre séparément VIN sélectionné et VIN fraîchement observé, vérifie celui-ci avec un délai allongé et un nouvel essai borné (aucune substitution par le VIN enregistré). Un répondant 7E8 ou 29 bits confirmé est un répondant OBD générique, jamais une validation d'ECU GM. Suppression des lignes de topologie GM dans le rapport sans VIN Camaro compatible sélectionné et observé ; aucune déduction d'ECU précis. Protection de la sauvegarde/synchronisation du profil Camaro contre un VIN hors Gen5/6 et des scans automatiques spécifiques au mauvais véhicule. Le scan générique reste disponible.
- PID : vérification de la longueur exacte du bitmap positif et distinction bitmap vide/réponse manquante. DTC : rapport par mode 03/07/0A, zéro code positif distinct d'une absence de réponse, conservation des codes précédents du mode indisponible. Readiness : vérification stricte de la longueur. Ressources et messages FR/EN.
- Régressions écrites, non exécutées. Le motif physique précis des échecs initiaux reste à confirmer sur un nouveau rapport après livraison ; ne pas déclarer Ford compatible avec les fonctions constructeur. Aucun checkpoint CI, aucune compilation ni Release. La priorité reste Camaro ; reprendre DoIP/C6 et valider en fin de protocole selon AGENTS.md.

## PR117 — simulation TCP Android et enregistrement 19/05 individuel décodé (25/11/2026)

- Android : le parcours public read conserve les ports ISO standard (UDP/TCP 13400, TLS 3496) ; un point d'entrée interne readAt permet uniquement les ports alternatifs sur loopback TCP non TLS. Les tests de simulation UDP/TCP empruntent le même parcours réel : découverte, activation, Alive Check, ACK, requête physique exacte, VIN initial et final. Sept opérations (19/04, 19/05 FF, 19/06, 19/18, 19/19 et variantes OBDonUDS 19/04/06), cas concordants et divergents écrits, non exécutés.
- UDS 19/05 individuel décodé sur Connectors, Android, Windows GUI/CLI : DID:longueur documenté, même décodeur structuré que FF mais sélection exacte d'un seul enregistrement et absence distinguée. Réponse originale conservée, refus des enregistrements supplémentaires ou inconnus, VIN F190 avant/après. Parcours brut historique maintenu. CLI Connectors stored-record --record 01 --did-lengths F40D:1 ; Windows --uds-stored-record 0x01 --stored-did-lengths F40D:1 avec VIN. FR/EN.
- Régressions écrites sur les trois plateformes. Aucun test, build ou checkpoint CI déclenché. C6 et DoIP restent ouverts ; ne pas considérer cette simulation écrite comme validée, ni la qualification physique comme acquise. Restent l'audit des fonctions DoIP applicables, les corrections de cohérence et la validation de clôture.

## PR117 — décodage des mémoires DTC utilisateur (25/11/2026)

- UDS 19/18 et 19/19 : parcours structurés Connectors Python, Android et Windows GUI/CLI. Sélection mémoire explicite, écho mémoire/DTC vérifié, longueurs documentées, VIN F190 avant/après et réponse brute originale conservée. Aucun repli vers la mémoire principale.
- CLI Connectors : memory-snapshot-decoded / memory-extended-decoded avec --memory, --dtc, --record et --did-lengths / --record-lengths. Windows : sélecteurs --uds-memory-snapshot / --uds-memory-extended et catalogues existants --snapshot-did-lengths / --extended-record-lengths. Ressources FR/EN.
- Régressions écrites sur trois plateformes et loopback Windows 19/18–19 avec VIN divergent. Aucun test ni build lancé. Restent simulation TCP Android et validation de clôture, autres intégrations DoIP applicables ; C6/DoIP restent actifs. Aucun merge ou Release.
- Référence : https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html (19/18 et 19/19).

## PR117 — données DTC étendues UDS 19/06 décodées (25/11/2026)

- Nouveau parcours 19/06 structuré sur Connectors Python, Android, Windows GUI et CLI : DTC et sélecteur explicites, catalogue numéro d'enregistrement:longueur documenté, découpage exact et conservation brute. 01..EF individuel, FE réglementaire OBD (90..9F), FF tous. Refus des numéros réservés, doublons, longueur inconnue, troncatures et réponses hors du sélecteur.
- UDS F190 et OBDonUDS F802/F810 avant/après sur la même session, sans repli automatique ni interprétation physique inventée. Parcours brut historique conservé. FR/EN.
- CLI Python `extended-decoded --dtc 010203 --record FF --record-lengths 01:2,90:1` (ou `obd-extended-decoded`). Windows `--uds-extended-dtc 0x010203 --uds-extended-record 0xFF --extended-record-lengths 01:2,90:1` (ou sélecteurs OBD), VIN obligatoire. Longueurs illustratives à remplacer par les définitions ECU.
- Tests de régression écrits sur les trois plateformes, scénarios Windows TCP loopback pour 19/04 et 19/06 avec VIN final divergent, exemples Connectors et aide Windows actualisés. Aucun test, build ou checkpoint CI déclenché. DoIP/C6 restent en cours ; aucune fusion ou Release. Reste notamment à compléter les parcours Android de simulation TCP, les autres fonctions applicables et la validation de clôture.
- Référence du format : https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html (19/06).

## PR117 — snapshots UDS 19/04 décodés et consolidation 19/05 FF (25/11/2026)

- Nouveau parcours 19 04 pour un DTC et un numéro explicite (00 réglementaire si applicable, 01..FE individuel, FF multiple), avec catalogue DID:longueur documenté. Android et Windows GUI/CLI, Connectors Python. Modes UDS (F190) et OBDonUDS (F802/F810) vérifiés avant/après sur la même session. Les valeurs historiques sont conservées en octets, sans extrapolation physique ni conversion en live data.
- Connectors : `snapshot-decoded --dtc 010203 --record FF --did-lengths F40C:2,F40D:1` et `obd-snapshot-decoded`. Windows : `--uds-snapshot 0x010203 --uds-snapshot-record 0xFF --snapshot-did-lengths F40C:2,F40D:1` avec VIN attendu, ou variantes `--obd-snapshot` et `--obd-snapshot-record`. Exemples de syntaxe : longueurs à confirmer pour chaque ECU. FR/EN.
- Refus des DTC/records inattendus, doublons, DID inconnus et troncatures. Pas de basculement automatique UDS vers OBDonUDS. Conservation du parcours brut existant.
- Régressions codec/CLI des snapshots écrites sur les trois plateformes, et scénarios 19/05 FF Windows loopback avec contrôle VIN et Android validation transport écrits. Aucun test ou build exécuté. Le loopback complet Android et les scénarios d'intégration des snapshots restent à compléter à la clôture. C6/DoIP restent actifs ; aucune CI intermédiaire, aucune fusion, Release ou qualification physique.
- Format de référence : https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html (19/04, 19/05).

## PR117 — lecture multiple UDS 19/05 FF (25/11/2026)

- Android, Windows GUI/CLI et Connectors CLI Python : requête 19 05 FF, catalogue DID:longueur explicite issu de la documentation ECU, vérification F190 avant/après sur la même session, découpage des enregistrements et DID, conservation des réponses brutes, refus des enregistrements dupliqués, DID inconnus et troncatures. Aucun calcul de longueur OEM par heuristique.
- CLI Python : `stored-records --did-lengths F40C:2,F40D:1` ; Windows : `--uds-all-stored-records F40C:2,F40D:1` avec VIN et adresses de la cible. Exemples de syntaxe uniquement, longueurs à confirmer pour chaque ECU. Interface FR/EN.
- Régressions codec, CLI et identité Python écrites, non exécutées. Restent à compléter : loopback de clôture Windows/Android, couverture d'intégration et validation finale DoIP. Aucun checkpoint CI, fusion, Release ou qualification physique.
- Référence structure du service : https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html#reportdtcstoreddata-by-record-number-0x05 (section 19/05).

## PR117 — consolidation 19/05 (25/11/2026)

- Implémentation Windows concurrente GUI/CLI conservée ; les deux méthodes redondantes et la validation dupliquée ajoutées pendant cette reprise ont été retirées. Un seul parcours typé Windows demeure.
- Le nombre de DID annoncé par l'ECU est désormais exposé séparément dans le JSON des CLI Python/Windows et les écrans Android/Windows (FR/EN), sans prétendre déduire les longueurs des DID constructeur. La réponse brute est préservée ; absence de donnée distincte.
- Aucun test, compilation ou checkpoint CI lancé. DoIP/C6 restent ouverts ; 19/05 FF et les autres intégrations DoIP restent à examiner avec définitions réelles et validation de clôture.

## PR117 — UDS 19/05 Windows GUI/CLI (25/11/2026)

- Parité Android/Windows/Connectors : numéro explicite, interface FR/EN, CLI --uds-stored-record avec VIN obligatoire, F190 avant/après, validation de l'écho et des bornes minimales DID, préservation brute et absence de données distincte. Tests parseur, codec, aide et loopback écrits, non exécutés. FF multi-record reste non proposé sans définition OEM. Aucun checkpoint CI ; DoIP/C6 restent ouverts.

## PR117 — UDS 19/05 Connectors et Android (25/11/2026)

- Lecture d'un enregistrement mémorisé explicite 00..FE, sans FF (découpage multi-record OEM non défini), F190 avant/après, réponse vide distinguée, écho et structure DID minimale contrôlés, données brutes conservées. Actions CLI Python/Android FR/EN ; 00 réservé au cas réglementaire compatible. Tests écrits non exécutés. Windows reste à raccorder. Référence https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html . Aucun checkpoint CI.

## PR117 — consolidation des lectures UDS historiques (25/11/2026)

- Loopback Windows 19/0F–13 : contrôle des requêtes physiques exactes, du résultat séparé et de la revalidation du VIN avant publication. Exemples CLI Connectors FR/EN. Tests écrits non exécutés ; aucun checkpoint CI. DoIP/C6 restent ouverts, prochaine analyse 19/05 et intégrations restantes.

## PR117 — lectures UDS historiques Windows (25/11/2026)

- Parité 19/0F–13 sur les trois plateformes : cinq actions GUI FR/EN, CLI explicite, contrôles identité, masque, longueur, écho et données brutes, scope miroir ou émissions historiques sans repli vers OBDonUDS. Tests écrits non exécutés, aucun checkpoint CI. DoIP/C6 toujours actifs.

## PR117 — lectures historiques Android (25/11/2026)

- UDS 19/0F–13 : cinq actions FR/EN distinctes, validation des paramètres avant réseau, rapport historique séparé de la liste active, F190 avant/après et contrôles des réponses. Tests écrits non exécutés. Windows reste à raccorder ; aucun checkpoint CI.

## PR117 — lectures UDS historiques Connectors (25/11/2026)

- 19/0F, 10, 11 (mémoire miroir) et 19/12, 13 (émissions historiques) : commandes explicites, F190 avant/après, résultats distincts des DTC actifs et OBDonUDS, contrôles masque/longueur/écho/doublons, conservation brute des détails. Ces variantes retirées en ISO 14229-1:2020 restent utiles uniquement pour ECU compatibles, aucun repli automatique. Référence https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html . Tests écrits non exécutés ; Android/Windows à raccorder. Aucun checkpoint CI.

## PR117 — parcours applicatifs des données étendues (25/11/2026)

- Tests loopback Windows pour 19/16 et 19/1A avec contrôle VIN final et conservation des octets, exemples utilisateur Connectors FR/EN. Tests écrits non exécutés. Aucun checkpoint CI. Taille uniforme documentée indispensable ; les dispositions variables OEM restent à fournir. DoIP/C6 actifs.

## PR117 — données étendues par numéro Windows (25/11/2026)

- Parité UDS 19/1A et 19/16 Android/Windows/Connectors : GUI/CLI FR/EN, numéro explicite et taille uniforme documentée pour les données, VIN F190 avant/après, corrélation écho/longueur/statut/doublons. Tests écrits non exécutés ; aucun checkpoint CI. DoIP/C6 restent actifs.

## PR117 — données étendues par numéro Android (25/11/2026)

- UDS 19/1A et 19/16 : deux actions FR/EN, numéro explicite et taille uniforme documentée pour 19/16, vérification écho/longueur/doublons/masque, F190 avant/après, résultat distinct de la liste active. Tests écrits non exécutés. Windows reste à raccorder, aucun checkpoint CI.

## PR117 — données étendues par numéro Connectors (25/11/2026)

- UDS 19/1A inventorie les DTC qui prennent en charge un numéro d'enregistrement ; 19/16 lit ses données pour plusieurs DTC avec taille uniforme explicite (pas de découpage OEM inventé). Écho, doublons, longueur et statut disponible contrôlés ; statut nul accepté pour 1A. VIN F190 avant/après, TLS par défaut, CLI FR/EN, tests écrits non exécutés. Référence : https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html . Android/Windows restent à raccorder ; aucun checkpoint CI.

## PR117 — consolidation du parcours mémoire DTC (25/11/2026)

- Libellé Android corrigé pour distinguer liste filtrée et inventaire, test loopback Windows de relecture VIN après 19/17, exemple Connectors FR/EN. Tests écrits, non exécutés ; aucun checkpoint CI. DoIP/C6 toujours en cours.

## PR117 — mémoire DTC sélectionnée Windows GUI/CLI (25/11/2026)

- Parité UDS 19/17–19 Android/Windows/Connectors : mémoire explicite, trois actions FR/EN, CLI avec VIN et sélecteur exclusif, F190 avant/après sur même session, corrélation mémoire/DTC/record, résultats bruts séparés de la liste active. Tests de codec/CLI/aide écrits non exécutés ; aucun checkpoint CI. DoIP/C6 restent ouverts.

## PR117 — mémoire DTC sélectionnée Android (25/11/2026)

- UDS 19/17–19 : décodeur commun, vérification mémoire/DTC/record, masque et octets bruts, session DoIP F190 avant/après, trois actions FR/EN et résultat distinct des défauts actifs. Tests écrits non exécutés. Windows reste à raccorder. Aucun checkpoint CI.

## PR117 — lecture DTC en mémoire sélectionnée Connectors (25/11/2026)

- CLI Python : `memory-dtcs`, `memory-snapshot`, `memory-extended` (UDS 19/17–19), mémoire explicite, masque ou DTC/enregistrement explicites, vérification de l'écho mémoire/DTC/enregistrement, statut et octets OEM conservés, VIN F190 avant/après, aucun contournement de sécurité OEM. Tests écrits non exécutés. Android et Windows restent à raccorder. Référence de format https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html . Aucun checkpoint CI.

## PR117 — journal JSONL DoIP Connectors (25/11/2026)

- Parité CLI Windows : `--trace-file` conserve les PDU UDP/TCP/TLS réels, horodatés UTC, avec attribution des événements d'inventaire à l'ECU concerné. Création exclusive, permissions POSIX restrictives, fichier distinct des sauvegardes/profils/certificats, fermeture et traces partielles conservées lors d'un échec. Les erreurs de journal interrompent le parcours. Aide et exemple FR/EN ; tests écrits non exécutés. Aucun checkpoint CI.

## PR117 — parcours applicatifs DTC permanents (25/11/2026)

- Tests loopback Windows F190 → 19/15 → F190 avec rejet du VIN final différent, sélection CLI Connectors TLS/UDS et exemple utilisateur FR/EN. Tests écrits, non exécutés ; aucune campagne CI.

## PR117 — DTC permanents UDS Windows GUI/CLI (25/11/2026)

- Parité Android/Windows/Connectors : lecture 19/15, bouton FR/EN et CLI --uds-permanent-dtcs avec VIN obligatoire, vérification F190 avant/après, résultat distinct du WWH et de la liste active. Tests parseur, transport et aide écrits sans exécution. Aucun checkpoint CI ; DoIP/C6 restent ouverts.

## PR117 — DTC permanents UDS Android (25/11/2026)

- Bouton FR/EN distinct, émission 19/15 sur session DoIP F190 vérifiée avant/après, statut courant brut et masque disponibles, liste active inchangée. Tests du décodeur écrits sans exécution. Windows reste à raccorder. Aucun checkpoint CI.

## PR117 — DTC permanents UDS Connectors (25/11/2026)

- CLI Python `permanent-dtcs` : lecture 19/15 distincte de 19/0A et WWH 19/55, VIN F190 avant/après, statut brut et masque de disponibilité, liste vide possible, refus des réponses tronquées, doublons et bits non disponibles. Tests écrits non exécutés. Référence de format : https://udsoncan.readthedocs.io/en/latest/_modules/udsoncan/services/ReadDTCInformation.html . Android et Windows restent à raccorder. Aucun checkpoint CI.

## PR117 — attribution des traces d'inventaire Connectors (25/11/2026)

- Parité des journaux multi-ECU avec Android/Windows : `--trace` ajoute inventory_target sur les échanges réels de découverte et de session associés à chaque adresse, sans attribuer la découverte finale à un ECU. Une erreur du collecteur de trace interrompt l'inventaire au lieu de simuler un ECU non vérifié. Tests écrits, non exécutés. Aucun checkpoint CI.

## PR117 — inventaire OBDonUDS Android et consolidation des sources (25/11/2026)

- Parité de l'inventaire OBDonUDS Android/Windows/Connectors : bouton FR/EN, F802/F810 stables par session, résultat VIN séparé du protocole brut, erreurs d'identité ECU traitées comme non vérifiées, pas de repli F190. Tests écrits sans exécution.
- Deux doublons Kotlin sous `com/camaradiag/gen5/ui` avaient été introduits dans les lots sévérité/inventaire. Les versions récentes ont été comparées aux versions canoniques : uniquement des insertions, aucune ligne ancienne perdue. Intégration dans `com/camarodiag/gen5/ui` et suppression des chemins erronés. Aucun test/compilation ni marqueur de clôture modifié. DoIP/C6 restent ouverts.

## PR117 — inventaire OBDonUDS Windows GUI/CLI (25/11/2026)

- Parité Windows/Connectors : `--obd-ecu-inventory` et bouton FR/EN, mêmes adresses explicites, F802/F810 vérifiés deux fois sur chaque session. VIN et protocole distingués, aucun repli F190, journal par ECU et annulation existants. Tests de parseur écrits, non exécutés. Android reste à raccorder. Aucun checkpoint CI.

## PR117 — inventaire OBDonUDS explicite Connectors (25/11/2026)

- Nouvelle opération `obd-ecu-inventory --ecus 1234,1235` : identité réglementaire F802/F810 vérifiée deux fois sur chaque session, VIN vérifié distingué de la prise en charge OBDonUDS ; valeur F810 inconnue conservée, aucune bascule F190. Échec TLS interrompt l'inventaire, VIN non ASCII traité comme erreur de protocole. CLI FR/EN et tests écrits, non exécutés. Android et Windows restent à raccorder pour cette variante. Aucun checkpoint CI.

## PR117 — inventaire explicite des ECU DoIP Android (25/11/2026)

- Parité Android/Windows/Connectors : liste d'adresses explicites (1–16), session F190 par ECU, sélection et revalidation de passerelle, arrêt si contexte révoqué ou TLS échoue. Résultat vérifié/non vérifié distinct, journal par adresse, UI FR/EN, aucune topologie ni capacité OEM déduite. Tests parseur et contexte écrits, non exécutés. Aucun checkpoint CI. Récupération des blobs Git du HEAD après réponses 404 intermittentes de l'API Contents.

## PR117 — inventaire explicite des ECU dans la console Windows (25/11/2026)

- Champ liste d'adresses logiques et bouton d'inventaire FR/EN ; utilisation du moteur partagé CLI, contrôles VIN/passerelle, résultats vérifiés/non vérifiés distincts, annulation, journal UDP et PDU attribués à chaque ECU. Le champ ECU unitaire n'est pas utilisé pour cet inventaire. Pas de modification du profil ni de découverte d'adresses supposées. Android reste à raccorder. Aucun test/compilation lancé.

## PR117 — inventaire explicite ECU DoIP Windows CLI (25/11/2026)

- Parité Connectors/Windows CLI : --ecu-inventory 1234,1235 remplace --ecu, 1–16 adresses connues, F190 par session, revalidation passerelle à chaque étape et en fin, TLS sans repli, résultat vérifié/non vérifié. Journal PDU attribué à l'adresse réellement interrogée. Annulation et refus des arguments concurrents. Moteur réutilisable pour GUI Windows. Tests écrits sans exécution ; Android et GUI Windows restent à raccorder. Aucun checkpoint CI.

## PR117 — inventaire explicite des ECU DoIP Connectors (25/11/2026)

- CLI ecu-inventory --ecus 1234,1235 (1 à 16 adresses explicites, aucun balayage implicite), VIN/testeur obligatoires, TLS par défaut, sessions séparées, F190 et revalidation de passerelle avant chaque ECU et après inventaire. Distinction VIN vérifié/non vérifié ; NRC conservé, échec TLS bloque sans repli, aucune capacité de programmation déduite. Tests écrits non exécutés. Android et Windows à raccorder. Aucun checkpoint CI.

## PR117 — validation stricte des paramètres CLI DoIP Windows (25/11/2026)

- Refus avant réseau des options inconnues, doublons, valeurs manquantes et sorties RX déjà présentes ; découverte/statut et échanges typés/bruts concernés. Adresse logique hexadécimale validée sans suppression incorrecte des zéros initiaux. Les commandes brutes restent bidirectionnelles. Tests de régression écrits non exécutés, aucune CI.

## PR117 — lectures sévérité DTC Windows (25/11/2026)

- Parité UDS 19/07–09 Android/Windows/Connectors : masques sévérité/statut, sévérité et unité fonctionnelle brutes, sélection DTC 24 bits, compte/format, validation réponse et F190 avant/après. Console FR/EN et CLI --uds-severity-count/list/status/info. Tests écrits non exécutés, aucun checkpoint CI. Sources de format : py-uds et udsoncan ReadDTCInformation.

## PR117 — lectures sévérité DTC Android (25/11/2026)

- UDS 19/07, 19/08, 19/09 : masques explicites, sévérité/unité/statut bruts et compte/format, réponse vide possible, validation du DTC et des filtres, F190 avant/après, résultats séparés de la liste active. UI FR/EN, tests écrits non exécutés. Windows reste à raccorder. Aucun checkpoint CI.

## PR117 — sévérité DTC UDS 19/07 à 19/09 Connectors (25/11/2026)

- CLI severity-count, severity-dtcs (masques sévérité/statut explicites), severity-info (DTC 24 bits). Compte et format bruts, sévérité/unité fonctionnelle/statut séparés ; résultat vide possible, corrélation du DTC et des masques, refus des doublons/troncatures et revalidation F190. Référence de format https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html et https://udsoncan.readthedocs.io/en/latest/_modules/udsoncan/services/ReadDTCInformation.html ; pas de conformité matérielle revendiquée. Tests écrits non exécutés. Android et Windows à raccorder.

## PR117 — trace et paramètres de session sauvegarde DoIP Windows (25/11/2026)

- CLI --doip-backup : --trace-file distinct du ZIP, conserve découverte et deux lectures réelles même en cas d'interruption ; options P2/P2*, routage ISO 13400 et quatre octets OEM explicites. Contrôle VIN/passerelle au départ et revalidation avant ouverture du socket, identité ECU et double lecture inchangées. Vérification offline reste sans réseau. Aide FR/EN et tests écrits non exécutés, aucune CI. Ne pas partager les traces contenant des données ECU ou VIN sans autorisation.

## PR117 — journal UDP DoIP Windows (25/11/2026)

- Extension de --trace-file aux découvertes unicast/broadcast/multicast IPv6, écoute passive IPv4/IPv6, état/alimentation, et identification UDP des sessions vérifiées. JSONL conserve endpoint, port, direction, données réelles, UTC. Les opérations de découverte peuvent être journalisées avant consentement ; aucun PDU diagnostique mutateur avant consentement. Monitoring revalide l'identité de passerelle avant connexion. Tests écrits non exécutés. Aucun checkpoint CI.

## PR117 — journal DoIP CLI Windows (25/11/2026)

- --trace-file NEW.jsonl pour les échanges bruts, séquences vérifiées, lectures typées et monitoring simple/multi-DID. Journal des PDU réels de session TCP/TLS (contrôle et diagnostic), UTC/direction/adresses/octets, écrit après consentement, CreateNew, jamais écrasé, conservé en cas d'erreur ; distinction du fichier RX. Ne couvre pas encore les datagrammes UDP de découverte. Aide FR/EN et tests écrits non exécutés. Aucun checkpoint CI.

## PR117 — UDS 19/14 Windows GUI/CLI (25/11/2026)

- Parité FDC Android/Windows/Connectors : compteurs signés, aucune confusion avec statut DTC, réponse vide valide, rejet des doublons et longueurs incohérentes, F190 avant/après. Bouton FR/EN et --uds-fault-counters. Tests écrits sans exécution. Aucun checkpoint ni clôture DoIP.

## PR117 — UDS 19/14 compteurs FDC Android/Connectors (25/11/2026)

- Lecture des compteurs de détection FDC, distincts des statuts DTC ; octet signé préservé y compris valeur nonpositive atypique, aucun défaut confirmé inféré, réponse vide possible, doublons et troncatures refusés. F190 avant/après, UI FR/EN et CLI fault-counters. Références : https://uds.readthedocs.io/en/stable/pages/knowledge_base/service.html#reportdtcfaultdetectioncounter-0x14 et documentation DTC py-uds. Tests écrits non exécutés. Windows reste à raccorder.

## PR117 — DTC historiques Windows GUI/CLI (25/11/2026)

- Parité avec Android et Connectors : quatre boutons FR/EN, --uds-historical-dtc 0x0B..0x0E, VIN F190 avant/après, statut courant brut, zéro ou un DTC. Aucun remplacement de la liste active ni interprétation OEM inventée. Tests écrits non exécutés ; aucun marqueur CI.

## PR117 — DTC historiques Connectors (25/11/2026)

- CLI Python : first-failed-dtc, first-confirmed-dtc, recent-failed-dtc et recent-confirmed-dtc ; VIN F190 vérifié avant/après, résultat JSON nul ou unique, statut courant brut et détection des réponses incohérentes. Tests écrits non exécutés. Windows reste à raccorder. Aucun checkpoint CI.

## PR117 — DTC historiques UDS 19/0B à 19/0E Android (25/11/2026)

- Premier/dernier DTC détecté et premier/dernier confirmé : quatre actions de lecture, résultat absent ou unique, statut courant brut, F190 avant/après, pas de modification de la liste de défauts actifs. Android FR/EN et tests écrits. Windows et Connectors à raccorder. Référence : https://udsoncan.readthedocs.io/en/latest/udsoncan/client.html (ReadDTCInformation). Aucune CI lancée.

## PR117 — identité des mesures DID simples (25/11/2026)

- Windows GUI/CLI : monitoring DID simple vérifie chaque VIN F190/F802 et protocole F810 demandé avant publication, comme le multi-DID. Connectors : lecture DID simple via méthode vérifiée ; F190 renvoie le VIN observé sans seconde lecture contradictoire, autres DID revalidation sur la même session. Tests de régression écrits, non lancés. DoIP reste actif, aucune CI intermédiaire.

## PR117 — corrélation des DID d'identité dans les mesures (25/11/2026)

- Multi-DID Android/Windows/Connectors et monitoring Android/Connectors : si la réponse contient le VIN ou la valeur F810 correspondant à la route, vérifier également ces octets dans chaque réponse avant publication (pas seulement avant/après la campagne). CLI Windows : même contrôle des DID simples et multi-DID. Budget Android identification stable ajusté. Exemples utilisateur Connectors FR/EN, tests écrits. Aucun lancement CI.

## PR117 — identification OBDonUDS vérifiée sur DoIP (25/11/2026)

- CLI Windows : --obd-identify avec --expected-vin, TLS par défaut, découverte F802/F810 répétée sur même session, résultat JSON stable y compris valeur non reconnue sans prétendre à une route OBDonUDS. Android, Windows GUI et Connectors CLI : même revalidation d'identification. CLI Windows contrôle également les données DID simples OBD, notamment VIN et valeur F810 retournés. Aide FR/EN, tests écrits, aucun lancement CI.

## PR117 — cohérence des routes et index vide DoIP (25/11/2026)

- Transport Android/Python/Windows : accepte 59 03 sans enregistrement (index réellement vide), refuse les index tronqués et les inventaires 59 0A sans masque. Android : sélecteur de route OBDonUDS vérifie l'opération DTC ou monitoring selon la demande et n'admet pas la programmation sur ces capacités de lecture ; vérification de contexte avant toute émission de session et avant restitution, sauvegardes liées au profil sélectionné. Tests de régression écrits, aucune exécution ni compilation intermédiaire.

## PR117 — monitoring multi-DID DoIP Windows (25/11/2026)

- Parité GUI et CLI Windows : --monitor-dids / --obd-monitor-dids, une requête 22 par échantillon, géométrie explicite et limitée à 4096 octets, F190 ou F802/F810 avant/après même connexion, annulation, mesures provisoires puis verdict final, journal existant. Aide FR/EN, tests parseur et loopback écrits. Aucun build/test lancé ni marqueur de clôture modifié.

## PR117 — monitoring multi-DID DoIP Android et Connectors (25/11/2026)

- Monitoring UDS/OBDonUDS de plusieurs DID dans chaque requête 22, disposition explicite, plafond 4096 octets par échantillon, validation de chaque réponse et identité avant/après sur le même socket. Android : UI FR/EN, rendu par DID, export CSV avec une ligne par DID et même index d'échantillon ; journal complet et fenêtre récente glissante. Connectors CLI : monitor-dids et obd-monitor-dids avec --layout, mesures provisoires sur stderr et résultat complet uniquement après revalidation. Tests écrits, non exécutés. Windows reste à raccorder. Aucun marqueur CI modifié.

## PR117 — inventaire DTC et index snapshots DoIP Windows (25/11/2026)

- Parité Windows GUI/CLI avec Android et Connectors : UDS 19/0A et 19/03, F190 avant/après, résultats séparés des défauts actifs. Ressources FR/EN, sélecteurs CLI exclusifs avec VIN, tests écrits. Aucun test ni compilation lancé. Référence de comportement : documentation udsoncan ReadDTCInformation (ISO 14229), sans revendication de conformité normative complète.

## PR117 — inventaire DTC et index snapshots DoIP Android/Connectors (25/11/2026)

- UDS 19/0A (DTC pris en charge, y compris statut zéro) et 19/03 (paires DTC/enregistrement snapshot), Android et Connectors CLI. Même session vérifiée F190 avant/après ; résultats séparés de la liste des défauts actifs. Rejet des doublons, formats tronqués et statuts non disponibles. Ressources FR/EN et tests écrits, non exécutés. Windows reste à raccorder. Aucun marqueur CI modifié.

## PR117 — DTC OBDonUDS classiques sur DoIP (25/11/2026)

- Android et Windows GUI : accès distinct aux DTC 19/01, 19/02, 19/04 et 19/06 sur ECU OBDonUDS ; vérification F802/F810 avant et après sur la même session, aucun repli implicite F190, journal et contrôle de contexte conservés. Android n'écrase pas la liste générique par le rapport réglementaire. Connectors CLI : obd-dtcs, obd-count, obd-snapshot, obd-extended ; tests Python ajoutés, aide FR/EN. Aucun lancement de tests ou compilation intermédiaire ; à valider uniquement à la fin du protocole. Aucune qualification physique.

## PR117 — décision propriétaire du 25/11/2026 : tests uniquement en clôture

- Aucun test ni build de validation pendant les développements, y compris les sous-lots et les seuils de 50 commits. Écrire les tests sans les exécuter. Une seule campagne à la fin effective du chantier ou du protocole ; corriger en bloc si échec. Gate global : nouveau marqueur .github/ci-final-checkpoint-request.txt avec scope: chantier ou scope: protocol, nom et critères de clôture. Ancien marqueur historique inactif. Pas de campagnes parallèles redondantes. C6/DoIP toujours actifs ; poursuivre les fonctionnalités sans déclencher de CI. Run 45556023009 lancé avant cette décision ; aucun nouveau run demandé ici.

## PR117 — parité CLI Windows DTC UDS génériques (25/11/2026)

- Services 19/01, 19/02, 19/04, 19/06 accessibles via sélecteurs UDS génériques distincts des commandes OBDonUDS. VIN F190 avant/après, TLS par défaut, choix exclusif, DTC et enregistrement explicites. Aide FR/EN et tests parseur. Le checkpoint 45556023009 valide le parent fc4270f754e509d2409acb14eb0787a712c2d63b, pas ce complément ; prochaine validation groupée nécessaire. Pas de qualification matérielle ni clôture C6/DoIP.

## PR117 — parité CLI Windows UDS DID et scaling (25/11/2026)

- Checkpoint 45553393453 sur ba811a855c781d93471a8adc8a02213a28ac2e07 : succès des 12 jobs techniques (revue ignorée PR draft). CLI Windows ajoute les sélecteurs exclusifs --uds-did et --uds-scaling-did (SID 22/24), VIN obligatoire, F190 avant/après, TLS par défaut, validation SID/DID/données non vides, JSON brut et aide FR/EN. Tests de parseur et décodage ajoutés. Ce nouveau lot nécessite validation ; aucune qualification physique ni clôture C6/DoIP.

## PR117 — vérification finale des défauts DoIP (25/11/2026)

- Android, Windows GUI et Connectors CLI : lectures DTC 19/01, 19/02, 19/04, 19/06 vérifient désormais le VIN F190 avant et après sur la même session, sans publier le résultat typé si l'identité a changé. Trace brute conservée. Tests Windows loopback et Python ajoutés. Checkpoint groupé demandé ; aucun résultat de compilation ou qualification matérielle revendiqué.

## PR117 — correction du simulateur Windows (25/11/2026)

- Checkpoint 45548303381 : compilation Windows 0 erreur/0 avertissement, autotest interrompu car serveur simulé ne répondait pas aux nouvelles vérifications F802/F810 de fin de lecture. Scénario ajusté au véritable dialogue, cas de protocole changé ajouté. Aucun retrait de test ni de contrôle de production. Nouvelle validation nécessaire.

## PR117 — service UDS 24 DoIP (25/11/2026)

- Lecture réelle ReadScalingDataByIdentifier sur Android, console Windows et CLI Connectors (`scaling-did --did F40C`). Contrôle du DID, données non vides, VIN relu avant/après même session, retour brut sans coefficients inventés. Interface FR/EN et tests Python. Postérieur au checkpoint 45548303381 ; pas encore validé par CI.

## PR117 — checkpoint multi-DID et contrôle d'identité (25/11/2026)

- Connectors CLI : revalidation F190 après la lecture simple UDS, avant retour JSON. Validation groupée demandée pour la parité multi-DID Android/Windows/Python, CLI Windows vérifiée, corrélation SID/DTC/WWH et lectures simples. Checkpoint précédent 45501625699 vert 12/12. Aucune fusion ni qualification matérielle.

## PR117 — vérification après lecture DID (25/11/2026)

- Lecture simple DoIP Android/Windows : rejet des données vides et relecture de l'identité avant publication du résultat (F190 pour UDS, F802/F810 pour OBDonUDS). Connectors : même protection OBDonUDS, test de changement du protocole en cours de session. Checkpoint WWH 45501625699 terminé avec 12/12 jobs techniques réussis ; lot multi-DID et durcissement suivants à valider.

## PR117 — corrélation UDS DoIP Android/Connectors (24/11/2026)

- Alignement du contrôle de réponse brute sur le comportement déjà présent Windows : SID 24, DTC 19/04 et 19/06 avec identifiant 24 bits et numéro de record spécifique (sélecteurs FE/FF préservés), groupe fonctionnel WWH et groupe 19/56. Les réponses contradictoires sont refusées au niveau transport, pas seulement dans les écrans spécialisés. Tests positifs/négatifs ajoutés, aucune restriction d’émission SID.

## PR117 — lecture CLI Windows vérifiée (24/11/2026)

- Les lectures typées existantes (WWH/OBD/DTC/multi-DID) peuvent sélectionner explicitement `--expected-vin` : relecture passerelle après consentement, F802/F810 pour OBD ou F190 pour multi-DID générique avant/après sur même session, TLS par défaut dans ce mode, options P2/P2* bornées, résultat complet seulement après vérification. `--obd-verified true` pour multi-DID réglementaires ; échange brut historique et séquence bidirectionnelle conservés. Tests de parseur ajoutés, validation à venir.

## PR117 — multi-DID console Windows DoIP (24/11/2026)

- Accès UI FR/EN aux requêtes multi-DID UDS/OBDonUDS, format CLI Windows déjà existant conservé (0xHHHH:longueur), vérification VIN et protocole avant/après sur une connexion unique, décodage via UdsMultiDidCli et journal existant. Validation à venir avec Android et Connectors multi-DID.

## PR117 — multi-DID Android DoIP (24/11/2026)

- Requête UDS 22 multi-DID et variante OBDonUDS, géométrie explicitement fournie, validation avant réseau, vérification VIN/protocole avant/après sur le même socket. Interface FR/EN, contrôles stricts d’ordre/longueur/doublons, tests ajoutés. Ce lot est postérieur au checkpoint WWH 45501625699.

## PR117 — multi-DID Connectors DoIP (24/11/2026)

- CLI `dids` / `obd-dids --layout DID:longueur,...` : vraie requête UDS 22 multi-DID, longueur explicite d'après définitions ECU, décodage strict des réponses dans l'ordre, limites et refus des doublons/troncatures. Identité vérifiée avant/après sur même connexion ; F802/F810 pour OBDonUDS. Aucun scaling supposé. Tests ajoutés, postérieurs au checkpoint WWH 45501625699 en cours.

## PR117 — checkpoint WWH multi-plateforme (24/11/2026)

- Run 45498873150 sur 876015f : réussi. Nouveau checkpoint demandé sur 81013db5c12775018c1c8b397f959e76cdce8275 incluant WWH Python, Android et Windows. Aucune qualification matérielle ni fusion.

## PR117 — WWH-OBD sur DoIP Windows GUI (24/11/2026)

- Console Windows : accès 19/42, 19/55, 19/56 avec masques et groupe explicites ; lecteur vérifié F802/F810 avant et après sur le même socket, réutilisation des codecs Windows, journal, annulation et réglages TLS/routage existants ; rendu FR/EN. À valider avec le lot Android/Python.

## PR117 — WWH-OBD sur DoIP Android (24/11/2026)

- Services 19/42, 19/55 et 19/56 groupe émissions 33 intégrés au transport Android, sélection explicite des masques et du groupe, vérification F802/F810 avant et après sur la même connexion, décodage structuré des défauts et métadonnées FR/EN. Aucune signification OEM inventée, aucune substitution de la liste DTC générale par un rapport filtré. Tests de codec et erreurs ajoutés. CI 45498873150 réussie sur 876015f ; ce nouveau lot et le précédent Python restent à valider.

## PR117 — services WWH-OBD Connectors sur DoIP (24/11/2026)

- CLI Python : `obd-wwh-dtcs`, `obd-wwh-permanent`, `obd-wwh-readiness` ; services 19/42, 19/55, 19/56 groupe émissions 33, selon les codecs existants Windows. Contrôle F802/F810 avant et après, même ECU/même socket, validation groupe et longueurs, décodage DTC/statut/sévérité sans inventer de signification OEM. Tests d’échanges et refus ajoutés ; nouveau lot postérieur au checkpoint 876015f, non validé matériellement.

## PR117 — checkpoint de correction Android (24/11/2026)

- Validation groupée demandée sur le lot corrigé b618d3477a3e42a8a4f5d0526abe5febb6a113dc. Aucun essai matériel ni Release.

## PR117 — correction du gate Android (24/11/2026)

- Run 45459305032 sur 8c2c607 : Windows, firmware, natif, Python et vérification de révision verts (11/12 jobs techniques). Android : InvalidTestClassError dans DoIpGatewayControlTraceTest ; deux tests runBlocking inféraient un retour Future.get non-void. Retour Unit explicite, aucune suppression de tests. Correction des trois avertissements Kotlin relevés (visibilité copy des cibles/certificats et propriété système nullable). Nouvelle validation nécessaire.

## PR117 — validation groupée DoIP (24/11/2026)

- Run 45456278234 sur d3e91f2 : 12/12 jobs techniques réussis. Demande du checkpoint suivant contenant 3a129db2739762ef77288a95cb01b8a478776901 : Android EID/IPv6/écoute passive et sélection interface, contrôle de passerelle Android/Windows/Python, P2/P2* monitoring CLI Windows. Le lot est implémenté, pas encore validé sur ce SHA. Ne pas déclarer le DoIP physiquement qualifié ni clôturer C6/C8.

## PR117 — sélection de l’interface IPv6 Android (24/11/2026)

- Liste réseau réelle, hors thread UI, des interfaces actives non-loopback capables de multicast IPv6 link-local ; menu de sélection FR/EN, saisie manuelle conservée. Aucun choix automatique, nouvelle lecture à l’ouverture du menu. La sélection reste à qualifier sur le matériel réel.

## PR117 — découverte réseau DoIP Android IPv4 passive et IPv6 (24/11/2026)

- Découverte active IPv6 link-local ff02::1 sur index d’interface explicitement choisi et écoute passive IPv4/IPv6, limitée au VIN sélectionné ; même collecte bornée et refus des identités concurrentes. L’écoute n’émet aucune demande diagnostique ou UDP DoIP ; verrou multicast Wi-Fi Android limité à la durée de l’opération, libéré sur annulation/erreur. Boutons FR/EN et tests de filtrage scope ajoutés. Interface numérique requise, multicast physique à qualifier. Validation groupée nécessaire.

## PR117 — parité monitoring CLI et contrôle Connectors (24/11/2026)

- Monitoring Windows CLI : P2/P2* optionnels dans le délai global, appliqués à toutes les requêtes de la session (identification et mesures), validation avant connexion, aide FR/EN. Connectors Python : contrôle d’identité avant status/power, contrôle du type de nœud annoncé dans EntityStatus, refus de substitution sans émission du contrôle. Tests ajoutés. Aucune qualification physique ; validation groupée à venir.

## PR117 — identité passerelle et IPv6 Windows (24/11/2026)

- Statut et alimentation DoIP dans la console Windows : revalidation de l’identité sélectionnée avant requête, sans effacer la sélection sur erreur de contrôle ; nouvelle découverte explicite nécessaire pour substituer la passerelle. Adresses IPv4 multicast et IPv6 link-local sans scope rejetées en unicast, scope exclu du nom TLS sans désactiver la vérification du certificat. Tests de validation ajoutés. Checkpoint précédent 45456278234 vert ; ce lot reste à valider.

## PR117 — contrôle d’identité avant status/power Android (24/11/2026)

- Les requêtes de contrôle conservent et revérifient l’identité de passerelle déjà sélectionnée avant d’émettre le statut/alimentation. Refus de substitution, ancienne sélection conservée en cas d’échec ; une nouvelle découverte explicite permet de choisir une nouvelle passerelle. EID filtré avant lecture VIN d’une réponse non demandée. Tests négatifs UDP ajoutés. Validation à venir avec le lot Android EID.

## PR117 — identification EID Android et portée IPv6 (24/11/2026)

- Découverte unicast ciblée par EID (champ optionnel FR/EN) et contrôle de la réponse avant sélection ; affiche aussi les octets d’action supplémentaire et de synchronisation VIN/GID sans interprétation OEM. Les changements d’état d’annonce ne sont pas confondus avec une substitution d’identité. IPv6 link-local : scope obligatoire, comparaison des hôtes avec scope et nom IP TLS sans scope, contrôle d’identité même si VIN UDP indisponible. Tests UDP loopback et scope ajoutés. Postérieur au checkpoint en cours 45456278234 ; nouvelle validation nécessaire. C6/DoIP actifs.

## PR117 — validation DoIP (24/11/2026)

- Run 45454645809 sur 8b4672f : succès de tous les 12 jobs techniques, dont Windows (0 erreur/avertissement), Android et Connectors. Revue ignorée sur PR draft.
- Nouveau checkpoint demandé sur le lot contenant 962a70b66d96670d006d89d2c3ecc6a25aebdf7b (IPv6 Python, paramètres session Android et Windows). Ce dernier lot reste à vérifier. C6 et DoIP actifs ; aucune fusion/Release.

## PR117 — paramètres de session DoIP dans la console Windows (24/11/2026)

- Paramètres P2/P2*, type d’activation et quatre octets OEM facultatifs disponibles dans la GUI Windows, défaut historique conservé, valeurs validées avant réseau. Transmission du contexte immuable à tous les parcours (identification, DID/DTC, monitoring, sauvegarde et séquences) ; paramètres visibles lors du double consentement. Ressources FR/EN et tests de validation ajoutés. Ce complément et les deux précédents ne sont pas inclus dans la CI 45454645809 ; prochain checkpoint nécessaire. C6/DoIP restent actifs.

## PR117 — options de session DoIP Android (24/11/2026)

- Réglages avancés facultatifs de routage et temporisation UDS sur Android, valeurs par défaut inchangées (5 s), validation P2/P2*/délai global et type d’activation/4 octets OEM avant réseau. Réponse NRC 78 seule renouvelle P2* dans le délai global ; ACK/AliveCheck ne le prolongent pas. Les paramètres choisis sont immuables pour la commande confirmée et affichés dans le dialogue ; sauvegardes et monitoring utilisent les mêmes options. Ressources FR/EN et tests de bornes/format ajoutés. Aucune valeur OEM supposée. À valider au prochain checkpoint (postérieur au run 45454645809).

## PR117 — découverte IPv6 link-local DoIP Connectors (24/11/2026)

- Client hôte Python : découverte active ff02::1 limitée à une interface explicite et écoute passive sur cette même interface, filtrage port/source link-local/scope, déduplication, VIN/EID et limite 32 résultats. CLI FR/EN `--multicast-ipv6-ifindex` et `--listen-ipv6-ifindex` ; aucune commande diagnostique émise pendant la découverte, aucune émission en mode passif. Les IP link-local unicast exigent un scope ; le scope de routage est distinct de l’identité IP vérifiée par TLS. Tests sockets simulés et parsing CLI ajoutés ; multicast réel non qualifié. Ce complément est postérieur au checkpoint 45454645809, qui valide le SHA précédent.

## PR117 — checkpoint de validation groupée DoIP (24/11/2026)

- Campagne 45453585549 sur `9afc5ff` : 11/12 jobs techniques réussis, Android y compris compilation/tests/lint/assemble du gate, ESP32/STM32, Python 3.11/3.12/3.13 et autres jobs Connectors/native. Windows : compilation 0 erreur / 0 avertissement, self-test monitoring en échec. Revue ignorée sur PR draft. Le correctif de concurrence et les compléments Windows/Connectors ultérieurs ne sont pas validés par cette campagne.
- Nouveau marqueur demandé sur le HEAD contenant `e888c091d1e669a638fb86f8da139058fadd1af0` : validation groupée des correctifs et fonctions DoIP. C6 / DoIP restent actifs, C8 non commencé. Ne pas clore un protocole ou déclarer une qualification physique sans preuve correspondante.

## PR117 — corrélation DoIP après consentement et TLS Windows (24/11/2026)

- Windows console : relecture UDP avant chaque ouverture diagnostique, comparaison IP/VIN/adresse/version/EID/GID avec la sélection et journal UDP existant. Windows CLI séquence et Python CLI séquence : nouvelle découverte après confirmation et avant connexion, refus de substitution ; pas de réémission automatique. Les annonces UDP ne sont pas authentifiées.
- Windows TLS : si certificat client explicitement choisi, exige authentification mutuelle et vérifie le certificat local effectivement utilisé avant activation du routage. Pas de repli en TCP. Régression de comparaison de passerelle et de non-connexion après substitution Python ajoutée. À compiler et valider ; aucun matériel qualifié.

## PR117 — monitoring DoIP en CLI Windows (24/11/2026)

- Options `--monitor-did` et `--obd-monitor-did` sur `--doip-exchange`, VIN obligatoire, sélection TCP/TLS (TLS par défaut), DID et bornes validés avant réseau, vérification de l’identité avant et après les mesures. Sortie JSONL horodatée, mesures provisoires jusqu’au résultat final, annulation Ctrl+C, aide FR/EN, tests parseur. Les options de temporisation P2/P2* spécialisées restent réservées aux échanges unitaires/séquences ; monitoring utilise le délai global. Aucune mutation ECU. À valider sur le prochain checkpoint.

## PR117 — découverte DoIP Connectors IPv4 / EID (24/11/2026)

- Extension du client Python et de sa CLI FR/EN : identification unicast par EID, découverte IPv4 explicite générique/VIN/EID, écoute passive, adresse locale de liaison, filtre port/source, déduplication et limite 32 passerelles. L’identification automatique VIN conserve le refus d’ambiguïté. La découverte ne vaut ni authentification ni autorisation d’écriture. Tests UDP locaux et écoute sans émission ajoutés. Qualification matérielle non réalisée ; validation CI à demander avec le lot courant.

## PR117 — correction de concurrence du transport DoIP Windows (24/11/2026)

- Run 45453585549 : compilation Windows 0 erreur / 0 avertissement mais échec du test de monitoring (session fermée entre deux requêtes). Cause probable identifiée : le producteur vérifiait l'état de l'échange après publication de la réponse, alors que le consommateur pouvait légitimement avoir terminé. Sérialisation de la publication, admission, vérification des doublons et transition à l'état inactif ; maintien du refus des données non sollicitées et de la retraite de connexion après incertitude. Test 128 requêtes successives ajouté. Nouvelle validation indispensable. Les réponses tardives identiques sur un socket réutilisé restent intrinsèquement ambiguës.

## PR117 — monitoring DoIP OBDonUDS Windows (24/11/2026)

- Bouton de monitoring réglementaire dans la console Windows, contrôle VIN F802/F810 avant et après mesures sur une seule connexion TCP/TLS, échantillons identifiés OBDonUDS, ressources FR/EN, annulation et trace existantes. Refus de terminer en succès si identification finale différente. Test réseau loopback ajouté. La campagne 45453585549 vise le HEAD précédent ; ce complément attend la prochaine validation groupée, sans interrompre la CI en cours.

## PR117 — monitoring DoIP OBDonUDS Android et Connectors (24/11/2026)

- Android : bouton de monitoring réglementaire, validation F802/F810 sur le même ECU avant et après mesures, vérification du DID et du contenu, même socket TCP/TLS et certificat choisi, journal/annulation existants, export CSV complet uniquement avec colonne service_protocol (UDS ou OBDonUDS). Mesures partielles visibles mais non exportables comme campagne réussie.
- Connectors Python : action CLI `obd-monitor`, identification réglementaire avant et après, mesures horodatées et partielles sur stderr, résultat complet uniquement après validation ; le monitoring UDS générique conserve F190. Aucune conversion de DID inventée. Tests ciblés ajoutés. CI groupée demandée via marqueur, résultats à constater.
- C6 et DoIP restent actifs ; pas de fusion, Release ni déploiement.

## PR117 — consolidation des chemins Kotlin et reprise CI (24/11/2026)

- Run 45452211613 sur `7321099` : échec Android (déclarations en double et import assertArrayEquals absent dans la copie historique) ; 11 autres jobs techniques réussis, revue ignorée sur PR draft. Cause : sept fichiers avaient été créés sous `com/camaradiag` au lieu de `com/camarodiag`. Historique et différences vérifiés ; les cinq versions enrichies remplacent leurs anciennes copies, les deux fichiers CanTraceFilter et son test sont déplacés sans modification. Les sept chemins erronés sont retirés. Les changements TLS, sélection passerelle, OBDonUDS et import JUnit sont conservés.
- Validation groupée à reprendre sur le HEAD corrigé avec le lot DoIP hôte ci-dessous. Aucun merge ni Release ; C6 et DoIP toujours actifs.

## PR117 — parité DoIP Connectors : temporisations UDS et routage configurable (24/11/2026)

- Client hôte Python : P2/P2* configurables, délai global indépendant, NRC 78 seul prolonge le délai de réponse sans dépasser le délai global. ACK et AliveCheck ne prolongent pas ce délai ; socket retiré après expiration ou erreur. Valeurs par défaut conservées (délai global).
- CLI FR/EN : `--p2-ms`, `--p2-star-ms`, `--routing-activation-type` (2 hex), `--routing-oem-data` (8 hex). Activation normalisée 7 ou 11 octets ; paramètres invalides refusés avant réseau. Aucune valeur OEM inventée. Tests d'horloge déterministe, routage TCP loopback et refus avant connexion ajoutés. Tests de cette tranche à confirmer par CI.
- C6/DoIP restent actifs ; aucune fusion, Release, qualification physique ou déploiement.

## PR117 — correctif compilation tests Android (24/11/2026)

Le run GitHub Actions push 45450659055 sur `70a6c6c` a terminé en échec sur `:diagnostic:compileDebugUnitTestKotlin` : import JUnit assertArrayEquals absent de UdsDtcDecoderTest.kt. Les autres jobs techniques ont réussi (review ignorée sur PR draft). Import ajouté ; aucune modification du protocole liée au correctif. Nouvelle demande de validation à ce HEAD. Le run intermédiaire 45451927382 a été déclenché sur `d11dab8` mais n'est pas une preuve pour le HEAD corrigé.

## PR117 — sélection de route DoIP et identification OBDonUDS (24/11/2026)

- `4ea1eaf` : Android conserve EID/GID, version, VIN et adresse logique de la passerelle choisie (y compris découverte VIN automatique) ; vérification à la nouvelle découverte avant ouverture TCP/TLS. Affichage EID/GID, invalidation à changement de contexte, tests de substitution. Ce contrôle corrèle les annonces UDP, ne les authentifie pas.
- `4ffc898` : Android distingue explicitement le parcours OBDonUDS de l'UDS générique. Lecture F802 (VIN réglementaire) et F810 (identification de protocole) sur le même ECU ; sélection de route ETHERNET/DOIP/OBD par le sélecteur commun uniquement avec VIN correspondant et F810=01 ; DID réglementaires F400–F8FF accessibles après contrôle, données brutes. Le parcours UDS F190 reste distinct.
- Parité console Windows et CLI Python Connectors : F802/F810, contrôle du VIN explicite et de la valeur de protocole, lecture DID réglementaire après identification. Pas d'inférence de disponibilité de tous les DIDs ni de décodage/scaling constructeur. Tests ciblés ajoutés. C6/DoIP restent actifs, C8 non commencé ; validation groupée demandée. Pas de fusion, déploiement, publication ou qualification physique.

## PR117 — authentification mutuelle TLS Android / KeyChain (24/11/2026)

- `1dcc903` : sélection de certificat client par le gestionnaire Android KeyChain, installation des identités PKCS#12 (.p12/.pfx) par l'interface système, alias + empreinte SHA-256 liés au compte sélectionné (état UI), VIN et IP de passerelle. Choisir la passerelle avant le certificat. Clé privée utilisée via KeyChain, ni export, ni stockage du mot de passe dans CamaroDiag. Relecture de l'identité à chaque opération ; validation de la période, usage TLS client et empreinte. Aucune sélection de remplacement automatique.
- Tous les parcours DoIP Android TCP/TLS (VIN, DID, DTC, détail, monitoring, sauvegarde, séquence confirmée) utilisent le même contexte TLS si certificat sélectionné. Vérification de confiance serveur et IP maintenue, TLS 1.2/1.3, mTLS doit présenter le certificat choisi avant activation du routage ; pas de repli automatique. Découverte/état/alimentation UDP ne présentent pas de certificat. Pour une AC privée, une chaîne reconnue par la politique de confiance Android/application est nécessaire ; aucun contournement ni ajout global de confiance.
- Sélection conservée pendant la session écran pour le compte/VIN/IP/TLS, pas persistée entre relances. Annulation de la sélection bloque l'envoi jusqu'à nouvelle sélection ou retrait explicite. Tests JVM de gestionnaire de clé et handshake loopback mTLS avec PKCS12 de test générés localement par keytool ; vérification du refus d'un autre VIN/IP/TCP, d'une mauvaise AC et d'un serveur n'ayant pas demandé le certificat. Validation complète Android/KeyChain reste à confirmer sur appareil. C6/DoIP actifs ; C8 non commencé.

## PR117 — client réseau Connectors, sauvegardes et sessions DoIP multiplateformes (24/11/2026)

- `6994f23` : client Python réseau ISO 13400 du sous-projet Connectors (hors firmware USB), identification/état/alimentation UDP, routage TCP/TLS, AliveCheck, ACK/NACK, UDS VIN/DID/DTC/19-01/19-04/19-06, monitoring sur même socket et traces PDU. `8abe505` : double lecture de zone mémoire avec profil VIN/adresses/identité, contrôle de données, ZIP compatible Android/Windows, publication exclusive après vérification, aucun écrasement.
- `85b9035` : séquences de 1 à 16 commandes UDS, séparateur point-virgule, total 4091 octets, même socket Android/Windows, consentement sur séquence et cible, arrêt à la première erreur, réponses et historique conservés. `17ba6af` : découverte VIN unique sur réseau IPv4 hôte, revalidation EID/GID, certificats clients TLS, CLI Connectors séquence avec deux confirmations interactives, conservation de résultats partiels Android, dialogues défilants.
- Complément CLI Windows : `--doip-exchange --sequence ... --expected-vin ...`, TLS par défaut pour ce mode, options P2/P2* et routage/certificat, revalidation VIN sur même session, pas de reprise automatique, aide FR/EN. Les séquences sont explicitement fournies, pas un workflow constructeur automatique ni une promesse de flash. Aucun firmware de carte modifié.
- Tests logiciels Python loopback et faux ECU, vérification archive, parsing de séquence et consentement ; tests C#/Kotlin écrits. Aucun build local (pas de copie réseau ni SDK .NET dans l'environnement) ; CI push demandée au marqueur et résultat non confirmé. C6 et DoIP actifs : restent sélection globale par fonction/ECU/connecteur, intégration des services et profils applicables, validation logicielle complète. C8 non commencé. Aucune qualification sur véhicule, fusion, publication ou déploiement.

## PR117 — console DoIP Android et détails DTC Windows/Android (24/11/2026)

- `b3b2a42` : commande UDS hexadécimale unitaire dans l'espace calibration Android, double consentement existant OK puis Oui/Non, contexte véhicule et génération console, payload figé, VIN annoncé et F190 vérifiés avant émission, réponse corrélée par SID et échos applicables. Pas de réémission automatique. Historique UDS TX après écriture socket; pas de preuve de réception ou d'écriture persistée sur le calculateur.
- `103a959` : lectures détaillées DTC 19/04 (instantanés) et 19/06 (données étendues) Android et Windows, numéro DTC/record explicites, contrôle du DTC retourné, statut et données brutes préservées sans inventer de définition OEM. Interfaces et chaînes FR/EN raccordées, tests ciblés ajoutés.
- Limites : commande unitaire, pas de session de programmation OEM persistante ni de profil arbitraire ; vérification F190 requise pour les parcours actuels. Reste intégration Connectors/transport/profils et workflows DoIP selon capacités, sélection globale par fonction, validation logicielle. C6/DoIP actifs, C8 non commencé. Les tests de ce lot ne sont pas déclarés exécutés ; demande de CI groupée au prochain marqueur. Pas de merge, déploiement, publication ni qualification matérielle.

## PR117 — lots fonctionnels groupés, console DoIP et comptage DTC (24/11/2026)

- `6bda16f` : console Windows DoIP avec commande UDS hexadécimale explicite, cible VIN/IP/adresses/logical/TLS et octets figés, double consentement OK puis Oui/Non, lecture F190 sur même session avant envoi, pas de réémission automatique en cas d'échec. Réponse et PDU réelles dans l'historique, FR/EN. Commande unitaire, pas de session OEM de programmation persistante; les ECU ne répondant pas à F190 ne disposent pas de ce parcours vérifié.
- `33709b5` : Android, masque de statut DTC configurable, 19/01 comptage natif et 19/02 liste filtrée, format et disponibilité affichés ; une liste filtrée ne remplace pas les DTC globaux. Complément Windows : comptage 19/01 sur même cible, ressources FR/EN et tests de réponse/validation VIN/commande.
- Développer en lots multi-fichiers cohérents plutôt que découper par fichier ou viser le nombre de commits. CI groupée demandée sur le marqueur de ce lot. Tests écrits mais résultats non confirmés ; le HEAD précédent e78fb8f ne retourne pas de runs via le connecteur. C6 et DoIP restent actifs, C8 non commencé. Pas de merge, déploiement ni release. Aucun profil OEM inventé.

## PR117 — journal DoIP TCP/TLS Windows et résolution automatique Android (24/11/2026)

- HEAD fonctionnel `72d8e0b8c211a62eb6c2b23d5c914e7cd0249d92`. Windows : raccordement des PDU DoIP réellement échangées (routage, alive checks, ACK/NACK, diagnostic) à la console et aux lectures VIN/DID/DTC, monitoring et sauvegardes mémoire (`5c9b1d2`, `fdea179`, `72d2071`, `ec0e2de`). Horodatage à l'observation, tampon interthreads borné à 16 Mio, vidage sur le thread WinForms, échec explicite si incomplet, export refusé pendant une opération. Pas de capture de paquets Ethernet/TLS chiffrés. Aucune duplication volontaire des requêtes UDS dans le journal GUI.
- Android : résolution du point de connexion par VIN lorsque l'IP est vide, vérification de la passerelle sélectionnée avant routage, contrôle de la passerelle du profil de sauvegarde et remplissage des adresses logiques depuis le profil importé (`271abb1`, `6825c7e`, `e5cb21e`). Les adresses logiques ne sont pas déduites du VIN, aucun profil constructeur n'est inventé. Le mode IP explicite reste disponible.
- Tests de simulation et ressources FR/EN ajoutés (`5b72dc6`, `2b81c46`, `72d8e0b`), non exécutés à ce stade. Le dernier marqueur 03029d6 n'a retourné aucun résultat de runs/statuts via le connecteur GitHub. Ne pas déclarer la compilation validée. Demande Debug groupée sur le prochain marqueur.
- C6 et ISO 13400 restent actifs. Sélection complète véhicule/ECU/connecteur/fonction et intégration de tous les transports restent à poursuivre, notamment SW-CAN brut TX et GMLAN ELM du ticket #110. C8 final non commencé. Une seule PR, aucun merge, déploiement, publication ni qualification physique.

## PR117 — découverte VIN Windows, contrôle identité ECU, cohérence Android (24/11/2026)

- HEAD fonctionnel `92ff94c5bb7737f11ab785ee1c1ac090e839debc`. Windows : découverte IPv4 VIN avec IP vide, sélection d'une passerelle unique (EID/GID compris), renseignement IP automatique, invalidation sur changement VIN/ECU/session, ressources FR/EN et journal TX/RX des octets de contrôle UDP (`ea81112`, `8d73938`, `d34362c`, `74537cb`). Découverte unicast manuelle conservée et filtrée par VIN lorsqu'il est renseigné.
- Lectures Windows DID/DTC et monitoring vérifient F190 sur la même session routée avant l'opération et le monitoring revérifie le VIN à la fin (`3dc09f3`). Android distingue aussi les EID/GID lors de la découverte VIN (`5755d11`). Pas d'attribution inventée d'adresses ECU, ni programmation DoIP qualifiée. La trace Windows des contrôles TCP/TLS reste à raccorder intégralement ; pas de capture réseau exhaustive.
- Autotests loopback UDP Windows/Android, F190 positif/discordant et ressources FR/EN écrits (`3abbec6`, `f87b33e`, `edf38f8`, `92ff94c`). Résultats de la CI précédente au SHA deb94e6 non accessibles via consultation des runs et statuts ; ne pas déclarer cette tranche compilée ou testée. Validation groupée sur le prochain marqueur.
- C6 + DoIP toujours actifs, C8 non commencé. Suite : intégration par fonction et sélection complète, journal TCP/TLS Windows, SW-CAN brut TX et GMLAN ELM dans la suite du périmètre #110. Une seule PR, aucun merge/Release/déploiement, qualification physique sur Release #48 seulement.

## PR117 — journal DoIP complet des contrôles et découverte VIN Android (24/11/2026)

- HEAD fonctionnel de cette tranche : `a7b3d408f79bbb6a11fc9f4f77abbf7a90814e7f`. Transport `266fdb6` et raccordement console `4100916` : journal commun TX/RX/CSV des contrôles UDP, activation de routage TCP/TLS, alive checks et ACK/NACK, en complément des UDS PDUs déjà raccordées. Les événements de contrôle contiennent les octets de PDU observés et le transport, pas des identifiants CAN artificiels. Pas de capture réseau exhaustive (paquets étrangers, invalides/tronqués ou chiffrement TLS).
- Découverte VIN IPv4 broadcast (ISO 13400 type 0003), adresse sélectionnée seulement si une seule passerelle annonce le VIN demandé, refus des ambiguïtés, remplissage Android et FR/EN (`c104a2b`, `2c4c9f6`, `13a144d`, `ec1b270`, `36c820f`). Adresse logique ECU et profil restent à établir, aucune attribution DoIP inventée pour LFX 2012. Annulation de la passerelle distincte de l'annulation monitoring/sauvegarde (`192e07b`).
- Tests loopback UDP écrits (statut, alimentation, NACK, octets, découverte VIN/ambiguïté) `2c67d20`, `a7b3d40` ; pas de résultat de compilation/tests sur ce HEAD à ce stade. Reste intégration complète par fonction, choix véhicule/ECU/protocole, SW-CAN brut TX, GMLAN ELM et autres standards #110. C6 + DoIP actifs, C8 non lancé. Aucune qualification matérielle avant Release #48, aucun merge/Release/déploiement autorisé.

## PR117 — DoIP vérification, monitoring, SW-CAN et Connectors (24/11/2026)

- Dernière CI complète réussie #45437800068 au SHA 00b2edf3efab9e0005774919800afd1322b7187c : 12 jobs techniques, revue sautée pour PR draft.
- Nouveaux raccordements : vérification ZIP DoIP Android 09f0cf1 ; monitoring Android en direct, TX/RX horodatés et export CSV VIN/ECU/DID 5333419 ; SW-CAN brut 61/62 capture silencieuse et détection STN c0eeff5 ; identification/état/alimentation passerelle Android UDP 9389efb ; vérificateur ZIP DoIP hôte Connectors 8afb0f8. Android limite de zone 1 Mio ; Windows/hôte 16 Mio. La trace DoIP Android demeure une fenêtre de 128 entrées ; l'archive globale console doit encore intégrer l'intégralité de ce trafic. SW-CAN brut TX non intégré ; UDS SW-CAN via STN déjà distinct.
- Annulation utilisateur préserve le résultat partiel affiché et interdit export de mesures incomplètes ; changement véhicule/compte/connecteur invalide le contexte.
- C6 + DoIP actifs. LFX 2012 ne doit pas recevoir de profil DoIP ou DID FlexFuel inventé ; qualification OEM/physique Release #48 uniquement. Reste : intégration intégrale console, sélection véhicule/calculateur/fonction, GMLAN ELM, autres capacités et protocoles de #110. Ne pas ouvrir C8 ni autre PR/branche. Checkpoint groupé intermédiaire, pas de fusion/Release/déploiement.

## PR117 — DoIP Android/Windows, sauvegardes et sélection de profils (24/11/2026)

- Dernière CI Debug complète réussie : #45431900505, SHA c6e634970337be47e63d9c2d0c27f6c5d8b459b0 (12 jobs techniques). Ne valide pas les changements ci-dessous.
- Depuis : sauvegarde mémoire DoIP Windows profil logique/VIN/identité, acquisition double et vérification ZIP, interface et CLI FR/EN (15203e3, b224cdf, e58a8ab, df0c557, 69d4cdf, 643430b). Android : sélection UDS/OBDb par capacités 3291f5a, correction DTC UDS db2e940, transport direct DoIP 1f2b709, interface VIN/DID/DTC et annulation 1626d32, monitoring 30 mesures ebaa4f4, console CAN filtrée 58314f0, sauvegarde DoIP 1 Mio avec profil JSON, deux lectures et export vérifié 9a4b53f/84be7d7. Dernier HEAD fonctionnel 84be7d7ed407c785f96631d08cdf895e9b1e9fef.
- Limites : Android DoIP unicast TCP/TLS, pas de certificat client OEM ni programmation ; mémoire limitée à 1 Mio/8192 blocs par passe/600 s, pas de carte ECU inventée, ZIP compatible format Windows mais compatibilité à tester. Trace Android sauvegarde limitée aux 128 dernières entrées. Pas de preuve physique, aucune qualification constructeur ; C6 et DoIP restent actifs, refonte globale véhicule/calculateur/fonction incomplète.
- Checkpoint groupé demandé sur le SHA du marqueur ci-dessous. Corriger les échecs avant progression. C8 final non commencé, une PR active, aucun merge/Release/déploiement Firebase sans autorisation. Priorité propriétaire : intégration fonctionnelle avant multiplication des tests.

## PR117 — intégrations fonctionnelles prioritaires, capacités et contexte (24/11/2026)

- Dernier checkpoint vert connu : #45423976707 sur 6f3545b8 ; **ne valide pas les commits ultérieurs**. Nouvelle CI groupée demandée à la révision exacte de ce lot.
- Gestion comptes Android/Firebase (#118) : liste paginée, droits modifiables, suppression confirmée (propriétaire, compte courant et administrateurs protégés côté serveur) c7f1cab, 9a07cca, 6595958. Aucun déploiement Firebase. Conserver historique licences ; déploiement séparé explicitement autorisé.
- C6 intégration : sélecteur commun ae8bb11, e30ca4b. ELM ISO-TP lectures 19/22 et réassemblage normal 11/29-bit 250/500 kbit/s, raccordements DID/DTC/OBDb caf5474. Réponses multiligne et liaison epoch f767011 ; invalidation véhicule/compte/connexion 26a1bd5. Console RAW CAN capture/TX 250/500 et consentement lié au débit 2a65975. Inventaire ATTP1..A, STDI et séparation configuration acceptée / réponse physique 08ce831, cd14aa0. Ne pas assimiler l'inventaire à une implémentation J1850/K-Line/J1939. ELM lecture seulement ; requêtes multitrames TX, NRC pending persistant, SW-CAN ELM et CAN-FD non disponibles via ce transport. La sélection véhicule/ECU/service complète demeure à raccorder à toutes les fonctions.
- DoIP actif : DID Windows 4805f75, FR/EN 25e96bd/b037cbd ; monitoring même socket 70d99a9, intervalle 250–60000 ms, 1–3600 échantillons et annulation. Tests ciblés ELM et DoIP c0ea895. Restent sauvegarde DoIP avec vrai profil logique/VIN (ne pas réutiliser artificiellement des IDs CAN), services compatibles FlexFuel, raccordement Android/Connectors et autres opérations #110. Ne pas inventer de profil ECU ni de qualification matérielle.
- Priorité utilisateur : intégrer les fonctions des protocoles et des chantiers ; tests proportionnés, checkpoint regroupé. C6 et DoIP actifs, C8 final non commencé. Une seule PR/branche ; aucune fusion ni Release sans accord. Après checkpoint, traiter ses échecs avant nouveau chantier.

## PR117 — nettoyage interface et checkpoint intermédiaire (24/11/2026)

- Console Windows `4bf89e5`, règle permanente `01024b3`, autres écrans Windows/Android `284ef69`, seconde passe `8ad4445`. Les anciens appels d'affichage sont commentés, ressources préservées ; conserver les états, erreurs, avertissements et limites utiles. Connectors : panneau expérimental et confirmations d'isolement conservés pour sécurité ; pas de GUI indépendante identifiée dans l'inventaire de sources.
- Inventaire et revue des écrans principaux Windows/Android réalisés ; pas de recette visuelle exhaustive ni de test physique. Déclencher checkpoint Debug groupé au HEAD exact, corriger les échecs avant clôture. C6/DoIP restent actifs ; DID générique `d303cbd`, ressources `3e7305c` / `38c9868`, raccordement graphique DID restant. C8 non commencé.

## Continuation immédiate après fusion PR95 — 24/11/2026

PR95 fermée et fusionnée dans main au SHA `02c26ffcc79eb25d0b04735020e9ed7985590fd4`. CI Debug #45410206158 et revue #45414433916 réussies sur bf850b3. La branche existante est synchronisée avec main et réutilisée faute d'action de suppression de branche accessible ; une seule PR active. Reprendre C6/DoIP immédiatement : raccordement GUI de la lecture DTC 19/02, tests et FR/EN, puis toutes les intégrations restantes selon le triplet connecteur + ECU + opération. Les reliquats C1/C2/C4, #63, #110 et C8 final restent dus. Aucune qualification physique revendiquée. Les références historiques à PR95 ne désignent plus la PR active.

---

## Décision propriétaire — scission PR95 et continuité immédiate (24/11/2026)

Le propriétaire autorise la scission, la validation puis la fusion de PR95 et la création immédiate de sa PR de continuation. Cette autorisation ne dispense pas du checkpoint Debug complet sur le HEAD exact ni du contrôle de fusionnabilité. Ne pas fusionner en cas d'échec, ne pas publier de Release intermédiaire volontairement. Conserver tous les commits et les issues ouvertes.

### Périmètre transféré, sans perte d'exigences
- C1 console CAN/OBD, C2 sauvegardes ECU (#63), C4 FlexFuel lecture seule : conserver les acquis et transférer toute exigence non satisfaite des issues et de l'audit PR95.
- C6 et DoIP restent le chantier et le protocole actifs. Dernier code au SHA a7ffed89063fafba41bd29ff2e5e245213562733 : lecture DTC UDS 19/02 via passerelle DoIP observée ; raccordement GUI, tests loopback et présentation FR/EN non terminés.
- #110 : toutes les couches et services ISO/SAE encore incomplets, intégration Android/Windows/Connectors par triplet connecteur + ECU + opération, distinction CAN brut/diagnostic, preuves RX/TX, revalidation contexte et erreurs.
- C8 demeure exclusivement la validation finale du périmètre global ; la validation de PR95 est intermédiaire. #106 audit, #108 chantiers ultérieurs et #48 recette Release restent dus, sans suppression d'engagement.
- Après succès des jobs sur le HEAD de clôture, vérifier le contrôle review, fusionner sans perdre les commits, puis supprimer l'ancienne branche si possible et ouvrir immédiatement une unique PR Draft de continuation depuis main. Si la suppression automatique dépend d'une Release, réutiliser la branche existante une fois synchronisée avec main plutôt que créer une troisième branche. Reporter le numéro réel de la nouvelle PR dans les consignes et le suivi.
- Aucun statut terminé pour un travail non achevé, aucun test matériel revendiqué, aucune fermeture des issues de reliquat.

---

## Reprise C6/DoIP — annulation et changement de contexte (24/11/2026)

- `fcfbc672544a12ad5c1ca4ee577534bcd636b9c9` : réutilisation de ReadDid F190 et contrôle d'annulation après callback RX et avant publication du VIN corrélé.
- `acea1693eee0287ab0f3111767348db714f75c57` : test loopback d'annulation pendant callback RX, écrit mais non exécuté.
- `b3c3819085a91a488afbd3df89f4ea3fb7f5e6f1` : invalidation de la découverte et annulation lors du changement d'adresse logique testeur/ECU, TLS ou certificat client. Nouvelle découverte requise. Aucun nouveau chemin d'écriture.
- C6/DoIP restent actifs ; intégration transversale incomplète, C8 non commencé. Aucun résultat CI PR visible au HEAD initial e3766b0 ; ne pas assimiler cette absence à un résultat de CI push. Aucun merge ou Release.

---

## Reprise C6/DoIP — lecture VIN ECU UDS Windows (24/11/2026)

- `7201e38` : contrôle facultatif de l'adresse logique annoncée par la passerelle lors du routage TCP/TLS. `47ddb6a` : service UDS F190 lecture seule, VIN ASCII 17 caractères valides, concordance avec le VIN UDP disponible, adresses explicites, copie indépendante TX/RX. `613bac4`, `6ba26de`, `d3b25a5` : ressources EN/FR et raccordement console Windows, TLS par défaut, certificat client facultatif, annulation et invalidation après erreur. `49a84bb` : historique TX demandé/RX corrélé distinct.
- `7239385`, `c6155fd` : tests loopback du service VIN et refus de routage vers une passerelle logique différente avant toute émission UDS ; 17 assertions supplémentaires écrites, non exécutées. Le checkpoint précédent `d5842eb` n'est pas observable avec la vue GitHub limitée aux événements PR. Ne pas dupliquer sans contrôle ; aucune compilation locale disponible (pas de dotnet, accès git réseau indisponible).
- Intégration limitée à lecture VIN, pas à une authentification, ni à l'écriture, ni à un sélecteur global. C6 et DoIP restent actifs ; C8 non commencé. Aucun test physique, merge, Release ou nouvelle branche.

---

## Reprise C6/DoIP — état passerelle dans la console (24/11/2026)

- `859401d`, `ad8c010`, `52dc2fc` : présentation commune FR/EN des réponses EntityStatus et PowerMode. `981bead` : raccordement graphique aux requêtes UDP réelles, version reprise de l'identification observée pour l'adresse exacte, invalidation au changement de contexte, annulation ou échec. `7435f65` : même décodage pour CLI. `d21ca1f` : 22 assertions supplémentaires dans les autotests existants (taille présente/absente, trois modes, type incorrect, ressources FR/EN).
- Aucun résultat de compilation ou de test nouveau constaté. C6 et DoIP restent actifs, C8 non démarré. État passerelle informatif, aucune autorisation ECU, aucun nouveau chemin d'écriture. Sélection transversale et intégration aux autres fonctions restent incomplètes.
- Un checkpoint groupé de la tranche console et des correctifs précédents est demandé après ce lot ; ne pas assimiler le déclenchement à une réussite. Aucun merge, Release ou essai matériel.

---

## Reprise C6/DoIP — intégration console Windows (24/11/2026)

- `15ad57b`, `8720d40`, `337cb86` : présentation commune de l'identité de passerelle et ressources FR/EN. `a62a69b` : découverte UDP unicast DoIP depuis la console graphique, avec adresse explicite, timeout, annulation et historique. `b181f75` : annulation à la fermeture et protection UI disposée. `ba9ad23` : CLI réutilise la présentation. `3c04f3f` et `e9a3c70` : vingt assertions de présentation et ressources enregistrées dans la suite d'autotests Windows.
- Intégration limitée à l'identification UDP de passerelle, pas à l'authentification ECU, à la console UDS, au diagnostic live ni aux écritures. Pas de faux support, ni d'assimilation au CAN brut. Sélection automatique par fonction et autres parcours encore à réaliser.
- Tests non exécutés à ce stade. C6/DoIP actifs, C8 strictement final, aucun merge/Release/essai physique. Demander un checkpoint groupé de cette tranche et des correctifs UDP/TCP précédents ; ne pas confondre le SHA de la demande avec un résultat.

---

## Reprise DoIP/C6 — correctifs supplémentaires du 24/11/2026

- `54e0486317c5290d19fa5331ea56775a4c68c40a` : les quatre collectes UDP propagent l'annulation externe avant de publier les résultats.
- `4007d981f5a574a406f7611b26b73b5d307584b6` : copie des octets OEM d'activation avant connexion et annulation préalable ; test TCP `383759a675fa68fb7f514b96309ee0e1f692326b`.
- `e8cd617ab6a2ef3b8596d55e8ba0e92d93fcd151` : EID figé avant émission, corrélation sur ce même EID pour unicast, broadcast et multicast ; deux assertions loopback `d678b4bb1e1e0cca49ec313ff4d4166746344e60`.
- Tests ajoutés, non exécutés. Aucun nouveau protocole, aucun C8, aucune qualification physique ni modification du consentement. Intégration par fonction toujours à réaliser, C6 et DoIP restent actifs. Le connecteur GitHub ne liste que les runs déclenchés par PR, pas les runs push du checkpoint ; ne pas confondre absence de résultat et absence de CI.

---

## Reprise DoIP/C6 — 24/11/2026

- `9fb924bfde58073e8938ac48a86369752a50b5f1` : validation du socket UDP déjà lié, durée et port source bornés, annulation préalable explicite.
- `6dc73472c3cfd0f28994f05c4bd6afa93b6f44c3` : six contrôles de régression ajoutés ; **non exécutés**. Aucun changement de capacité matérielle ni de consentement.
- Les consultations du checkpoint `266279ef` et de l'ancien HEAD `1b4864f7` ne renvoient aucun run via la vue limitée aux événements PR ; cela ne prouve pas l'absence de runs push. Ne pas dupliquer une campagne sans vérification. DoIP et C6 restent actifs. Sélection automatique par connecteur + ECU + opération et intégration applicative de tous les protocoles toujours à réaliser avant C8.

---

## Décision propriétaire — sélection par fonction (prioritaire)

- Résoudre automatiquement la pile de communication par **connecteur + ECU + fonction/opération** ; le même couple peut utiliser CAN brut en console et ISO-TP/UDS ou SAE J1979 en diagnostic selon capacités réelles. Distinguer transport, segmentation et service. Ne pas imposer un protocole unique à tout le couple.
- Revalider à chaque changement de contexte, afficher la pile réellement utilisée, conserver les sessions cohérentes et le double consentement lié à l'opération. Aucun repli automatique après émission mutante incertaine. Tester plusieurs fonctions sur un même couple, incompatibilités et changement de contexte. Cette décision précise et prévaut sur la règle précédente à deux critères. C6 et DoIP actifs ; C8 strictement final. Implémentation et tests non réalisés par ce commit documentaire.

---

## Décision propriétaire — sélection automatique des protocoles

- Le protocole est automatiquement sélectionné à partir du **couple connecteur effectivement utilisé + ECU cible**, en fonction des capacités négociées du connecteur/pilote, du profil ECU et de réponses de découverte non mutantes. Pas de sélecteur manuel obligatoire pour le parcours normal, pas de choix fondé sur le seul véhicule ou le seul connecteur.
- Invalider la sélection au changement de connecteur, ECU ou session ; afficher le transport/service réellement utilisé ; signaler explicitement les incompatibilités et ambiguïtés sans inventer de capacité. La détection n'autorise aucune écriture : conserver le consentement et les contrôles techniques existants.
- Intégrer et tester cette sélection dans chaque fonction applicable avant clôture du protocole. DoIP et C6 restent actifs ; C8 exclusivement après tous les autres chantiers et protocoles intégrés et validés. Règle AGENTS.md : 0b1990c1. Exigence enregistrée, implémentation et tests à réaliser.

---

## Décision propriétaire prioritaire — intégration des protocoles et C8 final (24/11/2026)

- C8 est strictement le dernier chantier, après C1/C2/C4/C6 ET la clôture logicielle de tous les protocoles du périmètre PR95, y compris leur intégration aux fonctions applicables Android, Windows GUI/CLI, Connectors, console, diagnostic, sauvegardes, calibration/programmation et récupération selon les capacités réelles. Les tests d'intégration font partie de chaque protocole, pas d'un reliquat à reporter à C8.
- Un seul chat, un chantier et un protocole actifs au maximum. Matrice de couverture par fonction/protocole/transport, parcours de bout en bout positifs et négatifs, consentement OK puis Oui/Non, sauvegardes et contrôles d'intégrité conservés. Cas non applicables justifiés, aucune capacité matérielle fictive.
- Checkpoint C6/DoIP demandé au SHA `266279ef79327541519ffcaec2789102d9a3a479` : aucun run retourné par la consultation actuelle ; résultat non établi, ne pas dupliquer le déclenchement ni passer à SAE J1979/C8 prématurément. Règle durable AGENTS.md commit `2b6db9ae`. Aucun essai physique avant Release #48, aucune fusion sans accord.

---

## Reprise unifiée — 24/11/2026, C6 + correction DoIP

- Décision propriétaire : ce chat unique pilote désormais les chantiers et protocoles. Les réservations historiques de fichiers à un autre chat sont obsolètes. Un chantier et un protocole actifs au maximum, validation avant passage au suivant. C6 actif ; DoIP rouvert pour corriger la régression Windows avant reprise SAE J1979.
- Run #45365181139, SHA c8660bdde1978fa5794c09d49b207c6bca104a82 : 11 jobs réussis, Windows échoué, review ignorée (draft). Logs Windows : build Debug 0 erreur/avertissement, SocketException 10013 au bind UDP passif.
- Correctifs 306094ae et b273b18c : réception passive commune sur socket UDP déjà lié ; autotest utilise un vrai port UDP éphémère attribué par OS au lieu d'un port réservé en TCP. Filtrage des sources conservé ; port production 13400 inchangé. Tests à exécuter au checkpoint groupé ; aucune réussite revendiquée sur ces nouveaux commits.
- AGENTS.md actualisé (abfd1656). Aucun essai physique avant Release #48, aucun merge/Release sans consentement. C8 final reste en attente de clôture C6.

---

## Reprise C6 — 24/11/2026, appairage et CI

- C4 validé logiciel : run #45363634459 SUCCESS, SHA c508902f43fa397554fcb8618fa605ab41ae4880, 12 jobs techniques réussis.
- C6 actif : commits 5e30f6bd, bf10d7a1 (code d'appairage TLS refusant les caractères de contrôle avant trim), d557f8ec et 99ab3761 (tests). Aucun fichier protocole modifié.
- Checkpoint C6 #45365181139 au SHA c8660bdde1978fa5794c09d49b207c6bca104a82 : Windows compile Debug avec zéro erreur et avertissement puis self-test DoIP FAIL SocketException 10013 sur Socket.Bind dans DoIpVehicleDiscovery.ListenAsync ligne 218, test DoIpUdpDiscoverySelfTests ligne 248. Session protocole propriétaire avertie via issue #110 commentaire 7127450558 ; ne pas modifier ces fichiers depuis C6. **Android SUCCESS** : `testDebugUnitTest lintDebug app:assembleDebug --warning-mode=fail`, BUILD SUCCESSFUL en 4m19 (tests communication/app exécutés). Natif et tous jobs Connectors SUCCESS ; **Windows FAILURE DoIP UDP seulement**, compilation 0 avertissement/erreur. Run complet FAILURE, aucune validation globale C6 ; attendre un correctif protocole puis revalider groupé.
- Pas d'essai matériel pendant PR95. Consentement OK puis Oui/Non et vérification physique pré-écriture impératifs. C8 qualité finale uniquement après clôture C6 ; aucun merge/Release sans autorisation.

---

## Reprise coordonnée — 24/11/2026, C4 export CSV (session de 10 h 14)

- **Chantier unique C4 (#52), protocole SAE J1979 classique réservé à l'autre session (#110)**. Relecture de `AGENTS.md`, de cette reprise, du suivi PR95 et de l'issue sauvegardes #63 ; aucune intervention sur ISO/SAE, aucune branche/PR nouvelle.
- `4f9b98b44a58ac10f7eada178f3ab886ec08a735` : `Lfx2012FuelHistoryCsv.export` fige la liste et les listes internes des lectures avant contrôle et écriture. Empêche l'utilisateur d'exporter une collection validée puis modifiée pendant le `Writer` CSV.
- `25b9feb7961e92a869c5cf48c413a78ac5ae6f3c` : régression avec `StringWriter` modifiant liste et octets éthanol après l'en-tête. Les tests supplémentaires `3be145c5` couvrent valeurs changées à horodatage identique et session mixte.
- Checkpoint **C4 regroupé, NON C8 final**, demandé via `c508902f43fa397554fcb8618fa605ab41ae4880` : [GitHub Actions #45363634459](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45363634459). Au dernier contrôle : `verify-revision`, Windows Debug (1 848 autotests hardware-free PASS), noyau natif et **tous** les jobs Connectors (ESP32, STM32, USB, parser, découverte Windows, Python 3.11–3.13) SUCCESS ; revue SKIPPED car PR draft ; Android `unit-tests` **SUCCESS**, `testDebugUnitTest lintDebug app:assembleDebug --warning-mode=fail` **BUILD SUCCESSFUL** (5m36). Campagne **SUCCESS, 12 jobs techniques réussis** au SHA exact `c508902f43fa397554fcb8618fa605ab41ae4880` ; review-gate SKIPPED car PR draft. **C4 clos côté logiciel dans son périmètre lecture seule**, sans qualification matérielle. Le commit de reprise `5df5a104` est postérieur au SHA testé et ne modifie pas le code exécuté. **C6 devient le chantier suivant autorisé**, puis C8 qualité finale.
- Aucun test physique en PR95 ; aucune qualification capteur FlexFuel, activation E85, sauvegarde/restauration OEM complète ; recette sur Release propriétaire #48 uniquement. Pas de merge, de Release ni de fermeture de PR sans accord.

---

## Point de reprise coordonnée — 24/11/2026 (C4 uniquement, pas de bascule de chantier)

- PR #95 toujours en draft, branche unique `chore/unified-ci`. Les instructions AGENTS.md, la priorité C1 → C2 → C4 → C6 → C8, l'issue #110 et #63 ont été relues.
- C1 et C2 : clôtures logicielles et campagnes antérieures consignées ci-dessous ; C2 gate #45351021895 : 2 jobs success. Ne pas convertir cette preuve en qualification véhicule.
- **C4 actif** : commit `3be145c5b4c3a177fb7e16c5b19dbb8630f7f66e` ajoute deux tests de non-régression de `Lfx2012FuelHistoryExportPolicy` : export rejeté si un échantillon conserve le même horodatage mais change sa valeur éthanol, et rejet d'un historique mixant deux sessions pourtant identique à son instantané. Aucun moteur protocoles ISO/SAE touché ; l'autre session garde SAE J1979 (#110).
- **Validation du nouveau commit** : tests ajoutés mais **non exécutés à ce stade**. Attendre la fin du développement C4 avant la campagne ciblée groupée, conformément à AGENTS.md. Ne pas déclarer ce HEAD vert sur la base d'une campagne antérieure. Contrôler également les jobs du HEAD final avant C6.
- Les jobs de la campagne DoIP #45334468537 sont success (review skipped car draft) au SHA historique documenté dans #110, **pas** sur le commit C4 ci-dessus. Consentement OK puis Oui/Non, contrôle octets/blocs et préservation des originaux restent non négociables pour toute voie mutante. Aucun merge/Release ni essai physique pendant PR #95.

---

# Reprise PR #95 — 24 novembre 2026, C4 et SAE J1979 classique

- C1 console clôturé côté logiciel (#59, campagne #45332946454).
- C2 : le périmètre logiciel de PR95 est terminé sur `cb5241a35635d80f0b9803e615ad77b4868d6ff6`. Les deux jobs de la [campagne C2 dédiée](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45351021895) ont réussi : Android communication/app et Windows Debug/autotests. Les correctifs `aba34286`, `ef009f03` et `5d1e1186` protègent les octets d’origine et leur couverture, le canal J2534, le firmware observé et le profil de lecture à l’export. Le rapport reste partiel pour une plage isolée. La couverture de toute la mémoire constructeur et la restauration physique relèvent des essais Release #48 et du suivi #63.
- Chantier actif suivant : C4 (#52), relevés éthanol/corrections carburant en lecture sur LFX 2012. C6 puis C8 qualité finale suivent dans cet ordre.
- DoIP : acceptation logicielle du correctif VIN ASCII `574d6de5` confirmée au SHA `5b9f8c00c3cdff01d77d70db92107ab9f9eacb7c` par la [campagne Debug](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45350268159). Le protocole actif devient SAE J1979 classique (#110). `60c1b123` décode le nom de calculateur Mode 09 sur CAN. Le Mode 02 PID 02 identifie le code ayant déclenché une image figée et signale aussi l’absence d’image.

# Reprise C6/Connectors hors protocoles — 24 novembre 2026

- **Décision propriétaire définitive** : PR #95 reste en draft sur `chore/unified-ci`, **aucun test réel sur voiture ou ECU pendant cette PR**. Seule la **Release après fermeture de PR** sera testée physiquement par le propriétaire via le **Lot 8, issue #48**. Une CI verte prouve le logiciel, pas la compatibilité physique ; ne plus présenter les essais véhicule comme un bloqueur de fusion. Règles reprises dans `AGENTS.md`, `docs/VALIDATION_LOGICIELLE.md`, `docs/AUDIT_PR95_C1_C2_C4_C6_C8.md` et issue #48. Toujours aucun merge, Release, branche ou PR nouvelle sans autorisation.
- **Périmètre séparé** : autre chat responsable exclusivement des piles **ISO/SAE**. Dans cette tranche, travail **C6, C1, USB/BLE/Wi-Fi/J2534 et clients Connectors**. C2 sauvegardes et C4 éthanol lecture seule ne deviennent pas « qualifiés matériellement ».
- **C6 Android Wi-Fi** : sockets remplacés/annulés isolés par identité de session ; anciennes connexions et lectures/écritures tardives ne peuvent fermer ou contaminer la nouvelle. Échecs E/S marqués sous verrou du socket courant, input invalide ou création de socket en échec laisse l'adaptateur actif ; faux sockets et tests concurrents (`dc903ee`, `12f9792`, `9dd4306`, `3af8db3`).
- **BLE / Bluetooth** : notifications bornées (128 paquets / 514 octets maximum), copie défensive, arrêt de session si file saturée, fermeture GATT avant callbacks et connexions GATT sérialisées ; publication Classic SPP/BLE liée à la génération de l'adaptateur (`6e58137`, `c783228`, `b2d9251`, `1c7709e`, `80c2074`).
- **USB Android** : `ObdConnectionSlot` atomique empêche publication/fermeture d'une session obsolète, tests de concurrence (8 ouvreurs) ; l'état perte E/S appartient au **port original**. La librairie `UsbSerialPort.write` retourne **Unit**, pas le nombre d'octets : correctif `8321ae4` et garde de commande non vide. Ne prétendre ni compter des octets acceptés par le driver ni prouver leur livraison bus. Prévalidation deviceId/baud avant déconnexion (`d33632f`, `af5d8a2`, `64357c1`, `58d69ba`, `4cfc701`, `6060cf0`).
- **J2534** : rejet du TLS PIN/hôte privé/port/profil CAN incohérents **avant** déconnexion de l'adaptateur ; publication d'une session TLS uniquement si génération encore courante. Pour l'effacement DTC et la console UDS brute, callbacks de contexte obligatoires au TX. **C1 console CAN brute** : ancien chemin TX sans approbation supprimé, signature exige un `EcuWriteConsentFlow.Approval` issu des deux écrans et revalidation live au payload physique. Mode simulation périmé ne remplace pas une connexion réelle (`1a11b78`, `efc66a0`, `67cca1b`, `0c6a4f1`, `6d5a0b6`, `2ab1895`, `0320514`).
- **CLI/connecteurs sans ECU** : Windows `--list-ports` avant tout dispatch protocole, option mélangée rejetée ; Python `--list-ports` liste descripteurs en lignes JSON échappées sans ouvrir les ports. Découverte de firmware prototype reste **non authentifiée, sans RX/TX ECU activé** ; pont Linux x64/ARM64 GUI/.deb reste **issue future #115** (`aca416e`, `370bc27`, `5cedb4e`, `627ca85`, `21f2e6c`).
- **C8** : [run #45311771440](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45311771440) sur `0bf30352` **totalement vert** (Android, Windows, natif, Connectors) ; [run #45312756165](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45312756165) sur `31077a5e` teste la dernière série C1/C6/USB au SHA exact, **vérifier le verdict avant affirmation**. L'échec Windows DoIP précédent (CS1929, run #45310320249) a été corrigé dans le chantier protocoles et Windows a réussi dans le checkpoint #45311771440. Le diff PR demeure parfois `mergeable_state=dirty` face au commit protocole présent dans `main` ; ne pas toucher aux fichiers de conflit ISO/SAE ni merger sans accord.
- **Recette future** : cas détaillés C6-CON-01 à C6-CON-13 ajoutés à [issue #48](https://github.com/Hellorheaven/CamaroDiag/issues/48#issuecomment-7120165373) pour le classeur Excel de la Release. Ce ne sont **pas** des résultats de tests physiques.

---

# Hors protocoles — connecteurs, C2, C6 et C8 (23 novembre 2026, dernière tranche)

- **Branche/PR** : `chore/unified-ci`, PR #95 unique en draft. La couche ISO/SAE est traitée dans l'autre chat ; les changements ci-dessous portent sur les connexions et sauvegardes hors protocole.
- **Connecteur Android USB** : `CamaroDiagConnectorBinaryDiscovery` utilise une limite globale de 3 s depuis **avant l'écriture USB**, contrôles d'annulation après write, tests de port bloqué / session remplacée (`6560598`, `5dfed01`). Cela reste uniquement la découverte du firmware prototype, **sans RX/TX ECU authentifié**.
- **Pont Windows / connecteur COM** : refus d'une sélection COM absente ou dupliquée à l'instant de la tentative (pas une preuve d'identité de périphérique), tests simulés (`8abec52`, `504ca9d`).
- **C6 / pont Windows J2534** : la copie du code d'appairage TLS valide exactement le format Android actuel (token base64url 43 caractères + empreinte SHA-256 64 hex), rejette les retours à la ligne et valeurs malformées (`e646551`, `a0852e9`, `e855158`). Sans authentification physique du boîtier Connectors.
- **C2 / client Python Connectors** : écriture exclusive d'une image et d'un manifeste **hors ligne**, synchronisation de fichiers/dossier POSIX quand disponible, relecture indépendante, publication du manifeste en dernier, nettoyage après échec et test de défaut (`bab2225`, `13888dc`). Réduction mémoire par comparaison incrémentale en blocs de 1 Mio avec test image multi-Mio (`32202fd`, `cb4c321`). Vérification d'un fichier ≠ preuve de source ECU physique ou de récupération.
- **C8 / CI** : campagne complète demandée au SHA `9d9a723` (run #45277412678) **annulée**, remplacée par la campagne lancée par l'autre chat au SHA `8524168` (run [#45277509716](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45277509716)). A cette étape : Windows/natif/connecteurs y sont réussis, Android encore en cours. Les deux commits C2 `32202fd` et `cb4c321` sont postérieurs à ce checkpoint et nécessitent un contrôle à leur HEAD. N'annoncer aucun résultat final global sans vérification.
- **Non résolu / blocage** : PR #95 régulièrement marquée `mergeable_state=dirty` ; ne pas merger automatiquement, ne pas toucher aux sources ISO-TP concurrentes. C2/C6 restent **ouverts** sur sauvegarde/restauration réelle, pilotes firmware, vrai RX/TX, alimentation et banc ECU ; C4 lecture seule. Le pont Linux CLI/GUI/.deb est l'évolution future issue #115, **pas** une seconde PR active.

---

## ISO-TP CAN classique — tests end-to-end J2534 simulés (23/11/2026)

- Périmètre exclusif : ISO 15765-2 sur CAN classique uniquement. Ne pas poursuivre avec un nouveau protocole tant que la qualification VCI/ECU du CAN classique manque. PR #95 unique (Draft), issue #110 ouverte, aucun merge/Release.
- Nouveaux autotests sans matériel `5b910cf8` : chemin complet ISO-TP FF/FC/CF par `IsoTpJ2534RawCanLink` simulé sur 11 bits, fenêtres BS=1, filtrage des ID parasites, réponses FC et arrêt sur OVERFLOW. `c592e2a4` : CAN classique 29 bits, flag CAN_29BIT_ID et absence de FD_CAN_FORMAT/BRS à l'émission, réception SF.
- Corrections précédentes `07621a4e` et `a0e14673` : échos/statuts et réponses après annulation écartés, filtres CAN 29 bits corrects. L'adaptateur reste dépendant d'un vrai pilote SAE J2534 dont le fonctionnement physique n'a pas été prouvé.
- Checkpoint groupé **#45275140467 sur `69018081` : Windows/J2534 et Android réussis**. Il est antérieur aux nouveaux tests ; ne pas leur attribuer ce résultat. Vérifier le prochain checkpoint complet sur un SHA exact avant de conclure.
- **Non terminé** : horodatages de bus STmin, qualification 11/29 bits sur équipement autorisé, erreurs VCI/bus-off, évaluation en lecture et écriture ECU selon identité/sauvegardes et consentement. Procédure : `docs/QUALIFICATION_ISOTP_CAN_CLASSIQUE.md`. Aucune preuve ECU/flash/rollback et aucun protocole suivant engagé.

---

# ISO-TP CAN classique — correctifs J2534 et checkpoint Windows (23/11/2026)

- **Protocole exclusif actif** : ISO 15765-2 / ISO-TP CAN classique, sur PR #95 Draft et branche unique `chore/unified-ci`. Aucun passage au protocole suivant ni fusion/Release.
- **Corrections committées** : `07621a4` protège la réception RAW J2534 contre les échos/status, les ProtocolID non corrélés et les réponses arrivant après annulation ; tests d'interface hors matériel. `a0e1467` passe le TxFlags CAN_29BIT_ID (0x100) aux filtres J2534 à identifiant 29 bits, contrairement à 11 bits (0), avec régressions. La spécification SAE J2534-1 §7.3.14 confirme ce bit sur le masque ET le modèle du filtre.
- **Checkpoint groupé** : `743fdd3b7b01d2efd7c2e7ec33d836bf04c3698e`, [Actions #45276201139](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45276201139) : **Windows Debug PASS, 0 erreur, 0 avertissement, 1621 autotests J2534 software/hardware-free PASS** ; noyau natif et connecteurs terminés PASS. Consulter le run pour le dernier statut Android/global : ne pas inférer le verdict d'un job encore actif.
- **Blocages de clôture ISO-TP** : pilotes/VCI J2534 réels, capture indépendante CAN 11 et 29 bits avec STmin entre CF, pertes et bus-off sur banc isolé, relevés N_Bs/N_Cr et preuve sur ECU autorisé. Aucune habilitation de flash, restauration ou confirmation constructeur déduite de ces autotests. Critères et commande sans mutation dans `docs/QUALIFICATION_ISOTP_CAN_CLASSIQUE.md`. Couverture indicative historique code hôte 75 %, pas conformité normative. Issue #110.

---

# Résultat de la campagne C6 regroupée — 23/11/2026

- **HEAD de code testé :** `690180812c38e7f324df7715a9427132a5c5fb60`, [GitHub Actions #45275140467](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45275140467), **success** sur les 2 jobs.
- **Windows/J2534 :** Debug `-warnaserror`, garde C6 anti-verrou artificiel et autotests du pont **réussis**, **1 618 self-tests hardware-free** recensés dans le journal.
- **Android :** `:communication:testDebugUnitTest :diagnostic:testDebugUnitTest :app:testDebugUnitTest --warning-mode=fail` **réussi**. Les deux défauts révélés dans le run précédent sont corrigés : compteur de contexte C6 et libération du writer d'archive CAN après `flush` échoué C1.
- **Écriture et console :** double consentement OK puis Oui/Non, revalidation utilisateur/compte/adaptateur/véhicule juste avant émission DTC et CAN brute, y compris pont TLS J2534. Aucune restriction OEM artificielle.
- **Limites :** pas de preuve d'essai physique de trame CAN, d'écriture sur ECU réel, de sauvegarde/restauration intégralement qualifiée ni de programmation d'un boîtier prototype. C2/C6 restent ouverts côté matériel et récupération, C4 lecture seule, C8 global/merge encore ouvert. Ce checkpoint **n'est pas une qualification complète Release ni un merge**.
- Cette mise à jour documentaire ultérieure à `6901808` ne change aucun code exécuté dans la campagne. Garder la **PR #95** unique et la branche `chore/unified-ci`; l'autre chat traite les protocoles. Pont Linux : future issue #115.

---

# Correctifs C1/C6 après le premier checkpoint C6 du 23/11/2026

- Premier checkpoint [#45274227208](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45274227208), SHA `d2ac70f` : **Windows/J2534 success**, Android compilé mais `:communication:testDebugUnitTest` a échoué **2/574 tests**, sur `EcuWriteConsentFlowTest.operatorAuthorizationIsRecheckedWhenQueuedWriteActuallyStarts` et `CanConsoleRawArchiveWriterTest.failedFlushMakesArchivePermanentlyIncomplete`. **Aucun statut vert global** sur cette campagne.
- C6 test corrigé : le contrôle de contexte est appelé cinq fois, pas quatre (`ed26b75`).
- C1 archive CAN : après un `flush` déjà en erreur, `close()` ne retente plus ce `flush` mais libère toujours le writer, en conservant `hasFailed=true` et `isComplete=false` (`85d031d`), test renforcé (`ae5b9b6`).
- C6 console CAN brute : l'objet de consentement issu des deux écrans de l'UI est désormais passé jusqu'au ViewModel (`8b475e0`). Le ViewModel compare compte, véhicule, génération d'adaptateur et génération console ; la fonction de validation est exécutée par le transport ELM **immédiatement avant l'émission du payload CAN** et non à la simple mise en file (`78e5530`, `20365b5`). Les commandes de configuration du transceiver ne sont pas des preuves d'émission ECU.
- Règle : aucun protocole ISO/SAE modifié, aucune capacité ECU/VCI simulée. C6 **ouvert** pour qualification physique et récupération. Regrouper la prochaine campagne C6 au nouveau HEAD ; ne pas merger, publier de Release ni annoncer des tests passés avant résultats.

---

# 23/11/2026 — ISO-TP CAN CLASSIQUE : point de reprise de fin de tranche

- Chantier de protocole **exclusivement ISO 15765-2 ISO-TP CAN classique**. Aucun nouveau protocole démarré, aucune autre branche/PR, aucune fusion. PR #95 Draft `chore/unified-ci`, issue #110.
- Logiciel : FF/CF/FC, DLC/longueurs, N_Bs/N_Cr et limite globale, STmin entre CF successives et **repli 127 ms verrouillé pour le transfert entier si un FC annonce un STmin réservé**, prise en charge des blocages FC/WAIT/OVFLW.
- Frontière pilote J2534 couverte par simulation ; nouvel enregistrement facultatif `--trace-file <chemin-neuf.csv>` autour de `--isotp-exchange` : `TX_REQUEST/TX_QUEUED/RX` et timestamp **hôte uniquement**, fichier CreateNew (ne pas écraser), données ECU/VIN à garder privées. `TX_QUEUED` n'est PAS un acquittement ou un timestamp sur bus.
- **Preuve vérifiée : commit `bf7953fcb66b3397da05106d1bbdc6efc5fdba67`, Actions #45274333738 : Windows Debug succès, 0 erreur, 0 warning, 1618 autotests hardware-free exécutés et réussis.** Native ECU et la majorité des connecteurs réussis ; Android et ESP32 étaient encore en cours lors de cette vérification. Ne pas conclure CI globale verte sans contrôler le rapport final. Les changements ultérieurs éventuels sur la branche ne sont pas couverts par ce SHA.
- Le protocole reste **non terminé physiquement** : qualification réelle VCI J2534/ECU, CAN 11/29, timing de bus (STmin), réponse aux pertes/bus-off, preuves de lecture/écriture OEM avec sauvegardes physiques. Critères de tests dans `docs/QUALIFICATION_ISOTP_CAN_CLASSIQUE.md`. Valeur indicative historique de couverture globale ISO-TP CAN classique : 75 % ; pas une mesure normative.
- Les autres protocoles restent en attente. Ne pas modifier leurs moteurs pendant cette tranche ; respecter les fichiers de C6/C2/Android possédés par les autres sessions. **Ne pas merger / Release.**

# Session hors protocoles — reprise C6 / ponts / C2 / C4 (23/11/2026)

**PR unique** : #95 sur `chore/unified-ci`. L'autre chat reste responsable des standards/protocoles ISO/SAE. **C6 reste OUVERT**, en particulier les essais matériels, l'intégrité physique, la sauvegarde/restauration prouvées et la qualification OEM. N'ajouter ni signature/clé de programmation CamaroDiag, ni blocage universel de services d'écriture. Ne pas fusionner.

## C6 — suppression DTC et contexte actuel à l'instant TX
- Le consentement opérateur a toujours deux écrans (OK puis Oui/Non) ; ajout de `EcuWriteConsentFlow.requireCurrentForTransmit` (commit `51ac6fe`) et tests pour refus après changement de génération d'adaptateur, véhicule ou compte (`cca08b3`).
- L'effacement standard sur **ELM/USB/BLE/Wi-Fi** repasse désormais une fonction de vérification de contexte sous les mutex de commande, **juste avant émission physique** (`BluetoothObdManager.sendAtCommandInternal`, `c34bf9e`).
- Même vérification pour le **pont Windows J2534** avant l'appel réseau `clearStandardObdDtcs`, après acquisition du mutex transport (`d99e18b`) ; le ViewModel lie les deux voies à la session opérateur en cours (`fd18159`, `a78a145`).
- Ces vérifications protègent des confirmations devenues obsolètes lors de commandes en file d'attente ; elles n'attestent pas la programmation ECU, le bench ou un éventuel rollback.

## Réparations de compilation C2/C4
- Runs antérieurs #45272690164 et #45273390209 : Windows, natif, connecteurs réussis, mais compilation Android en échec sur `EcuRestorePortableBundleTest.kt:136` (ByteArray au lieu d'InputStream) et `CalibrationWorkspaceScreen.kt:400-401` (smart cast multi-module). Correctifs `bfd5dba` et `7144db3`.
- Les nouveaux commits de cette tranche **ne sont pas encore validés** par CI à la rédaction ; ne pas annoncer build vert sur le HEAD. Une campagne groupée est nécessaire à la fin du lot, sans exécution par commit.

## Linux
- Pont Linux natif x64/ARM64, GUI, CLI, aide hiérarchique avec exemples et paquets .deb : issue [#115](https://github.com/Hellorheaven/CamaroDiag/issues/115), **future PR après #95**, pas de deuxième branche.

---

# Tranche hors protocoles — connexions et C6, 23/11/2026 (soir)

- **PR #95** sur l'unique branche `chore/unified-ci`. **C6 reste actif**, sans aucun verrou logiciel artificiel d'écriture. Les vrais prérequis ECU/OEM, la sauvegarde physique, le contrôle des blocs et le double consentement restent obligatoires. Aucune programmation/restauration de véhicule démontrée.
- **USB Android** : `UsbPermissionReconnectPolicy` rattache maintenant la reprise à la famille USB explicitement choisie (ELM/OBDLink ordinaire, KKL isolé, CamaroDiagConnectors découverte dédiée) et refuse les collisions d'identifiant dans l'énumération ; tests unitaires ajoutés. Commits `8d06118`, `136856c`.
- **Bluetooth BLE** : l'OBDLink CX n'est plus refusé par son seul nom dans `MainViewModel`; le pilote BLE existant détermine les capacités réellement accessibles. Une annulation de tentative BLE est propagée tandis que son timeout local est signalé comme échec. Commits `394a43b`, `eef9066`, `930c56d`. Pas de qualification matérielle CX ni de service GM prétendu.
- **C4 / CI** : dans le run [#45270916895](https://github.com/Hellorheaven/CamaroDiag/actions/runs/45270916895), Windows/natif/connecteurs réussis mais Android a échoué à compiler `Lfx2012FuelHistoryCsv.kt` (7 appels `Writer.write(Char)` invalides). Correction Kotlin `Writer.write(','.code)` dans `1837bbe`. **Aucune CI après cette correction n'a été lancée par cette tranche ; ne pas annoncer que le HEAD passe.**
- Développement logiciel hors normes ISO/SAE seulement ; l'autre chat traite les protocoles. Ne pas toucher à ses fichiers. Pas de nouvelle branche/PR, merge, Release ou workflow déclenché à chaque commit ; tests groupés après achèvement du chantier. C1/C2/C4/C6/C8 restent soumis aux preuves manquantes de l'audit.

---

# 23/11/2026 — ISO-TP CAN classique : seule famille de protocole active

- PR #95, branche unique `chore/unified-ci`, issue #110. **Ne commencer aucun autre protocole avant de clôturer ISO-TP classique** ; correction transversale des tests C# ne constitue pas un nouveau chantier protocole.
- Travaux logiciels : FC/OVFLW sur FF >1 MiB, tests limites 7/8 et 4095/4096, FC avec adressage logique, N_Bs/N_Cr indépendants et bornés par le budget global, STmin réservé reçu traité à 127 ms sans publicité locale réservée. Commits d'implémentation : `2371a0c`, `3551e42`, `1aa147e`.
- **Checkpoint testé : `23bbbe6474f7f82d4c48598c24ab578bc3e5adb4`, run #45268234408. Windows Debug BUILD avec 0 erreur, 0 avertissement ; 1593 autotests logiciel/pont J2534 réussis, dont ISO-TP et deadlines ; natif et connecteurs réussis. Android encore en cours lors de l'enregistrement de cette note ; ne pas annoncer globalement « CI verte » avant résultat.**
- Ce n'est **pas un protocole terminé physiquement** : qualification J2534/ECU CAN classique, 11/29 bits, N_Bs/N_Cr/STmin sur vrais adaptateurs, erreurs bus-off, lecture/écriture et scénarios d'interruption, attestation de capacité VCI restent exigés. Aucun flash/rollback constructeur physiquement prouvé. Statut ouvert, progrès logiciel indicatif historique 75 %, aucun 100 % inventé.
- Issue #110 mise à jour par commentaire avec les résultats. Aucun merge/Release, aucune autre PR, aucun test automatique par commit.

## C2 / C4 — fin de tranche indépendante des protocoles (23/11/2026, soir)

- **C2, toujours incomplet physiquement :** `EcuRestoreCoveragePreview` produit un inventaire exact des plages d'adresses absentes ou non issues de deux lectures déclarées physiques et concordantes ; les captures hors carte sont distinguées. La création du dossier de restauration hors ligne réclame désormais la couverture de la **carte fournie** avant publication. Ceci ne démontre PAS que cette carte est la carte mémoire OEM exhaustive, que l'origine physique a été authentifiée ou que le calculateur est récupérable. Tests dédiés ajoutés.
- **C4, lecture uniquement :** `currentSnapshotPercent` distingue une mesure réellement reçue dans la dernière enquête d'une mesure historique ; en cas d'absence de réponse éthanol, l'écran affiche le statut « historique seulement » en FR/EN. Aucun E85 activé et aucun profil moteur supposé.
- **C6 reste ouvert et NON BLOQUANT ARTIFICIELLEMENT :** la couche CAN valide seulement le format des trames ; test de non-régression sur charges utiles constructeur sécurité/écriture. Le double consentement utilisateur et les vérifications physiques exactes restent requis.
- **C8 :** GitHub ne renvoyait aucune exécution de workflows pour la demande groupée de la précédente tranche au moment de la consultation. Ne pas conclure tests verts. Nouvelle demande après stabilisation du lot ; aucun merge/Release.

## REPRISE DU CHANTIER HORS PROTOCOLES — C6 ACTIF (23/11/2026, soir)

- Ne pas perdre le chantier **C6** : il traite l'authentification constructeur réelle, le consentement utilisateur OK puis Oui/Non, l'intégrité des opérations et la sauvegarde/récupération ECU, **jamais** un verrou logiciel de programmation CamaroDiag. Les trames CAN valides restent admises quelle que soit leur commande OEM (test `SafeRawCanTransmitPolicyTest`), dans les limites du transport physique et de la confirmation utilisateur.
- Nouveau raccordement USB Android : retour de permission reçu par `UsbPermissionReceiver`, vérification effective de la permission, reprise uniquement de l'adaptateur et de la session sélectionnés grâce à `UsbPermissionReconnectPolicy` ; le choix CD/1 ASCII/binaire est conservé, le refus est localisé FR/EN. Aucun accès ECU certifié par la permission USB.
- Déconnexion J2534/USB/BLE/Wi-Fi unifiée et sérialisée au niveau du ViewModel. Annulation de la sonde ELM propagée au lieu d'être transformée en erreur.
- C1 : SHA-256 de l'instantané CSV pour déceler une modification à taille constante ; C4 : mesure PID actuelle distinguée d'une valeur historique non actualisée.
- C2 conserve la demande d'**image ECU entière physiquement restaurable**, pas de prétention de clôture. C8 : campagne Debug regroupée seulement après stabilisation de ce lot et du HEAD visé ; tests ajoutés ne valent pas tests exécutés.
- Développement standard ISO/SAE/UDS dans l'autre chat sur la même PR #95. Ne pas éditer ses fichiers de protocole ni publier un verdict de validation de ses changements. Une seule branche `chore/unified-ci` ; pas de fusion ni Release.

# Tranche ISO14230 KWP2000 — 23/11/2026

Corrélation facultative des premiers paramètres réémis après SID sur une réponse positive KWP (`--kwp-echo-bytes`), refus des réponses appartenant à un autre identifiant, statut NRC/0x78 préservé, tests offline ajoutés. PR #95 inchangée, aucune CI compil ni essai VCI/ECU pendant ce lot.

# Tranche J1979-2 / OBDonUDS DTC — 23/11/2026

Ajout d'une validation de corrélation sur réponse ISO 14229 19/02 avec masque de statut effectivement demandé, filtrage des entrées incohérentes et doublons, branchement CLI CAN/DoIP et tests offline `ObdOnUdsDtcStatusSelfTests` (non lancés). Même branche PR #95, aucune CI ni essai ECU, aucun merge. Garder distinctions pile logicielle / VCI / flash physique.

# Tranche protocoles SAE J1979 classique — 23/11/2026

PR #95 / branche `chore/unified-ci` / chantier #110 : extension offline du décodeur PID Mode 01/02 avec 35 nouvelles formules standard (pressions, évaporation signée, EGR, charge, catalyseurs, ratio, couple et temporisations), tests négatifs de taille et de freeze-frame. Aucune CI, compilation, essai véhicule ou validation matérielle sur cette tranche ; ne pas confondre décodeur et ECU pris en charge. Le chantier reste ouvert, matrice et protocole suivant à établir après revue. Ne pas merger/créer d'autre branche ou PR.

# Reprise — suivi développement 20h du 23/11/2026, protocoles par lots
Sur `Hellorheaven/CamaroDiag` PR #95 `chore/unified-ci`, chantier #110, dernière tranche (code hôte) : SAE J1979-2 0x19/01 DTC count (ISO-TP et DoIP, option `--obd-dtc-count-mask`), ISO-TP CAN/CAN-FD délai de transfert global et filtrage de trafic jusqu'au deadline (sans limite arbitraire de 64 trames), budget d'échange TX+RX Windows, UDS rejet réponses tardives après NRC 0x78, et ISO 14230 KWP2000 validation optionnelle des réponses positives/négatives/attente avec adresses RX (sans whitelist arbitraire SID). Nouveaux autotests offline ajoutés à `BridgeSelfTests`, non exécutés. La matrice des **15 familles de protocoles** et leurs pourcentages logiciels indicatifs (NON certifiés) est actualisée dans `docs/PROTOCOLES_STANDARDS_ET_RECUPERATION.md`. Pas de build, CI, tests physiques ni qualification flash/rollback dans cette tranche ; PR ouverte en draft, ne pas merger/créer de branche, regrouper les tests à la clôture du chantier. Garder les exigences de sauvegardes réellement relues et de double confirmation avant écriture.

# Suivi protocoles — lot J1979/J1979-2 et matrice (23/11/2026)
PR #95 `chore/unified-ci` et issue #110 : ajout J1979 classique de dix PID normalisés et des données Mode 06 CAN (bitmap MID, enregistrements TID/UASID avec valeurs brutes et sans verdict inventé) ; puis J1979-2 OBDonUDS : détection CAN fonctionnelle prioritaire `22 F8 10` avant fallback classique `01 00`, rejet des réponses F810 contradictoires, VIN `22 F8 02` décodé séparément du Mode 09 classique. Cmds nouvelles `--obd-auto-detect-can` et prise en charge `--obd-did F802` dans l'affichage ISO-TP/DoIP. Tests offline ajoutés aux autotests, **pas encore exécutés**, zéro workflow confirmé sur HEAD vérifié avant cette note. Matrice indicative des pourcentages détaillée dans `docs/PROTOCOLES_STANDARDS_ET_RECUPERATION.md` : J1979 classique 60 %, J1979-2 40 %, autres estimations inchangées. Aucune capacité physique universelle ni restauration ECU revendiquée. Maintenir seule PR #95 ; ne pas lancer CI par commit, merger ou supprimer de branche.

# Actualisation chantier protocoles SAE J1979 — 23/11/2026
PR95 `chore/unified-ci`, issue #110 : traitement protocole par protocole, J1979 actif. Les modes 03/07/0A sur CAN utilisent un count DTC avant les paires ; InfoTypes/CALID/CVN du mode 09 et statut MIL/DTC du PID 01 décodés ; séparation explicite entre J1979 classique et OBDonUDS. Dernier HEAD de référence lors de cette note : `4c472a60a5cfd4eecc69b739e91e1477fa228672`. Tests ajoutés **non exécutés**, aucune qualification véhicule ni CI finale ; la matrice d'estimation par standard est dans le commentaire de l'issue #110 du 23/11/2026. Respecter la règle d'un seul chantier actif, une seule PR, tests groupés et aucun merge sans accord.

# Addendum prioritaire 23/11/2026 — normes ECU et recette post-flash (issue #110)
La PR #95 sur chore/unified-ci couvre maintenant une première tranche ISO/SAE et contrôle offline de sauvegardes physiques ; état détaillé docs/PROTOCOLES_STANDARDS_ET_RECUPERATION.md. Exiger pile standard ISO 14229/13400/15765-2 (CAN-FD compris), 9141-2/14230 K-Line, SAE J1850/J2534/J1979/J1939 ; niveau réel transport/matériel/flash distingué. Après écriture, test ECU + véhicule, comparatif DTC et VIN, statut PASS/FAIL/INCONCLUSIF ; toute restauration est une nouvelle écriture avec backup original physique vérifié et double consentement OK puis Oui/Non, jamais automatique ; ECU muet nécessite récupération qualifiée sur banc. Respecter AGENTS.md. Ne pas créer nouvelle branche/PR, ne pas lancer CI par commit.

# PROMPT DE REPRISE — CamaroDiag

> Fichier de continuité du projet. À lire en premier dans tout nouveau chat consacré à CamaroDiag. Mettre ce fichier à jour dès qu'une décision durable, un changement de périmètre, une modification GitHub/PR, un résultat CI ou un état technique important est confirmé. Tenir le changelog propre à chaque PR (`docs/CHANGELOG_PR<number>.md`) à jour sur la branche de cette PR.

## CLARIFICATION PRIORITAIRE DU PROPRIÉTAIRE — C6 NE DOIT PAS BLOQUER L'ÉCRITURE (23/11/2026)

**C6 est un chantier de fiabilisation, PAS un chantier de remise en lecture seule.** Interdiction de réintroduire un verrou général `read-only`, une whitelist de pilotes ou d'ECU, un certificat/signature ou une permission CamaroDiag de programmation inventée, un interrupteur d'écriture désactivé par défaut, ou de transformer une fonction d'écriture réellement implémentée en fonction indisponible par prudence générique. Ne pas réinterpréter les anciennes notes « toute écriture interdite », « pilotes enregistrés uniquement » ou « read-only PR95 » comme une consigne actuelle : elles sont remplacées par cette décision.

**Conserver** le consentement explicite de l'utilisateur en deux étapes (avertissement OK, puis Oui/Non), les contrôles réels imposés par le matériel/constructeur/ECU, la vérification de l'identité, des secteurs et octets envoyés, la sauvegarde préalable réellement restaurable, les checksums, la relecture et le rollback prévus. Ce sont des conditions de cohérence d'une opération physique, **pas des licences ou freins artificiels à la programmation**.

Si un véritable pilote ou une capacité n'existe pas, le signaler précisément et développer la capacité manquante ; ne pas prétendre qu'une opération est opérationnelle et ne pas ajouter de verrou technique générique pour remplacer le développement. Ne pas modifier les protocoles actuellement confiés à l'autre chat. Respecter ce principe dans C6, C2, l'interface Android, le pont Windows, les tests et la documentation.

## ÉTAT DE REPRISE — C6 ET CONNECTEURS (23/11/2026, PR #95)

- **C6 n'est pas disparu et ne doit pas être clôturé.** Il couvre le consentement OK puis Oui/Non, l'identité de l'ECU, le bloc d'écriture exact, le pont TLS J2534, le pré-flash et la récupération après écriture. L'audit `docs/AUDIT_PR95_C1_C2_C4_C6_C8.md` et le contrôle `docs/C8_PARALLEL_NON_PROTOCOL_REVIEW.md` le suivent explicitement.
- Une correction C6 vient de lier le consentement d'effacement DTC à la génération de connexion au moment de l'avertissement ; toute reconnexion/déconnexion/expiration de compte invalide le « Oui » précédent. Tests unitaires ajoutés. La même exigence vaut pour toute future écriture physique, sans clé ni licence artificielle.
- C2 conserve les régions et les octets exacts des images hors ligne. Si la vérification finale d'un paquet de restauration échoue, supprimer l'index plutôt que laisser une image apparemment complète. **Toujours pas de preuve d'une image complète et physiquement restaurable sur ECU.**
- L'UI Adaptateur possède une commande générale de déconnexion pour USB, Wi-Fi, Bluetooth/BLE, J2534 et simulation. Les périphériques USB sont vérifiés par numéro Android final, puis par énumération ; les annulations USB/Wi-Fi propagent correctement leur `CancellationException`.
- Les développements protocoles SAE/ISO, l'écriture et les transports physiques du firmware connecteurs relèvent de l'autre chat. Pour cette intervention : connexions, sécurité de session, C1/C2/C4/C6/C8 et leurs tests ; pas de création de branche/PR, pas de merge, CI groupée en fin de chantier. Ne pas présenter l'ajout des tests comme une réussite CI.

## DERNIER LOT HORS PROTOCOLES — C6 ET PONT WINDOWS (23/11/2026)

- C6 : double validation DTC désormais liée au même adaptateur **et** à l'identité du véhicule/VIN depuis l'avertissement jusqu'au ViewModel. Même une reconnection rapide, un changement de profil véhicule ou un changement de compte invalide l'autorisation. Tests `EcuWriteConsentFlowTest` étendus.
- J2534 : le pont TLS appairé est visible distinctement de l'adaptateur ELM, sans affichage trompeur des capacités ELM ni prétention d'accès ECU. Le formulaire de connexion masque son jeton + empreinte TLS dans `toString()` et possède un test de confidentialité.
- C1 : la console brute revérifie déjà son contexte avant TX, ne pas supprimer cette protection. C2 : l'index d'un paquet de sauvegarde hors ligne est supprimé en cas d'échec de finalisation ; toujours pas de preuve de restauration physique.
- CI : une validation groupée Android/communication/Windows a été demandée via `.github/c1-console-gate-request.txt` au commit `671bbbe`. **Son résultat n'est pas confirmé** ; aucune validation C8 complète au HEAD final ni essai physique n'est revendiqué.
- C6 **reste actif** en lien avec C2 et le chantier des pilotes physiques. Ne pas annoncer programmation, sauvegarde complète, rollback ou prise en charge complète des connecteurs à la seule vue d'une UI ou d'une suite de tests.

## DÉCISIONS LES PLUS RÉCENTES — À APPLIQUER DANS TOUT NOUVEAU CHAT (23/11/2026)

**Lire impérativement [AGENTS.md](../AGENTS.md) et [l'audit de PR #95](AUDIT_PR95_C1_C2_C4_C6_C8.md).** Ces règles priment sur les anciennes indications de PR #35, branche `feat/live-can-monitor`, CI à chaque ~50 commits, verrouillage ECU globalement lecture seule et anciens blocages d'écriture décrits plus bas. Sur GitHub au 23/11/2026 : PR **#95** et branche **`chore/unified-ci`**, non fusionnée ; toujours rafraîchir ce statut en temps réel.

**Demande définitive du propriétaire :** aucun verrou ou licence d'écriture inventé par CamaroDiag. Conserver seulement les licences commerciales, les vraies exigences constructeur/ECU et les vérifications techniques de cohérence/integrité. Pour chaque opération pouvant écrire, afficher **avertissement de risque + responsabilité CamaroDiag (dans les limites légales) + OK**, puis une **deuxième confirmation Oui/Non** : Non, fermer ou perdre la session = zéro transmission. **Avant le premier effacement, faire une vraie lecture physique des octets d'origine de TOUS les secteurs à modifier, une double comparaison indépendante à l'image d'origine, une sauvegarde durable propre à chaque bloc avec adresse/longueur/région/ECU et une relecture du fichier de sauvegarde. Ne jamais se contenter d'un fichier BIN fourni par l'utilisateur ou d'un hash comme sauvegarde du bloc réel.** Vérifier avant la transmission que **l'adresse ECU, la région, le secteur d'effacement, la taille, les données et le checksum du bloc réellement envoyé** correspondent exactement au changement demandé ; contrôle pré-effacement et pré-trame ; lecture de vérification après écriture. Si sauvegarde / identité / géométrie / octets ne concordent pas, aucune commande d'effacement ou écriture. Ceci concerne tout ECU, tout protocole et tout adaptateur dès lors qu'un pilote réel existe ; pas de fausse compatibilité. Les commandes RAW sans définition mémoire cible ne peuvent pas être déclarées vérifiées bloc-à-bloc.

**Processus** : une seule branche+PR active ; ne pas fusionner sans accord ; une campagne de tests après un chantier terminé, correctifs groupés puis revalidation ; C1/C2/C4/C6/C8 à reprendre/auditer. Les validations logicielles ne prouvent pas la capacité de programmation physique.


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

## Décision impérative du propriétaire — jamais de sécurité ECU artificielle (23/11/2026)

Ne JAMAIS ajouter ni réintroduire, sans demande et accord explicites de l'utilisateur, des clés de confiance, signatures de profils, certificats de programmation, listes figées de pilotes ou interrupteurs bloqués par défaut qui ne sont pas exigés par le protocole réel ECU/transport. C'est une perte de temps et empêche les essais réels. Cette interdiction s'applique aussi aux tests, à la CI et aux futures PR. Toujours expliquer toute restriction nouvelle et obtenir une validation avant de l'implémenter.

Séparer les licences Firebase de CamaroDiag (droits d'utilisation), le jeton et le TLS du pont (sécurité réseau) et le seed/key GM `0x27` (véritable échange ECU). Aucun ne justifie une clé de signature CamaroDiag pour programmer un E39. En revanche, conserver les vérifications techniques qui évitent un ECU inutilisable : identité exacte, sauvegarde complète et réellement restaurable, mémoire/firmware compatible, alimentation stable, checksums OEM, vérification après écriture, récupération réelle. La disponibilité du code ne prouve pas sa validation sur matériel.

Une seule PR et une seule branche actives ; pas de fusion sans autorisation. C2 reste INCOMPLET jusqu'à preuve d'une image entière restaurable. Respecter cette décision sur toute reprise du développement.

**Nouvelle exigence UI confirmée le 23/11/2026 : avant chaque opération utilisateur susceptible d'écrire sur un ECU ou un élément connecté**, afficher deux écrans successifs : (1) avertissement sur les risques de dommage et mention que CamaroDiag décline toute responsabilité dans les limites de la loi, à acquitter avec **OK** ; (2) confirmation explicite **Oui / Non**, avec **Non** ou fermeture entraînant immédiatement l'annulation **sans envoyer la commande**. L'OK initial ne vaut jamais autorisation d'écriture. Réafficher pour toute nouvelle opération (pas à chaque paquet interne d'une écriture continue). Appliquer à tous les futurs pilotes, écrans, protocoles et adaptateurs, y compris consoles brutes et voies J2534 ; vérifier que le contexte de connexion n'a pas changé au moment de l'envoi. **Cette double confirmation est expressément souhaitée par l'utilisateur et ne doit pas servir de prétexte pour réintroduire d'autres clés ou blocages artificiels.**

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
- Conserver `main` et au plus une branche de travail associée à l'unique PR active ; vérifier les noms dans GitHub. Au 23/11/2026 : `chore/unified-ci`, PR #95. Ne jamais créer une nouvelle branche/PR de sa propre initiative.
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


### ARBITRAGE PRIORITAIRE DU PROPRIÉTAIRE — livraison rapide PR #54 (20/11/2026)
- **La programmation multi-modules et les développements étendus de sauvegarde/écriture partielle sont REPORTÉS À LA PROCHAINE PR**, à créer seulement après fusion/fermeture de #54 : issues **#68** (catalogue multi-modules et sauvegarde universelle) et **#67** (codage d'options/écriture ciblée), ainsi que l'activation matérielle des pilotes #64 et récupération physique #65. Cette décision **remplace toute mention antérieure** demandant de développer ces fonctionnalités dans #54.
- **Ne pas prolonger #54** avec de nouvelles interfaces ou fonctionnalités de programmation multi-modules. Conserver les fondations déjà commitées (#62/#63/#64/#65/#66) si elles compilent et ne bloquent pas la livraison, mais ne pas les utiliser comme critère de complétude des fonctionnalités matérielles.
- **#54 : priorité immédiate** aux correctifs P0 #56 (insets Android 10–16), #57 (sélection des capteurs), #58 (aide DTC), à l'état LFX 2012 #52 réellement pris en charge et aux corrections de tests/builds Android et Windows. Ne jamais annoncer une écriture ECU, une sauvegarde physique ou un essai sur véhicule sans preuve.
- **Prochaine PR, après #54** : #67/#68, sauvegarde partielle/complète systématiquement proposée avant chaque modification, avec preuves de couverture et d'intégrité, puis programmation par modules et validation matérielle. #55 reste également postérieure à #54, avec priorité à arbitrer.
- Conserver une seule branche/PR active, aucun merge avant validation finale sans erreur ni avertissement. Ne pas créer la PR suivante maintenant.

### Finalisation du lot logiciel PR #54 — 20/11/2026
- Checkpoint de référence : `85f592d` Android Debug/unit/lint/émulateurs API 29, 33, 36 = SUCCESS, Android Release signé = SUCCESS, Windows Debug/Release = SUCCESS. Les commits de cette section sont POSTÉRIEURS et pas encore validés.
- #62 Mode Expert : ajouts méthodes distinctes OBD/BENCH aux méthodes BOOT/BAM, Nexus/JTAG et CHIP-OFF ; parcours FR/EN et tests `EcuBenchExpertPlanTest` mis à jour. Toujours aucun pilote matériel enregistré.
- #63 Sauvegardes : `EcuBackupCoverage` ne déclare plus COMPLETE si une région requise manque ; `EcuBackupManifest` construit un inventaire SHA-256/longueurs à partir d'octets fournis, compare un second buffer et reste explicite sur absence de provenance physique. Tests `EcuBackupCoverageTest` et `EcuBackupManifestTest` ajoutés. Export/réimport matériel/paquet complet non disponible.
- #64 Contrats : `EcuProgrammingDriverContract` isole ELM/J2534/BENCH/BOOT/NEXUS/CHIP-OFF, identification, lecture, erase, write, readback et reset. Registre de pilotes vide, écriture matérielle non autorisée ; test de non-activation ajouté.
- #65 Reprise : `EcuRecoveryAssessment` classe les coupures sans réessai automatique, selon présence ECU, tension, identité, sauvegarde et pilote de reprise. Tests de scénarios ajoutés.
- #66 Audit avant/après LFX 2012 : `Lfx2012ProgrammingAudit` distingue observé, indisponible, non réalisé, à revoir ; tests sur VIN et horodatage / communication ; résumé de l'état avant et « après non réalisé » dans l'écran ECU. Sans donnée de flash physique.
- #67 Planification ciblée : `EcuPartialWritePlanner` calcule hors ligne les octets et secteurs modifiés, refuse géométrie invalide et zones protégées ; tests des frontières de secteur et préservation des octets voisins. L'écran Expert FR/EN accepte les dimensions mémoire saisies par l'utilisateur et affiche un aperçu de l'impact pour deux BIN. Géométrie et compatibilité matérielles **non vérifiées**, aucune écriture.
- **Étape immédiate** : lancer une CI de checkpoint sur HEAD via le workflow de lancement dédié, lire les journaux Android/Windows/Release, corriger toute erreur et avertissement de code. Pas de fusion si tests non terminés. La validation matériel/firmware/ECU reste impossible sans équipement.
- Maintenir les issues matérielles #52/#63/#64/#65/#67 ouvertes ; éviter d'étendre la PR à de nouveaux pilotes sans matériel vérifié. Le périmètre livrable immédiat est le logiciel préparatoire et le diagnostic lecture seule.


## FEUILLE DE ROUTE DES FUTURES PR — classement validé le 20/11/2026
**Référence de planification : 29 issues ouvertes examinées.** Les lots ci-dessous sont des **lots prévisionnels**, pas des numéros de PR déjà créées. Une seule PR de développement active : #54 sur feat/flexfuel-lfx-2012 ; ne pas créer les autres branches/PR avant sa fusion, fermeture et suppression de branche.

| Ordre | Lot futur | Priorité | Issues | Dépendances et objectif |
|---|---|---|---|---|
| 1 | **A — Sauvegarde et programmation ECU ciblée** | P0 | #63 #67 #64 #65 | Conserver les fondations déjà commitées ; construire sauvegarde vérifiée partielle/complète, diff secteurs, pilotes, readback et récupération ; banc réel requis pour annoncer l'écriture |
| 2 | **B — Programmation multi-modules** | P1 | #68 #66 #46 | Après A ; BCM/TPMS, ABS, SDM, HVAC, FPCM, IPC et atelier de calibration, inventaire selon VIN/RPO |
| 3 | **C — Interfaces et robustesse** | P1 | #41 #43 #50 | Matrice VCX/OBDLink/ELM/J2534, déconnexions et CI ; peut être avancé avant A si blocage technique |
| 4 | **D — Garage, comptes et télémétrie** | P1 | #42 #53 #44 | Isolation véhicule/compte et temps réel multi-ECU |
| 5 | **E — FlexFuel autres moteurs** | P2 | #55 #37 | Gen 5/Gen 6 V6/V8, matrice ECU/firmware distincte, après base LFX |
| 6 | **F — Service et actionneurs** | P2 | #45 #60 | Fonctions de service documentées et commandes sécurisées réellement supportées |
| 7 | **G — Ergonomie, recette, extensions** | P2/P3 | #40 #48 #49 #47 | Tablettes, cahier Excel, langues futures, plateformes étendues ; FR/EN uniquement tant que décidé |

**PR #54 à finir sans extension** : P0 #56/#57/#58, FlexFuel LFX 2012 #52 en lecture seule/contrôles vérifiés, correctifs déjà engagés #38/#59/#61, tests et build. #62/#63/#64/#65/#66/#67 ont déjà du code préparatoire dans #54 : conserver s'il est stable mais **ne pas en déduire que les fonctionnalités physiques sont terminées**. #68 et fonctionnalités complètes de #67 passent après #54. #53 et les autres lots ne doivent pas bloquer la livraison de #54.

**Règle universelle** : toujours proposer la meilleure sauvegarde effectivement accessible avant une modification ; afficher explicitement sauvegarde partielle, régions non lisibles, restauration non démontrée. Aucun flash ECU non validé. Pour chaque lot, créer sa branche/PR **seulement après clôture du lot précédent**, associer les issues, tests Android/Windows sans erreurs ni avertissements, puis merge et suppression de branche après validation. Ce tableau remplace les priorités historiques contradictoires sans effacer leur historique.

### PR54 — audit final des secteurs et matrice Android (20/11/2026)
- Correctif `012130b` : un secteur d'effacement ne peut plus être planifié s'il contient **n'importe quel octet protégé**, même quand l'octet que l'on souhaite modifier est en dehors de la zone protégée. Test de voisin protégé `ee62790` ajouté ; aucune écriture matérielle.
- Matrice instrumentée Android `0d5f0f1` étendue à **API 29, 30, 31, 32, 33, 34, 35, 36** (Android 10 à 16 et leurs niveaux intermédiaires). Tests sur matériel réel, encoches, tablette et paysage restent distincts.
- Premier checkpoint lancé sur `b22cee0` : Windows Debug/Release déjà verts ; Android tests unitaires et lint en cours lors du dernier constat. Le correctif secteurs et la matrice étendue sont **postérieurs à ce SHA**, nécessitant un NOUVEAU checkpoint avant tout merge.
- Ne pas prétendre une compatibilité ECU ou une sauvegarde/écriture physique sans validation matérielle. Les issues #52, #63, #64, #65 et #67 conservent leurs travaux matériels à venir. Ne pas étendre le périmètre de cette PR après validation finale sans nécessité de corriger un défaut.


### Matériel futur #69 — interface J2534 ET programmateur EEPROM sur banc (20/11/2026)
- Le propriétaire demande que l'interface matérielle CamaroDiag (ESP32-S3 / STM32 ou architecture à évaluer) **programme aussi les EEPROM et mémoires sur banc**, et ne soit pas limitée au connecteur OBD/J2534.
- **Issue #69 complétée** : port de programmation séparé, SPI EEPROM 25xx, I²C EEPROM 24xx, Microwire 93xx, NOR SPI via pilotes distincts ; supports SOIC/ZIF, 1,8/3,3/5 V avec protections et identification exacte. Programmer en circuit uniquement lorsque l'absence de contention est prouvée ; sinon dépose. La mémoire interne MCU exige BOOT/BAM, Nexus/JTAG ou autre interface selon référence et n'est pas une EEPROM externe.
- Procédure obligatoire : lecture initiale, proposition sauvegarde partielle/intégrale, double lecture concordante quand possible, dump brut + manifeste/hash, aperçu diff et granularité des pages/secteurs, écriture ciblée uniquement après précontrôles et **relecture indépendante de vérification**. Aucun clonage complet ni récupération garantis sans preuves.
- Planification : #69 en **lot matériel C après clôture de #54**. Dépendances logicielles #63 (sauvegarde), #67 (patch), #64 (pilotes) et #65 (récupération). Ne pas étendre ni retarder PR #54 ; ne créer aucune branche/PR supplémentaire à présent. Toute écriture doit être validée d'abord sur puce de test ou banc.


### 21/11/2026 — poursuite PR #70, lecture Windows et localisation
- Conserver `feat/ecu-backup-integrity` comme unique branche de travail ; ne pas fusionner avant validation complète. Licences désactivées sur Android et côté backend ; pas de déploiement Firebase Billing. Nom du produit immuable **CamaroDiag**.
- Cette passe : 589 chaînes traduites (28 clés véhicule/VIN/réglages sur 16 langues + 141 clés restantes en croate). 868 clés de référence ; 15 locales complètes en nombre de clés (EN FR DE IT ES HE RU ZH-CN JA KO-KR AR PT NL EL HR), 15 incomplètes, 6 333 occurrences de traduction encore manquantes. Audit statique de toutes les locales : aucune clé inconnue/dupliquée ni différence de paramètres dans les chaînes présentes. Cela ne remplace pas CI, lint et recette linguistique. Continuer les traductions directement dans `strings.xml`, ne pas recopier l'anglais ni ajouter de moteur de traduction.
- Correctifs Android : erreurs de Task Firebase et navigateur traitées sans exception non gérée ; expiration future obligatoire pour abonnement. Tests dédiés des droits. Licence toujours désactivée.
- Correctifs Windows #63 : VIN lu via F190 avant chaque passe mémoire, tous les 32 blocs et en fin de lecture ; refus en cas de changement ou réponse tronquée, annulation avant export et contrôle SHA-256 du binaire relu sur disque. Retour à la session de diagnostic par défaut si l'ECU est toujours identifié et si non annulé. Tests simulés ajoutés. Aucune validation physique revendiquée.
- Dernier commit de code/ressources de cette passe : `714cbaac`. CI de ce SHA : lancée, non encore acquise lors du constat. Kernel flash RAM original et validation sur banc restent nécessaires ; aucun flash matériel déclaré validé.


### 21/11/2026 — session suivante PR #70 (checkpoint ac06bf3)
- Travailler uniquement sur CamaroDiag, branche `feat/ecu-backup-integrity` ; CamaroDiagConnectors est hors périmètre pour le moment. Aucune fusion avant validation de la PR et aucun nouveau lot/branche concurrent.
- 63 commits de code et ressources depuis `d1c5d01`, vérifiés par GitHub compare. Correctif Compose de l'identité du compte ; identification physique GMLAN VIN/OSID dans Android via J2534, affichage et export distincts du Mode 09, validation du CAN ID, invalidation lors du changement de contexte. Lecture seule : ne pas confondre observation et qualification de flash.
- Traductions : 150 nouvelles chaînes GMLAN (5 × 30), 130 anciennes chaînes CS/DA, 314 chaînes de commandes et interverrouillages (22 × 12 + 25 × 2). Parité intégrale des anciennes ressources non atteinte ; conserver le test strict.
- Noyau C + C# : tests d'intégration réels du code de transaction contre modèle RAM de la flash MPC5566, échec avant effacement sur original incorrect, récupération après échec de pulse, blocs intermédiaires/hauts et préservation des voisins. Vérifications supplémentaires des registres, erreurs ECC et intégrité du plan de transfert RAM. Compilation croisée PowerPC réussie sur checkpoints antérieurs ; pas de binaire ECU déployable ni essai matériel.
- CI native verte à `3caf199` (44933594826), C# Windows verte à `b0b2281` (44932646651) ; le dernier HEAD nécessite validation renouvelée. Android précédemment bloqué par la parité et le lint Compose ; ne pas annoncer la réussite finale en attente des rapports.
- Prochaines actions : lire les rapports CI du dernier SHA, corriger les défauts, compléter les traductions restantes sans copier l'anglais, poursuivre le raccordement matériel isolé, démarrage/horloge/watchdogs/CAN et qualification sur ECU de banc réellement disponible, avant activation de l'écriture E39/E39A. Aucun succès physique revendiqué.


### 21/11/2026 — reprise PR #70, checkpoint f41bffd
- Continuer exclusivement `feat/ecu-backup-integrity` de CamaroDiag ; aucune nouvelle PR/branche, aucun merge avant développement et validation complète. CamaroDiagConnectors non modifié. Conserver CamaroDiag comme nom propre et la commercialisation des licences désactivée.
- 30 commits de code/ressources depuis `9d755ad` : correction du test de flux tronqué et des six avertissements Android de typographie/URI ; pin TLS défensif et test du trust manager. 358 traductions d'audit kilométrique réparties sur 14 locales ; parité des anciens écrans encore incomplète.
- Noyau MPC5566 : statut flash vérifié à chaque lecture et au handshake, lecture des mots non alignés optimisée. Mesures de tension avant transitions de programmation et transfert RAM. Tests de refus/coupure.
- Nouvelle orchestration locale : identification répétée, sauvegarde complète concordante, digest du kernel, séquence GMLAN, relecture intégrale avant effacement et après écriture avant statut Completed. Tests logiciels incluent modification hors secteurs ciblés. Raccordement physique interne à PassThru J2534 : canal dédié, réponses GMLAN corrélées, purge RX, aucun accès via serveur réseau, verrou de fichier du pilote et empreinte SHA-256 liée au canal qualifié. Ne jamais créer de profil qui déclare une qualification physique non mesurée.
- CI native confirmée sur `031256a` (44935844992), Windows réussie sur `2967b68` (44935309899). Dernier code `f41bffd` : résultats Windows+Android à vérifier ; tests matériels jamais exécutés. Aucun binaire RAM E39 déployable et aucune variante ECU/driver physiquement qualifiée fournie.
- Reprendre les CI, corriger erreurs/avertissements, compléter traductions réelles et qualification du noyau RAM (démarrage carte, horloge, watchdog, CAN, récupération, artefact signé/contrôlé), poursuivre tests E39/E39A et autres variantes. Aucun flash réel ni activation FlexFuel revendiqués. Garder #64, #63, #71/#72, #52 ouvertes jusqu'à preuve.


### 21/11/2026 — reprise diagnostic J2534, checkpoint c8e3971
- 51 commits depuis `2386e17` (avant ce checkpoint documentaire). Toujours uniquement CamaroDiag / PR #70 / branche `feat/ecu-backup-integrity`, aucun changement CamaroDiagConnectors et aucune fusion. Objectif cumulé demandé 200 commits, ne compter que les commits GitHub réels.
- Nouvelle voie OBD physique primaire ISO 15765-4 via J2534, sans émulation ELM : découverte des PID, télémétrie et CSV, DTC confirmés/en attente/permanents avec compte CAN exact, scan complet d'un répondant (freeze frame, monitor support, Mode 09), audit kilométrique. Le scan physique n'est pas une découverte fonctionnelle des modules. Les modes de DTC explicitement refusés restent indisponibles et les anciennes données correspondantes ne sont pas supprimées.
- Effacement standard antipollution activable localement dans le pont Windows (case à cocher/option CLI), avec confirmation Android, mesures fraîches vitesse/régime zéro et réponse positive exacte. Pas d'effacement de codes permanents ni d'ouverture de programmation, actuation, SecurityAccess ou effacement UDS. Vérification stricte d'accusé Mode 04 ELM aussi.
- Deux nouveaux libellés traduits dans les 30 langues ; ancienne parité de ressources encore incomplète. Ne jamais affaiblir le test de parité. Guide utilisateur mis à jour. Tests logiciels du scan/compteurs/réponses partielles et effacement ajoutés.
- CI Windows confirmée à `7eeeb1c` (44937811130, jobs build et validation réussis), noyau natif vert à `fbd93d0` (44938032182). Dernier Android à reprendre après CI ; au checkpoint antérieur `2386e17`, 187/188 tests app réussis, seul test de parité échoué, lint sans avertissement. Ne pas confondre les tests logiciels avec un essai sur véhicule. Aucun flash réel ni qualification matérielle J2534/ECU.
- Priorités : lire les résultats récents CI, corriger toute régression, compléter les anciennes traductions, développer le noyau RAM E39 réellement déployable avec qualification startup, watchdog, alimentation et récupération, et étendre aux variantes exactes. Conserver les limitations ELM/PassThru documentées, les licences non commercialisées et l'accès debug propriétaire uniquement.


### 21/11/2026 — checkpoint recette et intégrité native 4f14500
- Objectif de 200 commits cumulés depuis d1c5d01 : 199 avant ce checkpoint documentaire, 200 après publication. Cette reprise compte 52 commits de code/ressources et 1 checkpoint. PR #70, branche feat/ecu-backup-integrity uniquement ; pas de fusion, CamaroDiagConnectors inchangé.
- Recette propriétaire : contrôles physiques J2534 des PID, DTC et readiness ; contexte VIN/adaptateur/transport capturé, export conditionné à une recette terminée avec provenance, résultats tardifs rejetés après changement de connexion ou de véhicule. L'audit kilométrique J2534 lit les deux PID dans une transaction ; scan OBD protégé contre résultats périmés et lancements multiples.
- Natif : vérification de la géométrie et de l'état flash après impulsion, relecture des sélections remises à zéro, ACK exact, journal de récupération prioritaire après historique corrompu/ancien, lecture et CRC interdits pendant secteur incomplet, fragments non liés à l'écriture refusés. Tests ajoutés sans qualification sur ECU réel.
- 646 chaînes effectivement ajoutées dans les locales incomplètes (238 profils/DTC, 180 connexion/scan, 228 module/telemetrie/avertissements). La parité intégrale reste à terminer ; ne pas désactiver le contrôle.
- CI précédente Android e080322 : 189/190 tests, seul échec parité locale. Noyau natif vert à f93b7f3 (44939072504), Windows vert au checkpoint 7eeeb1c de la session précédente. Résultats du dernier code à vérifier. Aucun noyau ECU déployable qualifié physiquement ni flash réel.
- Suite : corriger toute régression CI, compléter les chaînes restantes, intégrer un démarrage RAM qualifié par carte et procédure de récupération vérifiée, tester les interfaces et variantes exactes sur matériel. Conserver les droits/licences en attente de commercialisation, les tests privés et l'absence de dépendance PCM Hammer. 


### 22/11/2026 — reprise PR #70 (checkpoint f2c91de)
- 17 commits de code/tests/ressources depuis 6b31a2f ; uniquement CamaroDiag, branche feat/ecu-backup-integrity, aucune fusion. À l'entrée : 194/195 tests Android réussis, parité locale en échec ; Windows et noyau natif verts. Douze avertissements TypographyDashes sur le nom normalisé J2534-2 corrigés localement, sans désactiver le lint global.
- Audit direct de strings.xml : 870 clés de référence ; 2 989 manquantes dans ET/FI/HU/GA/LV/LT/MT/PL/RO/SK/SL/SV. BG, CS, DA complets en nombre de clés. Test de parité désormais exhaustif dans son rapport (toutes les locales et toutes les clés manquantes), reste bloquant.
- Sauvegarde J2534 : export lié à l'empreinte originale vérifiée, copies figées, rejet si les deux buffers ont été modifiés ensemble, annulation respectée après retour en session par défaut. Deux tests de régression.
- Mise à jour APK : refus d'un même ID DownloadManager associé à des versions divergentes ; les entrées ambiguës ne permettent ni reprise ni suppression. Tests de régression ajoutés.
- Dernier SHA de code/tests f2c91de : CI à confirmer ; aucune validation physique ECU, aucun nouveau profil qualifié, ne pas activer le flash sur un appareil non qualifié. Poursuivre développement fonctionnel restant, puis traductions réelles fichier par fichier, vraie CI Android/Windows/native complète, et recette matérielle.


### 22/11/2026 — session suivante PR #70 (checkpoint 29aa0e2)
- 13 commits de code, tests et ressources depuis 30ce459, uniquement sur feat/ecu-backup-integrity. Aucun merge, aucun changement CamaroDiagConnectors.
- 510 chaînes traduites (255 SV + 255 RO). Les trois fichiers de chaînes agrégés contiennent 881/881 clés dans ces deux locales, aucune clé inconnue ou dupliquée et aucun écart de paramètres détecté. Reste 2 479 chaînes de strings.xml réparties dans 10 langues. Conserver le test bloquant de parité et prévoir validation linguistique et lint.
- Sauvegarde ECU : vérification renforcée des acquisitions partageant un même buffer, segments vides et VIN mal formé ; refus d'export avant toute écriture ZIP si les buffers sont aliasés ; 4 tests supplémentaires. Une paire de buffers distincts ne prouve pas une acquisition physique indépendante.
- CI à l'entrée 30ce459 : 196/197 tests Android, seul échec de parité, noyau natif vert. CI du nouveau HEAD à confirmer. L'écriture physique reste non qualifiée ; ne pas activer le flash sur un ECU non validé.


### 22/11/2026 — grande session PR #70 (checkpoint 6c5e400)
- 48 commits GitHub réels supplémentaires depuis le point de départ à 1423 commits, avant ce checkpoint documentaire. Branche feat/ecu-backup-integrity uniquement, aucune fusion, aucun changement CamaroDiagConnectors.
- Exports Android : dossier public /CamaroDiag/ avec permission SAF limitée et persistante, vérification de présence et réautorisation si supprimé, contexte compte/véhicule vérifié, fichier temporaire .partial, relecture indépendante et contrôle SHA-256 avant publication. Erreurs de stockage séparées de la liaison ECU. APK toujours dans Download.
- ECU : relecture indépendante de chaque secteur immédiatement avant effacement sur Kotlin et C#, surveillance tension renforcée, contrôle d'identité Kotlin après journalisation et avant effacement. Tests de régression sur perturbation du secteur suivant et tension. Windows conserve les deux acquisitions physiques séparées et peut créer un ZIP vérifiable par Android, ou un BIN brut sans preuve d'archive. Nouveau panneau de récupération hors ligne et rapport de progression du journal.
- Entrée locale E39/E39A conditionnée par un profil exact signé ECDSA P-256 (VIN, OS, DLL, canal, noyau, preuves de carte/récupération, limites). Registre de confiance de production volontairement vide ; aucune clé, carte ou signature matériellement qualifiée. Aucun flash physique possible dans cette configuration. Aucun accès réseau à ces opérations. Métadonnées et journal forcés sur disque avant ouverture ECU, consentement local explicite, aucun retry automatique. Les clés de test éphémères restent uniquement utilisées en simulation.
- 755 chaînes supplémentaires réellement traduites : SK 255, SL 255, PL 245. 873/873 clés strings.xml pour chacune, aucune inconnue/dupliquée ni écart de paramètres détecté. Reste 1724 clés réparties sur ET/FI/HU/MT (245 chacune), GA/LV/LT (248 chacune). Contrôle de parité maintenu bloquant.
- CI Windows compilation, tests sans matériel et installation x86/x64 réussis au SHA 24ccfd5 (run 44988059612). Noyau natif vert à 6c14bfe (44988379080). Ces résultats ne couvrent pas tous les commits ultérieurs. Contrôles Android et Windows ultérieurs à confirmer. CI renforcée pour imposer une compilation après modifications de programmation et d'export non encore validées. Ne pas fusionner tant que l'intégralité de la CI n'est pas verte.
- Suite : compléter les 7 locales réellement, contrôler les warnings et tests du HEAD, corriger tout défaut logiciel, puis qualifier noyau RAM/révision de carte/démarrage/transport et récupération sur matériel réel avant activation. Licences non commercialisées, aucun essai physique réalisé.


### 22/11/2026 — reprise PR #70, parité des langues (checkpoint ba9c351)
- La PR est passée de 1472 à 1506 commits avant ce checkpoint. Contributions de cette reprise et changements parallèles du dépôt conservés, sans écrasement ni nouvelle branche.
- Audit des 30 fichiers strings.xml au SHA ba9c351 : 873 clés chacun, aucune clé manquante, supplémentaire ou dupliquée, aucun écart de paramètres de format détecté. Les 1724 clés manquantes du checkpoint précédent sont désormais présentes. FI/HU/ET/LV/LT/GA terminés dans cette reprise (1479 chaînes), MT complété par les commits parallèles. La qualité linguistique et le test JVM complet restent distincts de cet audit statique.
- Correction ciblée des deux warnings TypographyDashes polonais sur les références exactes des bulletins GM, sans désactiver le lint global.
- Noyau natif : interdiction de traiter des trames après un échec d'initialisation du runtime ou de liaison CAN ; arrêt définitif et récupération requise après rejet d'une commande sur un secteur partiellement réécrit. Tests de non-régression ajoutés, suite native verte à dbe5086 (run 44989413490).
- CI Windows complète verte à edbd2be, Android alors 204/205 tests (seule parité linguistique en échec), lint avec deux avertissements polonais. Les contrôles du nouveau HEAD doivent être confirmés, notamment lint, compilation APK et tests JVM. Aucune programmation physique réalisée ni qualifiée ; ne pas fusionner avant validation complète et respect des exigences du lot.


### 22/11/2026 — reprise PR #70, checkpoint 7d61b31
- PR ouverte, branche feat/ecu-backup-integrity uniquement. 1515 commits au point de contrôle, contre 1493 au démarrage de cette reprise (22 commits supplémentaires sur la branche). Aucune fusion, aucune modification CamaroDiagConnectors.
- Les 30 locales contiennent les 873 clés de strings.xml : audit à ba9c351, aucune clé manquante, inconnue ou dupliquée, aucun écart de paramètres. Maltais : 245 traductions supplémentaires. Les autres locales restantes ont été complétées sur la branche. Contrôles de chaînes non vides et messages localisés du dossier /CamaroDiag/ ajoutés. Ne pas annoncer une recette linguistique humaine ni un build final avant résultats.
- Stockage Android : empreinte stable après fermeture réussie, relecture bornée à la taille écrite, rejet du contenu supplémentaire et de la fermeture en erreur ; trois tests. Exports de calibration, CAN et télémétrie liés au compte et au profil sélectionnés au lancement ; mise à jour des écrans seulement si contexte toujours courant.
- Journal Windows : refus des nouvelles écritures après append interrompu, fermeture fiable du flux et tests annulation/fermeture. Noyau MPC5566 : contrôle de chaque unité ECC avant impulsion, contrôle de toute la plage après programmation, modèle de perturbation d'unités adjacentes et intégration C/C# avec journal de récupération. CI native verte à 80e2df8 (44990046652) et 022934e (44990100782).
- Le dernier build Windows complet confirmé est celui de edbd2be (44988584266). Les builds Android/Windows du nouveau code doivent être confirmés. Les anciens résultats Android (204/205, parité + 2 warnings polonais) précèdent la complétion des langues et les corrections. Ne pas fusionner avant tests et lint verts.
- Qualification physique E39/E39A, carte/initialisation RAM, firmware exact, transport et récupération banc restent à réaliser. Aucun faux profil validé, aucune activation réelle de la programmation ni vente de licence.


### 22/11/2026 — périmètre matériel séparé, PR70 / Connectors #7
- Décision utilisateur : CamaroDiagConnectors devient la couche commune de communication et programmation matérielle Android/Windows, développée à part. Issue chapeau https://github.com/Hellorheaven/CamaroDiag/issues/86 créée, couvre OBD, CAN direct, BENCH, BOOT/BAM, BDM, JTAG/Nexus, SWD, EEPROM/NOR ISP et chip-off, et fédère les issues 1 à 5. Aucun fichier du dépôt Connectors modifié.
- CamaroDiag #79 et PR70 : contrat de pilotes distincts, qualification par cible/firmware/région/route exacte, sélecteur Expert huit modes, sauvegarde ZIP des deux BIN d'origine identiques importés depuis un programmateur externe (15 Mio par lecture max, /CamaroDiag/, provenance importée, couverture partielle). EEPROM externe exclue de l'effacement flash par secteurs. Tests de non-interchangeabilité et d'intégrité ajoutés. Import des deux BIN protégé contre changements de compte/véhicule et annulation. CI Android renforcée pour ces fichiers.
- Le socle J2534 et noyau E39/E39A existant reste conservé ; aucune connexion physique universelle ni écriture validée n'est revendiquée. L'intégration du futur module Connectors et sa qualification matérielle seront traitées séparément. Activation FlexFuel réelle toujours tributaire des définitions de cartographie exactes et de la qualification du pilote.
- Au checkpoint 115fc7b : 1539 commits, 30 locales complètes. Ancien HEAD 926933b : tests unitaires, noyau natif et Windows réussis ; build Android réussi mais instrumentation annulée. Ne pas annoncer dernière CI verte sans vérifier tous les jobs attendus au nouveau HEAD. Ne pas fusionner en cas d'erreur, warning ou validation requise incomplète.


### 22/11/2026 — Reprise PR70, Android natif CamaroDiagConnectors
- Exigence utilisateur : CamaroDiagConnectors fonctionne directement avec Android via USB-C/OTG, sans pont PC, Windows/J2534 facultatif. Exigence inscrite dans CamaroDiagConnectors #7 et #4. Le dépôt Connectors reste développé séparément, aucun fichier de ce dépôt modifié.
- Vérification de Connectors/main : firmware ESP32-S3 0.1.0 fournit uniquement PING, CAPS, STATUS en ASCII à 115200 ; fonctions CAN, EEPROM, flash et J2534 désactivées. Aucun protocole binaire opérationnel ou qualification physique. PR70 : découverte USB native dans Adaptateurs à partir de l'interface USB existante, strictement séparée des commandes ELM et du pont PC. Les trois réponses sont vérifiées avec taille/délai bornés et protocole/version stricts. Le rapport est historique, la liaison est refermée et aucun scan ECU ni profil de connexion opérationnel n'est activé. Action explicite même si la carte se présente sous un nom générique. Cinq tests unitaires et guide utilisateur ajoutés.
- Correction du test EcuFamilyCatalogCompatibilityTest (qualification exacte de la région/route dans son fixture). PR unit tests run 44991165861 au SHA 0a6245d réussi, après la correction et l'intégration de la découverte USB. Native ECU kernel tests au même SHA réussi. PC J2534 Bridge CI checkpoint vert, build Windows ignoré car non modifié (précédent checkpoint 277b7cb vert sans nouveau build Windows ; build complet confirmé à edbd2be). Validation Android complète déclenchée, résultat final à vérifier.
- Sauvegarde ciblée J2534 Android : couverture PARTIAL en absence de carte complète qualifiée, cohérente avec Windows/import externe ; origine et double acquisition conservées. Test mis à jour. Journal Android : l'écriture en cours refuse le retour à un préfixe valide antérieur et toute nouvelle tentative après erreur d'I/O/flush ; lecture bornée et test de régression. Ces derniers changements sont postérieurs au dernier résultat unitaire confirmé.
- Checkpoint 7005bed : 1552 commits avant mise à jour du document, 30 langues. Le registre des pilotes physiques reste vide faute de qualification ; le futur pilote Connectors devra respecter les routes, régions et cibles exactes et utiliser le protocole natif versionné réellement fourni. Aucun pilote universel fictif, aucun faux flash ni activation FlexFuel. Ne pas fusionner tant que CI complète du périmètre final et critères restants ne sont pas remplis. Le firmware et sa qualification, ainsi que l'intégration de ses commandes matérielles, relèvent des travaux indépendants liés aux issues Connectors #4/#7.


### 22/11/2026 — PR70, intégration des exigences Connectors P0 sécurité / anti-brick
- Sources : https://github.com/Hellorheaven/CamaroDiag/issues/86, https://github.com/Hellorheaven/CamaroDiag/issues/87 et https://github.com/Hellorheaven/CamaroDiag/issues/88. Aucun développement firmware Connectors effectué dans CamaroDiag.
- Le rapport ASCII 0.1.0 est explicitement non authentifié et ne donne aucune capacité de trafic ECU. Le gestionnaire USB refuse de sonder un connecteur nommé comme ELM, y compris en appel direct (nom USB = indice de routage, jamais preuve cryptographique). Tests ajoutés en 15d18e7.
- Séquenceur ECU : comparaison indépendante des secteurs non modifiés de la région qualifiée avant le premier effacement et après les écritures ; divergence = arrêt sans succès, sans retry ni reset automatique. Trois nouveaux tests simulés en 1574a0f. Aucune qualification sur banc revendiquée.
- Futur transport natif : appairage hors ligne et authentification mutuelle PC/Android ↔ boîtier, session anti-rejeu, isolation de la découverte, qualification et autorisation exacte par route/cible/firmware/région. Android sans PC. USB en priorité ; Wi-Fi/BLE non destructifs jusqu'à qualification. Ne pas traiter le nom USB, une capacité déclarée ou un checksum comme preuve d'authentification ou de restaurabilité.
- Couvrir OBD, CAN HS/SWCAN/FD, BENCH, BOOT/BAM, BDM, JTAG/Nexus, SWD, EEPROM/NOR ISP/chip-off (SWD et distinction sous-routes restent à intégrer explicitement). Les pilotes existants ne sont pas universels. Les opérations critiques et le journal de récupération doivent être locaux au futur boîtier ; ne pas prolonger aveuglément une opération interrompue depuis Android.
- Signature/secure boot du boîtier uniquement ; image ECU au format natif constructeur, checksum/signature OEM seulement si requis. Pas de chiffrement ou d'en-tête arbitraire sur CAN/EEPROM. Vérification complète, protections électriques, procédure de récupération qualifiée par profil, et sauvegardes /CamaroDiag/. Ne pas activer la programmation physique tant que les preuves et le firmware correspondant ne sont pas disponibles.
- HEAD initial d5a14d4 : quatre workflows GitHub terminés avec succès ; vérifier les jobs réellement exécutés (un checkpoint vert ne garantit pas un build complet). Dernier code 1574a0f : CI à confirmer. PR #70 maintenue en brouillon et unique branche de travail.


### 22/11/2026 — PR70, raccordements et SWD (Connectors #10)
- Nouvelle exigence : https://github.com/Hellorheaven/CamaroDiag/issues/89. Documentation du dépôt Connectors consultée à 04883775d7f97de4cd89da0de75c46bf076f3332 ; fiches LFX 2012 E39 candidat, E38 LS3/L99 2012 et E39A indépendante, toutes non qualifiées. Ne pas réutiliser les pins E39 pour E39A, ni inventer une séquence d'alimentation. USB 5 V logique, 12 V externe protégé dédié cible, VCC mémoire selon composant ; continuité de l'alimentation logique indépendante des données exigée pour opérations critiques.
- dd8a1cb : SWD ajouté au modèle, au sélecteur Expert et aux contraintes de routes/régions ; tests d'étanchéité entre SWD, BDM, JTAG/Nexus, CAN et mémoires externes. Aucun support matériel déclaré.
- cd6f181 : accès au guide et fiches documentaires dans Expert, sélection stricte année/RPO/famille saisie, repli catalogue sans rapprochement approximatif. Trois tests du catalogue. Liens figés sur la révision Connectors examinée, non autorisants.
- 8a60674 : indication non vérifiée, gestion de l'échec d'ouverture, CI Android complète requise pour tout changement de ce catalogue. Aucun nouveau texte non traduit (réutilisation des ressources).
- CI du checkpoint cbafdf7 : tests unitaires et noyau natif réussis, PC J2534 contrôle réussi mais vérifier si compilation effective, Android à vérifier. Nouveau code : CI en cours. Ne pas fusionner avant contrôle final et qualification du périmètre matériel revendiqué.
- Prochain lot : transport authentifié Android direct quand le contrat natif est établi, politique d'admission électrique liée au firmware et à une qualification réelle ; distinction des sous-routes EEPROM/NOR et des variantes CAN, journal local et récupération. Aucune branche supplémentaire, aucun fichier Connectors modifié.


### 22/11/2026 — PR70, finalisation logicielle et nouveau prototype binaire
- Point de départ 88f0114 : 1564 commits, 305 fichiers. Tests unitaires et noyau natif verts ; Windows checkpoint vert (compilation ignorée) ; Android en cours. Pour cbafdf7, tests Android, lint et rejet des warnings, compilation APK instrumenté réussis ; build APK alors en cours. Recontrôler le vrai dernier HEAD.
- Connectors PR #6 au SHA e8593d29 : nouveau mode expérimental USB binaire CD/1, hôte Python de découverte et TLS 1.3 de simulation ; pas de TLS USB embarqué ni de protocole ECU opérationnel. ef2c2e3 : intégration Android de la découverte CD/1, choix explicite du mode, aucune rétrogradation automatique, aucune capacité véhicule. 7c67738 et 61ec3ac : paquets complets et fragmentés, CRC32, longueur, correlation, refus de concaténation, tests de non-régression. 9c60c33 : journal ECU mono-région et test.
- Pour finaliser PR70, vérifier tests unitaires, build Debug et lint Android, instrumentation, build Windows x86/x64 et autotests ainsi que les critères utilisateur encore ouverts (#63,#64,#71,#73 à #79 et lot pertinent). Ne pas confondre le succès d'un checkpoint avec compilation complète. Maintenir licence commerciale désactivée, exports /CamaroDiag/ et fonctions physiques non qualifiées bloquées.
- Conseil de découpage : finaliser le socle logiciel de PR70 sans attendre la fin du développement indépendant du boîtier, mais ne pas retirer un critère obligatoire ni fusionner avant validation et arbitrage si un essai matériel reste requis. Les routes physiques, authentification USB embarquée, qualification électrique, reprise après coupure et FlexFuel sur ECU exact ne sont pas terminées. Aucun faux statut. Aucun changement dans Connectors.


### 22/11/2026 — Synchronisation PR70 avec Connectors PR #6 (77d9590)
- Connectors : 116 commits au moment de la consultation, clients Java Android CDC et .NET Windows, modèle de journal NVS embarqué CDJ/1, pas de commande USB permettant de le lire ni d'accès physique aux ECU. Aucun changement effectué dans ce dépôt.
- 9683d32 : alignement CD/1 sur les réponses canoniques des clients de référence, port CDC unique pour binaire ; refus d'une sélection ambiguë, handle de découverte séparé du canal partagé ELM, contrôle de l'époque de transport et de l'annulation ; une ancienne découverte ne ferme pas un nouveau canal OBD. Compatibilité réelle USB CDC/JTAG Espressif à vérifier, aucun repli ASCII automatique. Le firmware isolé doit être redémarré entre sessions.
- 0888671 : inspection hors ligne du checkpoint CDJ/1 (64 octets, CRC32, identifiant transaction, digest du manifeste, étape/sequence et transitions atteignables). Aucun accès au NVS depuis l'application ; aucun nouveau protocole, aucune autorisation de reprise ni preuve d'authenticité. Quatre tests ajoutés ; test de réponse CD/1 canonique supplémentaire. Aucune écriture physique activée.
- Statut initial : 1569 commits, 307 fichiers, branche unique de travail feat/ecu-backup-integrity. CI du nouveau code à confirmer. Conserver les pilotes et fonctions existants, ne pas fusionner tant que les vérifications complètes ne sont pas réussies. Le véritable contrat de transport authentifié reste une dépendance de Connectors #8 ; la liaison avec son journal embarqué dépend du protocole et de la recette de #9.


### 22/11/2026 — Développement synchronisé des deux PR
- Décision utilisateur : développer CamaroDiag #70 et CamaroDiagConnectors #6 ensemble, une seule branche active par dépôt ; maintenir les critères de fusion indépendants et des tests de compatibilité communs.
- Connectors 410a356 : session CD/1 canonique unique, firmware et Python ; 4d04244 : cohérence étapes/séquences CDJ/1 et verrouillage du writer après persistance incertaine. Révision du contrat et du vecteur partagé : 790d212aba2d8b7aae5d0fac2d274ed78402bda9.
- CamaroDiag : test d'interopérabilité du même vecteur synthétique CDJ/1, récupération requise et écriture bloquée. Les deux dépôts ont le test du même vecteur fixe, non authentifiant. Nouveau code à valider par CI. Le transport authentifié et les tests physiques restent bloquants pour toute programmation réelle.


### 22/11/2026 — Extension multimarque et TuneECU
- Décision : Connectors #11, #12 ; VCI automobile/moto multimarque et logiciels tiers autorisés. Android direct, pont PC facultatif, DLL/API standard et mode natif restent distincts. Ne pas confondre capacités J2534, CDC ou FTDI KKL ; les noms USB ne sont que des indices. Référence Connectors 1d4de23 : politique de séparation des modes et sécurité, schéma candidat FTDI K/L-Line et Bluetooth Classic additionnel (S3 BLE uniquement). Aucun accès moto physique qualifié.
- PR70 : protéger les ports explicitement nommés KKL/K-Line des sondes ELM/CDC dans le gestionnaire et l'UI, préserver les adaptateurs FTDI non identifiés comme KKL. Test de routage ajouté. Ne pas déduire un type de câble du seul VID FTDI ; un câble générique non identifié reste à sélectionner avec prudence. TuneECU doit pouvoir fonctionner indépendamment de CamaroDiag, sans imitation d'identité ni contournement des restrictions de l'éditeur. Aucun firmware ou mode reprogrammation tiers activé.


### 22/11/2026 — Socle commun K-Line passif
- Connectors ee9927f : analyse hors ligne ISO 14230-2 adressage physique, longueur courte/étendue, checksum, adresses exactes ; tests C++ et CI. Aucun TX, initialisation K-Line, ISO 9141 ou accès ECU.
- Côté CamaroDiag : KwpFrameInspector et tests Kotlin, mêmes vecteurs synthétiques que le firmware (82 F1 12 61 01 E7 et 80 F1 12 02 61 01 E7). Aucun support TuneECU réel déduit, aucune capacité K-Line active. Maintenir les tests physiques #12 séparés.


### 22/11/2026 — Session coordonnée pont Windows et protections
- PR70 a026fbf : découverte locale CD/1 dans le pont Windows, CLI explicite COM, test simulé et package System.IO.Ports ; pas de backend ECU ni relais réseau. 13445d8 / Connectors c5349e3 : délai Windows global par échange, test de réponse lente et réutilisation refusée.
- Connectors ad92a68 : arbitre de sessions exclusives de lecture, générations locales, refus de callback obsolète et verrou de récupération ; modèle isolé sans I/O. Le précédent lot ee9927f a ses quatre workflows verts.
- PR70 : snapshot privé unique avant contrôle/planification et protections pendant la double lecture baseline ; tests de coupure et mutation des images. Aucune activation de driver ECU. Attendre les CI complètes, pas de fusion avant build sans avertissements et validation des critères existants.

- Connectors 6c95fac : délais de découverte Android Java et Python, vérification des octets USB encore en tampon, tests de fragmentation lente. PR70 : onglet graphique local de découverte CD/1, sélection COM explicite, confirmation carte isolée, aucun sondage automatique, blocage si pont/console occupés ; même implémentation que CLI. CI du nouveau HEAD à vérifier.

- Pont Windows : inspection hors ligne CDJ/1 et test du vecteur commun avec firmware/Android ; UI récupération et CLI explicite. Toute étape reste bloquante pour les opérations physiques ; pas de commande embarquée d'export. Vérifier CI avant fusion.


### 22/11/2026 — Autorisation mutuelle TLS de référence
- Connectors 4d897e4 : CA + empreinte exacte des clients autorisés et du boîtier, révocation à la prochaine connexion, rejet reprise TLS ; tests socket Python. PR70 : fondation Android IP TLS1.3 dédiée (CA provisionnée, client KeyManager, vérification nom/ALPN/empreintes locales et distantes). Tests Kotlin de la politique post-handshake. Ne pas confondre avec CD/1 USB ou appairage du pont actuel ; non connecté à l'UI ni à une route ECU. Provisionnement sécurisé et tests croisés réels restent bloquants.


### 22/11/2026 — Finalisation PR70 : parité de récupération Android et intégrité export
- Ajout d'une analyse locale CDJ/1 en mode Expert, choix SAF explicite, lecture plafonnée à 65 octets (record 64), rejet des données surnuméraires, aucun accès ECU ni reprise automatique, UI reprend les ressources localisées existantes. Tests de lecture bornée. Le firmware ne fournit pas encore d'export de checkpoint.
- Exports /CamaroDiag : flux empoisonné après toute écriture/flush échoué, aucune empreinte de succès même si appelant intercepte l'erreur et fermeture réussie. Deux tests.
- CI 21cc16a : tests unitaires, Windows et natifs verts, build APK et lint Android sans warnings confirmés ; instrumentation encore en cours au moment du changement. Revalider le dernier SHA. Les issues Connectors #11 à #14 (interop OEM, TuneECU et packs) sont des dépendances séparées ; aucune implémentation firmware dans PR70. Ne pas fusionner avant recette requise et CI du dernier HEAD.


### 22/11/2026 — Décision de centralisation logiciel (#80)
- Remplace la politique antérieure de deux dépôts logiciels : tout firmware, code hôte, pilotes, tests, CI et backlog logiciel CamaroDiagConnectors sont centralisés dans CamaroDiag, PR #70 / branche feat/ecu-backup-integrity. Ne plus développer de code dans l'ancien dépôt. Celui-ci conserve le matériel (PCB, BOM, schémas, câblages, alimentation et validation électrique).
- Import de l'inventaire Git source PR6 81b578571a56c8d96b46932f24c55f34aacd1a65, y compris les fichiers déjà sur main. Module `connectors/`, quatre workflows `.github/workflows/connectors-*`. Inventaire : `connectors/IMPORT_MANIFEST.json`. Les documents matériels sont des liens vers la révision de référence, non des copies. Distinguer l'import d'un snapshot et l'intégration effective aux transports ; dédupliquer progressivement les clients de référence sous tests. L'historique Git reste dans l'ancien dépôt, aucune suppression avant contrôle.
- Issues source #1–5 et #7–14 à centraliser avec leurs liens et discussions ; tableau de correspondance dans #80. Toutes exigences, notamment #11 multimarque, #12 TuneECU, #13 GDS2/Tech2Win/DiagBox lecture/écriture, #14 packs firmware, restent en vigueur. Aucune branche/PR concurrente. Après validation du nouvel emplacement, fermer PR6 sans merger de nouveaux développements logiciels dans l'ancien dépôt et retirer le code de celui-ci de manière contrôlée en préservant les documents matériels. Ne pas annoncer les CI vertes avant résultats réels. Aucun nouveau pilote ECU physique activé.


### 22/11/2026 — Décision finale utilisateur (remplace les règles de répartition précédentes)
- Un seul dépôt actif : CamaroDiag. Intégrer intégralement les deux main et développements PR70/PR6, logiciel ET matériel, schémas/PCB/BOM/documentation et futurs développements dans `connectors/`. Aucun dépôt matériel indépendant à maintenir désormais. Toutes les sources utiles et les exigences sont suivies directement dans CamaroDiag (issues #80 à #93). Les 13 issues migrées #81–#93 sont canoniques, #80 pilote la migration.
- Les neuf références matérielles de la première importation sont remplacées par les fichiers complets d'origine. Les 84 chemins de l'instantané source sont présents ; quatre workflows relocalisés. Demande explicite de fusion de la consolidation dans main maintenant même si la qualification fonctionnelle/physique reste incomplète. Ne pas confondre consolidation Git et livraison/validation fonctionnelle ; inscrire les blocages restants dans les issues et maintenir les écritures non qualifiées bloquées. Aucune nouvelle branche de développement parallèle.

- Liens de câblage de l'application repointés vers l'instantané complet CamaroDiag 84c21a5, toujours non qualifié. Les références matérielles et PCB futurs sont maintenus dans le même dépôt.


### 22/11/2026 — Rationalisation CI post-consolidation (#94)
- Toutes les évolutions sont dans CamaroDiag, logiciel ET matériel sous connectors/. PR70 et ancienne PR6 fusionnées. Ne pas reprendre anciens refs/PR70/PR6 ni ouvrir deux PR de développement.
- 12 workflows identifiés, cible 8 : Android CI, PC J2534 Bridge CI, PR unit tests (y compris Firebase/licences), Native ECU kernel tests, Connectors CI (ESP32/STM32, C++, Java USB, Python 3.11/3.12/3.13, Windows), Release validation sans publication, Release CamaroDiag, nettoyage des branches fusionnées. Supprimer kickoff PR35/70 et remplacer quatre anciennes CI Connectors par une seule. Aucune perte volontaire de tests ; préserver cadence draft 50 commits et build complet ready-for-review.
- Android et Windows : lancement manuel sur main ou ref explicite avec SHA résolu pour artefacts ; supprimer référence à l'ancienne branche. Préflight signé sans publication uniquement main ; publication automatique conservée pour changements produit, pas les seuls documents/PCB. Nettoyage uniquement sur PR fusionnée, branche locale et SHA inchangé, aucune PR ouverte réutilisant le nom. Contrôle de CI avant fusion.
- Catalogue Android Debug existant #76 reste lié à ancienne PR70 ; adapter explicitement dans un lot avec tests de provenance GitHub, ne jamais ouvrir aux artefacts de forks ou prétendre que télécharger est anonyme.


### Règle impérative de gestion Git — issue #96
- Un seul dépôt actif : CamaroDiag. Une seule PR ouverte à la fois (brouillon inclus), ciblant main depuis sa branche locale unique. Il ne peut exister que main et cette branche. Sans PR, main seule.
- Avant toute création, interroger GitHub pour les PR ouvertes et toutes les branches. Si une PR existe, reprendre exclusivement celle-ci. Ne pas créer de branche ni de PR en parallèle, même pour un autre composant. En l'absence de PR, vérifier que seule main subsiste avant de créer la prochaine branche et sa PR.
- Après acceptation et CI obligatoires réussies sur le HEAD exact, fusionner la PR vers main ; vérifier sa fermeture et la suppression de sa branche. Ne travailler sur le prochain lot qu'après contrôle de main seule et zéro PR ouverte. Ne jamais supprimer main, une branche ayant avancé ou du travail non fusionné. Une CI signale les violations mais ne bloque pas atomiquement les créations GitHub : ces préconditions restent obligatoires pour chaque session et agent. En cas de violation, arrêter toute nouvelle création, examiner le travail existant et corriger sans perte.


### Reprise des développements PR6/PR70 après consolidation
- Continuer sur l'unique PR ouverte #95. CDJ/USB/1 : variante firmware de service isolée, lecture réelle du checkpoint NVS, export borné en hex ASCII, un ordre exact par boot, aucune écriture, aucun clear, aucune activation de route. Client Python `--port COM5 checkpoint --isolated --output checkpoint.cdj` refuse corruption, suffixes, troncatures, CRC/états invalides et écrasement. Fichier 64 octets compatible lecteurs Android/Windows existants. Ne pas effacer NVS, ne pas reflasher un boîtier en transaction ou récupération ; variante pour validation isolée uniquement, pas une procédure de restauration validée. Vérifier compilation/CI et tester sur vrai boîtier avant qualification. Durcissement de l'inspection NVS si le record possédé disparaît/change.

- PR95 : 1ccd8eb ajoute la capture USB CDJ/1 dans l'onglet Connectors Windows et CLI, tests hors ligne et conservation exclusive de preuve. Le contrôle Android des secteurs inchangés vérifie identité et alimentation entre acquisitions et après lecture ; test de coupure supplémentaire. Qualifier sur matériel réel, les protections restent actives. Une seule PR ouverte ; pas de fusion sans CI requises.

- Reprise PR95 : capture CDJ/USB/1 directement depuis Android, port CDC unique explicite, confirmation carte isolée, propriétaire/licence et profil contrôlés, réutilise l'arbitrage USB de découverte (pas d'ELM ni de session ECU), export via stockage vérifié `/CamaroDiag/`. Tests parseur/lecture fragmentée et refus données supplémentaires ; aucune autorisation d'écriture, aucune qualification physique.

- Organisation utilisateur (#78) : classification Android sous `/CamaroDiag/` (Backups/ECU, Backups/Diagnostics, Calibrations, Reports, Logs/Console, Logs/Recovery, Telemetry, Tests, Exports). Parité Windows dans Documents/CamaroDiag avec les mêmes catégories et Profiles ; dialogues de sauvegarde prépositionnés, noms uniques, chemin alternatif uniquement au choix explicite, refus des conflits/répertoires redirigés. Préserver fichiers existants et stockage privé. Tests Windows ajoutés ; vérifier CI.

- Stockage Android : catégorie obligatoire à chaque appel d'export (10 appels existants), vérification d'identité des documents temporaires et publiés ; le chemin complet est renvoyé dans chaque message de succès. Tests supplémentaires (#78). Les applications d'installation utilisent toujours DownloadManager et vérification de signature, hors du classement des exports.

- Demande #97 : sous-répertoire `/CamaroDiag/APK/`, téléchargement updater en transit privé puis signature/package/version et copie SAF vérifiée avant lancement installateur. Rétention cinq fichiers archivés gérés (versionCode puis ID), nettoyage après publication et démarrage, indépendamment de la suppression du transit après installation. Aucun fichier hors APK, aucun APK navigateur externe ni ancienne sauvegarde supprimés. Autorisation SAF explicite ; retenter si accès perdu. Tests et CI nécessaires.

- Complément utilisateur #97 : afficher le chemin exact `/storage/emulated/0/CamaroDiag/APK/` dans la modale de téléchargement et la notification, conserver la progression jusqu'à publication vérifiée, possibilité de masquer/réouvrir la modale sans annuler le transfert. Les libellés existants sont réutilisés pour les langues ; rétention cinq APK complets gérés. Continuer sur PR95, ne pas créer une autre branche.


- Hotfix #98 (main et PR95, demande explicite) : logo Google 18dp, vérification fondée sur le claim Firebase signé email_verified après renouvellement, relecture au retour du navigateur, feedback erreurs, actualisation après ajout d'un fournisseur, grâce hors ligne seulement sur même adresse précédemment confirmée, sans privilèges propriétaire offline. L'objet des mails nécessite la configuration Firebase Authentication (nom public CamaroDiag et modèle de vérification). Ne pas modifier l'ID projet/OAuth ni annoncer une modification serveur sans confirmation.


### UX Auth #99 — compte Google et mots de passe
- Google devient la connexion par défaut ; aucun champ e-mail/mot de passe n'est affiché tant que l'utilisateur n'a pas explicitement choisi « Connexion avec e-mail et mot de passe ». Lors du changement de mode, nettoyer les saisies.
- Aucun champ « ajouter/créer mot de passe » sur compte lié Google, même si `password` est présent parmi les fournisseurs ; aucun changement de mot de passe sur compte Google. Seuls les comptes e-mail purs (`password` et non `google.com`) ont dans « Mon compte » une action Changer mon mot de passe, avec ancien mot de passe + double saisie du nouveau. Vérifier ≥8 caractères, égalité, différence de l'ancien ; appeler `reauthenticate` puis `updatePassword` Firebase ; ne pas toucher aux UID, e-mails ou claims. Tests de la politique.

## Suivi protocole PR95 — session du 23 novembre 2026 (complément, non validé)

Travaux ajoutés sur `chore/unified-ci` sans nouvelle branche/PR : ISO-TP mixte 11/29 bits avec N_AE et tests SF/FF/CF/FC ; J1939-81 décodage passif Address Claimed NAME ; J1939-21 requêtes PGN EA00, réponses ACKM E800 et tests négatifs ; DoIP sécurisé TLS TCP 3496 sans contournement de certificat, écoute UDP passive, états entité/alimentation, ACK et activation stricts ; J2534 mode réception réellement passif (initialisation K-Line exclue). État détaillé : `docs/PROTOCOLES_STANDARDS_ET_RECUPERATION.md` et `docs/CHANGELOG_PR95.md`.

**Ne pas annoncer d'implémentation complète ni de compatibilité véhicule** : J1939-22 FD.TP, revendication d'adresse active J1939-81, authentification client/certificats OEM DoIP, J1979-2 complet, pilotes physiques/firmware et flash/rollback ECU restent incomplets ; les nouveaux tests sont écrits mais **aucune CI sur le HEAD de cette session n'a été constatée**. Dernier HEAD observé avant ce complément : `357c962b3f750a5b3b470dc8bf72a8468c69d0f1` ; PR #95 ouverte, draft, non fusionnable au moment de cette lecture (contrôler à nouveau). Tests à exécuter par lot selon AGENTS.md, sans déclenchement par commit. Ne jamais fusionner sans accord utilisateur.


## Reprise protocoles — SAE J1939-22 CAN-FD, complément du 23/11/2026

Le chantier SAE J1939-22 a progressé directement sur la branche PR95 `chore/unified-ci`. Fichiers ajoutés : `pc-bridge/CamaroDiag.J2534Bridge/J1939FdMultiPg.cs`, `J1939FdTransportFrames.cs`, `J1939FdBamSession.cs`, `J1939FdCmdtSession.cs` et suites de tests `*SelfTests.cs`. La CLI Windows `StandardProtocolCli.cs` et son dispatch dans `Program.cs` exposent les commandes `--j1939-fd-multipg`, `--j1939-fd-bam` et `--j1939-fd-cmdt`, en send/receive, avec J2534 FD_CAN brut et double confirmation des émissions. Tests enregistrés dans `BridgeSelfTests`. Une partie OBDonUDS SAE J1979-2, la corrélation UDS DoIP, et les corrections J1939 classique / ISO-TP ont également progressé ; détails dans `docs/PROTOCOLES_STANDARDS_ET_RECUPERATION.md` et `docs/CHANGELOG_PR95.md`.

**Validation obligatoire avant clôture du chantier** : compiler Windows Debug avec warnings as errors puis lancer `--self-test` une seule fois au checkpoint de chantier ; corriger groupément les échecs. Sur l'état observé, les tests sont seulement écrits, **pas exécutés** sur le dernier HEAD. Ne pas présumer que la nouvelle couche FD.TP satisfait intégralement SAE J1939-22, les variantes d'assurance, les sessions multiples ou les timings hardware : vérifier les références SAE et tester un vrai adaptateur compatible FD_CAN. Le support physique J1939-81 (NAME revendiqué et conflits), certificats OEM, SAE J1979-2 complet et les sauvegardes/restaurations ECU éprouvées restent des chantiers. PR95 doit rester ouverte/brouillon, aucune fusion sans instruction du propriétaire. Re-vérifier l'état GitHub avant chaque écriture car plusieurs chats interviennent.


## Reprise des protocoles — session longue du 23 novembre 2026 (complément)

Tous les travaux ci-dessous sont enregistrés sur `chore/unified-ci` dans PR #95 **sans nouvelle branche ni fusion**. Suite à la tranche J1939-22 CAN-FD, les corrections incluent : délais FD.TP globaux/par phase, abort CMDT corrélé sur numéros de segments et EOM incohérent, claim NAME J1939-81 explicite avec arbitrage initial et réponse à PGN EE00, parser et constructeur KWP ISO14230 en adressage physique/fonctionnel, correction checksum J2534 (automatique par défaut, manuel `--flags 0x200`), SW_ISO15765_PS `0x8007` vs SW_CAN_PS `0x8008` et configuration optionnelle `SW_CAN_HS_DATA_RATE`, services applicatifs UDS 0x24/0x28/0x2A/0x2F/0x85, réponses NRC 0x78 validées jusqu'à la réponse finale, DoIP v0x02/0x03/v0x04, versions routage attente confirmation 0x11, generic NACK, certificat client du magasin Windows, et découverte OBDonUDS fonctionnelle F810 CAN 11 bits à plusieurs ECU (source fixe 0x7DF, réponses 0x7E8..0x7EF). Les nouveaux fichiers et tests sont cités dans `docs/PROTOCOLES_STANDARDS_ET_RECUPERATION.md` et `docs/CHANGELOG_PR95.md`.

**Ne pas déclarer le développement terminé.** Compilation Windows Debug, auto-tests intégrés `BridgeSelfTests`, pilote J2534 réel, pile connecteurs, matériel CAN-FD/SW-CAN/K-Line, ECU OEM et récupération post-écriture restent à qualifier. Pas de preuve de CI sur HEAD de cette session. L'identification F810 CAN 11 bits ne couvre pas automatiquement les variantes 29 bits ni toutes les éditions J1979-2 ; J1939-22 avec assurance data et concurrence multi-nœuds, revendication NAME persistante et tous les services applicatifs SAE/ISO restent inachevés. Préserver la double confirmation pour toute émission potentiellement mutante et tous les contrôles de vrais blocs/secteurs avant écriture. Pas de flash ni de rollback OEM revendiqué sans matériel/validation. **Ne lancer la campagne CI complète qu'au checkpoint d'un chantier fini**, jamais à chaque commit. Une autre conversation développe en parallèle sur la même branche : toujours refetch la version de fichier et le HEAD et ne jamais forcer ou fusionner la PR.


## Reprise protocoles — session de développement 23 novembre 2026 (13h57–14h20)

Chantier continué **uniquement dans la PR #95 / `chore/unified-ci`**. Ajouts SAE J1939-22 : support Multi-PG **FBFF 11-bit CAN-FD** en complément FEFF 29-bit, option `--fbff true` avec canal J2534 ouvert réellement en 11-bit, codec et CLI multi-C-PG `--groups '0xF004:010203;0xFECA:C7FF'` avec vérification de taille avant émission et SHA-256 des vrais octets de trame ; prise en charge des C-PG vides explicitement, tests sur modes, DLC, drapeaux et contradictions. Protection FD.TP BAM/CMDT : contrôle du padding FF après la longueur de dernier segment et Abort CMDT si incorrect.

SAE J1979-2 et ISO 14229 : groupes DID F4/F5, F6 surveillance historique, F7, F8 informations véhicule dont F810 ; requêtes et parseurs WWH DTC `19 42` (statut/sévérité), `19 55` (permanent), `19 56` (readiness group), `19 04` (snapshot DTC), `19 06` (données DTC étendues). CLI ISO-TP et DoIP : `--obd-did`, `--obd-wwh-status`, `--obd-wwh-severity`, `--obd-wwh-permanent`, `--obd-readiness-group`, `--obd-snapshot`, `--obd-snapshot-record`, `--obd-extended-dtc`, `--obd-extended-record`. Décodage brut sans supposer les longueurs et unités des données OEM. Corrélation supplémentaire des groupes WWH 0x33, groupes de readiness, DTC 24 bits et identifiants précis de records (sauf demande all-record FE/FF). Tests ajoutés dans les `*SelfTests.cs` existants, **non exécutés**. Aucun build/CI de cette tranche, aucun matériel J2534/ECU/DoIP disponible dans la session.

Respecter le point d'arrêt : une seule PR, pas de création de branche, pas de fusion, pas de CI à chaque commit. **La réalisation logicielle n'est pas égale à la conformité physique**. Relecture finale Windows avec warnings-as-errors, tests regroupés, diagnostics sur adaptateurs réels et qualification ECU restent nécessaires. État de détail : `docs/PROTOCOLES_STANDARDS_ET_RECUPERATION.md`, `docs/CHANGELOG_PR95.md`.


### Reprise protocoles — correctifs complémentaires après tests de trames

- Nouveau chemin OBDonUDS pour DTC WWH : `19 42` (masques de statut/sévérité, groupe émission 33), `19 55` (permanents), `19 56` (readiness), `19 04` (snapshot) et `19 06` (données étendues). Sélecteurs CLI documentés dans `docs/PROTOCOLES_STANDARDS_ET_RECUPERATION.md`.
- Les réponses `19 04`/`19 06` contrôlent désormais le DTC 24 bits et le numéro de record lorsqu'une seule valeur est demandée ; les demandes `FF` ou `FE` multi-record sont préservées. Valeurs brutes non interprétées sans schéma ECU/OEM.
- J1939-22 : FEFF CAN-FD 29 bits / FBFF 11 bits négociés comme tels dans le pilote J2534, C-PG explicitement vide accepté, vérification du padding FD.TP, gestion d'une expiration intermédiaire de 30 secondes J2534 pendant une attente BAM/RTS à délai global plus long, sans modifier les délais courts des phases CTS/DT/EOM.
- Suites de tests ajoutées dans `UdsStandardServicesSelfTests`, `StandardVehicleProtocolsSelfTests`, `IsoTpCanFdSelfTests`, `J1939FdBamSelfTests`, `J1939FdCmdtSelfTests`, `J1939FdMultiPgSelfTests`. **Aucune compilation locale ni CI vérifiée sur cette session ; ne pas prétendre les avoir exécutées.** Aucun flash ni test physique.


## Complément chantiers standards du 23/11/2026 — UDS/J1850/J2534

Ce créneau est resté sur la PR #95 `chore/unified-ci` : aucune nouvelle branche, aucun merge et aucune publication. Les travaux concernent les transports ISO/SAE **et leurs tests hors matériel**, pas la certification d'un ECU. Actualiser le HEAD GitHub avant toute reprise : d'autres sessions écrivent sur cette branche.

- **ISO 14229 / UDS** : `UdsStandardServices.ReadDids/DecodeDids` pour plusieurs DID dans une même requête, avec longueurs explicites issues des définitions ECU et contrôle d'ordre, de longueur et de trames supplémentaires ; CLI ISO-TP/DoIP `--uds-dids` et autotests.
- **Download/Upload UDS** : `DecodeTransferParameters` traite le LFI et maxNumberOfBlockLength des réponses `0x74/0x75`, y compris les champs jusqu'à huit octets. `NativeUdsDownloadProtocol` réutilise le parseur commun en gardant sa limite native. Les constructeurs `RequestDownload/Upload` ont un ALFI explicite adresse/taille sur 1–8 octets ; les anciennes signatures 32 bits conservent le contrôle anti-dépassement de l'espace 32 bits.
- **Lecture UDS 0x35/0x36/0x37** : `NativeUdsUploadProtocol` reconstruit la longueur exacte, valide chaque compteur y compris `0xFF -> 0x00`, refuse les NRC et trames trop longues, et expose `UploadTwiceAsync` : deux lectures physiques indépendantes, égalité octet à octet et SHA-256 uniquement si cohérentes. `NativeUdsUploadSelfTests` est enregistré dans `BridgeSelfTests`. **Ce moteur générique n'est PAS encore raccordé à une cartographie/identité ECU complète ni à un stockage/restauration physique qualifiés.** Les tests sont simulés, pas des lectures de voiture.
- **J1850 VPW/PWM** : `J1850Crc` calcule CRC-8 SAE (vecteur `123456789 = 4B`) et peut valider une frame brute. L'option J2534 `--j1850-wire-crc true` s'applique uniquement aux DLL qui conservent le CRC physique dans RX ; pas de deuxième CRC injecté à l'émission, ignorer les événements de statut et ne pas confondre codec avec transceiver J1850 réel.
- **J2534 CAN brut** : `IsoTpJ2534RawCanLink.Unpack` vérifie l'indicateur de réception `CAN_29BIT_ID` cohérent avec le canal 11/29 bits et la présence de format FD. Autotests ajoutés.
- **CLI et catalogue** : erreurs numériques invalides attrapées proprement dans les principales commandes, matrice protocolaire synchronisée avec l'état réellement codé, test du nombre d'entrées du catalogue passé de 13 à 14.

**Point bloquant** : aucune campagne Windows Debug (warnings as errors), aucun `--self-test` réellement lancé au dernier HEAD de cette tranche, aucun pilote J2534 ou calculateur physique vérifié. Ne déclarer ni « 100 % standard » ni « sauvegarde intégralement restaurable ». Restent la qualification physique, J1979-2 intégral, OEM seed/key/routines/checksums/bootloader par ECU, J2411 et les différents adaptateurs, plus les chantiers C1/C2/C4/C6/C8 détaillés dans l'audit. Exécuter une campagne regroupée au checkpoint de chantier et corriger sans lancer une CI par commit.
