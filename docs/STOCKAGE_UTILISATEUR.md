# Stockage utilisateur CamaroDiag

Android utilise le dossier public `/storage/emulated/0/CamaroDiag/` autorisé par l'utilisateur. Les exports sont classés automatiquement dans les sous-dossiers suivants, créés à la demande.

| Sous-dossier | Contenu |
|---|---|
| Backups/ECU | Captures ECU et paires de dumps |
| Backups/Diagnostics | Sauvegardes des sessions de diagnostic |
| Calibrations | Fichiers et modèles de calibration |
| Reports | Rapports (dont FlexFuel) |
| Logs/Console | Traces de communication |
| Logs/Recovery | Journaux CDJ/1 de récupération |
| Telemetry | Enregistrements temps réel |
| Tests | Rapports de tests fonctionnels |
| Exports | Autres exports |

Les fichiers existants restent en place. Les nouveaux fichiers ne les écrasent pas. Les écritures temporaires et les vérifications SHA-256 sont conservées dans chaque dossier. En cas d'erreur de création du dossier, l'export échoue sans revenir silencieusement à la racine.

Objectif de parité Windows : adopter la même classification sous le dossier CamaroDiag choisi par l'utilisateur ; les chemins libres existants ne sont pas modifiés par ce lot. Les données internes, les clés et les fichiers de travail sensibles restent dans le stockage privé approprié.
