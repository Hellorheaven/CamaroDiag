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
