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
