# Changelog — PR #35 : Sniffer / Console CAN

Ce journal appartient à la branche de la PR #35. Il sera intégré à `main` avec la fusion de la PR. Les évolutions de cette PR doivent être ajoutées ici au fil du travail.

## 2026-11-19

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
- Les exports CSV à colonnes fixes acceptent maintenant les 64 octets CAN FD (data0 à data63), et non plus seulement data0 à data15.
- Prise en charge des First Frames ISO-TP à longueur 32 bits lorsque FF_DL vaut zéro, avec reconstitution de messages jusqu'à 1 Mio. Les longueurs tronquées et les annonces dépassant ce plafond sont rejetées.
- Le nombre de messages ISO-TP incomplets/abandonnés est désormais affiché dans l'analyse Sniffer, avec les libellés anglais et français.
- L'historique d'échanges associe désormais aussi les requêtes/réponses OBD-II standard des modes 0x01 à 0x0A, en plus des services UDS ; les services OBD-II usuels disposent de libellés explicites.
- Douze tests de régression couvrent les échanges OBD-II Mode 01, les réponses UDS ambiguës, les DID manquants/concurrents, les longueurs CAN FD étendues, la reconstitution ISO-TP 32 bits jusqu'à 4096 octets, les trames tronquées, le bouclage du numéro de séquence, les sessions de directions opposées partageant un ID CAN, le remplacement d'une First Frame et les exports CSV 64 octets.
- Commits de code : 2019c9a, f0728f3, 1a022f1, 827b758, cdebf21, 8c0170e, 4b36a9e. Ressources : 44f9237, e24727b. Commits de tests et correctifs des fixtures : 82fb72c, 9d71440, 87306ad, 7283076, 0ddc5e1, 4caf6ba, d3841a9, bf6d73c, 6411bbd.
- Aucun build ni test automatisé n'a encore été lancé sur ce lot ; les tests sont ajoutés mais pas déclarés validés.
- Extension ISO-TP First Frame : décodage de la longueur 32 bits lorsque FF_DL vaut zéro, reconstitution de charges utiles supérieures à 4095 octets et plafond d'analyse à 1 Mio. Les longueurs étendues tronquées ou hors plafond sont rejetées sans allocation de la charge annoncée.
- Commits supplémentaires : code `cdebf21` et `226c7a4` ; tests `d3841a9`, `bf6d73c` et `2262dc9`.
- Synchronisation avec main : commit de merge e7aa7ad6e8ddf5b351dbb12666171d38284e3fbe. La branche n'est plus en retard sur main ; GitHub indique mergeable_state: clean.
