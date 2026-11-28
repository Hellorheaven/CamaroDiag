# CamaroDiag — Pont Linux natif (migration C++20)

Première migration **sans Python** de l'inspection CF/1 SAE J1979 Mode 02
depuis l'outillage hôte expérimental vers un exécutable Linux C++20 (CLI, transports CF/1 et CL/1 et tests migrés vers C++20, validation de clôture encore à réaliser).

## Construction (ARM64, ARMv7, x86_64)

Prérequis : compilateur C++20 (g++), make, en-têtes et bibliothèque OpenSSL libcrypto.

~~~sh
sudo apt install build-essential libssl-dev
make
./camarodiag-native --help
~~~

Le même code C++20 est prévu pour sur ARM et x64; **aucun paquet .deb ni binaire
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
déclencheur, pages et relecture sont vérifiés en C++20. Les enregistrements
tronqués ne sont jamais comptés comme observations complètes. Une
requête à venir est **théorique**, jamais déclarée transmise.

Les JSON produits par cet outil C++20 sont canoniques pour son vérificateur
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

Le code C++20 authentifie le défi CU/2 par HMAC-SHA256 avant d'envoyer la preuve client,
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

Les sorties C++20 natives emploient intentionnellement les schémas
`*-linux-c-v1` : elles exposent un sous-ensemble strict des champs du rejeu
historique Python, C# et Kotlin, sans prétendre que le JSON est interchangeable.
Le nom de schéma commun ne doit **jamais** être conservé pour des objets dont
les champs de preuve diffèrent. Les vérificateurs C++20 n'acceptent que les
rapports exacts de leur propre sortie canonique. Un import portable complet
demande le rapprochement explicite des schémas et de leurs règles de contrôle.

## Paquet Linux natif (.deb)

Sur Debian, Ubuntu ou Raspberry Pi OS, installer la chaîne C++20 et `dpkg-dev`,
puis construire nativement sur l'architecture ciblée :

```sh
sudo apt install build-essential libssl-dev dpkg-dev
make
make deb
# Fichier local : out/camarodiag-native-j1979_0.1.0_<arch>.deb
```

Le paquet ne comporte qu'un exécutable C++20 natif et de la documentation. Les
dépendances `libc` / `libcrypto` sont calculées sur le système cible par
`dpkg-shlibdeps`. Il s'agit du **pont SAE J1979 partiel**, pas encore de
l'interface graphique ni du paquet de livraison définitif.

## SAE J1979 SocketCAN / ISO-TP directement en C++20 (sans Python)

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

**État : raccordement C++20 nouvellement écrit et non encore compilé/testé** selon
le gate de clôture unique du protocole SAE J1979. Le rapport C++20 possède un
schéma distinct du rapport Python historique, et les autres sous-commandes
Python doivent encore être migrées puis comparées avant suppression du code.
L'absence de recette physique ne vaut ni validation ECU ni certificat SAE.

## Surveillance native SAE J1979 Mode 01 en C++20

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

## Rejeu CL/1 Mode 01 natif en C++20 (sans Python)

```sh
./camarodiag-native --inspect-j1979-live-native-prefix incomplete.cl VIN prefix.json
./camarodiag-native --inspect-j1979-live-native complete.cl VIN report.json
./camarodiag-native --verify-j1979-live-native-prefix incomplete.cl prefix.json proof.json VIN
./camarodiag-native --verify-j1979-live-native complete.cl report.json proof.json VIN
```

Le rejeu indépendant vérifie en C++20 les trames CL/1, l'ordre, CRC, les
horodatages, les requêtes et réponses Mode 01/09, les PID autorisés et
les compteurs du terminal. Un rapport incomplet reste explicitement
incomplet ; un rapport ancien ou réécrit ne passe pas la vérification
canonique octet par octet. Les sources sont relues avant création privée
et exclusive du rapport ou de la preuve. Aucun de ces contrôles
hors ligne ne contacte l'ECU ou n'authentifie un transport physique.

## Acquisition CU/3 USB CL/1 Mode 01 native en C++20

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
puis un rejeu C++20 indépendant et relecture de la source avant le rapport.
Le fichier original est conservé en cas d'échec. Aucun repli sur Python,
CU/1/CU/2 ou autre ECU. Le rapport de succès est expressément une preuve
de cohérence CL/1 hors ligne, **pas une attestation de véhicule physique**.
Le secret est un fichier privé 32 octets, non écrit dans le JSON.

Intégration ajoutée mais non encore compilée/testée, conformément à la
consigne de gate unique. Le firmware connecté doit encore démontrer sa
capacité CU/3 réelle avant une utilisation sur véhicule.

## Moniteurs Mode 06 — mise à l'échelle native

`j1979_monitor_scaling.cpp` reprend les 79 définitions UASID explicites du
module Python `j1979_monitor_scaling.py`. Les rapports du scan `--j1979-can`
conservent les octets d'origine et présentent, pour une réponse CAN Mode 06
cohérente, chaque MONID/TID/UASID et ses valeurs encodées. Les définitions
connues exposent unité, valeur, minimum et maximum et la comparaison
`BELOW/WITHIN/ABOVE/INVERTED_LIMITS`; une définition inconnue reste
`scaled: null`. L'absence d'interprétation ne signifie pas échec du moniteur.
La comparaison numérique n'est **jamais** un verdict réglementaire ni
une preuve qu'un moniteur a effectivement fonctionné.

## Informations véhicule Mode 09 — reprise native

Le décodeur C++ `j1979_vehicle_info.cpp` reprend les règles du module Python
`j1979_vehicle_info.py` pour les InfoTypes 08 et 0B (compteurs IPT
essence/diesel) et 0D/0F (texte d'identification). Les rapports du scan
contiennent le nombre de cycles, les compteurs et les ratios uniquement
lorsque le format CAN complet est valide. Une division par zéro produit
`null`, et les formats inconnus ou fragments d'autres ECU ne sont pas
fusionnés. Les données restent des informations applicatives, pas une
attestation de conformité ni d'identité matérielle.

## Mesures physiques et trames figées Mode 01/02 natives

`j1979_measurement.cpp` transpose les 55 définitions numériques explicites
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

## Offline maintenance journal / Journal de maintenance hors ligne

The C++20 bridge can replay CM/1 journals with an independently supplied VIN.
The following commands never contact a vehicle or authorize a retry:

```sh
camarodiag-native --inspect-j1979-maintenance session.cm VIN report.json
camarodiag-native --verify-j1979-maintenance session.cm report.json proof.json VIN
camarodiag-native --inspect-j1979-maintenance-prefix interrupted.cm VIN prefix.json
camarodiag-native --verify-j1979-maintenance-prefix interrupted.cm prefix.json proof.json VIN
```

Exit codes: 0 = consistent evidence without a declared recovery requirement;
7 = recovery required, including missing terminal; 2 = rejected input or output.
Reports preserve CRC-checked original observations and SHA-256. Verification
requires exact canonical native report bytes; it does not yet accept differently
formatted Python reports. No physical provenance, monitor completion or ECU
programming permission is inferred. Linux CU/4 and CU/5 acquisition remain separate.

FR : ces commandes n'envoient aucune requête au véhicule. Le code 7 indique
une récupération nécessaire, pas une autorisation de réessayer. Conserver le
journal original ; ne jamais déduire une opération réussie d'un préfixe tronqué.

## CU/1 et réseau legacy ELM — développement non validé

Les commandes ci-dessous sont raccordées au code C++20 et au Makefile, sans exécution de test ni construction de paquet à ce stade. Le choix de la liaison reste explicite, et la confirmation locale n'autorise que les lectures. Les schémas natifs ne constituent pas encore une compatibilité intégrale avec les exports Python.

```sh
./camarodiag-native --j1979-usb --port /dev/ttyACM0 --key-file key.bin --capture session.cj --output session.json --tx 7E0 --rx 7E8 --vin VIN --confirm-physical-channel
./camarodiag-native --inspect-j1979-campaign session.cj VIN offline.json
./camarodiag-native --verify-j1979-campaign session.cj offline.json proof.json VIN
./camarodiag-native --j1979-legacy-discover --port /dev/ttyUSB0 --protocol 2 --baud 38400 --output sources.json --confirm-physical-channel
./camarodiag-native --j1979-legacy --port /dev/ttyUSB0 --protocol 2 --source 10 --vin VIN --output scan.json --confirm-physical-channel
./camarodiag-native --inspect-j1979-legacy-discovery 2 response.txt atdpn.txt replay.json
```

Codes ELM fixes : 1 PWM, 2 VPW, 3 ISO 9141-2, 4 ISO 14230-4 lente, 5 ISO 14230-4 rapide. Le scan exige la source documentée et le VIN ; la découverte ne choisit jamais automatiquement un ECU. Les mesures dérivées et les preuves d'identité sont séparées de la capture brute. Une réponse valide n'atteste pas l'identité physique de l'ECU. Les opérations mutatrices, le rejeu indépendant complet du scan, les formats historiques et les autres fonctions Python restent à rapprocher avant suppression des outils existants.

## CU/4–CU/5, Mode 02 et Mode 01 legacy natifs (non validés)

Le transport de maintenance authentifié et la récupération d'archives sont des chemins distincts. Les journaux CM/1 originaux et la preuve hors ligne restent requis ; un reçu ou un CRC ne certifie pas une opération ECU. Aucun retry mutateur automatique.

Les captures legacy LFR/1 (freeze) et LLR/1 (live) conservent les réponses brutes, le périmètre sélectionné et les informations de recette. Elles sont relues et rejouées avant génération du rapport. Pour le live, la cadence et les contrôles périodiques du VIN sont inclus dans le rejeu. Les exports numériques nécessitent le contexte approprié ; un PID inconnu doit rester brut. Les preuves comparent les octets canoniques et les SHA-256. Ces schémas ne remplacent pas encore à l'identique les schémas Python historiques. Les captures legacy sont persistées en fin de recette (y compris arrêt contrôlé), pas en streaming ; une interruption brutale du processus peut perdre des observations non persistées.

```sh
camarodiag-native --inspect-j1979-legacy-freeze 2 10 VIN capture.lfr report.json
camarodiag-native --verify-j1979-legacy-freeze 2 10 VIN capture.lfr report.json proof.json
camarodiag-native --inspect-j1979-legacy-live 2 10 VIN capture.llr report.json
camarodiag-native --verify-j1979-legacy-live 2 10 VIN capture.llr report.json proof.json
```

Les tests sont raccordés au Makefile et réservés au gate de clôture. Aucune validation matérielle, compilation ou livraison APK ne résulte de ce portage.

## Rejeu de scan, maintenance, découverte réseau et catalogues locaux

Ce lot ajoute des chemins C++20 pour le scan legacy LSR/1, la maintenance legacy, la découverte CAN classique avec sélection physique explicite des calculateurs, et les catalogues de mesures fournis localement. Les projections sont calculées en fractions exactes et sont conditionnées à la vérification de la capture, du VIN, de la portée du transport et de l'empreinte SHA-256 de la référence. La CLI expose les options dans `--help`. Les rapports et preuves natifs ne sont pas automatiquement interchangeables avec les anciens schémas Python.

La maintenance legacy demande une confirmation interactive et des journaux distincts ; une commande d'incertitude ne doit jamais être relancée automatiquement. Les captures de lecture legacy restent enregistrées en fin d'acquisition. Les tests de ce lot sont intégrés mais non exécutés ; aucune qualification matérielle, parité totale ni couverture normative n'est revendiquée.

## Native J1979: direct CAN, ELM CAN and durable legacy journals

The following commands are implemented in C++20; regression tests are registered in the native Makefile but have not yet been run at the protocol closure gate.

- `--j1979-maintenance-can`: explicit primary physical ECM, private directory, CM/1 journal, independent inspection, one-shot operation and recovery marker. Local socket closure is not independent proof of physical transceiver shutdown. Retain the marker for manual review; do not automatically retry maintenance.
- `--j1979-freeze-can`: explicit SocketCAN physical scope, shared Mode 02 acquisition, incremental CF/1 evidence and offline replay.
- `--j1979-elm-can`, `--j1979-elm-live`: fixed ELM CAN profile, selected physical source and read-only diagnostic requests; raw adapter transcript retained in the report.
- Legacy scan, freeze and live create an exclusive `.lj` journal beside the capture (or beside the scan report if no capture was requested). Each observation is synced before proceeding; the terminal denotes the end of recording, not diagnostic success. Offline inspection accepts structurally verified prefixes without claiming completeness. Recovery requires a valid terminal and deterministic replay.

Offline journal commands (no vehicle transmission):

```sh
camarodiag-native --inspect-j1979-legacy-journal PROTOCOL SOURCE VIN JOURNAL.lj REPORT.json
camarodiag-native --recover-j1979-legacy-journal PROTOCOL SOURCE VIN JOURNAL.lj RECOVERED_CAPTURE
```

Use a new output path; existing files are never overwritten. The recovered capture uses the corresponding LSR/1, LFR/1 or LLR/1 format. CRC consistency is not cryptographic authentication or physical-vehicle verification. Consult `--help` for the live command parameters.

## ELM CAN Mode 02, maintenance et vérification des traces (28/11/2026)

Les profils CAN fixes 6–9 sont désormais raccordés aux commandes natives
`--j1979-elm-can-freeze` et `--j1979-elm-can-maintenance` avec sélection physique explicite.
La première utilise le plan CF/1 et la seconde le moteur partagé Mode 04/08
et le journal durable CM/1. La maintenance exclut les réponses CAN étrangères,
exige les contrôles de consentement, VIN et immobilité, et interdit tout réessai
automatique. La fermeture du descripteur série ne prouve pas l'arrêt physique
du transceiver.

L'option `--adapter-capture` conserve une trace ECR/1 pour les commandes ELM CAN
scan, live, freeze et maintenance. ECR/1 est écrit à la fin de session ; les
captures CF/1 et CM/1 restent incrémentales. Le contrôle hors ligne peut
rejouer les échanges ELM et les rapprocher des observations CF/1 ou CM/1.
Les preuves scan/live ECR/1 ne constituent pas encore un rejeu intégral de
leur recette. Utiliser `--help` pour les arguments exacts des commandes
`--inspect-j1979-elm-trace` et `--verify-j1979-elm-trace`.

Le rapport legacy expose également les décodages Mode 05/06, les identifiants
et compteurs Mode 09 et le contexte de mesure courant. Les mesures figées
numérotées ne sont interprétées qu'avec un trigger stable et un VIN recontrôlé,
sans emprunter les paramètres de mesure actuels. Les régressions ajoutées
n'ont pas été exécutées. Il ne s'agit ni d'une qualification du véhicule,
ni d'une parité Python complète, ni d'une release.

## Journal EJR/1, capture ECP/1 et maintenance historique

L'option `--adapter-journal trace.ejr` des commandes ELM CAN scan, live, freeze et maintenance conserve les échanges au fil de l'eau. Une erreur d'écriture ou de relecture retire la session. `--adapter-capture trace.ecr` conserve le format ECR/1 fermé existant. Le terminal EJR/1 prouve la fermeture du transcript, pas l'exécution physique réussie. Un préfixe interrompu est inspectable mais non convertible en ECR/1 complet.

Pour scan/live, `--campaign-capture capture.ecp` conserve le plan, le transcript ECR/1 et la chronologie des callbacks nécessaires au rejeu du moteur de recette. Le rapport d'inspection ECP/1 est le rapport natif interne (et non l'enveloppe ELM). Le format ECP/1 est persisté après la recette ; utiliser EJR/1 en complément pour les interruptions.

```sh
camarodiag-native --inspect-j1979-elm-journal 6 7E0 7E8 VIN trace.ejr proof.json
camarodiag-native --recover-j1979-elm-journal 6 7E0 7E8 VIN trace.ejr trace.ecr
camarodiag-native --inspect-j1979-elm-campaign 6 7E0 7E8 VIN capture.ecp native.json
camarodiag-native --verify-j1979-elm-campaign 6 7E0 7E8 VIN capture.ecp native.json proof.json
camarodiag-native --inspect-j1979-legacy-maintenance VIN result.json baseline.json intent.json proof.json
camarodiag-native --inspect-j1979-historical-can-maintenance VIN result.json baseline.json proof.json
```

VIN doit être remplacé par le VIN documenté. Les inspecteurs hors ligne n'ouvrent aucun canal véhicule et ne prouvent ni consentement, ni durabilité avant transmission, ni authenticité du calculateur. Le nouvel inspecteur legacy exige les deux préfixes séparés pour une commande déclarée tentée. Le vérificateur historique CAN distingue une baseline absente, séparée ou embarquée. Tests raccordés au Makefile, non exécutés.


### Historical legacy PID00 discovery inspection (offline)

`camarodiag-native --inspect-j1979-historical-legacy-discovery discovery.json proof.json`

Replays historical Android, ELM and J2534 discovery reports and checks the
recorded responder classifications, raw frames, bitmaps and eligibility.
The output includes the input SHA-256; the command never opens a transport,
does not select an ECU, and creates a new proof without overwriting a file.
J2534 driver frames do not supply independent link checksums. A consistent
historical report does not authorize a new session or prove vehicle coverage.
The discovery-to-native-scan comparison uses a replayed LSR/1 capture,
not claims in an edited scan JSON report:

`camarodiag-native --bind-j1979-historical-legacy-discovery 2 10 1G1YY22G965109876 discovery.json scan.lsr proof.json`

An explicitly chosen protocol, source and VIN are required. Both input
files are hashed and reread; changed PID00 bitmap is reported rather than
silently replacing the historical selection. A failed scan remains failed.
This does not yet port inspection of historical selected-source scan JSON
from Android, ELM or J2534.

### Historical classic CAN discovery verification

`camarodiag-native --inspect-j1979-can-discovery-report discovery.json proof.json`

Offline replay of native SocketCAN, historical Python, J2534 and Android discovery-only exports, including fixed Android ELM profile, short complete single-frame DLC, pending/terminal responses, conflict detection, exact responder evidence and eligible IDs. No physical scan, automatic selection or programming authority is inferred. Network reports containing selected physical scans must not be used with this command; the physical campaign verification remains a separate migration item.

### Historical physical CAN read sequence inspection

`camarodiag-native --inspect-j1979-historical-can-plan historical VIN report.json proof.json`

Use `android` for interleaved inventory and final VIN read. Historical Python/J2534 order is separate from the native SocketCAN recipe. Validates read-only scope, raw response envelopes, negative codes, inventory order, terminal cutoffs and raw VIN. The result explicitly does not attest derived projections, transport provenance or whole-network verification. Input JSON may contain finite measured decimals; integer fields remain exact. No live channel is opened.

The historical plan inspector additionally recomputes the scalar arithmetic, DTC lists and availability inventories and compares any saved basic summaries. These historical values are not a claim that current ECU-specific scaling has been established. Complex multi-signal, monitor, categorical and operating-context projections still require migration.

### Historical multi-ECU network plan verification

`camarodiag-native --inspect-j1979-historical-can-network VIN network.json proof.json`

Reconstructs admission, validates the exact ordered selected responder set, matches physical CAN pairs, and replays each ECU's read plan and basic summaries. A failed selection cannot carry physical read observations; terminal failures cannot be followed by new ECU reads. Windows duplicated discovery must match. Does not certify the complete derived catalogue or current hardware identity and does not operate any channel. Use discovery-only verification for an empty selection.

When a historical step includes structured application evidence, the verifier now compares its shape, NRC, DTCs, historical scalar, PID support page, freeze trigger, readiness, raw Mode 06 records, VIN and basic Mode 09 identity fields against original bytes. Unknown non-null application fields or missing evidence in a declared evidence-bearing successful step are rejected. Complex aggregate projections remain separately scoped.

### Offline verification of native live CAN reports

```sh
camarodiag-native --inspect-j1979-native-live 7E0 7E8 VIN17 LIVE.json PROOF.json
```
Use the independently known 17-character VIN instead of `VIN17`. The command replays the native Linux CAN live sequence (SocketCAN or ELM application report), checks the support pages, selected PIDs, identity checks, cycle timing, stop state and numeric projections. It compares the regenerated report, writes a new proof with the source SHA-256, never connects to a vehicle, and does not prove adapter authenticity, physical provenance or actual clock behavior. JSON timing contains only exchange timestamps, not every clock callback; exceptional clock regressions and some historical timing ambiguities may be rejected. Historical Python/Android/Windows live formats use the separate verify-live command below.

### Historical selected-source legacy campaigns

```sh
camarodiag-native verify-legacy --report ARCHIVE.json --vin VIN17 --output PROOF.json
``
The original report is read-only and the output must be new. Historical J1850 PWM/VPW, ISO 9141 and KWP slow/fast ELM, Android and J2534 source reports are inspected from application requests, raw frames, selected-source payloads and advertised inventory. Optional numeric, readiness, monitor and scaling claims are recomputed where supported. J2534 driver frames cannot establish the omitted link checksum. Historical completion is not native current identity recheck, hardware qualification or permission to transmit. Some historical schemas and extensions still require parity review before retiring Python.

### Historical Mode 02 evidence

```sh
camarodiag-native verify-freeze --report CAN-FREEZE.json --vin VIN17 --output PROOF.json
camarodiag-native verify-legacy-freeze --report LEGACY-FREEZE.json --vin VIN17 --output PROOF.json
```
The commands independently reconstruct each requested frame, support page, initial/final trigger, attempted read, stop condition and identity from saved responses. Android ISO-TP and headered ELM transcripts are replayed; J2534 header evidence does not prove driver-managed link checksums. Additional manufacturer-specific frame numbers remain raw except for the defined trigger and support inventory. Both commands require a new output file, recheck the original input and retain its SHA-256. These are offline consistency inspections, not authenticated physical tests.

### Historical live evidence

```sh
camarodiag-native verify-live --report LIVE.json --vin VIN17 --output PROOF.json
```
Replays chronological identity admission, selected PID inventory, configuration and trim layout, sample ordering, scalar and multisignal statistics, and available selected-source transport transcripts. Historical raw-only Android CAN data is reassembled independently; legacy ELM headers and checksums are checked and J2534 driver headers are compared without claiming link checksum verification. No vehicle connection, authenticity claim, programming permission or time alignment is implied.

```sh
camarodiag-native compare-live --report RUN1.json --report RUN2.json --vin VIN17 --output COMPARISON.json
```
Compares two to sixteen independently replayed recordings of the same VIN, ECU channel and numeric/raw mode. Duplicate inputs are rejected by SHA-256. Only shared PIDs are compared; missing channels remain null, not zero. A comparison does not synchronize vehicle time or infer diagnostic conclusions.

### Historical embedded evidence verification

```sh
camarodiag-native verify-j1979-capture --capture CAPTURE.cj1 --report HOST.json --vin VIN17 --output PROOF.json
camarodiag-native verify-native-freeze --capture CAPTURE.cf --report HOST.json --vin VIN17 --output PROOF.json
```
Historical Android, Windows and Python CJ/1 and CF/1 JSON reports are compared with the binary records. The CF/1 verifier independently reconstructs the full historical recipe and checks it against the native binary replay; partial captures cannot establish completion. Both commands preserve source SHA-256 and do not independently validate the declared live credentials or actual physical ECU. They require distinct inputs and a new proof path.

### Native raw live engineering projection

After a native SocketCAN or ELM ISO-TP raw recording, replay it using independent identifiers and VIN:

```sh
camarodiag-native project-native-live --report live.json --tx 7E0 --rx 7E8 --vin VIN17 --output projection.json
camarodiag-native export-native-live-csv --report live.json --tx 7E0 --rx 7E8 --vin VIN17 --output samples.csv
```
The source recording is never rewritten. The commands verify the raw request sequence and canonical report before projecting values, preserve missing values, and require initial/final VIN agreement before attaching engineering interpretations. Context-dependent scaling is never silently replaced by historic default arithmetic. Partial projections are retained with exit status 2. JSON binds the original SHA-256; CSV is a convenience view, not an independently authenticated proof. Neither command opens a vehicle connection.

```sh
camarodiag-native compare-native-live --report run1.json --report run2.json --tx 7E0 --rx 7E8 --vin VIN17 --output comparison.json
```
Comparison replays each raw native report, not its exported projections. It requires the same VIN and CAN pair, distinct input hashes, and at most sixteen recordings. Per-channel means are compared only when all recordings retain identity, numeric samples and identical scaling provenance (including configuration bytes where applicable). Missing channels and unverified identity are explicit, not zero. No diagnostic or time alignment claim is made.

```sh
camarodiag-native export-legacy-live-csv --capture session.llr --protocol 2 --source 10 --vin VIN17 --output samples.csv
```
The exporter replays binary LLR/1, verifies selected-source checksums and correlates each sample with its exact exchange timestamps. Unverified final identity or raw-only mode leaves engineering cells empty. It never opens the adapter and never overwrites an existing output.

### Journaled selected SocketCAN network

```sh
camarodiag-native --j1979-can-network --interface can0 --rx 7E8 --rx 7E9 --vin VIN17 --output network.json --journal-directory NEW_DIR --confirm-functional-scope
camarodiag-native verify-network-journals --report network.json --journal-directory NEW_DIR --interface can0 --vin VIN17 --output network-proof.json
```
The directory must not exist. Functional discovery is saved before physical scans; each attempted selected ECU receives a private SCJ journal. The verifier rejects unrelated entries, foreign scope, swapped journals, missing successful reports and invalid terminal states. Header-only or torn journals retain their interrupted status; the command never connects to an ECU. A completed proof requires all selected ECU journals to bind to the report. This does not independently authenticate the original vehicle, adapter or discovery transmission.
