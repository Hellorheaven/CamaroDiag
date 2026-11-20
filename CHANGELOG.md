# Changelog — CamaroDiag

Toutes les dates sont en UTC sauf indication contraire. Ce journal conserve les décisions et changements confirmés. Il ne remplace pas l'état GitHub en temps réel : consulter les PR, commits et workflows pour connaître la situation actuelle.

## 2026-11-20

## 2026-11-20

### Application — À propos et mises à jour
- Ajout d'une rubrique « À propos » dans les réglages avec affichage du nom, de la version installée et du numéro de build Android.
- Ajout d'une vérification manuelle des releases GitHub, avec distinction entre les canaux Debug et Release.
- Téléchargement de l'APK correspondant au canal installé, puis proposition d'installation Android.
- Vérification du nom de package et de la signature APK avant d'autoriser l'installation ; aucun APK de canal différent n'est proposé.
- La vérification n'est plus déclenchée automatiquement à l'ouverture de l'application : elle est lancée depuis la rubrique « À propos ».
- Traductions anglaise et française ajoutées ; les autres langues utilisent les ressources anglaises par défaut.
- Version Android actuelle issue de `version.properties` : 2.0.0 (versionCode 17). Aucun numéro de version n'a été incrémenté pour cette modification.

# 2026-11-19

### Documentation de continuité
- Création de `docs/PROMPT_REPRISE.md` comme point d'entrée pour reprendre le projet dans un nouveau chat sans perdre les décisions, contraintes, contexte matériel, périmètres de PR et procédure de reprise.
- Création de ce `CHANGELOG.md` pour tracer les évolutions importantes et leurs preuves GitHub.
- Règle : mettre à jour les deux fichiers après toute décision durable ou opération GitHub confirmée ; ne pas enregistrer de suppositions comme des faits.

### PR #35 — Sniffer / Console CAN
- Périmètre confirmé : un seul module combinant capture entrante, analyse/décodage et console ; émission possible lorsque l'adaptateur, son firmware, le bus et le protocole la supportent réellement.
- La PR #35 reste limitée à ce module. Télémétrie, garage, calibration, injecteurs, déconnexion globale, matrice complète des interfaces, multi-marque, langues et tablettes sont exclus de cette PR.
- Clarification publiée dans la conversation de la PR #35 (commentaire ID `6999604027`).
- PR : https://github.com/Hellorheaven/CamaroDiag/pull/35
- Ne pas fusionner sans autorisation utilisateur et sans validations réelles.

### Backlog fonctionnel
- Réorganisation du backlog en lots fonctionnels indépendants dans `docs/FUTURE_FEATURES_CAN_VEHICLE_GARAGE.md`.
- Lots documentés : adaptateurs/capacités ; garage/profils ; déconnexions ; télémétrie multi-ECU ; maintenance/injecteurs ; atelier calibration ; plateformes multi-marques ; cahier de recette Excel ; langues ; adaptation tablette.
- Langues demandées : 24 langues officielles de l'UE, plus hébreu, russe, chinois simplifié, japonais, coréen et arabe ; choix manuel indépendant du téléphone et option « Suivre la langue du téléphone ».
- Tablettes : profils de recette explicites 8", 10" et 12", chacun testé portrait/paysage. Les pouces définissent des appareils de référence, pas des seuils de layout ; l'interface doit s'adapter en dp et à la largeur de fenêtre.
- Commit historique de réorganisation : `1b6769068a596a60ec60bcc79259e2acdca0eeaf`.
- Commit historique langues/tablettes : `ffdc24ce869fc8027d45cd947344ed741410ec38`.
- Commit profils tablette 8/10/12 : `e26c0d91971abdab764657e66ba49ddd569ff559`.
- Backlog : https://github.com/Hellorheaven/CamaroDiag/blob/main/docs/FUTURE_FEATURES_CAN_VEHICLE_GARAGE.md

### État CI historique — à ne pas interpréter comme actuel
- Sur un ancien head de PR #35 (`ffc95eebced0413c9b25c7f143c82247a3b81afa`), les workflows Android et Windows affichaient un état global réussi, mais les jobs de compilation/package étaient ignorés (`skipped`).
- Conclusion : ces exécutions ne prouvaient pas que les APK ni les packages Windows avaient été compilés. Vérifier les runs les plus récents avant toute conclusion.
