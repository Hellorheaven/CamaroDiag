# Normes automobiles, recette après écriture et récupération des calculateurs

**Chantier #110, PR #95. Ce document décrit l'état du CODE et non une certification sur véhicule.**
Ne pas confondre : norme publiée, codec validé par tests unitaires, transport réellement relié à une interface, service diagnostique ECU, programmation validée sur banc et procédure de récupération éprouvée.

## Standards applicables

| Couche / standard | Déjà en place ou ajouté | Non couvert / condition manquante |
|---|---|---|
| UDS ISO 14229 | Client diagnostics en lecture, session autorisée, parser NRC, constructeur de séquence 0x34/0x36/0x37 pour bootloader compatible, parseurs DoIP | Séquences complètes 0x31 OEM erase/checksum/dependencies, SecurityAccess OEM, installation du loader et reset boot/application par famille |
| ISO-TP ISO 15765-2, CAN classique | Pont J2534 existant avec filtre flow-control et CAN 11/29 bits, observateur/connecteur | Qualification ECU réel, géométrie et bootloader |
| CAN-FD ISO 11898 / ISO 15765-2:2024 | **Nouveau** `IsoTpCanFdSession` envoi/réception SF/FF/CF/FC (escape FF 32 bits, BS, STmin, CTS/WAIT/OVFLW, séquences) ; `IsoTpJ2534RawCanLink` sur canal brut FD_CAN 0x8011, DLC 8–64 et option BRS ; débit de phase de données par `SET_CONFIG 0x805C` | Qualification réelle de l'adaptateur, timing µs précis sous Windows, qualification matérielle des adressages ISO-TP mixte/étendu, éventuelles limites du firmware J2534-2 ; pas de garantie de flash ECU |
| DoIP ISO 13400-2 / UDS ISO 14229-5 | Diagnostic TCP 13400 et TLS 3496 avec validation du certificat serveur, option certificat client Windows, échanges UDS bidirectionnels et ACK/NACK corrélés, découverte et statut UDP | Compatibilité des certificats et politiques OEM, authentification calculateur, scénarios de réveil/routage constructeur, essais sur passerelles réelles et procédure de flash/récupération physique |
| K-Line ISO 9141-2 | J2534-1 : ouverture de canal réel, Tx/Rx de trames et FIVE_BAUD_INIT via ioctl du fabricant ; essais sur véhicule à faire | Activation 5 bauds et synchronisation / timing par adaptateur, validation lecture/trames sur véhicule |
| KWP2000 ISO 14230 | Inspecteur de réponse physique côté Android ; **nouveau** encodage KWP à longueur courte/longue et somme additive côté PC ; numéro protocole J2534 standard | Message sequencing/correlation avancée et sessions physiques K-Line sur pilote réel |
| SAE J1850 VPW / PWM | Canaux J2534 VPW/PWM en émission et réception, calculateur CRC-8 SAE J1850 hors ligne, vérification CRC RX activable uniquement si la DLL conserve l'octet physique | Codage physique, arbitrage, timings, interprétation des headers OEM, comportement CRC de chaque DLL et essais réels |
| SAE J2534-1/J2534-2 | Chargement des DLL, validation ABI, pont PassThru sur CAN ISO15765 et SW-CAN | Les canaux bidirectionnels génériques K-Line, J1850, CAN/FD ont été ajoutés ; qualifier chaque DLL fournisseur, ses extensions, filtres et paramètres spécifiques |
| SAE J1979, J1979-2 (OBDonUDS) | PIDs/DTC OBD-II existants, service DID F810, plages DID émissions, DTC WWH et statuts, snapshot/extended-data, requêtes et réponses ISO-TP/DoIP | Couverture exhaustive SAE J1979DA, définitions et unités à jour, variantes constructeurs, découverte fonctionnelle et tests de conformité véhicule |
| SAE J1939-21 / J1939-81 | Identifiants PGN 29 bits, BAM et RTS/CTS classiques, TP.DT, EOM, requête/ACK PGN, codec NAME et revendication explicite d'adresse sur CAN brut | Gestion persistante d'identité J1939-81, collisions simultanées réelles, sessions multiplexées, DM J1939-73 avancés et vérification de l'adaptateur physique |\n| SAE J1939-22 CAN-FD | Codec et commandes FEFF 29 bits/FBFF 11 bits Multi-PG (C-PG multiples), FD.TP.CM/DT, BAM et CMDT RTS/CTS en lecture/écriture de trames via J2534 FD_CAN | Assurance TOS/TF autres que 2/0, multiplexage multi-pairs/temps réel, bus FD qualifié, J1939-81 persistant et tests physiques complets ; programmation ECU non garantie |
| GM GMLAN/SAE J2411 | Transport SW-CAN J2534 existant et lecture identification GM ; moteur E39 de banc spécifique | Ne pas généraliser une procédure E39 à un autre ECM ni à tous les véhicules GM |

**Le catalogue logiciel a des colonnes explicites `TransportPresent` et `PhysicalWriteQualified`. Toutes les entrées sont actuellement non qualifiées pour un rollback véhicule.** Des IDs SAE J2534 classiques sont utilisés tels quels ; CAN-FD requiert une négociation indépendante avec la DLL J2534-2 et peut ne pas être offert par l'adaptateur. Ne pas inventer un ID universel pour les extensions.

## Procédure de recette post-programmation

1. **Fin de transfert** : contrôler strictement chaque paquet transmis par rapport au plan mémoire, vérification CRC/checksum attendue et relecture indépendante de tous les secteurs modifiés ainsi que des secteurs protégés/voisins (la voie E39 de banc possède déjà des contrôles partiels).
2. **Sortie de programmation** : effectuer les routines OEM de validation de mémoire/dépendances si le profil ECU les documente, appliquer le reset ou le cycle contact seulement selon la procédure qualifiée. Pas de reset universel inventé.
3. **Diagnostic lecture seule** : vérifier la réponse de l'application après réveil, VIN réel, ID logiciel/OS, état diagnostic, éventuels codes défauts nouveaux par comparaison avec la baseline ; stabilité électrique.
4. **Véhicule à l'arrêt** : contrôle par l'opérateur des témoins, démarrage/ralenti, fonctions pertinentes et absence d'anomalie nouvelle. Certaines fonctions ne sont pas applicables pour un ECU hors moteur : **NON APPLICABLE doit être prouvé**, jamais considéré comme PASS par défaut.
5. **Essai dynamique** : si nécessaire et véhicule légalement sûr, l'opérateur qualifié réalise l'essai sur route ou banc ; CamaroDiag ne commande pas automatiquement le véhicule. Collecter le compte rendu et son horodatage.
6. **Verdict** : PASS uniquement quand toutes les preuves demandées sont complètes, FAIL pour divergences, INCONCLUSIF en absence de lecture/test. La fin du flash (journal « Completed ») n'est **pas** un certificat de sécurité du véhicule.

Le modèle de vérification `EcuPostFlashAssessment` représente ces preuves et refuse de transformer « non testé » en « OK » ; il **n'envoie aucune commande au calculateur**. Le transport DoIP peut transmettre des services UDS quelconques, y compris d'écriture ; la compatibilité matérielle reste à tester. Les services d'écriture nécessitent le consentement opérateur et les réelles sessions/conditions OEM.

## Restauration des secteurs physiquement sauvegardés

- Le moteur existant enregistre avant l'effacement les **octets originaux lus sur le véritable ECU** dans `<journal>.blocks/<adresse>-<taille>.ecu-block`. Les métadonnées incluent région, adresse, taille, SHA-256 et sont associées aux événements du journal. Conserver aussi `<journal>.meta.json` (VIN/OSID) et l'image de référence.
- Le nouvel inspecteur `NativeE39SavedBlockAssessment` recompte chaque secteur préparé, exige un fichier par secteur, contrôle la métadonnée originale et calcule à nouveau l'empreinte des **octets réellement sauvegardés sur disque**. Un fichier absent, tronqué, ajouté, déplacé ou modifié rend le résultat NON VÉRIFIÉ.
- L'aperçu hors ligne `EcuPostFlashAssessment.PrepareRestorationPreview` reconstitue les originaux uniquement si **tous** les secteurs physiquement capturés existent, intègres et correspondent exactement à la géométrie attendue. Il reste utilisable après un **journal interrompu**, précisément pour ne pas perdre le secours en cas d'écriture avortée. Il **ne programme rien**.
- **Avant chaque nouvelle écriture/restauration** : état ECU actuel et identité VIN/OSID vérifiés par une lecture indépendante, géométrie et état d'effacement compris, tension sécurisée, chemin de reprogrammation et mode récupération éprouvés sur ce matériel, vraie clé OEM si nécessaire, restauration des checksums et voisins selon le profil, puis **nouvel avertissement OK et confirmation Oui/Non**. Les sauvegardes sont immuables et une nouvelle transaction produit son propre journal.
- ECU muet, alimentation interrompue, application qui ne démarre plus : le transport standard peut être inutilisable ; procédure boot/recovery bench, BDM/JTAG ou équipement constructeur suivant le modèle, **aucune promesse de rollback CAN/K-Line/DoIP**. Ne pas retenter un flash non qualifié.

L'onglet **Récupération ECU** du pont Windows indique désormais combien de blocs originaux ont réellement passé les vérifications des fichiers physiques et que les tests du véhicule sont distincts de la fin du journal. Les contrôles de fichiers sont OFFLINE : ni connexion au véhicule, ni remise en état automatique.

## Vérification et limites de ce lot

Autotests : catalogue ISO/SAE, KWP framing, ISO-TP CAN-FD SF/FF/CF/FC avec blocs/temporisations/longues séquences et défauts, J1939-21 BAM et RTS/CTS émission/réception avec PGN/adressage, DoIP header/routing/ACK/NACK/adressage et conservation de tous les SID y compris d'écriture, UDS write DID/write memory/routine/transfer/security avec appariement des réponses, test de verdict post-écriture et falsification de sauvegardes physiques, préparation d'un aperçu y compris après interruption.

**Non livré dans ce premier lot** : qualification physique des pilotes K-Line/J1850/J1939/CAN-FD, qualification matérielle ISO-TP mixte, J1939-81 address claiming et J1939-22 FD.TP/multi-PG, DoIP TLS/UDP, adaptation transport Android, OBDonUDS complet, routines OEM postflash, automatisation des mesures véhicule, programmation de restauration physique et qualification sur un calculateur réel. Un test J1939-21 sur modèle RAM et une trame J2534 ne certifient aucune écriture constructeur. Le fait qu'une DLL J2534 fournisse un canal bidirectionnel n'est pas une preuve de compatibilité ECU. Les logiciels doivent afficher « pas qualifié » plutôt que « pris en charge » pour ces fonctions.


## Commandes d'échange bidirectionnel (Windows)

Les deux commandes utilisent les standards (DoIP TCP ou DLL SAE J2534 du fabricant). **Toute commande sortante**, dont le service est arbitraire, déclenche avertissement **OK** puis confirmation séparée **OUI/NON**. C'est le consentement opérateur, pas une interdiction de service UDS. Les trames sont transmises sans changement des données fournies ; la DLL J2534 reste responsable de la couche physique, des filtres et du timing.

```text
CamaroDiag.J2534Bridge.exe --doip-exchange --host 192.0.2.10 --tester 0E80 --ecu 1234 --payload 22F190
CamaroDiag.J2534Bridge.exe --doip-exchange --host 192.0.2.10 --tester 0E80 --ecu 1234 --payload 2EF19001
CamaroDiag.J2534Bridge.exe --j2534-standard --adapter-index 0 --protocol-id 0x04 --baud 10400 --kline-init five:33 --data 83F11022019077 --read-frames 3
CamaroDiag.J2534Bridge.exe --j2534-standard --adapter-index 0 --protocol-id 0x8012 --baud 500000 --data 000007E022F190 --mask FFFFFFFF --pattern 000007E8 --flow-control 000007E0
```

Les adresses IP `192.0.2.10`, CAN et données ci-dessus sont **illustratives** et ne doivent pas être émises vers un ECU réel sans profil correct. La forme des données varie selon le protocole et le driver. Pour K-Line, utiliser `--kline-init fast:<trame-hex>` ou `five:<adresse-hex>` suivant le véhicule, jamais une initialisation présumée universelle. Le débit FD de phase de données est à configurer via les paramètres J2534-2 du driver ; le débit nominal seul ne le spécifie pas.

La CLI DoIP diagnostique sait désormais négocier TLS 1.2/1.3 sur le port sécurisé, mais ne gère pas les certificats clients OEM ni les autorités privées absentes du magasin système ; elle doit donc être utilisée uniquement avec une passerelle autorisant explicitement une connexion TCP 13400 non chiffrée, sur réseau véhicule isolé. Ne pas présenter son exécution en simulation comme une qualification véhicule.

## Nouveaux transports multipaquets — lecture et écriture (23/11/2026)

La CLI Windows exploite uniquement les **canaux CAN bruts** des DLL SAE J2534 pour ces moteurs. Utiliser `CAN (0x05)` pour J1939-21 et ISO-TP classique, ou `FD_CAN (0x8011)` pour le moteur ISO-TP CAN-FD. Ne **jamais** passer les paquets déjà fragmentés à un canal `ISO15765 (0x06)` ou `FD_ISO15765 (0x8012)` qui segmente déjà les messages.

### ISO-TP CAN-FD / CAN classique

```text
CamaroDiag.J2534Bridge.exe --isotp-exchange --adapter-index 0 --tx-id 7E0 --rx-id 7E8 --baud 500000 --fd true --tx-dl 64 --brs true --data-baud 2000000 --payload 22F190
CamaroDiag.J2534Bridge.exe --isotp-exchange --adapter-index 0 --tx-id 7E0 --rx-id 7E8 --baud 500000 --fd false --tx-dl 8 --payload 2EF1900102
CamaroDiag.J2534Bridge.exe --isotp-exchange --adapter-index 0 --tx-id 7E0 --rx-id 7E8 --baud 500000 --fd false --mode receive
CamaroDiag.J2534Bridge.exe --isotp-exchange --adapter-index 0 --tx-id 7E0 --rx-id 7E8 --baud 500000 --fd false --tx-address 0xF1 --rx-address 0x10 --payload 22F190
CamaroDiag.J2534Bridge.exe --isotp-exchange --adapter-index 0 --tx-id 7E0 --rx-id 7E8 --baud 500000 --fd true --tx-dl 64 --data-baud 2000000 --payload-file "C:\\ecu\\sector.bin"
```

- `--mode receive` attend un message ECU sans requête diagnostique sortante. Une réception multipaquet déclenche uniquement les réponses FlowControl nécessaires ; ne remplace pas une opération de diagnostic requête/réponse.
- `--mode exchange` est le comportement par défaut, avec requête et réponse ; il exige la confirmation opérateur avant émission.
- `--payload-file` transmet exactement les octets du fichier dans la limite de 1 Mio pour le moteur logiciel (plafond d'allocation, **pas** promesse de capacité de l'ECU) ; les adresses/secteurs nécessaires à un flash réel restent définis par le pilote ECU. L'acceptation de la trame seule ne prouve pas l'écriture flash.
- `--wftmax` configure le nombre de réponses FlowControl WAIT consécutives acceptées avant abandon (défaut 4 ; bornes 0–255) ; cette valeur n'élimine pas les temporisations de session.
- `--block-size` et `--stmin` contrôlent les réponses FlowControl produites ; le moteur respecte celles du pair, y compris les valeurs sub-millisecondes. Une programmation avec timing précis ne sera considérée matériellement qualifiée qu'après preuve sur le matériel ciblé.
- `--extended true` sélectionne un identifiant CAN 29 bits (et non l'adressage ISO-TP étendu, accessible par --tx-address/--rx-address).
- La réception multi-trame utilise `RX_DL` issu du `CAN_DL` de la première trame envoyée par l'ECU, indépendamment du DLC de nos trames TX/FC ; les CF non terminaux incohérents sont rejetés.
- Les réponses CAN sont corrélées à l'identifiant attendu. Les transactions susceptibles de modifier l'ECU demandent un nouvel avertissement OK puis confirmation OUI/NON.

L'adressage ISO-TP étendu à un octet est implémenté dans le moteur SF/FF/CF/FC et accessible par `--tx-address 0xF1 --rx-address 0x10` (octets illustratifs). Ce mode n'implique pas des identifiants CAN 29 bits. Les deux adresses logiques sont requises ensemble. Tests unitaires ajoutés mais non exécutés ; prise en charge matérielle et adressage mixte non qualifiés.

### SAE J1939-21

```text
CamaroDiag.J2534Bridge.exe --j1939-transport --adapter-index 0 --baud 250000 --source 0xF1 --target 0x10 --pgn 0xF004 --mode receive
CamaroDiag.J2534Bridge.exe --j1939-transport --adapter-index 0 --baud 250000 --source 0xF1 --target 0x10 --pgn 0xF004 --mode send --payload 0102030405060708090A0B
CamaroDiag.J2534Bridge.exe --j1939-transport --adapter-index 0 --baud 250000 --source 0xF1 --target 0x10 --pgn 0xF004 --mode send --broadcast true --payload 0102030405060708090A0B
```

- Messages directs jusqu'à 8 octets (remplissage `FF` conservé en réception), BAM de diffusion, RTS/CTS point à point, TP.DT par paquets de 7 octets et acquittement de fin : prévus dans le moteur. Taille maximale J1939-21 : **1 785 octets**.
- **Important** : l'adresse locale `--source` doit être légitime sur le réseau et correctement réclamée selon J1939-81 dans le véhicule. Le moteur ne revendique pas l'implémentation de l'address-claim ni de l'ISO-TP sur les messages J1939.
- Les trames de référence sont des exemples de syntaxe : aucune émission sur véhicule réel sans identification et qualification des PGN/adresses et du sens de la commande.

## Références de normes et implémentations

- [SAE J2534-1 et table des protocoles (document public de référence)](https://law.resource.org/pub/us/cfr/ibr/005/sae.j2534.2002.pdf)
- [SAE J2534-2/11_0500 CAN FD (éditeur SAE)](https://saemobilus.sae.org/standards/j2534-211_5_00-pass-thru-extended-features-flexible-data-rate)
- [ISO 13400-2:2025 (éditeur ISO)](https://www.iso.org/cms/live/live/en/sites/isoorg/contents/data/standard/08/79/87961.html)
- [SAE J2534 protocoles et identification capacité (référence d'API)](https://scandoc.org/en/develop/j2534/pt_ioctl_device_info.html)
- [SAE J1979-2 OBDonUDS (SAE International)](https://saemobilus.sae.org/standards/j1979-2_202104-e-e-diagnostic-test-modes-obdonuds)
- [SAE J1939-22 CAN FD (SAE International)](https://saemobilus.sae.org/standards/j1939-22_202209-fd-data-link-layer)
- [ISO 15765-2 / ISO-TP (documentation noyau Linux)](https://dri.freedesktop.org/docs/drm/networking/iso15765-2.html)
- [AUTOSAR DoIP — spécification basée sur ISO 13400](https://www.autosar.org/fileadmin/standards/R24-11/CP/AUTOSAR_CP_SWS_DiagnosticOverIP.pdf)
- [Eclipse OpenSOVD — architecture DoIP](https://github.com/eclipse-opensovd/classic-diagnostic-adapter/blob/main/docs/03_architecture/03_communication/01_doip_communication.rst)
- [SAE : correspondance couches diagnostiques, K-Line, J1850, DoCAN](https://saemobilus.sae.org/topics/electrical-electronics-and-avionics/on-board-diagnostics-obd)

**Ne pas recopier ni redistribuer des documents normatifs commerciaux** dans le dépôt : documenter les références, implémenter les interfaces publiques et utiliser les SDK licenciés des fabricants pour les mécanismes non ouverts.

## Identification DoIP UDP ciblée — 23 novembre 2026

Commande ajoutée : `CamaroDiag.J2534Bridge.exe --doip-discover --host 192.0.2.10 --timeout-ms 2000`. Il s'agit d'une **requête d'identification ISO 13400-2 type 0x0001 envoyée en UDP unicast** à l'adresse IP explicitement fournie. Le client accepte la réponse de même adresse source (type 0x0004), avec VIN (17 octets), adresse logique, EID, GID, action supplémentaire et état de synchronisation facultatif. Aucun UDS n'est envoyé et l'identité annoncée n'est pas une preuve d'authenticité ni de programmation. L'exemple IP appartient à la plage de documentation, pas à un véhicule réel.

La découverte par diffusion explicite est maintenant ajoutée avec \`--broadcast-ip\` (pas d'adresse par défaut). L'écoute des annonces spontanées, le support TLS/OEM et la qualification de passerelle réelle restent à réaliser. Les tests de parsing ont été ajoutés, **pas exécutés**.

La commande de découverte ciblée accepte également `--vin 12345678901234567` pour le message ISO 13400-2 type `0x0003`, ou `--eid 010203040506` pour `0x0002`. Ces variantes ne changent pas le caractère non authentifié de la réponse UDP ; un VIN déclaré indisponible (motif tout à zéro ou `FF`) reste inconnu et n'est jamais fabriqué.

Exemple broadcast limité dans le temps : `CamaroDiag.J2534Bridge.exe --doip-discover --broadcast-ip 192.0.2.255 --timeout-ms 2000`. La destination doit être **la bonne adresse de diffusion du réseau véhicule**, choisie par l'opérateur ; cette commande n'est pas une analyse universelle du réseau. Le collecteur retourne au plus 32 identités par adresse IP et adresse logique, ignore les paquets invalides et signale l'absence de réponse. Le traitement de plusieurs annonces et leur authenticité matérielle restent à qualifier.

## KWP2000 et réception générique J2534 — 23 novembre 2026

Le décodeur ISO 14230 des **trames physiques complètes** accepte les charges utiles de 1 à 255 octets, les longueurs codées dans le format ou dans l'octet supplémentaire, contrôle l'adresse de destination, l'adresse de source et le checksum additif. Les autres formats d'adressage KWP ne sont **pas encore** pris en charge par ce décodeur : cela ne remplace ni l'initialisation K-Line ni les DLL J2534 du fabricant.

La CLI `--j2534-standard` propose `--mode receive` sans `--data`, pour lire un canal J2534 compatible sans émettre de requête. En `--mode exchange` (par défaut), `--data` reste obligatoire et l'avertissement puis la double confirmation sont conservés. Pour les DLL retournant **le message filaire complet**, `--kwp-rx-target 0xF1 --kwp-rx-source 0x33` active la validation optionnelle de l'en-tête physique KWP, de la longueur et du checksum. Les DLL qui suppriment ces octets ne doivent pas activer cette option. Ces fonctions ne constituent pas la qualification d'un adaptateur K-Line, J1850 ou d'une écriture ECU réelle.

## J1939-73 — décodage DM1/DM2

Le moteur peut afficher les messages diagnostiques normalisés **DM1 (PGN 0xFECA, défauts actifs)** et **DM2 (PGN 0xFECB, défauts précédemment actifs)** reçus par la CLI J1939. Après reconstruction éventuelle par J1939-21 TP, il extrait l'état et le clignotement des voyants, le SPN 19 bits, le FMI 5 bits, le compteur d'occurrences 7 bits et l'indicateur de méthode de conversion. Un champ de code à zéro n'est pas annoncé comme une panne. Lorsque le bit CM indique une méthode ancienne, le SPN n'est **pas** extrapolé depuis le codage moderne : CamaroDiag affiche `undecoded` et conserve les quatre octets bruts. Un décodeur spécifique aux méthodes antérieures reste à qualifier. Les messages incomplets sont refusés ; les 2 octets de remplissage FF d'un paquet direct sont tolérés. Il ne s'agit ni d'un effacement de DTC, ni d'une base de signification OEM pour tous les SPN, ni d'une qualification véhicule.

## DoIP TCP — Alive Check standard (23/11/2026)

La session DoIP traite désormais les requêtes `Alive Check` TCP de la passerelle (type `0x0007`, charge utile vide), tant durant l'activation de routage que pendant un échange UDS, par une réponse `0x0008` contenant **l'adresse logique réelle du testeur** sur 2 octets, avec la même version DoIP que la requête. Le codec refuse les charges utiles non conformes. Cette réponse de gestion de session **n'est pas** une opération UDS ni une autorisation de programmation. Tests ajoutés mais non exécutés ; ni TLS ni essais réseau physique certifiés.

### ISO-TP : adressage mixte CAN 11/29 bits

Le moteur traite également le format mixte à un octet N_AE : **identifiants CAN TX/RX distincts, sur 11 ou 29 bits** et **même N_AE dans les deux directions**, en lecture et en écriture de trames de transport. La CLI utilise `--mixed-address 0xA7` avec `--tx-id` et `--rx-id` (11 bits par défaut, ou 29 bits avec `--extended true`). Ne pas confondre avec `--tx-address` / `--rx-address` du format étendu (octets logiques éventuellement distincts). Exemple de syntaxe : `--isotp-exchange --adapter-index 0 --tx-id 18DA10F1 --rx-id 18DAF110 --baud 500000 --fd false --extended true --mixed-address 0xA7 --payload 22F190`. Les identifiants et N_AE sont illustratifs ; leur pertinence pour le calculateur reste à confirmer. Tests ajoutés, non exécutés.

### J1939-81 — identification passive NAME

Le décodeur `J1939AddressClaim` lit le PGN 0xEE00 diffusé sur CAN 29 bits, exige huit octets de NAME, expose identité, code fabricant, instance ECU, fonction, système véhicule et capacité d'adresse arbitraire. La CLI de réception peut afficher ce NAME pour l'adresse source ciblée. **Il n'effectue ni revendication d'adresse, ni résolution des conflits NAME, ni sélection automatique d'une adresse source**, prérequis restant à implémenter/qualifier avant émission J1939 sur bus réel. Tests ajoutés et non exécutés.

### DoIP sécurisé par TLS (ISO 13400-2)

Le client Windows expose `--doip-exchange --host gateway.example.org --tester 0E80 --ecu 1234 --tls true --payload 22F190`. Il ouvre le **port TCP 3496** et effectue une négociation TLS 1.2/1.3 avec vérification de chaîne et de nom d'hôte via les autorités de confiance de Windows, contrôle de révocation en ligne, et **sans désactivation du certificat ni repli implicite en clair**. `--tls false` est le mode historique TCP 13400 qui ne convient qu'aux passerelles autorisant explicitement ce mode. Les certificats privés OEM ou l'authentification réciproque avec certificat client nécessitent une configuration complémentaire non réalisée ; aucune connexion réelle n'est encore qualifiée. La confirmation des transmissions UDS reste obligatoire.

### SAE J1939-21 — requête d'un PGN (RQST, 0xEA00)

La CLI accepte désormais `--j1939-transport --adapter-index 0 --baud 250000 --source 0xF1 --target 0x10 --pgn 0xFECA --mode request`. Elle construit la trame standard RQST PGN 0xEA00 contenant le PGN demandé sur trois octets little-endian (`CA FE 00` pour DM1), l'envoie à la destination indiquée via J2534 CAN brut, puis tente de recevoir le PGN demandé et de le décoder lorsqu'il est pris en charge. **L'émetteur doit avoir une adresse SAE J1939-81 légitimement revendiquée** ; la CLI ne revendique pas automatiquement cette adresse. La requête est un envoi actif, déclenchant le consentement OK puis Oui/Non. Certains PGN peuvent produire un accusé de réception d'un autre PGN plutôt que la donnée demandée : la réponse ACK/NACK SAE J1939-21 (PGN 0xE800) est reconnue et corrélée au PGN demandé, mais l'interprétation détaillée des réponses OEM reste à qualifier. Aucun effacement DTC implicite ni support ECU réel revendiqué.

Les requêtes PGN peuvent recevoir une réponse standard **ACKM** (PGN 0xE800) plutôt que les données demandées : le logiciel distingue ACK (contrôle 0), NACK (1), accès refusé (2) et impossibilité de répondre (3), vérifie les trois octets du PGN référencé et ignore les ACK concurrents d'autres PGN. Un ACK positif n'est pas une preuve de réception du message demandé, ni la réussite d'une écriture ECU. Tests codec/session ajoutés, non exécutés.

### DoIP — écoute passive des annonces

`CamaroDiag.J2534Bridge.exe --doip-discover --listen true --timeout-ms 5000` écoute les annonces ISO 13400 UDP sur le port local 13400, pour une durée bornée, **sans émettre de paquet de découverte ni commande diagnostique**. L'écoute accepte jusqu'à 32 identités distinctes, ignore les paquets malformés, et exige que ce port UDP soit libre et autorisé par le pare-feu. L'absence de réponse ne signifie pas qu'aucun véhicule DoIP n'existe : toutes les passerelles n'annoncent pas spontanément leur présence. Les annonces restent non authentifiées et les essais réseau réels restent à faire.

### Invariant du mode J2534 sans émission

La commande `--j2534-standard --mode receive` interdit également `--kline-init` : `FIVE_BAUD_INIT` et `FAST_INIT` sont des actions de signalisation physique qui pourraient transmettre malgré l'absence de `--data`. Pour initialiser K-Line, sélectionner explicitement une transaction avec consentement opérateur ; ne pas assimiler l'ouverture d'un canal passif à la création d'une session K-Line prête à diagnostiquer.

La prise en charge du format mixte sur identifiants CAN **11 bits** a été confirmée dans les tableaux de cadrage ISO 15765-2 puis ajoutée aux paramètres et aux tests CLI. Le moteur applique le même N_AE sur SF/FF/CF/FC ; il ne présume aucune cartographie d'identifiants constructeur.

### DoIP — requêtes UDP d'état

`CamaroDiag.J2534Bridge.exe --doip-status --host 192.0.2.10 --timeout-ms 2000` consulte le statut de l'entité (requête 0x4001, réponse 0x4002), incluant type de nœud, nombre de sockets ouvertes/maximales et taille de message maximale facultative. Ajouter `--power-mode true` pour la requête 0x4003 / réponse 0x4004 : 0=pas prêt, 1=prêt, 2=non pris en charge. Il s'agit de messages **UDP de contrôle/observation**, sans service UDS ni écriture ECU ; le « mode prêt » annoncé ne valide ni l'autorisation de programmation ni l'état réel de la tension. Tests des formats ajoutés, non exécutés ; essais sur passerelle réelle non réalisés.


### SAE J1979-2 OBDonUDS — premiers services standardisés

Les commandes ISO-TP et DoIP acceptent maintenant une source d'opération unique `--obd-did 0xF40C` (lecture UDS 0x22 des plages d'émissions F400–F5FF et F700–F7FF), ou `--obd-dtc-mask 0xFF` (UDS ReadDTCInformation 0x19 sous-fonction 0x02). Ces options sont exclusives du `--payload` brut et de `--payload-file`, et ne sont **pas** acceptées en mode ISO-TP `receive` qui reste sans émission de requête. Le moteur conserve les services UDS génériques en lecture **et écriture** : le champ OBD de cette commande n'est pas une liste noire OEM.

Le décodeur lit les réponses `0x62` en conservant les octets du DID sans inventer d'unité ou de formule d'émissions ; il traite les réponses `0x59 0x02` selon le format UDS (masque de statut disponible, DTC 24 bits, octet de statut), avec rejet des trames incomplètes et contrôle du DID. Il ne remplace pas les définitions de paramètres SAE J1979DA, les tables DTC constructeur, ni les autres modes J1979-2 (2026). **Fonctions logicielles ajoutées, tests non exécutés, ni adaptateur ni véhicule qualifiés.**

Les réponses DoIP finales partagent désormais `UdsStandardServices.RequireResponse` avec les autres transports UDS : contrôle de SID, session, niveau SecurityAccess, routine, DID, DTC et compteur de bloc. Les réponses intermédiaires `0x7F ... 0x78` restent acceptées jusqu'au délai borné de session, sans limite arbitraire de 12 messages.


### SAE J1939-22 CAN-FD — première couche FEFF Multi-PG

Le codec `J1939FdMultiPg` sait composer et décoder les trames **FEFF Multi-PG PGN 0x2500**, en regroupant un ou plusieurs C-PG de format 4 octets d'en-tête (TOS, format de bande-annonce, PGN 18 bits, longueur) suivis de leurs données. La tranche couvre uniquement **TOS=2 / TF=0** (J1939 sans assurance de sécurité fonctionnelle/cyber) : les autres formats doivent être déclarés non pris en charge, pas lus comme des données ordinaires. Les trames CAN-FD respectent les DLC valides jusqu'à 64 octets ; la charge utile d'un seul C-PG ne dépasse pas 60 octets. Les PGN PDU2 sont diffusés, les PGN PDU1 peuvent être dirigés.

La commande Windows `--j1939-fd-multipg --adapter-index 0 --baud 500000 --data-baud 2000000 --brs true --source 0xF1 --target 0xFF --pgn 0xF004 --mode send --payload 01020304` tente une **émission** sur le canal brut `FD_CAN` du pilote J2534, avec deux confirmations opérateur avant envoi. `--mode receive --source 0xF1 --target 0x10` attend une trame provenant du pair, et `--pgn` peut filtrer le groupe contenu. Aucun chemin de réception ne génère de paquet diagnostique. L'interface et les timings CAN-FD/BRS dépendent effectivement du pilote et du matériel installés : aucun matériel réel testé.

**État actualisé** : FD.TP.CM/FD.TP.DT, FEFF, FBFF 11 bits et gestion initiale J1939-81 ont été ajoutés après la première tranche ; voir les sections de suivi ci-dessous. Restent non qualifiés : assurance data TF ≠ 0, comportement sur bus réel, surveillance continue J1939-81, sessions concurrentes et interopérabilité matérielle. Ce chantier ne doit pas être annoncé comme « J1939-22 intégral ». Les tests hors matériel Multi-PG sont intégrés au `BridgeSelfTests` mais **pas exécutés** sur le HEAD de cette session. La structure FEFF/TOS=2/TF=0 a été recoupée avec le projet public `juergenH87/python-can-j1939` et les descriptions SAE J1939-22, sans importer son code dans le dépôt.


### SAE J1939-22 — FD.TP pour messages segmentés (BAM et CMDT)

Une deuxième tranche J1939-22 ajoute les **trames FD.TP.CM (PGN 0x4D00) et FD.TP.DT (PGN 0x4E00)**, distinctes des transports J1939-21 classiques et des ISO-TP. Les en-têtes comportent numéro de session (4 bits), type de commande, tailles/numéros de segments sur 24 bits et PGN transporté. Les segments FD.TP.DT portent au maximum 60 octets utiles ; seul DTFI=0 est actuellement reconnu. Tests codec et cas malformés enregistrés dans `BridgeSelfTests`, mais non exécutés.

**BAM broadcast PDU2** : commande `--j1939-fd-bam --adapter-index 0 --baud 500000 --source 0xF1 --target 0xFF --pgn 0xF004 --session 2 --mode send --payload-file image.bin`. Le moteur segmente les données de plus de 60 octets en trames FD.TP.DT, espace les émissions d'au moins 10 ms, envoie le message de fin EOM_STATUS, et sait reconstituer le flux en mode réception avec contrôle de séquence, de longueur, de session et de fin. **Ce n'est pas un flash ECU** : l'envoi d'un PGN arbitraire ne remplace pas protocole constructeur, sauvegarde, carte mémoire et sécurité. Les transmissions impliquent avertissement OK puis consentement Oui/Non. Ne pas utiliser l'exemple `image.bin` comme permission d'écrire un secteur.

**CMDT dirigé RTS/CTS** : commande `--j1939-fd-cmdt --adapter-index 0 --baud 500000 --source 0xF1 --target 0x10 --pgn 0xEF00 --session 1 --window 2 --mode send --payload-file payload.bin`. Le moteur implémente RTS, fenêtres CTS et pauses bornées, DT numérotés, EOM_STATUS et EOM_ACK et l'abandon sur expiration ; en réception il émet les CTS nécessaires à la reconstruction puis répond par EOM_ACK. `--mode receive` ne transmet aucune requête de données (mais les CTS/EOM_ACK de la session de transport ne sont pas « passifs »). Les réponses/segments d'autres adresses ou sessions sont filtrés. Le PGN transporté dirigé doit être PDU1 et l'adresse source locale doit déjà être légitimement revendiquée selon J1939-81. Les `--data-baud` et `--brs` sont optionnels et effectifs uniquement si le pilote J2534 FD_CAN les gère.

**Limites :** C-PG FEFF Multi-PG, BAM et CMDT logiciels restent **non validés sur matériel**, aucune preuve de bit timing ni des variantes SAE/AUTOSAR complètes ; sécurité des données TF/assurance, session ownership multi-pairs, concurrence des BAM, contrôles fonctionnels et événements EOM sur véhicule, revendication active J1939-81 et procédures constructeur sont encore à qualifier. Le catalogue ne doit pas déclarer J1939-22 intégral ni programmation ECU physiquement opérationnelle.


### Compléments standards du 23/11/2026 — KWP, UDS, J2534 et DoIP

**ISO 14230 KWP2000** : le codec existant à adressage physique `0x80` est complété par un codec d'adressage fonctionnel `0xC0`, incluant longueur courte (1–63), longueur étendue (64–255), adresses logiques et checksum additif. La CLI `--j2534-standard` peut générer le message avec `--kwp-service`, `--kwp-tx-target`, `--kwp-tx-source` et éventuellement `--kwp-functional true`, plutôt que demander une trame physique pré-assemblée. Le parsing RX fonctionnel est opt-in avec `--kwp-rx-functional true`, en gardant les adresses RX explicites ; le format RX réel dépend du mode de réponse de l'ECU. Les fonctions d'initialisation électrique 5 bauds / fast init sont toujours déléguées au pilote J2534 et nécessitent la double confirmation avant échange ; tests de formats ajoutés, pas de validation physique.

**ISO 14229 UDS** : ajout des constructeurs `ReadScalingDataByIdentifier` (0x24), `InputOutputControlByIdentifier` (0x2F), `CommunicationControl` (0x28), `ControlDTCSetting` (0x85), `ReadDataByPeriodicIdentifier` (0x2A). Aucun service n'octroie une sécurité, une autorisation OEM ou la modification de secteurs sans sauvegarde. La corrélation des réponses DID inclut 0x24. La commande ISO-TP peut sélectionner `--uds-validate true` pour attendre une réponse finale après des NRC `7F SID 78` et refuser les réponses d'autres SID/DID ; les commandes SAE J1979-2 activent cette validation automatiquement. Le mode brut ISO-TP demeure disponible pour les protocoles propriétaires non-UDS ; aucune whitelist d'écriture introduite. Tests NRC, DID, erreurs et paramètres CLI ajoutés.

**SAE J2534-2 / SW-CAN** : correction documentée des IDs de protocole : `SW_ISO15765_PS=0x8007` et `SW_CAN_PS=0x8008` (SAE J2411 / GMLAN). Les deux canaux optionnels sont reconnus et sélectionnables par `--j2534-standard --protocol-id`, mais doivent être réellement fournis par le pilote et le matériel. Le mode `receive` n'accepte ni initialisation K-Line ni `FLOW_CONTROL_FILTER` (ce filtre pourrait transmettre automatiquement). La lecture J2534 ignore les échos/indications du pilote en respectant un délai global et ne confond plus ces événements avec une absence de réponse ECU.

**SAE J1939-22** : un délai global et des délais par phase encadrent FD.TP BAM et CMDT, avec CTS/DT/EOM et EOM_ACK. Les transferts étendus peuvent recevoir une limite explicite jusqu'à 600 000 ms, sans autoriser une attente élémentaire J2534 supérieure à 30 secondes. Un CMDT erroné envoie un Abort corrélé au lieu de produire un acquittement de réussite ; un abort du pair interrompt les données. Tests de faux CAN et limites ajoutés.

**SAE J1939-81** : un `--name` explicite (64 bits hexadécimaux) peut déclencher la revendication initiale de source sur le même lien CAN 29 bits, puis 250 ms d'observation des NAME concurrents. En cas de perte, envoi du message Cannot Claim (SA=FE), aucune application PGN émise ; requêtes de PGN Address Claimed traitées dans la fenêtre d'observation. **Ceci n'est pas une gestion persistante complète J1939-81** : pas de sélection automatique d'une autre adresse, pas de surveillance des conflits après le lancement de la transaction ni garantie d'identité constructeur ; l'écho matériel peut masquer un NAME identique. Qualification sur réseau et matériel requise.

**DoIP** : client TCP/TLS configurable en version 0x02 ou 0x03 ; messages UDS jusqu'à 1 Mio en mémoire, entrée `--payload-file`, capture binaire `--rx-file` créée sans écrasement et vérifiée par SHA-256, refus `GenericNegativeAck 0x0000` interprété, certificat TLS client du magasin Windows sélectionné par empreinte SHA1 (aucun secret/PFX passé en ligne de commande). La validation du certificat serveur et du nom DNS reste obligatoire. Le plafond d'1 Mio est une limite d'implémentation, pas celle de la norme. Les échanges TLS réels, la compatibilité avec les certificats OEM, les pilotes physiques et toute programmation ECU restent **non qualifiés**.

Les tests mentionnés sont **ajoutés, non exécutés** sur la branche à ce stade. Aucune qualification physique, aucun succès de flash/restauration ou de conformité intégrale des standards n'est revendiqué.


### Correctifs de conformité complémentaires — 23/11/2026

**ISO 15765-4 / SAE J1979-2 : découverte fonctionnelle** : `--obd-discover-can --adapter-index 0 --baud 500000 --timeout-ms 2500` émet une seule trame CAN classique 11 bits à l'ID fonctionnel `0x7DF`, contenant `03 22 F8 10 CC CC CC CC` (DID F810). Les réponses physiques `0x7E8` à `0x7EF` sont collectées et dédoublonnées pendant une durée bornée, en vérifiant `04 62 F8 10 XX` et en n'interprétant que la valeur XX=01 comme une identification positive OBDonUDS. Les autres trames ne deviennent pas des résultats positifs. Il s'agit uniquement de la détection fonctionnelle **CAN 11 bits à ce débit choisi**, et non de la détection générale des variantes CAN 29 bits, de tous les ECU, des autres protocoles d'émissions ou d'une autorisation d'écriture. Les tests utilisent un faux CAN. Le code reconnaît également le protocole DoIP v0x04 de l'édition ISO 13400-2:2025 et refuse les versions réservées.

**ISO 13400 DoIP** : les réponses de routage code 0x11 « confirmation véhicule requise » n'autorisent jamais la transmission diagnostique. Le client attend un nouveau code 0x10 dans la fenêtre de connexion initiale et distingue les refus de routage (p. ex. authentification manquante code 0x04). Le contrôle du message ACK diagnostique peut comparer le préfixe de données de la requête courante lorsque le gateway renvoie un écho, ce qui évite d'accepter un ancien ACK appartenant à une transaction précédente.

**SAE J2534-2 GM SW-CAN** : l'identifiant optionnel SW_CAN_PS=0x8008 est distinct de SW_ISO15765_PS=0x8007 ; le paramètre `SW_CAN_HS_DATA_RATE=0x8010` peut être fourni explicitement via `--j2534-standard --protocol-id 0x8008 --sw-can-hs-baud ...`. Ce changement de mode est demandé au vrai pilote par `PassThruIoctl SET_CONFIG` et n'est autorisé qu'en mode exchange après double confirmation. Aucun mode haute vitesse ou transceiver physique n'est simulé.

**ISO 14230 + J2534 : checksum** : sur le canal K-Line J2534 normal, le pilote ajoute et vérifie le checksum ; le constructeur d'en-tête KWP retire donc l'octet checksum avant `PassThruWriteMsgs`. En mode explicite `--flags 0x200` (`CHECKSUM_DISABLED` / `ISO9141_NO_CHECKSUM`), le logiciel envoie le checksum calculé et le contrôle réellement sur RX. En mode automatique, le parseur valide en-tête, longueur et adresses de la trame sans checksum fournie par le pilote ; il ne prétend pas avoir vu ou contrôlé physiquement cet octet, délégué au pilote. Tests régressifs sur les deux modes et le rejet des checksums invalides.

**ISO 14229 / SAE J1939-22** : nouveaux messages normalisés UDS 0x24, 0x28, 0x2A, 0x2F et 0x85 avec tests d'assemblage ; le routage UDS strict ISO-TP attend les réponses finales après les NRC 0x78. La variante J1939-22 FD.TP dirigée signale immédiatement les Abort du pair pendant les segments et émet un Abort plutôt qu'un EOM_ACK face à un message de fin incohérent.

Toutes les fonctions ci-dessus exigent encore **compilation Windows, auto-tests intégrés, pilotes conformes et validation physique**. Le catalogue distingue logiciel présent de programmation ECU réellement qualifiée. Ne déclarer ni standard intégralement couvert ni restauration ECU garantie.

### SAE J1939-22 — Multi-PG FEFF/FBFF et protections de trames (mise à jour)

- `--j1939-fd-multipg --fbff true --source 0x80 --target 0xFF --mode send --pgn 0xF004 --payload 112233 --adapter-index 0 --baud 500000` utilise **CAN-FD 11 bits**, l'identifiant CAN correspondant à la source annoncée, et une diffusion globale. `--fbff false` (par défaut) conserve **FEFF CAN-FD 29 bits**.
- `--groups '0xF004:010203;0xFECA:C7FF'` compose plusieurs C-PG en un seul paquet FEFF ou FBFF, avec vérification de la taille totale CAN-FD avant l'accès au pilote ; la forme `--pgn` / `--payload` reste disponible pour un seul C-PG. Le hash de confirmation porte sur les octets effectivement transmis dans la charge utile CAN-FD, pas uniquement sur un élément du groupe.
- Les réassemblages FD.TP BAM et CMDT contrôlent désormais aussi le remplissage `0xFF` après le dernier octet utile annoncé, sans supprimer des octets légitimes `0xFF` présents dans les données. En CMDT, un remplissage incorrect provoque un Abort au lieu d'un EOM_ACK.
- Codage et tests hors matériel ajoutés, mais **pas de preuve de conformité intégrale ni de qualification physique**. La mise en circulation d'un émetteur J1939-81 suppose une identité et une adresse source authentiques et autorisées.


### SAE J1979-2 / WWH-OBD — données étendues et diagnostic réglementé

Les commandes ISO-TP et DoIP `--obd-did` prennent en charge les familles de DIDs législatives **F400–F5FF** (données instantanées), **F600–F6FF** (surveillance historique, non obligatoirement présente), **F700–F7FF** (autres données OBD) et **F800–F8FF** (informations véhicule, dont `F810` pour l'identification du protocole). La conversion physique de chaque octet n'est pas inventée : elle dépend de SAE J1979DA et de la réponse du véhicule. Les DIDs génériques non législatifs restent accessibles par le service UDS brut `0x22`.

DTC réglementés, en plus du service générique `19 02` :
- `--obd-wwh-status 0x08 --obd-wwh-severity 0xFF` construit la requête WWH-OBD `19 42 33 FF 08` pour les DTC d'émissions (groupe fonctionnel 0x33), avec filtres de sévérité/statut explicites. Le décodage de `59 42` préserve le masque de statut, le masque de sévérité, le format DTC, la sévérité et le statut de chaque code 24 bits.
- `--obd-wwh-permanent` construit `19 55 33` pour les codes permanents. Le décodage `59 55` conserve format et statut sans inventer de sévérité absente de ce format.
- `--obd-readiness-group 0x06` construit `19 56 33 06` et décode le résultat, en vérifiant le groupe de réponse et les enregistrements DTC complets. La valeur du groupe est un exemple : **son sens et sa disponibilité doivent être vérifiés dans la table normative et sur l'ECU**.

Ces sélecteurs sont mutuellement exclusifs des autres sources de requêtes ; le mode ISO-TP `receive` les refuse. Le contrôle UDS de la réponse compare maintenant également l'identifiant du groupe fonctionnel WWH 0x33 et celui du readiness group 0x56. **Tests unitaires ajoutés mais non exécutés sur le dernier HEAD**, aucune qualification fonctionnelle OBDonUDS du véhicule.


**UDS ISO 14229 0x19 / 0x04 — instantanés de DTC (snapshot)** : la CLI ISO-TP ou DoIP accepte `--obd-snapshot 0x123456 --obd-snapshot-record 0x01` et construit `19 04 12 34 56 01`. Le numéro d'enregistrement 0x01 est la valeur par défaut ; 0xFF permet de demander tous les enregistrements à un calculateur qui le prend en charge. Le code vérifie l'identité du DTC sur 24 bits, extrait son statut et conserve **les octets bruts des valeurs DID** : aucune taille/unité des paramètres de l'ECU n'est devinée. La sous-fonction 0x19/0x06 (données étendues) reste disponible en UDS brut. La réponse est soumise à la validation générique de corrélation DTC, et le mode passif interdit la demande. Tests ajoutés mais non exécutés ; voir SAE J1979-2, ISO 14229 et données ODX du calculateur pour interpréter les mesures.


**ISO 14229 / SAE J1979-2 : données étendues DTC `0x19/0x06`**. Les commandes ISO-TP et DoIP comprennent `--obd-extended-dtc 0x123456 --obd-extended-record 0x90`. Le numéro `0x90` sélectionne un enregistrement réglementé ; sans `--obd-extended-record`, la valeur `0xFE` demande tous les enregistrements OBD réglementés. L'ECU peut refuser une requête non prise en charge. Le décodeur extrait le DTC 24 bits, son statut et le reste des enregistrements **en octets bruts** : leur géométrie et leurs unités varient selon le calculateur. Les réponses `59 06` d'un autre DTC sont rejetées. L'option ne transmet pas en mode ISO-TP `receive`. Tests de codec, CLI et corrélation ajoutés et non exécutés.


### Compléments de garde-fous (23 novembre 2026)

- En SAE J1939-22, le canal J2534 `FD_CAN` est ouvert en mode **11 bits** pour `--fbff true`, et en mode **29 bits** pour FEFF/FD.TP. Les tests de parseur vérifient les drapeaux de connexion demandés au pilote. Le mode `--groups '0xEF00:;0xFECA:11'` représente explicitement un C-PG sans octet utile : l'implémentation ne rajoute pas de données fictives.
- La validation UDS `19 04` et `19 06` compare aussi l'identifiant du **record effectivement retourné** lorsqu'un numéro de record spécifique est demandé. Les sélecteurs `FF` (tous les enregistrements) et `FE` (tous les enregistrements étendus réglementés pour `19 06`) restent des demandes multi-enregistrements et ne sont pas rejetés sur une seule valeur de record de la réponse.
- Le dernier segment de chaque transfert J1939-22 FD.TP vérifie désormais son remplissage `FF` après la quantité utile annoncée. En CMDT, l'incohérence entraîne un Abort explicite.
- Ces changements sont enregistrés et accompagnés de **tests écrits non exécutés**. Ils ne valent pas une recette sur ECU ni une compilation validée par Windows.


**Délais FD.TP BAM et CMDT** : la réception d'un message peut attendre jusqu'à 600 000 ms si l'opérateur a demandé ce délai. Les appels unitaires au pilote J2534 restent limités à 30 secondes ; lors de l'attente initiale non segmentée (annonce BAM ou RTS dirigé), l'expiration d'une de ces tranches est désormais traitée comme une étape d'attente, pas comme l'échec du délai global. En revanche, les **délais courts normatifs des phases CTS/DT/EOM** ne sont pas prolongés par ce mécanisme. Deux tests de faux adaptateur simulent explicitement une expiration intermédiaire suivie d'une séquence recevable. Tests commités, **non exécutés** ; la précision des attentes et la gestion des interruptions restent à valider sur pilote et bus CAN-FD réels.


### J1939-22 FEFF / FBFF et C-PG multiples — complément 23/11/2026

La couche Multi-PG sait maintenant composer/décoder deux formats de CAN-FD : **FEFF**, trame CAN 29 bits PGN 0x2500 avec destination, et **FBFF**, trame CAN **11 bits** dont l'identifiant est l'adresse source revendiquée (diffusion uniquement). La CLI `--j1939-fd-multipg` accepte `--fbff true` (envoi vers `--target 0xFF`, priorité 0) ou le mode FEFF par défaut. Les filtres/flags de connexion J2534 respectent le mode 11 bits ou 29 bits choisi. Les deux formats conservent la même limitation TOS=2/TF=0 sans assurance OEM.

L'option `--groups "0xF004:010203;0xFECA:C7FF"` permet de transmettre **plusieurs C-PG** dans une seule trame FEFF/FBFF, avec refus avant ouverture du pilote si la longueur combinée dépasse 64 octets ou si la destination du PGN est incompatible. Elle est exclusive de `--payload`, `--payload-file` et `--pgn` et ne s'utilise qu'en mode send. La confirmation affiche les PGN, le nombre d'octets réellement émis et l'empreinte SHA-256 du contenu CAN-FD. Les tests de construction et parsing sont enregistrés mais non encore exécutés.

### ISO 14229 — plusieurs DID en une requête UDS

La pile UDS fournit `ReadDids` et `DecodeDids` pour coder `22 DID1 DID2 ...` et vérifier les réponses `62 DID1 DATA1 DID2 DATA2 ...`. Puisque les longueurs des données d'un DID sont **spécifiques aux définitions du véhicule**, les longueurs doivent être fournies par l'utilisateur/ODX ; elles ne sont jamais déduites arbitrairement de l'identifiant. Exemples CLI : `--uds-dids "0xF190:17,0xF187:4"` sur les commandes ISO-TP et DoIP, avec confirmation d'émission habituelle. Le décodeur refuse les DID réordonnés, les données tronquées et les octets supplémentaires. Le mode ISO-TP `receive` reste sans émission. Tests nouveaux, non exécutés.


### Lecture mémoire UDS standard, ALFI et contrôle des acquisitions — complément 23/11/2026

Le moteur `NativeUdsUploadProtocol` construit une requête `0x35 RequestUpload`, négocie le `maxNumberOfBlockLength` via une réponse `0x75`, reçoit les blocs `0x76 TransferData` avec numéros stricts, y compris le retour du compteur `FF → 00`, et termine par `0x37 / 0x77`. Il vérifie le nombre d'octets exactement demandé, les limites négociées, les dépassements de région et les NRC du calculateur. Il ne transmet aucun service d'effacement ou de programmation. `UploadTwiceAsync` effectue **deux transactions de lecture indépendantes**, compare bit à bit les octets et calcule le SHA-256 seulement si elles correspondent ; le refus d'une différence interdit de présenter un résultat comme sauvegarde vérifiée.

Le constructeur ISO 14229 `RequestDownload` / `RequestUpload` prend désormais en charge des champs explicites d'adresse et taille de **1 à 8 octets** (ALFI), tout en préservant la vérification des limites 32 bits dans les anciennes signatures. `DecodeTransferParameters` valide les réponses `0x74 / 0x75` selon longueur big-endian, nibble réservé et capacité de bloc incluant SID/compteur ; le moteur natif de téléchargement réutilise le même décodeur. Un test de régression refuse l'enroulement d'une adresse 32 bits après la généralisation ALFI.

**Non terminé pour C2 :** le moteur de lecture générique n'est pas encore associé à une cartographie mémoire qualifiée de chaque ECU, aux vrais pilotes et à l'interface utilisateur complète. Le retour `0x77` et le SHA-256 ne prouvent pas à eux seuls la validité de toute l'image ECU. Le stockage physique durable dans une archive, l'identité VIN/OSID, l'exhaustivité des secteurs, une relecture indépendante et un chemin de restauration réellement vérifié sont des conditions obligatoires distinctes. Tests enregistrés, pas de véhicule ni de calculateur physique testé.

### SAE J1850 et J2534 — trames brutes

Ajout d'un codec CRC-8 SAE J1850 `J1850Crc` (polynôme `0x1D`, initialisation `0xFF`, XOR final `0xFF`, référence ASCII `123456789` → `0x4B`) et de tests sur la corruption. Ce codec est un outil **hors ligne** : les pilotes PassThru J1850 VPW/PWM peuvent calculer ou supprimer eux-mêmes l'octet CRC ; **ne pas l'ajouter automatiquement** à leurs messages sans qualification du pilote. Ce travail ne couvre pas les formes d'impulsions, l'arbitrage VPW/PWM ni la synchronisation électrique.

La réception CAN/J2534 brute vérifie maintenant aussi le bit de statut `CAN_29BIT_ID` de la DLL, en plus du DLC, du format CAN-FD et des plages d'ID ; une trame reçue avec un mode 11/29 bits contradictoire est rejetée. Des tests de régression couvrent ces différences ; vérifier les flags renvoyés par chaque pilote réel.


La commande J2534 générique possède désormais l'option `--j1850-wire-crc true`, réservée à SAE J1850 VPW/PWM ; elle valide uniquement les octets de réponse que le pilote a livrés, lorsque ce pilote **conserve le CRC physique dans les trames RX**. Sans cette garantie fournisseur, laisser l'option à false : un pilote qui a retiré le CRC présenterait forcément un faux échec. L'option ne reconstruit ni les durées d'impulsion, ni la collision de bus, et ne modifie jamais les trames TX.


## Tranche SAE J1979 — services classiques et réponses (23/11/2026)

Sur la branche unique de la PR #95, des codecs **logiciels** supplémentaires sont présents dans le pont Windows :

- `ObdJ1979Transaction` : construction des services 01..0A, réponses positives, NRC et corrélation facultative de l'identifiant. Le codec ne bloque pas les commandes mutatrices 04/08, qui restent soumises au consentement utilisateur par la couche de transaction physique.
- `ObdJ1979PidSupport` : bitmaps de 32 PID pour les modes 01/02, pages 00..E0, bit de page suivante, et numéro de freeze frame pour le service 02 ; refus des réponses incomplètes, incorrectes ou hors plage.
- `ObdJ1979Vin` : récupération stricte des 17 caractères du VIN depuis la réponse ISO-TP **déjà réassemblée** `49 02 01 + 17 octets ASCII` ; il ne représente pas une reconstruction de messages non-CAN ou J1979-2.
- `ObdJ1979Dtc` : conversion des paires de deux octets SAE J2012 en codes P/C/B/U, sans prétendre interpréter le contenu spécifique d'un constructeur ou la structure de chacune des réponses de service 03/07/0A.
- Tests offline ajoutés et enregistrés dans `BridgeSelfTests`. **Non exécutés dans cette tranche**, tant que le chantier protocole n'est pas clos ; aucun build vert, qualification matérielle, conformité exhaustive J1979 ou nouveau transport physique n'est revendiqué.

**À développer** : construction complète des requêtes par service et version normative, profil des PID/InfoType et métadonnées J1979DA, décodage des réponses distinctes des services 03/07/0A par transport, recherche multi-ECU et gestion des timeouts, raccordement aux liens SAE J1850/K-Line/CAN et à l'interface, puis tests interopérabilité et matériel. Les codecs n'accordent aucune capacité de programmation OEM.


## SAE J1979 : découverte physique des PID (lot protocole 23/11/2026)

La commande Windows `--j1979-pids` utilise un canal **CAN brut SAE J2534** avec un moteur ISO-TP (sans double segmentation). Elle demande explicitement l'adaptateur, le débit et une paire physique TX/RX. Le moteur envoie successivement les pages 00, 20, 40, ... E0 des PID annoncés, sur **un ECU**; un échec de réponse n'est pas converti en liste vide. Les requêtes sont uniquement celles des modes 01 et 02, et un index de freeze frame est exigé pour le mode 02 lorsqu'il diffère de zéro.

Exemple de syntaxe, adresses illustratives (pas de validation sur véhicule) :

```text
CamaroDiag.J2534Bridge.exe --j1979-pids --adapter-index 0 --tx-id 7E0 --rx-id 7E8 --baud 500000 --j1979-mode 1 --timeout-ms 5000
CamaroDiag.J2534Bridge.exe --j1979-pids --adapter-index 0 --tx-id 7E0 --rx-id 7E8 --baud 500000 --j1979-mode 2 --freeze-frame 0
CamaroDiag.J2534Bridge.exe --isotp-exchange --adapter-index 0 --tx-id 7E0 --rx-id 7E8 --baud 500000 --fd false --payload 0902 --j1979-decode true
```

`--j1979-decode true` interprète le message applicatif revenu du lien ISO-TP pour les pages de disponibilité PID, le VIN Mode 09 et les paires DTC classiques 03/07/0A, et laisse les valeurs sans définition normative de PID à l'état **brut**. Elle refuse les erreurs de corrélation, et une réponse négative reste négative. Pour les autres services 01..0A, elle ne prétend pas fournir les échelles J1979DA ou vérifier l'effet d'une commande. La commande générique ISO-TP garde la double confirmation existante avant toute requête sortante, y compris mutatrice.

**Limites** : aucune découverte multi-ECU fonctionnelle ni J1850/K-Line dans cette nouvelle commande, aucune preuve de qualification VCI/ECU, aucune intégration UI Android. La couverture des modes J1979 ainsi que J1979-2 reste incomplète ; les autotests associés sont écrits mais non exécutés lors de ce lot, faute de campagne de clôture.


### Première tranche de valeurs SAE J1979 normalisées

Le décodage du flux applicatif `--isotp-exchange --j1979-decode true` inclut désormais les PID classiques **05** (température), **06/07/08/09** (corrections carburant par banque), **0C** (régime), **0D** (vitesse), **10** (débit d'air), **11** (papillon) et **52** (pourcentage éthanol) en modes 01/02 lorsque la longueur et l'identifiant de la réponse correspondent à la requête. Les formules connues sont appliquées ; aucune lecture physique, présence de capteur, adaptation E85 ni capacité d'écriture n'est déduite de l'existence du décodeur. Les PID sans formule implémentée sont toujours affichés **bruts**. Les réponses absentes, tronquées ou incohérentes ne reçoivent aucune valeur par défaut.

Des tests offline positifs/négatifs ont été **écrits, non exécutés** sur ce nouveau lot. Le reste des valeurs SAE J1979DA par édition et profil reste à développer, ainsi que leur validation sur calculateur réel.

### Découverte fonctionnelle J1979 sur CAN 11 bits

Une deuxième commande peut envoyer **une seule** requête SAE J1979 Mode 01 PID 00 sur l'ID fonctionnel CAN `0x7DF`, puis collecter les réponses standard `0x7E8..0x7EF` pendant une fenêtre bornée. Les doublons et les trames étrangères ne deviennent pas de nouveaux ECU ; seules les réponses single-frame ISO-TP avec le PID 00 et les 4 octets de bitmap sont retenues. Elle ne déclare pas que tous les autres PID sont disponibles et n'identifie pas le VIN.

```text
CamaroDiag.J2534Bridge.exe --j1979-discover-can --adapter-index 0 --baud 500000 --timeout-ms 2500
```

La commande s'appuie sur le pilote fournisseur SAE J2534 et sur CAN brut classique, pas CAN-FD et pas sur une simulation ; elle n'a cependant **pas été qualifiée sur adaptateur ni véhicule**. Les preuves tests sont pour le moment uniquement des vecteurs offline écrits, non exécutés. Pour lire les pages suivantes d'un ECU ayant répondu, utiliser ensuite la commande `--j1979-pids` avec une paire TX/RX physique explicitement vérifiée.


## SAE J1979 — corrections d'identification et DTC CAN (23/11/2026)

- Après réassemblage ISO-TP, les réponses CAN des modes **03, 07 et 0A** comportent le nombre de codes défaut avant les paires SAE J2012. Le traitement `ObdJ1979Dtc.DecodeCanCounted` vérifie ce nombre, distingue les paires de remplissage nulles et refuse les codes surnuméraires ou tronqués. Cette interprétation est réservée au chemin CAN ; les variantes historiques ISO 9141, KWP2000 et J1850 ne sont pas traitées comme des trames CAN.
- En **mode 09**, les InfoTypes disponibles (00), le numéro de calibration CALID (04, 16 caractères ASCII par valeur), le CVN (06, quatre octets par valeur) et le VIN (02) disposent de décodeurs typés pour le flux applicatif CAN déjà réassemblé. Le nombre d'éléments rapporté dans les réponses est contrôlé. Un CVN n'est pas une preuve d'authenticité ou de conformité constructeur de la programmation.
- Le sélecteur `--j1979-decode true` n'interprète plus une commande UDS/J1979-2 comme une commande OBD-II classique. Les commandes arbitraires de lecture/écriture UDS restent disponibles via les modes de transport standards avec le consentement explicite requis ; le décodage ne constitue pas une nouvelle interdiction d'écriture.
- **Qualification** : tests unitaires ajoutés, pas encore exécutés dans cette tranche. La couverture SAE J1979 n'est pas complète (versions SAE J1979DA, tous les PID/monitor IDs et services, transports J1850/K-Line, expérience véhicule). La conformité logicielle ou matérielle ne peut pas être déclarée sur ces seuls codecs.

Référence de format : SAE J1979 (éditions historiques) et documentation de la procédure d'essai des modes OBD-II ; l'adaptation aux éditions récentes doit être validée séparément.
