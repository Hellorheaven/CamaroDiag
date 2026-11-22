# Validation logicielle CamaroDiag

Les actions de développement sont consultables depuis [GitHub Actions](https://github.com/Hellorheaven/CamaroDiag/actions). Le dépôt unique couvre Android, Windows, le connecteur et les travaux embarqués. Les tests simulés ne qualifient pas le matériel automobile.

| Workflow | Rôle |
| --- | --- |
| Android CI | Tests, lint sans avertissement, APK Debug, recette API 29–36 ; lancement manuel possible. |
| PR unit tests | Régressions rapides Android et politiques Firebase/licences hors ligne. |
| PC J2534 Bridge CI | Compilation, autotests et installateurs Debug x64/x86. |
| Native ECU kernel tests | Noyau ECU natif et compilation croisée. |
| Connectors CI | Tests C++, compilation ESP32/STM32, client Android Java, hôte Python, Windows. |
| Release validation (no publication) | Sur main, vérifier les versions Release sans publier de version ni tag. |
| Release CamaroDiag | Publication signée APK et installateurs Windows pour changements du produit sur main ou lancement explicite. |
| Delete merged pull request branches | Nettoyer uniquement la branche d'une PR venant d'être fusionnée, sous contrôles. |

Les builds Debug périodiques sur PR brouillon sont configurables (50 commits pertinents par défaut) ; les changements critiques et le passage prêt à relire nécessitent une validation complète. Un checkpoint vert avec jobs ignorés n'est pas un build. Pour les validations complètes manuelles Android/Windows, sélectionner le workflow depuis main, puis fournir la référence ou le SHA à contrôler. Les changements matériel/documentaire seuls ne doivent pas déclencher toutes les compilations ni une publication de l'application.
