# Guide utilisateur — Adaptateur ELM327 et diagnostic OBD-II

## Compatibilité

CamaroDiag prend en charge les adaptateurs ELM327 utilisant une liaison Bluetooth classique (Bluetooth SPP) pour le diagnostic OBD-II standard, sous réserve de la qualité réelle de l’adaptateur et de son firmware.

Les adaptateurs ELM327 génériques et les clones n’offrent pas tous les mêmes fonctions. Un boîtier qui s’annonce « ELM327 compatible » ne garantit pas une communication fiable.

La présence du Bluetooth LE (BLE) ne signifie pas que le boîtier est compatible avec la liaison Bluetooth classique utilisée par ce profil. Les adaptateurs BLE doivent être considérés comme non vérifiés tant que leur modèle précis n’est pas indiqué comme pris en charge.

## Première connexion

1. Stationner le véhicule dans un endroit sûr.
2. Brancher l’ELM327 sur la prise diagnostic OBD-II.
3. Mettre le contact conformément aux indications du véhicule et de l’adaptateur.
4. Sur Android, associer l’adaptateur dans les réglages Bluetooth lorsqu’un appairage est nécessaire.
5. Dans CamaroDiag, sélectionner l’adaptateur Bluetooth correspondant.
6. Lancer d’abord l’identification du véhicule, puis un diagnostic OBD-II standard.

CamaroDiag fonctionne sur Android 10 ou version ultérieure. Le nom affiché par l’adaptateur et la qualité de la liaison dépendent du fabricant.

## Informations et fonctions accessibles

Lorsque le calculateur et l’adaptateur répondent correctement, le diagnostic standard peut fournir :

- Le VIN et certaines informations d’identification du calculateur.
- Les paramètres moteur normalisés annoncés par le véhicule, par exemple régime, températures, pressions et données de sondes lorsqu’elles sont disponibles.
- Les codes défauts confirmés, en attente et permanents.
- Les informations d’état des systèmes antipollution (« readiness »).
- Les données figées associées à un défaut, lorsque le véhicule les fournit.
- Certains résultats de tests embarqués.

CamaroDiag interroge les paramètres annoncés par le calculateur. Si un paramètre n’est pas pris en charge ou si le calculateur ne le retourne pas, aucune valeur n’est inventée.

## Comprendre les résultats

- **Aucune donnée :** le calculateur ne fournit pas ce paramètre dans les conditions présentes, ou la communication n’a pas abouti.
- **Défaut présent :** lire le code et son contexte avant toute intervention. L’effacement d’un code ne répare pas la cause.
- **Valeur indisponible :** cela ne prouve pas une panne du capteur ou du calculateur.
- **Connexion instable :** vérifier l’alimentation de l’adaptateur, l’appairage Bluetooth, le contact et la qualité de l’ELM327.

Les valeurs affichées dépendent du calculateur, de l’année, du moteur, de la transmission, de la calibration et de l’interface utilisée.

## Simulation sans véhicule

Le mode de démonstration permet de découvrir certains écrans et résultats sans voiture ni adaptateur. Les données affichées dans ce mode sont artificielles et signalées comme telles.

Elles ne doivent jamais servir à diagnostiquer, régler ou réparer un véhicule.

## Limites de l’ELM327

Un ELM327 Bluetooth classique permet le diagnostic OBD-II standard lorsqu’il est correctement implémenté. Il ne fournit pas, à lui seul :

- Un accès garanti aux fonctions propriétaires GM ou à tous les calculateurs.
- Un diagnostic exhaustif du BCM, TCM, ABS/EBCM, IPC ou SDM.
- Une interface J2534 ou un accès de programmation.
- Une garantie de capture CAN brute complète ou d’émission sur tous les réseaux du véhicule.

Les fonctions UDS et les signaux spécifiques Camaro nécessitent une interface et un profil explicitement compatibles. L’utilisation d’un ELM327 ne débloque pas ces fonctions.

CamaroDiag est un outil indépendant et ne remplace pas les procédures de réparation constructeur. Ne pas lancer de diagnostic nécessitant une intervention sur les commandes du véhicule pendant la conduite.
