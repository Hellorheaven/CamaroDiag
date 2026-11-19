# Changelog — PR #35 : Sniffer / Console CAN

Ce journal appartient à la branche de la PR #35. Il sera intégré à `main` avec la fusion de la PR. Les évolutions de cette PR doivent être ajoutées ici au fil du travail.

## 2026-11-19

### Analyse des traces — cohérence DLC / données
- ISO-TP : rejet des First Frames dont la longueur annoncée pourrait tenir dans une Single Frame de même capacité. Cela évite de transformer une trame de transport incohérente en message UDS complet. Tests de frontière ajoutés (First Frame invalide de 6 octets et séquence valide de 8 octets).
- Tests CSV précédemment ajoutés : correction des séparateurs de lignes pour fournir de vrais enregistrements distincts aux parseurs.
- Complément : les exports CSV à colonnes fixes `data0…data63` rejettent aussi les octets renseignés après le DLC, au lieu de les ignorer. Tests ajoutés pour les formats ELM, ASC, CSV en colonne `data` et CSV à colonnes fixes.
- Les parseurs ELM327, Vector ASC et CSV rejettent maintenant une trame lorsque le nombre d'octets fournis ne correspond pas exactement au DLC déclaré. Les octets surnuméraires ne sont plus tronqués silencieusement, ce qui évite d'analyser une trame mal formée comme si elle était valide.
- Ajout d'un test couvrant les trois formats. Exécution Gradle non disponible depuis cette session ; le test est ajouté mais non déclaré comme exécuté.

### Périmètre confirmé
- Module utilisateur unique **Sniffer / Console CAN** : capture, analyse, décodage, console et émission lorsque les capacités réelles de l'interface, du firmware, du bus et du protocole le permettent.
- La fonctionnalité ne doit pas être arbitrairement limitée à la lecture seule ; aucune capacité non vérifiée ne doit toutefois être annoncée.
- Les autres domaines (télémétrie, garage, calibration, injecteurs, déconnexion globale, matrice générale des adaptateurs, multi-marques, langues et tablettes) restent hors de cette PR et dans le backlog sur `main`.
- Clarification de périmètre publiée dans la conversation PR #35 (commentaire ID `6999604027`).

### Documentation et suivi
- Règle documentaire : prompt de reprise et backlog exclusivement sur `main` ; changelog tenu dans la branche de chaque PR.
- Les résultats de tests/builds seront ajoutés avec le SHA concerné. Un workflow global vert avec compilation/package `skipped` ne prouve pas un build réel.
- PR : https://github.com/Hellorheaven/CamaroDiag/pull/35

### CI — correction du seuil de build périodique
- Correction de `.github/workflows/android-ci.yml` : le seuil cumulatif est maintenant évalué avant le contrôle du contenu du dernier commit. Le build se déclenche donc dès que le nombre de commits Android depuis le dernier build réussi atteint l'intervalle, même si le commit qui franchit le seuil ne modifie que la documentation.
- Diagnostic du run précédent : intervalle GitHub configuré à 5 pour ce dépôt au moment du run ; 5 commits Android depuis le dernier build réussi, mais le job `test-and-build` avait été ignoré car le dernier commit ne contenait aucun fichier Android. Ce comportement contredisait le principe de seuil cumulatif.
- Commit correctif : `dd36f03908e5e80fda5678c8c192f74dec014562`. La correction attend encore un run CI réel pour être considérée comme validée.

### Correctifs Lint et filtres de direction CAN
- Les deux messages de déconnexion sont disponibles en anglais et en français ; les autres locales utilisent volontairement la ressource anglaise par défaut jusqu'au lot d'internationalisation. Les ressources déclarent explicitement cette règle afin que Lint ne réclame pas de traductions provisoires.
- Ajout de filtres d'affichage RX / TX / Tout dans la capture CAN, combinables avec la recherche locale par ID CAN et octets.
- La direction RX/TX est maintenant visible sur chaque ligne de trame. Le filtre reste local à l'affichage et ne modifie pas le trafic ni les filtres matériels.
- Vérification CI à effectuer sur le nouveau commit ; aucune validation de compilation n'est revendiquée avant le résultat du workflow.

### Interface Sniffer — recherche des trames
- Ajout d'un champ de recherche dans la liste des trames capturées : recherche insensible à la casse dans l'identifiant CAN (format hexadécimal) et dans la séquence d'octets.
- Affichage du nombre de trames correspondant au filtre par rapport au total capturé ; la liste reste bornée aux 100 trames les plus récentes pour préserver la fluidité.
- Ajout des libellés dans les ressources des 14 langues présentes dans la PR.
- Cette fonction est une recherche locale dans la capture déjà chargée ; elle ne modifie pas les filtres matériels de l'adaptateur.
- Commit UI : `2e070ea749875e47e92b1d50e45ec9860dc9c313`. Les commits de ressources et le correctif de l'icône de recherche suivent dans l'historique de la branche.

### Politique de langues — règle de travail confirmée
- Pour les nouvelles fonctionnalités, seules les ressources anglaises (values/) et françaises (values-fr/) sont obligatoires dans les PR fonctionnelles.
- Les autres langues seront traduites dans le lot d'internationalisation dédié ; les traductions déjà présentes sont conservées.
- En l'absence de ressource dans la langue choisie, Android retombe sur la ressource anglaise par défaut (values/).
- Pour le message de déconnexion Bluetooth ajouté dans cette PR, les traductions provisoires des 12 autres locales ont été retirées ; les versions anglaise et française sont conservées.


### Export de capture CAN — 2026-11-19
- Correction de l'intégration UI : `CanSnifferScreen` reçoit désormais le callback `onExport` déjà fourni par l'écran parent.
- Ajout du bouton d'export dans l'écran Sniffer CAN ; il est activé uniquement lorsqu'une capture terminée contient des trames.
- Export déclenché via le sélecteur de document Android déjà câblé dans `CamaroDiagApp`.
- Commit : `29513d72f4aca160be3f598fa261b7219afdef4a`.
- Validation CI : en attente d'un résultat observable ; aucun build réussi n'est revendiqué à ce stade.

### Corrélation UDS et durcissement ISO-TP / CAN FD — 2026-11-19
- La corrélation UDS ne choisit plus arbitrairement une requête quand plusieurs demandes du même service sont en attente : une réponse n'est associée que si un seul candidat reste compatible.
- Pour les services ReadDataByIdentifier (0x22) et WriteDataByIdentifier (0x2E), une réponse positive doit contenir et faire correspondre le DID attendu. Les NRC sans DID restent corrélables uniquement lorsque la requête est non ambiguë.
- Les durées d'échange négatives dues à des horodatages incohérents ne sont plus présentées comme des mesures valides.
- Prise en charge des Single Frames ISO-TP au format de longueur étendue CAN FD ; détection et abandon explicite d'une Consecutive Frame tronquée.
- Le compteur des messages ISO-TP incomplets inclut maintenant les sessions remplacées, les séquences interrompues et les sessions encore ouvertes à la fin de la capture.
- Une nouvelle First Frame invalide ou une Single Frame qui interrompt une session existante abandonne immédiatement l'ancienne réassemblage ; les Consecutive Frames suivantes ne peuvent plus compléter un message périmé.
- Les exports CSV à colonnes fixes acceptent maintenant les 64 octets CAN FD (data0 à data63), et non plus seulement data0 à data15.
- Prise en charge des First Frames ISO-TP à longueur 32 bits lorsque FF_DL vaut zéro, avec reconstitution de messages jusqu'à 1 Mio. Les longueurs tronquées et les annonces dépassant ce plafond sont rejetées.
- Le nombre de messages ISO-TP incomplets/abandonnés est désormais affiché dans l'analyse Sniffer, avec les libellés anglais et français.
- L'historique d'échanges associe désormais aussi les requêtes/réponses OBD-II standard des modes 0x01 à 0x0A, en plus des services UDS ; les services OBD-II usuels disposent de libellés explicites.
- Quinze tests de régression couvrent les échanges OBD-II Mode 01, les réponses UDS ambiguës, les DID manquants/concurrents, les longueurs CAN FD étendues, la reconstitution ISO-TP 32 bits jusqu'à 4096 octets, les trames tronquées, le bouclage du numéro de séquence, les sessions de directions opposées partageant un ID CAN, le remplacement d'une First Frame et les exports CSV 64 octets.
- Commits de code : 2019c9a, f0728f3, 1a022f1, 827b758, cdebf21, 8c0170e, 4b36a9e, 226c7a4, c192fa0, ee832ff, d688897. Ressources : 44f9237, e24727b. Commits de tests et correctifs des fixtures : 82fb72c, 9d71440, 87306ad, 7283076, 0ddc5e1, 4caf6ba, d3841a9, bf6d73c, 6411bbd, 6fed7ab, d3841a9, bf6d73c, 2262dc9, 07dbf85, 0197fb9.
- Aucun build ni test automatisé n'a encore été lancé sur ce lot ; les tests sont ajoutés mais pas déclarés validés.
- Extension ISO-TP First Frame : décodage de la longueur 32 bits lorsque FF_DL vaut zéro, reconstitution de charges utiles supérieures à 4095 octets et plafond d'analyse à 1 Mio. Les longueurs étendues tronquées ou hors plafond sont rejetées sans allocation de la charge annoncée.
- Commits supplémentaires : code `cdebf21` et `226c7a4` ; tests `d3841a9`, `bf6d73c` et `2262dc9`.
- Synchronisation avec main : commit de merge e7aa7ad6e8ddf5b351dbb12666171d38284e3fbe. La branche n'est plus en retard sur main ; GitHub indique mergeable_state: clean.


### Corrélation avancée, console OBD et décodage DTC — 2026-11-19
- L'historique des échanges corrèle maintenant les réponses positives OBD Mode 01/02/05/06/08/09 par PID ou identifiant de test, même si plusieurs requêtes du même service sont en attente et que les réponses arrivent dans le désordre.
- Les DIDs des services UDS 0x22, 0x2E et 0x2F sont vérifiés sur la réponse positive. Les sous-fonctions des services UDS 0x10, 0x11, 0x19, 0x27, 0x28, 0x31 et 0x3E sont comparées ; RoutineControl vérifie également l'identifiant de routine 16 bits. Une réponse négative sans identifiant reste non associée lorsqu'il existe plusieurs candidats.
- Ajout d'un décodage des réponses OBD-II 0x43, 0x47 et 0x4A en codes DTC standard à cinq caractères (familles P/C/B/U). Les couples 00 00 de remplissage sont ignorés ; le mode de lecture, l'ID CAN, la direction et les octets bruts restent attachés à chaque observation. Aucun libellé constructeur ou diagnostic spécifique au VIN n'est inventé.
- La console accepte maintenant les demandes de lecture de défauts mémorisés (03), en attente (07) et permanents (0A), en complément des paires Mode 01/09 déjà autorisées. Le mode 04 d'effacement, les écritures UDS et les trames CAN arbitraires restent rejetés.
- L'écran CAN affiche les identifiants PID/test et les codes DTC décodés. Les nouvelles chaînes sont fournies en anglais et en français ; les autres langues utilisent la ressource par défaut.
- Le cycle d'envoi réserve maintenant son état avant le passage au dispatcher IO, empêchant deux clics rapides de lancer des commandes simultanées sur le même adaptateur. Le lancement est également refusé tant qu'un job de capture est actif.
- Tests ajoutés pour les réponses Mode 01 hors ordre, DID 0x2F, sessions UDS concurrentes, RoutineControl avec RID distincts, décodage DTC 03/07/0A, padding et exclusion des réponses qui ne sont pas des DTC.
- Commits de ce lot (19) : `89c1ff1`, `0ecbddf`, `848f622`, `cf20527`, `077408a`, `ff3d1f6`, `a8d542c`, `15ca4b7`, `801509f`, `d8444b8`, `7faafbe`, `5c2c582`, `1620efb`, `18ed064`, `79b3c5a`, `336f6ad`, `ef0593d`, `5dabb17`, `094cd77`.
- Vérification de cohérence des fichiers et présence des cas de test effectuée par lecture du dépôt. Aucun build Android, test Gradle, CI ni essai sur véhicule n'a été exécuté pour ce lot ; ils ne sont donc pas déclarés réussis.


### Durcissement temporel ISO-TP et extension des PID moteur standard — 2026-11-19
- Le réassemblage ISO-TP rejette maintenant une session partielle lorsqu'une Consecutive Frame horodatée arrive plus d'une seconde après la précédente. Le délai est réinitialisé à chaque trame valide ; il n'est pas appliqué lorsque les horodatages sont absents ou non monotones. Ce seuil sert à écarter les fragments périmés dans les traces, pas à certifier les temporisations d'un calculateur réel.
- Les réponses UDS négatives tronquées (0x7F sans code NRC) sont ignorées : elles ne ferment plus une requête en attente et ne sont pas reclassées comme de nouvelles requêtes.
- Extension du décodage SAE J1979 Mode 01 : durée moteur, distance avec MIL, niveau carburant, distance depuis effacement DTC, pression barométrique, températures catalyseur B1/B2 S1/S2, temps MIL, temps depuis effacement, position pédale relative, température huile, avance d'injection, débit carburant et couple moteur (PID 0x1F, 0x21, 0x2F, 0x31, 0x33, 0x3C–0x3F, 0x4D, 0x4E, 0x5A, 0x5C–0x5E, 0x61–0x63).
- Correction des libellés PID 0x47–0x4B : les PID 0x47/0x48 sont les positions papillon B/C ; les PID 0x49–0x4B sont les positions de pédale d'accélérateur D/E/F.
- Sept tests supplémentaires couvrent l'expiration et le renouvellement du délai ISO-TP, le NRC tronqué, les valeurs PID ajoutées, les longueurs requises et la distinction papillon/pédale/couple.
- Commits : `a6f4694`, `40fdb5f`, `da97229`, `ad81382`, `ab2b88e`, `6f73059`, `3aa42ce`, `7d49d02`, `eca7c95`.
- Ces tests ont été écrits et relus sur la branche ; aucun test Gradle, build Android ou validation sur véhicule n'a encore été exécuté sur ce lot.


### Libellés PID localisés — 2026-11-19
- Les 23 nouveaux libellés PID ajoutés à l'analyse des traces utilisent maintenant les ressources Android : anglais et français sont fournis ; les autres locales retombent sur l'anglais.
- Les libellés papillon B/C et pédale D/E/F restent distincts dans les deux langues.
- Commits : UI `1f66163`, ressources EN `f940717`, ressources FR `39cdaf3`.
- Vérification statique : 23 références du helper, 23 clés anglaises et 23 clés françaises présentes, sans doublon sur ces clés. Aucun build ni test Gradle n'a été exécuté.

### Corrélation UDS et horodatages — 2026-11-19
- La corrélation d'une réponse UDS exige désormais que son horodatage ne précède pas celui de la requête lorsque les deux horodatages sont disponibles.
- Les traces sans horodatage conservent le comportement basé sur l'ordre de capture ; aucune chronologie n'est inventée.
- Ajout du test de régression `doesNotCorrelateResponseWhoseTimestampPredatesRequest`.
- Commits : `774f6f9` (analyse), `cd108f5` (test initial), `bad6a31` (correction du séparateur de lignes du test).
- Vérification statique du code et du test uniquement ; aucun test Gradle/build CI ni validation sur véhicule exécuté.

### Transport BLE — gestion des déconnexions — 2026-11-19
- Le transport OBDLink BLE mémorise maintenant les erreurs GATT et les déconnexions inattendues.
- Une déconnexion pendant une écriture termine immédiatement l'attente d'écriture avec une erreur explicite.
- Une déconnexion pendant une lecture réveille le lecteur en attente et remonte une erreur au lieu de laisser la commande se terminer comme une réponse vide ou un simple timeout.
- La cause de déconnexion est réinitialisée lors d'une nouvelle connexion et à la fermeture du transport.
- Commits : `1f15322` (état d'erreur BLE), `205fe6b` (réveil des lectures en attente).
- Relecture statique uniquement ; aucun test Android/Gradle, build ou essai avec OBDLink CX exécuté.

### Transports USB et Wi-Fi — détection de fermeture — 2026-11-19
- La lecture USB remonte désormais une fin de flux (EOF) comme une déconnexion explicite au lieu d'attendre silencieusement le timeout de commande.
- La lecture TCP Wi-Fi traite également le retour EOF comme une fermeture de la connexion par l'adaptateur.
- Les timeouts de lecture normaux restent distincts d'une fermeture de transport.
- Commit : `5d16421`.
- Relecture statique uniquement ; aucun test matériel, test Gradle ou build CI exécuté.

### Commandes ELM — validation de fin de réponse — 2026-11-19
- Les commandes ELM ordinaires sur Bluetooth classique, BLE, USB et Wi-Fi exigent maintenant la réception du prompt `>` avant de retourner une réponse.
- Si le délai expire sans prompt, une `SocketTimeoutException` explicite est remontée : une réponse partielle n'est plus présentée comme complète.
- Le mode de capture CAN continue reste exempté de cette exigence, car les commandes de monitoring ne terminent pas sur le prompt ELM normal ; l'arrêt reste piloté par ESC et la durée bornée de capture.
- Les fins de flux USB/Wi-Fi et les déconnexions BLE restent des erreurs de transport distinctes du timeout de réponse.
- La règle de complétude est isolée dans `ElmResponseCompletion` et couverte par trois tests unitaires : réponse complète, réponse incomplète et capture streaming sans prompt.
- Commits : `a8b7f5d` (comportement), `6f7d42a` (helper), `afac1ef` (tests), `13bca42` (intégration aux quatre transports).
- Relecture statique des quatre chemins et présence des trois cas de test vérifiées ; les tests Gradle n'ont pas encore été exécutés, aucun build CI ni essai sur véhicule réalisé.

### Reconnexion BLE — isolation des sessions GATT — 2026-11-19
- Le transport BLE expose maintenant un état de connexion dérivé de la session GATT active et de l'absence d'erreur de connexion.
- Les callbacks Bluetooth tardifs provenant d'une ancienne instance GATT fermée ou remplacée sont ignorés ; ils ne peuvent plus marquer par erreur une nouvelle session comme déconnectée.
- Le canal OBD signale désormais indisponible si la socket Bluetooth classique est fermée ou si la session BLE a signalé une déconnexion.
- Les transports USB/Wi-Fi conservent la détection de fermeture au niveau des opérations de lecture/écriture ; aucune reconnexion automatique n'est prétendue.
- Commits : `2f4205e` (callbacks GATT obsolètes), `d9e0a45` (état du canal OBD).
- Relecture statique uniquement ; aucun test Gradle/build CI ni essai sur matériel exécuté.

- La politique de disponibilité du canal est maintenant isolée dans `ObdTransportConnectionState`, avec quatre tests couvrant socket Bluetooth ouverte/fermée, session BLE active/inactive, modes démo/simulation et transport non pris en charge.
- Commits complémentaires : `aec1251` (règle pure), `70e7b42` (tests), `6f4c3ba` (intégration au gestionnaire).
- Les tests sont ajoutés mais non exécutés à ce stade.

### Correction de la remontée de déconnexion BLE — 2026-11-19
- Les pertes de connexion OBDLink BLE pendant lecture/écriture remontent maintenant sous forme d'`IOException` (`ObdTransportDisconnectedException`), afin d'activer le traitement commun de perte de connexion dans le ViewModel au lieu de laisser l'état UI de l'adaptateur inchangé.
- Ajout d'un test de classification de cette exception.
- Validation Gradle/build Android : non exécutée dans cette session ; aucune validation matérielle BLE effectuée.
- Le traitement commun d'une perte de transport ferme désormais aussi l'adaptateur physique et réinitialise son état interne. Cela évite qu'un canal USB/Wi-Fi reste déclaré disponible après une erreur d'E/S, alors que seul l'état visuel avait été réinitialisé.
- Les callbacks GATT `STATE_DISCONNECTED` et les statuts de rupture de connexion sont également convertis en `ObdTransportDisconnectedException` ; une écriture en attente ne remonte donc plus une `IllegalStateException` ignorée par le suivi global.
- Commit complémentaire : `ff1ddd8` (classification des callbacks GATT) et `0476497` (dernier chemin de lecture).
### Classification des erreurs de transport — 2026-11-19
- Les erreurs de déconnexion physique (exception dédiée BLE, EOF USB et erreurs d'E/S) déclenchent toujours la remise à zéro de l'adaptateur.
- Un `SocketTimeoutException` lié à l'absence du prompt ELM est maintenant traité comme un échec de commande, pas comme une preuve de déconnexion : l'adaptateur reste sélectionné et l'utilisateur peut réessayer.
- Ajout de cinq tests unitaires couvrant déconnexion BLE, EOF, timeout ELM, erreur non-E/S et erreur E/S générique.
- Commits : `ca00d0b` (politique), `f623f56` (tests), `09397df` (intégration), `b404801` (nettoyage import).
- Tests non exécutés dans cette session ; validation Gradle et CI encore requises.

### Restauration de l'interface ELM après capture CAN — 2026-11-19
- Suppression de l'envoi isolé de `AT` en fin de capture : ce n'est pas la commande ELM de remise à zéro des paramètres (la commande de retour aux valeurs par défaut est `ATD`, qui réinitialiserait aussi des paramètres de protocole dont l'application dépend).
- La sortie du mode monitor restaure explicitement les paramètres de formatage utilisés lors de l'initialisation de l'adaptateur : `ATE0`, `ATL0`, `ATH1`, `ATS0` et `ATCAF1`.
- La restauration reste exécutée dans `NonCancellable` et chaque commande est tentée indépendamment, même si une étape échoue.
- Commit : `7f5033f`.
- Contrôle documentaire des commandes ELM ; aucun test Gradle, build Android ni essai matériel n'a été exécuté.

### Filtres temporels des traces CAN — 2026-11-19
- Correction d'un cas limite : un horodatage `NaN` passait auparavant un filtre temporel borné, car les comparaisons avec `NaN` sont toutes fausses.
- Les filtres temporels rejettent désormais explicitement les horodatages absents ou non finis (`NaN`, infinis) ; les traces sans critère temporel restent inchangées.
- Ajout de tests pour `NaN` et l'infini positif.
- Commits : `d6dba73` (correctif), `f983b9d` (tests).
- Tests Gradle/build Android non exécutés.

### Validation des métadonnées CSV CAN — 2026-11-19
- Le parseur n'infère plus silencieusement la longueur d'une trame lorsque le CSV déclare une colonne DLC mais fournit une valeur invalide ou hors plage.
- Les horodatages CSV non vides mal formés ou non finis (`NaN`, `Infinity`) font maintenant rejeter la ligne au lieu d'être convertis en horodatage inconnu.
- Un horodatage vide reste accepté comme inconnu lorsqu'il est réellement absent ; cela préserve les captures sans horodatage.
- Ajout de trois tests couvrant ces comportements.
- Commits : `0c3f635` (parseur), `be238ac` (tests).
- Tests Gradle/build Android non exécutés.

### Validation des horodatages Vector ASC — 2026-11-19
- Le parseur ASC accepte maintenant les notations décimales et scientifiques valides.
- Les horodatages non finis (`NaN`, `Infinity`) sont reconnus comme métadonnées invalides et la trame est rejetée, au lieu d'être ignorée silencieusement.
- Ajout d'un test couvrant les deux valeurs non finies et une notation scientifique valide.
- Commits : `b04682d` (parseur), `55383ba` (tests).
- Tests Gradle/build Android non exécutés.
