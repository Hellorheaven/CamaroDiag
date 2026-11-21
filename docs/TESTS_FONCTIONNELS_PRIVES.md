# Tests fonctionnels privés — Android et Windows

## Accès au laboratoire

L'onglet **Tests fonctionnels** sur Android n'apparaît qu'avec une session **Google** confirmée et le droit administrateur signé par Firebase (`owner_functional_tests`). La session doit être vérifiable en ligne : le mode hors ligne de l'application ne donne jamais accès à cet onglet. Le changement de compte, la déconnexion et l'annulation interrompent la campagne en cours.

Sur Windows, l'onglet **Tests fonctionnels privés** reste verrouillé jusqu'à la connexion à ce même compte Google dans le navigateur système. L'autorisation repose sur la signature du jeton Firebase, le projet, sa durée de validité et le droit signé. Elle expire au plus tard dix minutes après connexion ; aucune adresse Gmail n'est codée dans l'application.

## Comment activer le compte propriétaire (une seule fois)

L'administrateur Firebase procède **sur son poste d'administration**, jamais depuis l'application, sans mettre l'adresse ni les mots de passe dans les dépôts :

1. Installer `firebase-admin` dans un environnement Node.js privé ; préparer les identifiants d'un compte de service Firebase Admin **hors du dépôt** avec `GOOGLE_APPLICATION_CREDENTIALS`.
2. Définir les variables d'environnement privées `CAMARODIAG_OWNER_GOOGLE_EMAIL` (le Gmail à autoriser) et `CAMARODIAG_OWNER_HMAC_KEY` (clé aléatoire et secrète d'au moins 32 caractères).
3. Exécuter `node tools/admin/enroll-functional-test-owner.mjs fingerprint` pour calculer l'empreinte **HMAC-SHA256**. Conserver cette empreinte et la clé hors de GitHub.
4. Définir `CAMARODIAG_OWNER_EXPECTED_HMAC` avec cette empreinte et exécuter `node tools/admin/enroll-functional-test-owner.mjs grant`. Le script vérifie l'empreinte en temps constant, révoque cet accès aux autres comptes puis autorise l'unique compte Google cible. Aucun Gmail n'est affiché dans la confirmation.
5. Se déconnecter/reconnecter sur Android pour rafraîchir le jeton. Le simple hash public d'une adresse est **interdit** : il permettrait de retrouver l'adresse par essais.

**Windows uniquement :** configurer, dans l'environnement privé du poste, `CAMARODIAG_GOOGLE_DESKTOP_CLIENT_ID` (client OAuth Google de type *Desktop* autorisant la boucle locale), `CAMARODIAG_FIREBASE_WEB_API_KEY` et `CAMARODIAG_FIREBASE_PROJECT_ID`. Certaines configurations Google exigent aussi `CAMARODIAG_GOOGLE_DESKTOP_CLIENT_SECRET` (identifiant OAuth public d'application native, pas un secret d'authentification propriétaire). Ces valeurs ne contiennent pas l'adresse du propriétaire. Google Sign-In et le fournisseur Google de Firebase doivent être configurés dans le même projet. Sans configuration, l'onglet reste **verrouillé**.

## Lancement d'une recette

Sur **Android**, connecter un adaptateur réel depuis l'écran Interface puis ouvrir **Plus → Tests fonctionnels → Tout lancer**. L'application vérifie à nouveau la session propriétaire, présente les cinq familles de connexion (Bluetooth classique/ELM, BLE/OBDLink, USB OTG, Wi-Fi et pont Windows J2534), tous les calculateurs candidats de la topologie et les services OBD accessibles. Elle teste le **seul canal connecté** : lecture VIN, découverte PID, défauts en lecture seule et moniteurs antipollution (ou identification UDS sur J2534). Un autre adaptateur exige une connexion distincte ; il n'est jamais déclaré testé par simple présence dans la liste.

Sur **Windows**, ouvrir l'onglet privé, cliquer sur **Se connecter avec Google**, puis **Tout lancer**. Les pilotes J2534 détectés sont sondés réellement. La double lecture du VIN/OSID ECU nécessite une sélection d'interface, les identifiants CAN connus et la coche confirmant que le profil concerne bien l'ECM. Les autres calculateurs et interfaces sans profil matériel vérifié restent **NON TESTÉS**. Le pont Android et la console locale doivent être fermés pendant la campagne.

Les résultats sont **OK**, **ÉCHEC** ou **NON TESTÉ** avec détail par test et export JSON. Les tests de sécurité logiciels, lorsqu'ils sont utilisés, sont clairement distingués des lectures physiques. Un résultat OK sur l'interface ne valide ni la communication avec tous les calculateurs ni la capacité de programmation.

## Périmètre et précautions

La campagne globale **n'effectue aucune écriture ECU, aucun effacement DTC, aucun SecurityAccess ni aucune action sur les actionneurs**. Elle ne certifie pas de pilote de flash, de map mémoire, de checksum constructeur ou de récupération. Tester chaque calculateur exige ses adresses/son protocole vérifiés pour le VIN et son équipement réel ; les profils absents ne sont pas inventés. Les essais physiques exigent le véhicule, le matériel OBD, l'alimentation et les pilotes. Les résultats de CI logiciels ne constituent pas une validation matérielle.

Le compte administrateur Firebase et les clés privées doivent rester protégés. Un binaire modifié localement ne peut pas être considéré comme une frontière de sécurité absolue contre son propre utilisateur.

## Télécharger les versions Debug (issue #76)

- **Android** : après authentification Google et attribution du droit Firebase signé, ouvrir **Plus → Versions Debug**. L'écran consulte les **métadonnées publiques** des artefacts de la PR #70 via l'API GitHub, présente les derniers ZIP Android et Windows disponibles avec leurs SHA et dates d'expiration, puis ouvre la page GitHub de téléchargement dans le navigateur.
- **Windows** : onglet **Téléchargements Debug**, connexion Google du propriétaire puis **Actualiser** ; les boutons Android et Windows ouvrent les mêmes archives GitHub. Le téléchargement Windows comprend les installateurs Debug **x64 et x86** dans un même ZIP.
- Les artefacts GitHub Actions expirent généralement après **30 jours** dans ce dépôt. Les liens ne sont visibles que pour la branche `feat/ecu-backup-integrity` et la PR #70, à condition que le SHA du nom d'archive soit celui du workflow, et que le fichier soit encore disponible.
- **Important :** GitHub exige une connexion à GitHub pour télécharger les archives Actions, distincte de l'identité Google/Firebase utilisée pour ouvrir l'onglet. Aucun jeton GitHub n'est stocké dans CamaroDiag. Après téléchargement, extraire le ZIP pour obtenir l'APK ou les EXE d'installation.
- **Limite de confidentialité :** le claim Firebase protège l'accès aux **onglets** dans le client officiel ; les artefacts d'un dépôt GitHub public ne deviennent pas privés du seul fait que le bouton est masqué. Pour des fichiers Debug confidentiels accessibles uniquement au propriétaire, utiliser à l'avenir un stockage privé et un service de distribution validant le jeton Firebase sur chaque requête, sans lien public.
- **Limite de validation :** les fichiers peuvent exister même si le contrôle global CI a échoué. Un artefact publié n'implique pas une build validée, un calculateur testé, ou une application signée de manière interchangeable avec la version Release.

Pour définir l'identité propriétaire, l'administrateur renseigne **en privé** le Gmail transmis par le propriétaire dans `CAMARODIAG_OWNER_GOOGLE_EMAIL`, exécute la procédure ci-dessus et vérifie l'apparition des deux onglets. Ne jamais inscrire le Gmail en clair dans le code ni dans un secret de l'APK.
