# Remplacement des composants Python — CamaroDiag

Directive utilisateur : **supprimer tout Python du projet livré**, sans réécrire les composants natifs existants pour changer de langage. Le C++ ESP32 est conservé. Les scripts Python restent transitoirement en dépôt tant que le remplacement natif de leurs capacités n'est pas complet et validé : ne pas provoquer de régression par suppression prématurée.

## Cibles techniques

| Plateforme | Implémentation native à privilégier |
|---|---|
| Android | Kotlin exclusivement, code métier et UI Compose existants ; migrer les sources Java internes |
| Windows | C# exclusivement (.NET), pont J2534 et interface existants |
| Linux ARM/x64 | **C++20 exclusivement** pour le développement natif, avec migration progressive des anciens fichiers C, paquet .deb sans interpréteur |
| Firmware ESP32-S3 | **C++ existant** (ESP-IDF/Arduino/PlatformIO) ; aucun nouveau moteur de protocole en C |
| STM32G4 | C/C++ de son firmware et de ses pilotes, selon les modules existants |

## SAE J1979 : migration en cours dans la PR #117

Les implémentations Python comprennent des commandes et bibliothèques pour : découverte et inventaire CAN, DTC/VIN et Mode 01/02, trames figées et rapports CF/1, capture CL/1 CU/3 et Mode 01 live, anciennes liaisons J1850/K-Line, catalogues et échelles, surveillance, effacement Mode 04, EVAP Mode 08, opérations mutatrices distinctes, maintenance/journaux et preuves. Le pont Linux en migration **C++20** couvre **une partie seulement** des lectures CAN, du rejeu CF/1 et CL/1 et des captures CU/2 et CU/3. La reprise Android Kotlin et Windows C# est également partielle. Ne pas annoncer la parité globale avant comparaison fonctionnalité par fonctionnalité et validation finale.

Les modules Python hors SAE J1979 (DoIP, mémoire, découverte générique, récupération, transport sécurisé et journal de programmation) ne seront pas arbitrairement mélangés dans le chantier J1979 : prévoir leur migration native dans les chantiers associés **après** clôture du protocole actif, sans effacer leurs contrats encore nécessaires.

## Conditions de suppression définitive du Python

1. Pour chaque commande Python et chacune de ses fonctions métier, identifier au moins un appelant natif équivalent dans le produit réellement distribué ; vérifier arguments, portée ECU, réponses et modes d'échec.
2. Raccorder les tests et recettes au code natif, conserver l'intégrité des preuves et rapports, maintenir les services autorisés et refuser les fonctions non qualifiées sans inventer de prise en charge.
3. Supprimer les routes encore câblées vers Python, les entrées `pyproject.toml`, dépendances `pyserial`, invocations CI, scripts et fichiers `.py` correspondants, uniquement après parité attestée.
4. Exécuter **le gate unique de fin de protocole** après développement complet, corriger ses échecs, puis générer l'APK Release signée et vérifiée suivant le processus v2.0.46. Ne pas annoncer d'APK compilée ou de protocole terminé avant la preuve.

## Correction de portée effectuée

Les nouveaux fichiers ISO-TP `connectors/firmware/shared/j1979_isotp_native_c.{c,h}` et l'inclusion provisoire `connectors/firmware/esp32s3/src/j1979_native_c.c` ne faisaient que dupliquer un moteur embarqué C++ existant. Ils sont retirés. Le pont Linux a migré ses sources `main` et `cf1_usb` et les sept tests C vers C++20 (SHA `170d8af5836146358c5c455a3e6a13b40d42268d`, non compilé à ce stade) ; le firmware ESP32 conserve les classes C++ existantes. Aucun développement Python nouveau n'est autorisé.

## Lot 27/11/2026 — Android / Linux

SHA code : `2f466499f6e0b3a2e9b506b4dd5675cc7291f77b`. Authentification et transports CU/1–CU/5, OTG et rejeu CL/1 portés en Kotlin ; le pont Linux C++20 couvre désormais une partie des échelles ECU et des moniteurs/corrections carburant Python, avec tests non exécutés. Les 128 fichiers Python recensés restent présents : parité du rejeu, CLI, rapports et fonctionnalités non achevée. Il reste également des sources et tests Java Android et les 19 fichiers C du noyau MPC5566. Aucun de ces composants ne doit être supprimé avant remplacement effectif et validation. Aucun gate ou APK dans ce lot.


## Reprise PR117 — lot natif CJ/1 et CM/1 (27/11/2026)

- Base du lot : `62c8ac6b6eb58b29e9eb80313e90ee4dca1e82ed`. Sept commits de code, 18 fichiers, +2086/-882 lignes, jusqu'à `ab25a62b0d2b318d3d62c54ab1fd10e10625341d`. Aucun build/test lancé et aucune validation revendiquée.
- Android : rejeu CJ/1, journal CM/1, découverte binaire et adaptateur USB désormais en Kotlin. Il ne reste que cinq fichiers Java de tests, à migrer sans perdre les assertions.
- Linux C++20 : inspection et vérification CM/1 (complet et préfixe déchiré), SHA-256, rapports et régressions synthétiques ; inspection CJ/1 et validation de l'ordre, CRC, compteurs, flags, services en lecture et rapports partiels. Les tests sont intégrés au Makefile, pas exécutés.
- Reste : CU/1 et parité des données dérivées du rapport Python CJ/1, autres fonctions Python et migration des 19 fichiers C MPC5566 selon contraintes de cible. Le corpus Python reste présent (128 fichiers) comme référence jusqu'à parité démontrée. Ne pas le supprimer par simple renommage.
- Protocole SAE J1979 toujours ouvert ; aucun passage à C6 ni aux autres protocoles. Référence normative J1979DA_202607 toujours non disponible, qualification physique encore à établir. Aucun checkpoint intermédiaire. APK v2.0.47 (versionCode 64) non produite : poursuivre jusqu'à clôture réelle, gate final unique, puis production et vérification de l'APK signée selon la procédure de v2.0.46. PR non fusionnée.


## Reprise du lot natif — 27/11/2026

SHA code : `d4b042da2f48991c48a2846b55d5899483420b1d`. Le scan legacy LSR/1, la maintenance fonctionnelle legacy, la découverte CAN et les campagnes physiques multi-ECU, ainsi que les catalogues locaux et leurs projections CL/1, CF/1 et LLR/1 disposent maintenant de chemins C++20 et de tests de régression écrits. Les schémas et la compatibilité historique restent à rapprocher. Le journal de maintenance et son intention sont persistés et relus avant commande ; la persistance des captures de lecture n'est pas encore incrémentale. Pas de validation exécutée, pas de suppression du corpus Python ni du noyau C MPC5566, pas de release APK.

## Reprise 28/11/2026 — ELM CAN et inspection maintenance

SHA code `c0ece3575a372c0ab12d83f499b78b1ed47fc033`. Journal EJR/1 progressif, capture et rejeu ECP/1 scan/live, inspection native des rapports de maintenance legacy (baseline et intent), portage des schémas historiques CAN Mode 04/08 SocketCAN/Android/J2534 et tests écrits. Les autres exports historiques, les appels produits, les outils Python hors J1979 et les sources C MPC5566 restent à migrer/valider. Ne pas déclarer de parité globale ni de clôture de protocole. Aucun test/build/release exécuté.
