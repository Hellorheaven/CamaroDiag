# Politique CI — builds Debug sans warning

## Règle de fusion
Aucune pull request vers main ne doit être fusionnée tant que les deux builds Debug ne sont pas terminés avec succès et sans avertissement :

- Android CI / test-and-build
- PC J2534 Bridge CI / build-windows-debug

Un job skipped, en attente, annulé ou exécuté sur un ancien SHA ne constitue pas une validation.

## Contrôles Android
- Kotlin : kotlin.allWarningsAsErrors=true.
- Java : -Werror appliqué à toutes les tâches JavaCompile.
- Gradle : org.gradle.warning.mode=fail et --warning-mode=fail sur les tâches CI.
- Android Lint : toute entrée XML de sévérité Warning ou Error fait échouer le build.

## Contrôles Windows
- dotnet build et dotnet publish en configuration Debug utilisent -warnaserror.
- Tout avertissement émis par Inno Setup fait échouer la génération des installateurs x64/x86.

## Protection GitHub à activer sur main
Dans Settings → Branches (ou Rulesets), créer/éditer une règle ciblant main et activer Require status checks to pass before merging. Ajouter exactement les deux contrôles ci-dessus comme checks obligatoires. Activer également Require branches to be up to date before merging pour exiger une validation sur la tête à jour.

La connexion GitHub utilisée par l'automatisation ne dispose pas du droit d'administration nécessaire pour lire ou modifier les règles de protection du dépôt. Le code CI est donc configuré pour rejeter les warnings, mais l'obligation côté bouton Merge dépend de l'activation de ces deux checks dans les paramètres du dépôt.
