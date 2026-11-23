# Normes automobiles, recette après écriture et récupération des calculateurs

**Chantier #110, PR #95. Ce document décrit l'état du CODE et non une certification sur véhicule.**
Ne pas confondre : norme publiée, codec validé par tests unitaires, transport réellement relié à une interface, service diagnostique ECU, programmation validée sur banc et procédure de récupération éprouvée.

## Standards applicables

| Couche / standard | Déjà en place ou ajouté | Non couvert / condition manquante |
|---|---|---|
| UDS ISO 14229 | Client diagnostics en lecture, session autorisée, parser NRC, constructeur de séquence 0x34/0x36/0x37 pour bootloader compatible, parseurs DoIP | Séquences complètes 0x31 OEM erase/checksum/dependencies, SecurityAccess OEM, installation du loader et reset boot/application par famille |
| ISO-TP ISO 15765-2, CAN classique | Pont J2534 existant avec filtre flow-control et CAN 11/29 bits, observateur/connecteur | Qualification ECU réel, géométrie et bootloader |
| CAN-FD ISO 11898 / ISO 15765-2:2024 | **Nouveau** `IsoTpCanFdSession` envoi/réception SF/FF/CF/FC (escape FF 32 bits, BS, STmin, CTS/WAIT/OVFLW, séquences) ; `IsoTpJ2534RawCanLink` sur canal brut FD_CAN 0x8011, DLC 8–64 et option BRS ; débit de phase de données par `SET_CONFIG 0x805C` | Qualification réelle de l'adaptateur, timing µs précis sous Windows, adresses ISO-TP mixtes, éventuelles limites du firmware J2534-2 ; pas de garantie de flash ECU |
| DoIP ISO 13400-2 / UDS ISO 14229-5 | **Nouveau** encodeur/décodeur normatif, adresse logique, routage 0x0005/0x0006, diagnostic 0x8001 et ACK/NACK ; client TCP 13400 bidirectionnel (lecture et écriture UDS, avec acquittements corrélés) sur IP explicitement fourni | Découverte UDP, TLS DoIP si requis, authentification OEM, diagnostics constructeur, intégration UI et essais sur vraie passerelle. Les services d'écriture sont transmis sans liste noire applicative ; la procédure flash physique et ses paramètres constructeur restent à qualifier. |
| K-Line ISO 9141-2 | J2534-1 : ouverture de canal réel, Tx/Rx de trames et FIVE_BAUD_INIT via ioctl du fabricant ; essais sur véhicule à faire | Activation 5 bauds et synchronisation / timing par adaptateur, validation lecture/trames sur véhicule |
| KWP2000 ISO 14230 | Inspecteur de réponse physique côté Android ; **nouveau** encodage KWP à longueur courte/longue et somme additive côté PC ; numéro protocole J2534 standard | Message sequencing/correlation avancée et sessions physiques K-Line sur pilote réel |
| SAE J1850 VPW / PWM | Identifiants J2534-1 normatifs et sélection de la bonne couche | Le transport générique J2534 gère désormais émission/réception via la DLL ; vérification du driver réel, adresses/CRC et tests physiques nécessaires |
| SAE J2534-1/J2534-2 | Chargement des DLL, validation ABI, pont PassThru sur CAN ISO15765 et SW-CAN | Les canaux bidirectionnels génériques K-Line, J1850, CAN/FD ont été ajoutés ; qualifier chaque DLL fournisseur, ses extensions, filtres et paramètres spécifiques |
| SAE J1979, J1979-2 (OBDonUDS) | OBD-II modes standards en partie, DTC et PID véhicule existants | OBDonUDS complet, couverture historique/non CAN et tests de conformité |
| SAE J1939-21 | **Nouveau** identification PGN 29 bits, message direct (1–8 octets), `J1939ClassicTpSession` bidirectionnel BAM et RTS/CTS, TP.DT, EOM_ACK, gestion des adresses et numéros de séquence ; CLI J2534 CAN brut | Address-claim SAE J1939-81, synchronisation de sessions concurrentes, tests sur ECU réel, DTC SAE J1939-73 et profils applicatifs |\n| SAE J1939-22 CAN-FD | DLC CAN FD normalisé et accès raw FD_CAN ; non équivalent au TP classique | FD.TP.CM/FD.TP.DT, multi-PG, FEFF/FBFF et essais matériels selon norme J1939-22 distincte |
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

**Non livré dans ce premier lot** : qualification physique des pilotes K-Line/J1850/J1939/CAN-FD, adaptation ISO-TP aux adressages mixtes, J1939-81 address claiming et J1939-22 FD.TP/multi-PG, DoIP TLS/UDP, adaptation transport Android, OBDonUDS complet, routines OEM postflash, automatisation des mesures véhicule, programmation de restauration physique et qualification sur un calculateur réel. Un test J1939-21 sur modèle RAM et une trame J2534 ne certifient aucune écriture constructeur. Le fait qu'une DLL J2534 fournisse un canal bidirectionnel n'est pas une preuve de compatibilité ECU. Les logiciels doivent afficher « pas qualifié » plutôt que « pris en charge » pour ces fonctions.


## Commandes d'échange bidirectionnel (Windows)

Les deux commandes utilisent les standards (DoIP TCP ou DLL SAE J2534 du fabricant). **Toute commande sortante**, dont le service est arbitraire, déclenche avertissement **OK** puis confirmation séparée **OUI/NON**. C'est le consentement opérateur, pas une interdiction de service UDS. Les trames sont transmises sans changement des données fournies ; la DLL J2534 reste responsable de la couche physique, des filtres et du timing.

```text
CamaroDiag.J2534Bridge.exe --doip-exchange --host 192.0.2.10 --tester 0E80 --ecu 1234 --payload 22F190
CamaroDiag.J2534Bridge.exe --doip-exchange --host 192.0.2.10 --tester 0E80 --ecu 1234 --payload 2EF19001
CamaroDiag.J2534Bridge.exe --j2534-standard --adapter-index 0 --protocol-id 0x04 --baud 10400 --kline-init five:33 --data 83F11022019077 --read-frames 3
CamaroDiag.J2534Bridge.exe --j2534-standard --adapter-index 0 --protocol-id 0x8012 --baud 500000 --data 000007E022F190 --mask FFFFFFFF --pattern 000007E8 --flow-control 000007E0
```

Les adresses IP `192.0.2.10`, CAN et données ci-dessus sont **illustratives** et ne doivent pas être émises vers un ECU réel sans profil correct. La forme des données varie selon le protocole et le driver. Pour K-Line, utiliser `--kline-init fast:<trame-hex>` ou `five:<adresse-hex>` suivant le véhicule, jamais une initialisation présumée universelle. Le débit FD de phase de données est à configurer via les paramètres J2534-2 du driver ; le débit nominal seul ne le spécifie pas.

La CLI DoIP ne gère pas encore l'ouverture TLS ni la découverte UDP requises par certaines passerelles ; elle doit donc être utilisée uniquement avec une passerelle autorisant explicitement une connexion TCP 13400 non chiffrée, sur réseau véhicule isolé. Ne pas présenter son exécution en simulation comme une qualification véhicule.

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
- `--block-size` et `--stmin` contrôlent les réponses FlowControl produites ; le moteur respecte celles du pair, y compris les valeurs sub-millisecondes. Une programmation avec timing précis ne sera considérée matériellement qualifiée qu'après preuve sur le matériel ciblé.
- `--extended true` sélectionne un identifiant CAN 29 bits (et non l'adressage ISO-TP étendu, accessible par --tx-address/--rx-address).
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
