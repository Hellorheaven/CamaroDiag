# Guide de compatibilité — Chevrolet Camaro

Ce guide présente les Camaro et les adaptateurs pour lesquels CamaroDiag propose des fonctions de diagnostic. La prise en charge d’une voiture ou d’un adaptateur ne signifie pas que toutes les données et tous les calculateurs sont accessibles.

## Camaro prises en compte

| Génération | Années | Motorisation |
|---|---:|---|
| Gen 5 | 2010–2011 | 3.6 V6 LLT |
| Gen 5 | 2012–2015 | 3.6 V6 LFX |
| Gen 5 | 2010–2015 | 6.2 V8 LS3 |
| Gen 5 | 2010–2015 | 6.2 V8 L99 |
| Gen 5 | 2012–2015 | 6.2 V8 LSA compressé |
| Gen 5 | 2014–2015 | 7.0 V8 LS7 |
| Gen 6 | 2016–2023 | 2.0 turbo LTG |
| Gen 6 | 2016–2024 | 3.6 V6 LGX |
| Gen 6 | 2016–2024 | 6.2 V8 LT1 |
| Gen 6 | 2017–2024 | 6.2 V8 LT4 compressé |

Cette liste définit les configurations visées par l’application, pas une garantie de diagnostic approfondi pour chaque finition, marché ou calculateur. Les informations effectivement retournées par le véhicule restent déterminantes.

## Adaptateurs et connexions

| Adaptateur | Connexion | Utilisation prévue dans CamaroDiag | Limites importantes |
|---|---|---|---|
| ELM327 Bluetooth classique | Bluetooth SPP | Diagnostic OBD-II standard | Qualité très variable selon le fabricant et le firmware ; fonctions GM avancées non garanties |
| ELM327 USB | USB OTG | OBD-II standard selon l’interface | Android doit prendre en charge USB Host/OTG |
| ELM327 Wi-Fi | Wi-Fi | OBD-II standard selon l’interface | Dépend de l’adresse réseau et du port fournis par l’adaptateur |
| OBDLink MX+ authentique | Bluetooth classique | OBD-II et certaines lectures UDS/signaux étendus lorsque le profil du véhicule est compatible | L’accès dépend de la version exacte, du calculateur et des informations de diagnostic disponibles |
| OBDLink MX / LX | Bluetooth classique | OBD-II standard | Ne pas considérer ces modèles comme équivalents au MX+ pour les fonctions étendues |
| OBDLink EX / SX | USB | OBD-II standard selon le modèle | Connexion USB Host/OTG requise ; fonctions étendues selon le profil |
| OBDLink CX | Bluetooth LE (BLE) | Détection BLE possible | Le diagnostic Camaro/GM n’est pas activé avec ce modèle dans le profil actuel |
| VXDIAG VCX SE / VCX Nano | PC Windows et J2534 | Lectures UDS prises en charge via le pont PC CamaroDiag | Le pilote, la révision du boîtier et les fonctions réellement exposées par le fournisseur J2534 doivent être compatibles |

Les adaptateurs Bluetooth classiques et Bluetooth LE ne sont pas interchangeables. La présence d’une prise USB, d’un pilote ou de la mention « CAN » sur une fiche commerciale ne prouve pas que l’interface permet le diagnostic GM attendu.

## Fonctions de diagnostic

| Fonction | Disponibilité |
|---|---|
| Identification du véhicule | VIN et informations disponibles via le calculateur |
| Diagnostic OBD-II moteur | Lecture des paramètres standard pris en charge par le véhicule |
| Codes défauts | Lecture des codes confirmés, en attente et permanents lorsque le calculateur les fournit |
| Données figées | Lecture lorsque le véhicule les expose |
| État antipollution | Lecture des moniteurs disponibles |
| Tests embarqués | Lecture des résultats retournés par le calculateur |
| Signaux communautaires Camaro | Certains signaux peuvent être consultés avec une interface et une configuration compatibles |
| Données UDS | Lecture conditionnelle selon le calculateur, l’adaptateur et la configuration |
| Console et capture CAN | Fonctions dépendantes des capacités réelles de l’adaptateur et du réseau véhicule |
| Atelier de fichiers ECU | Analyse de fichiers locaux, sans écriture dans le calculateur |

Les fonctions sont proposées uniquement lorsque le véhicule et l’interface peuvent réellement fournir les informations nécessaires. Une donnée absente n’est pas remplacée par une valeur estimée.

## Essais individuels avec la console CAN / OBD

Dans l'onglet **Console CAN / OBD**, connectez d'abord une interface réelle compatible. Les requêtes standard en lecture seule (`010C` pour le régime, `0902` pour le VIN, `03` pour les défauts) utilisent le transport OBD actif. Avec un pont J2534 compatible, cette lecture peut rester disponible sans capture CAN passive.

Le PID `0152` peut afficher un taux d'éthanol **uniquement si le calculateur le fournit réellement**. Une absence de donnée ne doit pas être interprétée comme 0 % d'éthanol ni comme une preuve d'incompatibilité FlexFuel. Les historiques affichent séparément les requêtes envoyées, les réponses ECU et les messages de l'adaptateur.

L'envoi de trames CAN brutes requiert une interface dont la capacité physique a été vérifiée et une confirmation avant transmission. Les services de reprogrammation, d'effacement ou de sécurité ECU ne sont pas autorisés par la console de diagnostic. Les trames OEM arbitraires peuvent avoir des effets sur le véhicule et **ne sont pas certifiées sûres** par ce contrôle ; ne les utiliser que dans un cadre de diagnostic documenté.

Ces essais individuels sont possibles pendant le développement sous réserve des préconditions de sécurité ; ils ne remplacent pas la validation exhaustive sur véhicule prévue au tout dernier lot du projet.

## Calculateurs accessibles et limites

Le diagnostic OBD-II standard concerne principalement le groupe motopropulseur. Certaines lectures étendues peuvent être disponibles selon le véhicule et l’interface.

CamaroDiag ne garantit pas actuellement un scan exhaustif de tous les calculateurs BCM, TCM, ABS/EBCM, tableau de bord (IPC), airbag (SDM) ou des autres modules GM. Les commandes de carrosserie, le codage, les procédures de sécurité, la programmation et le flashage ne sont pas proposés comme fonctions validées.

Un moteur de scan UDS **en lecture seule** permet de préparer un inventaire ECM, TCM, BCM, IPC, EBCM/ABS et SDM. Chaque calculateur doit disposer d'un profil correspondant au VIN et à la calibration du véhicule, et son identité (famille, matériel, OS, calibration, CVN, VIN) doit être confirmée avant la lecture des défauts. En l'absence de ce profil ou de réponse valide, le calculateur est indiqué comme non vérifié, jamais comme sain. Une boîte manuelle n'est pas présentée comme équipée d'un TCM automatique. Ces composants logiciels ne signifient pas que le balayage physique de tous ces calculateurs est déjà qualifié sur une Camaro réelle.

## Précautions

- Stationner le véhicule dans un endroit sûr avant toute opération.
- Ne pas lancer de diagnostic nécessitant une intervention pendant la conduite.
- Ne pas effacer un défaut avant d’avoir conservé son code et les informations utiles à son analyse.
- Ne pas considérer une absence de réponse comme la preuve d’une panne.
- Vérifier la référence exacte de l’adaptateur et ses conditions de compatibilité.

CamaroDiag est un outil indépendant d’aide au diagnostic et n’est ni fabriqué ni certifié par General Motors.
