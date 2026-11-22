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

Windows : les dialogues de sauvegarde de l'interface utilisent par défaut `Documents/CamaroDiag/` et les mêmes catégories, avec `Profiles` pour les profils mémoire. Ils proposent un nom unique ; un emplacement différent peut être choisi explicitement. Les chemins CLI explicites et anciens fichiers restent inchangés ; aucune migration automatique. Un dossier invalide ou redirigé est refusé, sans repli silencieux. Les données internes, les clés et les fichiers de travail sensibles restent dans le stockage privé approprié.

Les APK de mise à jour Android sont gérés séparément par DownloadManager et vérifiés avant installation ; le dossier de téléchargements appartient au mécanisme système. Les clés, journaux internes et fichiers temporaires d'installation privés ne sont pas des exports publics.

Android vérifie également que le fournisseur de documents renvoie l'identité exacte du fichier temporaire et du fichier publié dans la bonne catégorie. Le résultat affiché après sauvegarde contient le chemin complet et le nom unique ; aucun retour silencieux à la racine.
