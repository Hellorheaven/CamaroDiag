# ELM327 — diagnostic standard et validation hors véhicule

## Profil de développement

Le profil par défaut de l'application correspond maintenant à une Camaro Gen 5 2012 V6 LFX avec boîte manuelle. Les fonctions Mode 01 restent génériques SAE J1979 : elles ne supposent pas que chaque PID existe sur le LFX.

## Initialisation ELM327

Le moteur OBD-II envoie les commandes d'initialisation suivantes :

- `ATZ` — reset de l'adaptateur
- `ATE0`, `ATL0`, `ATS0`, `ATH0` — format de sortie stable
- `ATSP6` — ISO 15765-4 CAN 11 bits / 500 kbit/s, profil OBD-II courant de la Camaro Gen 5
- `ATI` et `AT@1` — identification et description retournées par le firmware
- `ATDP` — protocole détecté
- `ATRV` — tension lue par l'adaptateur

Les commandes de diagnostic ne doivent être envoyées qu'après une connexion Bluetooth SPP réelle. Le test unitaire utilise un canal simulé et ne constitue pas une preuve de compatibilité d'un clone particulier.

## Décodage Mode 01 ajouté

Le moteur interroge uniquement les PIDs annoncés par les bitmaps de support de l'ECU.

- `22` : pression de rail relative à la dépression collecteur, `AB × 0,079 kPa`.
- `24–2B` : rapport lambda et tension des sondes O2 large bande.
- `2D` : erreur EGR.
- `2E` : commande de purge EVAP.
- `34–3B` : rapport lambda et courant des sondes O2 large bande.
- `3C–3F` : températures catalyseur des banques 1/2, capteurs 1/2.
- `52` : pourcentage d'éthanol, si exposé.
- `5B` : durée de vie batterie hybride, si exposée.

Les PIDs hybrides ou diesel peuvent exister dans le standard sans être pertinents pour le LFX. Ils ne seront affichés que si le calculateur les annonce et répond ; leur présence dans le décodeur ne signifie pas que le véhicule est équipé de cette fonction.

## Messages d'erreur ELM

Les réponses textuelles telles que `BUFFER FULL`, `DATA ERROR`, `BUS ERROR`, `CAN ERROR`, `NO DATA`, `STOPPED` et `UNABLE TO CONNECT` sont écartées du flux hexadécimal. Elles ne doivent jamais être transformées en valeurs de capteur.

## Validation simulée

Les tests unitaires utilisent `ObdCommandChannel` pour injecter des réponses déterministes, notamment :

- RPM, VIN, CAL ID et CVN ;
- identification ELM327 ;
- réponses O2 large bande avec ratio, tension et courant ;
- pression de rail relative ;
- température catalyseur ;
- absence de données et buffer saturé ;
- statut de readiness et codes défauts.

Aucun de ces tests ne prouve une réponse réelle de la Camaro. Les données simulées restent exclusivement dans les tests.

## Limites matérielles

Un ELM327 Bluetooth classique donne accès à l'OBD-II standard lorsqu'il est correctement implémenté. Il ne devient pas une interface J2534, ne garantit pas le diagnostic UDS propriétaire GM, ne fournit pas une capture CAN brute complète et n'autorise pas le diagnostic exhaustif BCM/TCM/EBCM/IPC/SDM.

Les fonctions propriétaires GM restent séparées et désactivées en l'absence d'adresses, DID et formats documentés puis vérifiés.
