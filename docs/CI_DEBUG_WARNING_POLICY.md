# Politique CI — builds Debug sans warning

## Règle de validation avant fusion

Aucune pull request vers `main` ne doit être considérée comme validée tant que les contrôles Android et Windows applicables au contenu de la PR ne sont pas terminés.

- Android CI : `android-debug-validation`, contrôle stable distinguant compilation effectuée, build périodique différé et cas non applicable ; le job de compilation réel est `test-and-build`.
- PC J2534 Bridge CI : `windows-debug-validation`, contrôle stable distinguant compilation effectuée, build périodique différé et cas non applicable.
- Un job de compilation `skipped`, en attente, annulé ou exécuté sur un ancien SHA ne constitue jamais une preuve de compilation réussie.
- `NOT APPLICABLE` signifie uniquement qu'aucun fichier Windows pertinent n'a changé ; ce statut ne signifie pas qu'une compilation a été exécutée.
- `BUILD DEFERRED` signifie que des changements Windows existent mais que le seuil périodique n'est pas atteint. Ce statut n'est pas une compilation.

Les contrôles stables à configurer dans la protection de branche sont `Android CI / android-debug-validation` et `PC J2534 Bridge CI / windows-debug-validation`, et non les jobs conditionnels de compilation.

## Contrôles Android

- Kotlin : `kotlin.allWarningsAsErrors=true`.
- Java : `-Werror` appliqué à toutes les tâches JavaCompile.
- Gradle : `org.gradle.warning.mode=fail` et `--warning-mode=fail` sur les tâches CI.
- Android Lint : toute entrée XML de sévérité Warning ou Error fait échouer le build.
- La compilation Android reste indépendante du filtrage Windows.

## Compteur périodique Android

La variable `CAMARODIAG_DRAFT_BUILD_INTERVAL` s'applique également au suivi Android, avec une valeur par défaut de 50.

- Les commits Android pertinents comprennent les modules applicatifs (`app`, `core`, `diagnostic`, `communication`, `control`, `mileageaudit`, `storage`, `third_party`), les configurations et wrappers Gradle, `version.properties` et le workflow `.github/workflows/android-ci.yml`.
- Les commits purement Windows, notamment `pc-bridge/**` et ses fichiers .NET, ne font pas progresser le compteur Android.
- Le compteur repart uniquement du SHA du dernier job `test-and-build` réellement réussi. Le succès du job d'évaluation ou du contrôle stable ne réinitialise jamais le compteur.
- Si le seuil n'est pas atteint en Draft, le contrôle indique `BUILD DEFERRED`; le contrôle ne prétend pas que les tests, lint ou APK ont été exécutés.
- Le passage en Ready for review déclenche immédiatement les tests unitaires, Android Lint et la génération de l'APK Debug lorsque des fichiers Android pertinents ont changé.
- Si aucun fichier Android pertinent n'a changé, le contrôle indique `NOT APPLICABLE` sans lancer le build Android.
## Contrôles Windows Debug

La détection compare la base de la PR à sa tête et considère comme pertinentes :

- Tout le répertoire `pc-bridge/**` : sources, projets, ressources, installateur et configuration.
- Les configurations .NET partagées : `global.json`, `NuGet.Config`, `nuget.config`, `Directory.Build.props`, `Directory.Build.targets`, `Directory.Packages.props`, fichiers de réponse MSBuild, solutions `.sln` / `.slnx`, fichiers `.props` / `.targets` et `.config/dotnet-tools.json`, y compris dans les sous-répertoires.
- Le workflow `.github/workflows/pc-bridge-ci.yml` lui-même. Toute modification de sa logique force une validation Windows Debug.

Une modification Android, Kotlin, Compose, DTC, Gradle ou des ressources Android ne déclenche pas à elle seule une compilation Windows.

Les builds Windows Debug utilisent :

- `dotnet build` avec `-warnaserror`.
- `dotnet publish` avec `-warnaserror`.
- Installateurs Inno Setup x64 et x86.
- Tout avertissement Inno Setup fait échouer le job.

## Compteur périodique Windows

La variable GitHub Actions `CAMARODIAG_DRAFT_BUILD_INTERVAL` définit le seuil. Sa valeur par défaut est 50 si elle est absente ou invalide.

- L'évaluation légère est exécutée à chaque événement de PR pris en charge.
- Seuls les commits qui modifient les chemins Windows ou .NET partagés ci-dessus alimentent le compteur Windows.
- Les commits purement Android ne font pas progresser ce compteur.
- Le point de départ est le dernier SHA ayant réellement réussi le job `build-windows-debug`. Le succès de l'évaluateur ou du contrôle stable ne réinitialise jamais le compteur.
- En l'absence de build Windows réussi antérieur, le compteur part de la base de la PR.
- Si le seuil n'est pas atteint en Draft, le contrôle indique `BUILD DEFERRED` ; le build reste dû et sera retenté à chaque commit pertinent jusqu'à une réussite réelle.
- Le passage en Ready for review déclenche immédiatement un build Windows si des fichiers Windows pertinents ont changé.
- Chaque nouveau commit en Ready déclenche une validation Windows immédiate si les changements cumulés de la PR concernent Windows.
- En l'absence de modification Windows pertinente, le contrôle indique `NOT APPLICABLE` et aucun runner Windows n'est démarré.

## Windows Release

Le workflow `.github/workflows/release-apk.yml` conserve le job Android Release indépendant.

Le job `bridge-installers` n'est exécuté sur `main` que si le push modifie les sources/projets/installateurs de `pc-bridge/**`, les configurations .NET partagées ou les workflows Windows concernés. Une modification Android seule ne déclenche pas les installateurs Windows.

Le job `cleanup-old-releases` accepte explicitement le résultat `skipped` de `bridge-installers` lorsque le build Windows n'était pas nécessaire. Il reste conditionné à la réussite de la Release Android et du contrôle de détection.

Les builds Windows Release x64/x86 utilisent `dotnet publish -warnaserror` et rejettent les avertissements Inno Setup.

## Protection GitHub à activer sur main

Dans Settings → Branches (ou Rulesets), créer/éditer une règle ciblant `main` et activer Require status checks to pass before merging.

Configurer le contrôle Windows stable :

- `Android CI / android-debug-validation`
- `PC J2534 Bridge CI / windows-debug-validation`

Conserver le contrôle Android correspondant à la validation Android réellement exigée par la politique du dépôt. Activer également Require branches to be up to date before merging.

La connexion GitHub utilisée par l'automatisation ne dispose pas du droit d'administration nécessaire pour modifier les règles de protection du dépôt. Le code CI peut produire les contrôles, mais leur caractère obligatoire côté bouton Merge dépend des paramètres du dépôt.
