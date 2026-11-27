# CamaroDiag — Pont Linux natif (migration C++20)

Première migration **sans Python** de l'inspection CF/1 SAE J1979 Mode 02
depuis l'outillage hôte expérimental vers un exécutable Linux C++20 (les transports CF/1 et CL/1 historiques sont encore en C durant leur migration).

## Construction (ARM64, ARMv7, x86_64)

Prérequis : compilateur C++20 (g++), compilateur C uniquement pour les anciens transports restant à migrer, make, en-têtes et bibliothèque OpenSSL libcrypto.

~~~sh
sudo apt install build-essential libssl-dev
make
./camarodiag-native --help
~~~

Le même code C se compile sur ARM et x64; **aucun paquet .deb ni binaire
précompilé n'est encore livré**. L'utilisation ne requiert pas d'interpréteur
Python ni de dépendance pyserial.

## Commandes déjà implémentées

La voie complète vérifie aussi l'enregistrement terminal, les états par frame,
la durée, les drapeaux et la cohérence avec le rejeu déterministe. L'absence
d'attestation du calculateur est toujours indiquée.


~~~sh
./camarodiag-native --inspect-j1979-freeze-native-prefix capture.cf VIN prefix.json
./camarodiag-native --inspect-j1979-freeze-native complete.cf VIN complete.json
./camarodiag-native --verify-j1979-freeze-native-prefix capture.cf prefix.json preuve.json VIN
~~~

Le VIN est fourni explicitement, indépendamment de la capture.
Le flux CF/1 est borné : en-têtes, séquences, tailles, CRC32, plan
CAN/VIN canonique, réponse et tentative transport, ordre exact des
requêtes Mode 09/02, inventaire propre à chaque freeze frame, PID 02
déclencheur, pages et relecture sont vérifiés en C. Les enregistrements
tronqués ne sont jamais comptés comme observations complètes. Une
requête à venir est **théorique**, jamais déclarée transmise.

Les JSON produits par cet outil C sont canoniques pour son vérificateur
natif : le vérificateur impose une égalité octet à octet avec le rejeu
indépendant. Les rapports historiques Python/Kotlin/C# ont d'autres
projections et ne sont **pas encore acceptés par ce vérificateur**.
Les sorties refusent l'écrasement et les symlinks d'entrée, les entrées
sont relues avant publication et les fichiers sont créés en mode 0600.

La réussite d'une inspection est uniquement un résultat **hors ligne
sur préfixe incomplet** : jamais preuve de réponse authentifiée
d'un ECU, de fin de campagne ou de capacité à écrire.

## État de migration

Ce lot n'est **pas** la suppression globale de Python. Les acquisitions
réelles CU/2, les autres commandes SAE J1979, le pont J2534 Windows,
les autres outils hôtes et l'interface Linux ne sont pas encore portés.
Ne retirer les anciens outils qu'après remplacement complet de leurs
contrats, tests et interfaces. La CI de clôture protocolaire doit être
lancée uniquement lorsque tous ces raccordements sont terminés.

## Acquisition CU/2 native sur Linux

Le firmware compatible doit être qualifié et doté préalablement d'un secret
partagé de 32 octets. Le terminal local demande une seconde confirmation ;
aucun échange, recherche de port, découverte de véhicule ou réessai automatique
n'est déclenché par --help, ni par l'analyse hors connexion.

~~~sh
./camarodiag-native --j1979-freeze-native \
  --port /dev/ttyACM0 --key-file key.bin \
  --capture campagne.cf --output campagne.json \
  --tx 7E0 --rx 7E8 --vin 2G1FA1E30D7654321 \
  --frame 00 --pid 0C --budget-ms 60000 \
  --confirm-physical-channel
~~~

Le C authentifie le défi CU/2 par HMAC-SHA256 avant d'envoyer la preuve client,
puis enregistre les octets CF/1 de la liaison, y compris les fins tronquées.
L'outil relit le fichier et vérifie la campagne hors connexion avant export.
Un rapport d'échec reste non validé et une capture interrompue est conservée
pour analyse. Rien n'autorise la programmation ou une identification certaine
de l'ECU physique. Cette voie n'a pas encore subi la validation de clôture.

## Vérification indépendante d'une capture complète

~~~sh
./camarodiag-native --inspect-j1979-freeze-native complete.cf VIN complete.json
./camarodiag-native --verify-j1979-freeze-native complete.cf complete.json preuve.json VIN
~~~

Le contrôle de capture complète reconstitue les enregistrements, les champs
terminaux et leurs statuts. La preuve vérifie les originaux puis les relit
avant publication. **Le rapport enregistré par la voie live USB contient des
drapeaux d'authentification de session ; il ne se confond pas avec un rapport
hors ligne**, et ne peut être accepté par le vérificateur hors ligne que si
les champs et leur portée correspondent exactement. Ni rapport ni preuve ne
sont une attestation d'identité matérielle.

## Compatibilité et versionnement des preuves

Les sorties C natives emploient intentionnellement les schémas
`*-linux-c-v1` : elles exposent un sous-ensemble strict des champs du rejeu
historique Python, C# et Kotlin, sans prétendre que le JSON est interchangeable.
Le nom de schéma commun ne doit **jamais** être conservé pour des objets dont
les champs de preuve diffèrent. Les vérificateurs C n'acceptent que les
rapports exacts de leur propre sortie canonique. Un import portable complet
demande le rapprochement explicite des schémas et de leurs règles de contrôle.

## Paquet Linux natif (.deb)

Sur Debian, Ubuntu ou Raspberry Pi OS, installer la chaîne C et `dpkg-dev`,
puis construire nativement sur l'architecture ciblée :

```sh
sudo apt install build-essential libssl-dev dpkg-dev
make
make deb
# Fichier local : out/camarodiag-native-j1979_0.1.0_<arch>.deb
```

Le paquet ne comporte qu'un exécutable C natif et de la documentation. Les
dépendances `libc` / `libcrypto` sont calculées sur le système cible par
`dpkg-shlibdeps`. Il s'agit du **pont SAE J1979 partiel**, pas encore de
l'interface graphique ni du paquet de livraison définitif.

## SAE J1979 SocketCAN / ISO-TP directement en C (sans Python)

La campagne native de lecture physique offre un premier remplacement de la
commande Linux Python `j1979_cli`. L'interface CAN (`can0`, `can1`, ...), la
paire physique de CAN IDs et le VIN attendu sont sélectionnés **avant** toute
ouverture de canal. Aucun broadcast fonctionnel, aucun effacement de défauts,
aucune fonction Mode 08 de commande ou de programmation n'est envoyé.

```sh
./camarodiag-native --j1979-can --interface can0 --tx 7E0 --rx 7E8 \
  --vin 2G1FA1E30D7654321 --budget-ms 180000 \
  --output evidence.json --confirm-physical-channel
```

Une confirmation interactive locale supplémentaire est nécessaire. Le
rapport privé JSON (mode `0600`) conserve l'ordre, les requêtes et réponses
hexadécimales brutes, les durées, le VIN observé, les inventaires indépendants
Mode 01/02/06/08/09, les DTC et les arrêts de campagne. Les statuts `RAW`
restent non interprétés; un NRC, un timeout ou un PID non documenté ne peut
pas être promu automatiquement en mesure étalonnée. Le socket Linux ISO-TP
contrôle les réponses obsolètes, la liaison et la fenêtre temporelle, sans
retransmission après une émission incertaine.

**État : raccordement C nouvellement écrit et non encore compilé/testé** selon
le gate de clôture unique du protocole SAE J1979. Le rapport C possède un
schéma distinct du rapport Python historique, et les autres sous-commandes
Python doivent encore être migrées puis comparées avant suppression du code.
L'absence de recette physique ne vaut ni validation ECU ni certificat SAE.

## Surveillance native SAE J1979 Mode 01 en C

```sh
./camarodiag-native --j1979-live --interface can0 --tx 7E0 --rx 7E8 \
  --vin 2G1FA1E30D7654321 --pid 0C --pid 0D --cycles 30 \
  --interval-ms 1000 --budget-ms 180000 --output live.json \
  --confirm-physical-channel
```

Le choix du VIN, des PID et la confirmation interactive sont obligatoires.
La limite est de 32 PID et 10 000 lectures. Le JSON conserve chaque réponse
RAW, son horodatage relatif, l'identité initiale et finale et toute interruption.
Une lecture manquante n'est pas promue en mesure ni en preuve physique. La
mise à l'échelle des valeurs et l'intégration CU/3 restent à porter depuis
les anciens outils Python. Tests non exécutés avant le gate de clôture.

## Rejeu CL/1 Mode 01 natif en C (sans Python)

```sh
./camarodiag-native --inspect-j1979-live-native-prefix incomplete.cl VIN prefix.json
./camarodiag-native --inspect-j1979-live-native complete.cl VIN report.json
./camarodiag-native --verify-j1979-live-native-prefix incomplete.cl prefix.json proof.json VIN
./camarodiag-native --verify-j1979-live-native complete.cl report.json proof.json VIN
```

Le rejeu indépendant vérifie en C les trames CL/1, l'ordre, CRC, les
horodatages, les requêtes et réponses Mode 01/09, les PID autorisés et
les compteurs du terminal. Un rapport incomplet reste explicitement
incomplet ; un rapport ancien ou réécrit ne passe pas la vérification
canonique octet par octet. Les sources sont relues avant création privée
et exclusive du rapport ou de la preuve. Aucun de ces contrôles
hors ligne ne contacte l'ECU ou n'authentifie un transport physique.

## Acquisition CU/3 USB CL/1 Mode 01 native en C

```sh
./camarodiag-native --j1979-live-usb-native \
  --port /dev/ttyACM0 --key-file secret.bin \
  --capture session.cl --output session.json \
  --tx 7E0 --rx 7E8 --vin 2G1FA1E30D7654321 \
  --pid 0C --pid 0D --cycles 30 --interval-ms 1000 \
  --budget-ms 180000 --confirm-physical-channel
```

Cette voie utilise une authentification CU/3 HMAC-SHA256, la conservation
en lecture seule des octets CL/1 (y compris troncatures), CRC/séquences,
puis un rejeu C indépendant et relecture de la source avant le rapport.
Le fichier original est conservé en cas d'échec. Aucun repli sur Python,
CU/1/CU/2 ou autre ECU. Le rapport de succès est expressément une preuve
de cohérence CL/1 hors ligne, **pas une attestation de véhicule physique**.
Le secret est un fichier privé 32 octets, non écrit dans le JSON.

Intégration ajoutée mais non encore compilée/testée, conformément à la
consigne de gate unique. Le firmware connecté doit encore démontrer sa
capacité CU/3 réelle avant une utilisation sur véhicule.

## Moniteurs Mode 06 — mise à l'échelle native

`j1979_monitor_scaling.c` reprend les 79 définitions UASID explicites du
module Python `j1979_monitor_scaling.py`. Les rapports du scan `--j1979-can`
conservent les octets d'origine et présentent, pour une réponse CAN Mode 06
cohérente, chaque MONID/TID/UASID et ses valeurs encodées. Les définitions
connues exposent unité, valeur, minimum et maximum et la comparaison
`BELOW/WITHIN/ABOVE/INVERTED_LIMITS`; une définition inconnue reste
`scaled: null`. L'absence d'interprétation ne signifie pas échec du moniteur.
La comparaison numérique n'est **jamais** un verdict réglementaire ni
une preuve qu'un moniteur a effectivement fonctionné.

## Informations véhicule Mode 09 — reprise native

Le décodeur C `j1979_vehicle_info.c` reprend les règles du module Python
`j1979_vehicle_info.py` pour les InfoTypes 08 et 0B (compteurs IPT
essence/diesel) et 0D/0F (texte d'identification). Les rapports du scan
contiennent le nombre de cycles, les compteurs et les ratios uniquement
lorsque le format CAN complet est valide. Une division par zéro produit
`null`, et les formats inconnus ou fragments d'autres ECU ne sont pas
fusionnés. Les données restent des informations applicatives, pas une
attestation de conformité ni d'identité matérielle.

## Mesures physiques et trames figées Mode 01/02 natives

`j1979_measurement.c` transpose les 55 définitions numériques explicites
du décodeur Python historique, et l'odomètre 0xA6. Les rapports du scan
et de la surveillance live utilisent les mêmes valeurs strictement corrélées,
sans interpréter les PID inconnus. Pour les paramètres sensibles à la
configuration du calculateur (PID 4F/50), `context_required=true` et
`number=null` préservent l'absence de preuve. Les trames figées sont
attribuées uniquement à leur frame Mode 02; un deuxième octet de trim n'est
pas attribué à une banque supérieure sans preuve d'implantation.

## Transition vers C++20

Les nouveaux décodeurs, les campagnes CAN, la surveillance Mode 01 et le transport ISO-TP Linux sont maintenant en C++20. Les buffers sont bornés et les allocations temporaires protégées par RAII. La frontière ABI historique conserve encore des destructeurs explicites.

Les anciens parseurs CF/1/CL/1, le transport USB et le point d'entrée C restent à migrer ; la suppression finale des modules Python dépend aussi de leur remplacement fonctionnel. Aucun build/test intermédiaire ni APK v2.0.47 n'a été lancé à cette étape.
