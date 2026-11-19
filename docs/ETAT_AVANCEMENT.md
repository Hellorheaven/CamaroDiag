# Tableau de bord — CamaroDiag

Dernière vérification : 2026-11-19 07:22 (Europe/Paris)  
Dépôt : https://github.com/Hellorheaven/CamaroDiag  
PR suivie : [#35 — CAN Sniffer / Console](https://github.com/Hellorheaven/CamaroDiag/pull/35)  
Branche : `feat/live-can-monitor`

> Ce document est un instantané manuel mis à jour lors des contrôles GitHub. Il ne se rafraîchit pas automatiquement en arrière-plan.

## État de la PR #35

- Statut : ouverte, Draft, non fusionnée.
- Commits : 118.
- Fichiers modifiés : 26.
- Ajouts / suppressions : +1 565 / -146.
- Branche : 118 commits en avance et 16 en retard sur `main` selon la comparaison effectuée le 19/11.
- Dernier SHA de référence contrôlé : `6db6797087e7d956fc0f838088101a4ef5f90b2c`.

## Validation CI sur ce SHA

| Contrôle | Résultat |
|---|---|
| Android — tests unitaires | Réussis |
| Android — Lint | Échec |
| Android — APK Debug | Non généré (étape ignorée après échec Lint) |
| Windows — workflow | Terminé avec succès |
| Windows — compilation Debug | Ignorée, donc non validée |

### Blocage Android identifié

Le job Android CI #830 échoue sur `MissingTranslation` pour la ressource `connection_lost_bluetooth` dans 12 répertoires de langue : de, ru, ko, pt, el, it, es, iw, zh, ar, ja et nl.

La règle produit souhaitée est : anglais et français obligatoires pour les nouvelles fonctionnalités ; les autres traductions seront faites dans le lot d'internationalisation. Il faut corriger la configuration/ressource Android de façon cohérente avec cette règle, puis relancer CI. Ne pas déclarer le correctif validé avant une exécution verte.

## Avancement fonctionnel estimé

Ces pourcentages sont des estimations de périmètre, pas des mesures automatiques ni des taux de tests réussis.

- PR #35 — CAN Sniffer / Console : **60 %** (estimation prudente, à réévaluer après les prochains changements).
- Projet CamaroDiag complet : **25 %** (estimation globale, lots futurs inclus).

## Prochaines priorités

1. Corriger la détection Lint de la ressource anglaise/française sans ajouter de traductions provisoires dans les autres langues.
2. Relancer Android CI et obtenir un APK Debug.
3. Examiner les 16 commits de retard de la branche sur `main` et anticiper les conflits avant toute fusion.
4. Poursuivre le périmètre CAN : capacités réelles des adaptateurs, console et transmission seulement quand le matériel le permet.
5. Garder la distinction entre implémenté, compilé, testé automatiquement et validé sur véhicule.

## Historique

- 2026-11-19 07:22 : contrôle PR #35 et CI Android #830 / Windows #518. Tests unitaires Android réussis ; Lint échoue sur MissingTranslation ; APK non produit ; compilation Windows ignorée.
- 2026-11-19 : règle de travail internationalisation confirmée : anglais/français pour les nouvelles fonctions, traductions complètes des autres langues dans un lot global dédié.

## Règles de mise à jour

À chaque contrôle demandé ou étape de développement significative, mettre à jour la date, le SHA, les compteurs PR, les résultats CI, les blocages, les pourcentages estimés et les prochaines priorités. Ne jamais présenter un contrôle ignoré comme réussi ni un test logiciel comme une validation sur véhicule.
