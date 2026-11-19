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
