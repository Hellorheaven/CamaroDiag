# Licences CamaroDiag — implémentation en attente, non activée

## Situation actuelle

La base technique des licences est conservée dans la branche de la PR #70, mais la commercialisation et les restrictions sont **inactives** à la demande du propriétaire.

- **Application Android :** `LicenseRolloutConfig.ENABLED = false` dans `app/src/main/java/com/camarodiag/gen5/auth/LicenseRolloutConfig.kt`. Aucun onglet « Licence » n'est affiché. Toutes les fonctionnalités ordinaires restent accessibles aux utilisateurs authentifiés, sans paiement ni vérification Firestore.
- **Fonctions Firebase :** `CAMARODIAG_LICENSES_ENABLED` est absent par défaut ; les endpoints d'achat et de notification Stripe refusent toute utilisation s'il n'est pas exactement égal à `true`. **Aucune fonction, règle ou collection de paiement n'est déployée par ces commits.** Ne pas lancer le déploiement sur Firebase pour activer la vente.
- **Aucun service payant n'est activé** par le code GitHub ; le compte de facturation, le compte Stripe, les tarifs et les secrets de paiement restent à configurer ultérieurement.
- **Outils réservés :** les onglets « Tests fonctionnels privés » et « Versions Debug » restent strictement séparés de la future licence et requièrent le droit signé Firebase `owner_functional_tests`. Les deux comptes Google propriétaires sont fournis en privé lors du provisionnement administrateur ; leurs adresses ne figurent pas dans les sources.

## Architecture prête pour une activation future

Le modèle prévoit trois états : **gratuit** (temps réel + lecture/effacement DTC), **premium** (toutes les fonctions ordinaires), **propriétaire** (y compris outils Debug). Une licence sera liée au `uid` Firebase et à son adresse e-mail vérifiée.

L'activation commerciale exigera, **dans une version future et après validation explicite** :

1. Décider du prix, des conditions commerciales et de la méthode de distribution Android (Play Store ou distribution directe, avec examen des obligations de facturation de la plateforme).
2. Configurer Firebase Authentication, Firestore, les règles de sécurité, Firebase Functions, Stripe Checkout, les secrets et le webhook signé ; tester dans un environnement de test isolé.
3. Valider les scénarios de paiement, double notification, échec, annulation, remboursement, contestation, changement de compte, révocation et fonctionnement hors ligne.
4. Autoriser délibérément les deux interrupteurs **indépendants** : `LicenseRolloutConfig.ENABLED` côté Android et `CAMARODIAG_LICENSES_ENABLED=true` côté serveur. Ne pas activer l'un sans l'autre.
5. Publier une version validée après compilation Android, tests et revue des contrôles des opérations premium. Le pont Windows devra également être couvert par une vérification robuste avant qu'une restriction commerciale puisse lui être appliquée.

**Attention :** les classes de licence et le code de paiement sont une préparation, pas un service de vente opérationnel. Il ne faut ni annoncer un achat possible, ni créer un lien de paiement actif tant que le déploiement et les tests ne sont pas terminés.


## Statut de licence informatif et administration (PR #95, issue #101)

Le statut **Licence : Oui / Non** est distinct du contrôle d'accès. Pendant le lancement, `LicenseRolloutConfig.ENABLED=false` reste inchangé : tous les utilisateurs authentifiés conservent toutes les fonctions ordinaires même si le statut de licence est **Non**. Les outils privés Debug et tests fonctionnels restent protégés par `owner_functional_tests`.

L'écran « Mon compte » interroge `getMyLicenseStatus` (Callable Firebase, région `europe-west1`). En cas d'indisponibilité du serveur, il affiche « Statut indisponible » et **n'invente pas une licence négative**. La source du statut peut être « achat » (enregistrement valide de `licenses/{uid}`) ou « attribution administrative ».

Les deux comptes propriétaires possédant la revendication signée `owner_functional_tests` accèdent à « Administration des comptes ». Ils peuvent chercher un UID par adresse e-mail et positionner la licence à **Oui** ou **Non** à l'aide de `adminGetAccount` et `adminUpdateAccount` avec `action=license`. Les opérations administratives sont vérifiées côté serveur : connexion Firebase, e-mail vérifié, compte non désactivé, Google lié, revendication signée, et session récemment authentifiée (5 minutes). Le backend interdit toute modification d'un autre compte propriétaire ainsi que l'auto-modification.

Les décisions manuelles sont stockées dans `manualLicenseOverrides/{uid}`, **séparément** du registre d'achat Stripe. La valeur manuelle Oui/Non prévaut pour l'affichage et n'efface jamais l'historique de paiement. Toute modification est tracée dans `accountAdminAudit`. Ces collections ne sont accessibles qu'aux Cloud Functions avec Admin SDK : les règles Firestore refusent les accès directs des clients.

**Préconditions avant utilisation réelle** : déployer explicitement Cloud Functions + Firestore avec approbation de la facturation Blaze si nécessaire, configurer Firebase App Check pour les appels protégés, valider les tests de droits et de compilation Android, puis publier une application actualisée. Les commits seuls n'activent pas le service. **Ne pas activer le paywall** à ce stade : le futur moteur d'autorisation premium devra intégrer les décisions manuelles, l'état des paiements et les remboursements dans une politique unifiée et testée avant activation.
