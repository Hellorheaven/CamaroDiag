# CamaroDiag — Identité et licence seulement dans Firebase

## Règle de minimisation des données (#104)

CamaroDiag n'envoie **aucune donnée diagnostic ou véhicule** vers Firebase/Firestore :
aucun PID, DTC, VIN, trame CAN, trace, kilométrage, rapport de scan, sauvegarde ECU,
calibration, cartographie, export utilisateur ni contenu de console.

- **Firebase Authentication** : comptes, UID, e-mail, vérification, état actif/désactivé, fournisseurs d'identité. Les droits `owner_functional_tests` et `admin_accounts` sont des Custom Claims signés.
- **Firestore** : la seule collection du parcours d'accès est `licenses/{uid}`. Elle contient les champs nécessaires au droit de licence : UID, adresse e-mail normalisée pour l'association, état, origine (promotion, achat, attribution administrative), dates et éventuel choix manuel. Ce ne sont pas des données OBD.
- **Local** : toutes les données de diagnostic, véhicules, scans, journaux CAN et exports restent sur le téléphone ou PC / le répertoire choisi par l'utilisateur.
- **Sécurité** : Firestore interdit l'accès client direct ; les lectures/attributions/modifications des licences passent par des Cloud Functions Firebase qui vérifient l'identité et les droits.

Le projet n'utilise plus les collections `manualLicenseOverrides`, `promotionalLicenses`,
`licenseSettings` et `accountAdminAudit` dans les nouveaux parcours d'administration/licence.
Les changements manuels enregistrent uniquement les propriétés `manualOverride`,
`manualChangedAt`, et `manualChangedBy` **dans** `licenses/{uid}`. Aucun journal d'administration séparé n'est enregistré dans Firestore.

## Politique de licence demandée

- **Gratuit** : diagnostic temps réel ; lecture et effacement DTC. Configuration
  technique, connexion, véhicule, compte, aide, mises à jour restent accessibles.
- **Avec licence** : modules ECU, console CAN, scan complet, analyse kilométrique,
  sauvegardes et cartographies, commandes/pilotage et exports avancés.
- **Sans licence** : fonctions avancées visibles, grisées, avec explication au clic.
- **Propriétaires** : les deux comptes dotés du claim `owner_functional_tests`
  conservent l'accès total, y compris outils Debug privés.

Les utilisateurs vérifiés reçoivent temporairement une licence **promotionnelle gratuite**
lors du premier contrôle serveur, sans fausse preuve de paiement. Une licence déjà
révoquée par l'administration ne sera pas réattribuée automatiquement.
L'attribution automatique est contrôlée par la configuration **serveur**
`CAMARODIAG_AUTO_GRANT_LICENSES` (valeur `false` pour la désactiver).
La désactivation n'enlève pas rétroactivement les licences déjà accordées ;
cela demande une opération de révocation distincte. Aucune configuration globale
de licence n'est enregistrée dans Firestore.

## Préconditions de mise en service

Le code en PR ne signifie pas déploiement. L'accès aux Cloud Functions exige un
déploiement explicite, App Check correctement configuré et les tests Android/serveur.
Le forfait Blaze Firebase peut être nécessaire et entraîner des frais ; demander
l'accord avant toute activation de facturation ou tout déploiement.

La **vente de licences Stripe reste désactivée**. Le code historique de préparation
commerciale contient encore des registres de transactions de paiement (sessions,
paiements et remboursements) séparés : ces écritures ne sont pas utilisées tant
que la vente reste désactivée. Elles devront être refactorisées ou approuvées
explicitement avant toute future activation commerciale pour respecter strictement
la règle « identités et licences seulement ».

La vérification des licences s'effectue côté serveur. Les données diagnostic ne
seront jamais mises en base cloud pour ce contrôle. L'activation définitive des
restrictions Premium exige une validation des protections d'opérations sensibles
(y compris Windows/ECU), au-delà du simple grisement d'un menu.


## Fréquence des vérifications (#105)

L'application n'interroge pas les licences à intervalles réguliers.
Au **démarrage**, CamaroDiag valide la session Firebase et effectue **une
consultation explicite** du statut de licence côté serveur lorsque le
contrôle commercial est activé. Le résultat, y compris l'origine de la licence,
est conservé uniquement en mémoire pour cette session. L'ouverture de
« Mon compte » affiche ce résultat sans nouvel appel réseau.

Les seules exceptions applicatives : changement de compte, actualisation
explicitement demandée ou action administrative (recherche, modification,
révocation), qui doit contacter le serveur. Un redémarrage complet de
l'Activity Android peut recréer la session visuelle. Le SDK Firebase
Authentication peut lui-même rafraîchir ses jetons pour maintenir
l'authentification : **cela ne garantit pas une seule requête réseau totale**.

Compromis : la révocation côté serveur d'une licence ou d'un compte en cours
d'utilisation ne se reflète normalement dans l'interface qu'au démarrage
suivant, au changement de compte ou lors d'une actualisation explicite. Les
opérations à risque (écriture ECU, commandes véhicule) doivent conserver
leurs contrôles de sécurité propres, indépendamment de ce cache de présentation.

Tant que `LicenseRolloutConfig.ENABLED=false`, les vérifications de licence
au démarrage restent désactivées : ne pas activer le verrou sans déployer
et tester le service serveur et la gestion Firebase App Check.
