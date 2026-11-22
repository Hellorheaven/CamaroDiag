# Déploiement Firebase — CamaroDiag

Ce guide est spécifique au projet Firebase **`camarodiag-e1ed1`**.
Il ne crée pas de nouvelle base Firestore ni de nouveau compte de facturation.

## État actuel

- Forfait Blaze confirmé dans la console par le propriétaire.
- Firestore `(default)` existe, sans collections : situation normale.
- Authentication et les claims propriétaires sont configurés.
- App Check : les deux applications Android sont enregistrées ; Firestore et
  Authentication affichent **Non appliqué**.
- Firebase Functions : le code n'est **pas** déployé par une simple PR GitHub.

## Contrôles avant déploiement

1. Vérifier la CI complète de la PR #95 (tests Android, lint, Windows, connecteurs et
   tests Node du backend) : pas de fusion ou publication APK sur une CI rouge.
2. Garder `CAMARODIAG_AUTO_GRANT_LICENSES` activé tant que l'utilisateur le demande.
   La valeur par défaut du code vaut `true` ; ne jamais appeler cette attribution
   une transaction d'achat.
3. Garder `CAMARODIAG_ENFORCE_APP_CHECK` à `false` tant que les APK Debug et
   Release ne sont pas testés avec leurs vrais certificats et jetons.
4. Ne pas déployer le codebase **`billing`** : Stripe est désactivé.
5. L'accès à Cloud Functions est susceptible de générer des coûts Google Cloud,
   même sans licence vendue. Un plafond de dépenses peut interrompre le contrôle
   des licences et n'est pas une garantie d'absence de facture.

## Déploiement ciblé (Google Cloud Shell, compte autorisé)

Lancer seulement après validation de la CI, avec une session Google ayant accès
au projet. Depuis le dépôt GitHub (branche validée) :

```bash
git clone --branch chore/unified-ci https://github.com/Hellorheaven/CamaroDiag.git
cd CamaroDiag
node --test functions-accounts/entitlement-policy.test.js
npx firebase-tools login --no-localhost
npx firebase-tools deploy --project camarodiag-e1ed1 --only "firestore:rules,functions:accounts"
```

La connexion `firebase-tools login` n'est nécessaire que si la session CLI
n'est pas déjà authentifiée. Ne jamais coller de clé de service ni de jeton
App Check dans GitHub, la conversation ou une documentation publique.

Le déploiement ciblé crée ou met à jour uniquement les fonctions :
- `getMyLicenseStatus`
- `adminGetAccount`
- `adminUpdateAccount`

Les fonctions utilisent `europe-west1`. Les règles `firestore.rules`
interdisent toutes les lectures/écritures **directes** par le client ;
le SDK Admin des fonctions reste autorisé.

## Validation fonctionnelle sans écriture de données diagnostic

1. Se connecter avec un utilisateur Firebase ayant une adresse e-mail vérifiée.
2. Au premier lancement, `getMyLicenseStatus` doit répondre
   `{licensed:true, source:"promotion", verified:true}`.
3. Firestore doit alors contenir **uniquement** `licenses/{uid}` pour ce
   parcours (aucun véhicule, VIN, code DTC, trace CAN, rapport, ECU, export).
4. Avec les comptes propriétaires déjà dotés du claim
   `owner_functional_tests`, vérifier l'accès à l'administration et
   aux fonctionnalités réservées.
5. Depuis l'administration, attribuer une licence, la révoquer, vérifier que
   la révocation prend le pas sur la promotion à la connexion suivante ;
   tester la désactivation/réactivation et le refus de modification d'un propriétaire.
6. Vérifier que le panneau « Mon compte » utilise le statut mis en mémoire
   et **n'entraîne pas de nouvelle consultation des licences**.
7. Vérifier qu'un utilisateur sans droit ne peut pas accéder aux opérations
   Premium par un chemin indirect, et qu'en cas d'échec réseau seuls
   les écrans gratuits restent accessibles.
8. Vérifier les APK Debug et Release signés, les jetons App Check, et les
   contrôles de sécurité des écritures ECU séparément.

## Bascule future, uniquement à la demande du propriétaire

- Pour arrêter d'offrir des licences aux nouveaux utilisateurs :
  définir `CAMARODIAG_AUTO_GRANT_LICENSES=false` dans la configuration d'environnement
  du **codebase accounts**, puis redéployer. Les licences déjà accordées
  ne sont pas retirées automatiquement.
- Pour imposer App Check après validation des deux APK :
  définir `CAMARODIAG_ENFORCE_APP_CHECK=true` puis redéployer les trois fonctions,
  et vérifier séparément l'état des services dans Firebase Console.
- Pour ouvrir les paiements : nécessite un autre lot explicitement autorisé.
  Aucun code Stripe n'est publié par cette procédure.

## Déploiement impossible depuis un simple commit

Les commits GitHub et la création des collections Firestore sont deux événements
distincts. Si la commande de déploiement n'a pas été exécutée avec succès dans
un environnement authentifié, **le service n'est pas en production**.
