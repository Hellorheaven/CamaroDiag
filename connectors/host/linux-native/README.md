# CamaroDiag — Pont Linux natif en C

Première migration **sans Python** de l'inspection CF/1 SAE J1979 Mode 02
depuis l'outillage hôte expérimental vers un exécutable Linux C11.

## Construction (ARM64, ARMv7, x86_64)

Prérequis : compilateur C11, make, en-têtes et bibliothèque OpenSSL libcrypto.

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
