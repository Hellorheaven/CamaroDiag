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

Les nouveaux fichiers ISO-TP `connectors/firmware/shared/j1979_isotp_native_c.{c,h}` et l'inclusion provisoire `connectors/firmware/esp32s3/src/j1979_native_c.c` ne faisaient que dupliquer un moteur embarqué C++ existant. Ils sont retirés. Le pont Linux continue à utiliser son implémentation native C utile ; le firmware ESP32 conserve les classes C++ existantes. Aucun développement Python nouveau n'est autorisé.
