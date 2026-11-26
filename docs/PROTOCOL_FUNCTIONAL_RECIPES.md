# Recettes fonctionnelles par protocole / Protocol acceptance
Les modèles ci-dessous sont des candidats, pas des confirmations de compatibilité sur un VIN. Vérifier ECU, marché, variante, câblage et interface. / Candidates only: verify actual ECU, market, variant, wiring and interface. Priorité Camaro puis parc disponible / Camaro first, then owner's fleet.
Statut logiciel distinct de la recette physique : **Terminé — à tester en réel** (software complete — physical test pending), puis **Terminé — validé en réel** sur le périmètre prouvé. DoIP : logiciel validé au SHA e3b5f2a5a0ef704da7a5d622ef8b11116f337d96 ; recette fonctionnelle dédiée à compléter ; aucun essai réel revendiqué. J1979 : en cours.
| Protocole | Candidats prioritaires / candidates | Interface et preuve / interface and evidence |
|---|---|---|
| SAE J1979 | Camaro 2012; Mach-E 2023 (réponse OBD déjà observée); 207 CC 2010; Corolla 2023; X1 F48 2020 | ELM/STN/J2534 compatible; PID, DTC, VIN, services applicables |
| SAE J1979-2 | Mach-E / Corolla à vérifier; aucun Camaro confirmé | CAN ou DoIP; identification réglementaire et DID/DTC |
| CAN classique | Camaro 2012; 207 CC; Mach-E; Corolla; X1 | CAN, débit et adresses observés; capture et lecture |
| CAN-FD | Mach-E candidate; Corolla à vérifier | interface FD; preuve trame FD et débit |
| ISO 15765-2 classique | Camaro 2012; 207 CC; Mach-E; Corolla; X1 | CAN ISO-TP; VIN multitrames et contrôle de flux |
| ISO 15765-2 FD | Mach-E candidate, Corolla à vérifier | FD et profil ISO-TP confirmé |
| ISO 14229 UDS | Camaro selon ECU; X1; Mach-E; Corolla | transport et ECU documentés; DID, DTC, NRC |
| ISO 13400 DoIP | Mach-E à vérifier; X1 F48 à vérifier (ENET ne prouve pas DoIP); aucune Camaro confirmée | Ethernet, IP/adresses documentées, découverte UDP et routage TCP/TLS, VIN, DTC |
| ISO 9141-2 | Ducati 848 2008 candidate K-Line, dialecte à vérifier; anciens véhicules | KKL/ELM compatible et câble moto documenté |
| ISO 14230 KWP2000 | Ducati 848 2008 candidate; 207 CC selon ECU | K-Line documentée; ne pas confondre KWP sur CAN et K-Line |
| SAE J1850 VPW | Camaro Gen4 1996–2002 candidate (hors parc) | interface VPW; identification OBD |
| SAE J1850 PWM | Ford ancien (hors parc) | interface PWM; identification OBD |
| SAE J2411 SW-CAN / GM GMLAN | Camaro 2012 selon réseau/ECU | interface SW-CAN et broche documentée; preuve séparée par protocole |
| GM Class 2 | Camaro Gen4 candidate (hors parc) | matériel GM VPW compatible; identité |
| GM ALDL | Camaro Gen3/ancienne Gen4 selon ECU (hors parc) | interface ALDL selon débit et brochage |
| ISO 17987 LIN | Camaro 2012 ou autres selon sous-réseau, accès direct non garanti | banc LIN ou réseau documenté; capture passive |
| SAE J1939-21 / -22 / -81 | poids lourd ou banc (hors parc), aucun confirmé | J1939 classique/FD selon variante, PGN, adresse et transport |
| SAE J1708 / J1587 | poids lourd ancien ou banc (hors parc) | matériel J1708, trames MID/PID et checksum |
| KWP1281 / TP2.0 | VAG ancien (hors parc) | K-Line / CAN selon protocole, interface documentée |
| SAE J2534-1 / -2 | Camaro 2012 puis véhicules compatibles | pont Windows et DLL fabricant; preuve séparée par variante |
| SAE RP1210 | poids lourd ou banc (hors parc) | DLL RP1210 compatible |
| ISO 22900-2 D-PDU API | Camaro ou autres selon VCI et module logiciel OEM disponible | VCI et D-PDU API fournisseur, identification et diagnostic |
## Export / evidence
Un JSON par campagne et protocole, jamais un faux PASS. Champs : identifiant stable du protocole et variante, version, horodatage, véhicule choisi, VIN observé, ECU/adresse, transport, connecteur, résultats de chaque étape, données brutes, erreurs, contexte réel/simulé. Les VIN et traces réels restent privés. Tests read-only par défaut ; essais mutateurs distincts et explicitement autorisés. Les véhicules hors parc ne sont pas des achats prescrits.

### Android — DoIP
Tests fonctionnels > ISO 13400 : connecter le réseau Ethernet de diagnostic isolé, confirmer le VIN exact et renseigner IP + adresses logiques documentées, TLS selon passerelle. Exécuter puis exporter le JSON individuel. UDP identification, status/power, VIN ECU, DTC count/read ; écriture reste non testée. Ne pas choisir d'adresses au hasard. / Use documented addresses and the isolated vehicle network. No write, no automatic TLS fallback.

### Android — J1979 legacy / anciens réseaux
Sur un véhicule dont le réseau réglementaire J1850 VPW/PWM, ISO 9141-2 ou ISO 14230-4 est confirmé, lancer la recette J1979 avec un adaptateur compatible. Le scan sélectionne le Mode 05 et le format Mode 06 legacy seulement après lecture du numéro de protocole ELM. Les requêtes de tests dépendent des bitmaps effectivement annoncés ; un véhicule sans bitmap optionnel reste non testé pour ce périmètre. Les réponses Mode 05 sont visibles dans le scan et dans le JSON autonome ; les résultats Mode 06 conservent TID, CID, type de limite, valeur/limite encodées et échanges bruts. Aucun résultat de monitorisation ou attribution ECU ne doit être déduit du seul succès de lecture. Aucun Mode 04/08 envoyé par ce scan.
Les Camaro 2012 et véhicules récents sur CAN ne sont pas des candidats pour le Mode 05. Candidat alternatif : Camaro Gen4 équipée du diagnostic réglementaire J1850 VPW, à confirmer selon millésime/marché. Ducati 848 et autres véhicules du parc : le simple usage de K-Line ne démontre pas le support de J1979.
On a confirmed legacy emissions link, run the independent J1979 recipe with a compatible interface. Only advertised oxygen sensors/test IDs are read; optional bitmap absence leaves coverage untested. The report retains encoded records, not ECU attribution or monitor completion. No automatic Mode 04/08.
Reference: ISO 15031-5:2006, sections 6.5 and 6.6 (https://studylib.net/doc/25827573/iso-15031-5-2006).

### Windows — SAE J1979 sur ISO 15765-4 CAN
Dans les tests fonctionnels privés, authentifiez-vous, sélectionnez votre pilote J2534, le profil CAN et les adresses physiques documentées, puis confirmez le profil du calculateur. Arrêtez le pont et la console avant de lancer **SAE J1979 CAN**. Le test vise un seul calculateur à la fois, sans GMLAN implicite ni diffusion fonctionnelle. Candidats prioritaires : Camaro 2012, Peugeot 207 CC 2010, Ford Mustang Mach-E 2023, Toyota Corolla 2023 et BMW X1 F48 2020, sous réserve de compatibilité du profil et de l'interface. VIN et DTC lus en priorité, puis PID, trame figée si DTC déclencheur, moniteurs 06, inventaires 08 et 09 et types 09 annoncés. Export JSON autonome : version, pilote, CAN IDs, profil, durée, VIN réellement reçu, requête/réponse et résultat par étape. Un NRC ou un silence ne signifie jamais zéro défaut ; un résultat positif ne prouve pas la réussite d'un moniteur embarqué. La campagne a une limite de temps ; les Modes 04, 05 legacy et l'actionnement 08 ne sont pas exécutés.
In private functional tests, authenticate, select a documented J2534 physical ISO 15765-4 CAN profile, confirm the ECU and stop bridge/console. Run **SAE J1979 CAN**, then export its independent JSON. The candidate vehicles above are not confirmed compatible with every ECU or interface. Only read requests are emitted; no broadcast, mutation or simulated fallback.

### Connectors — hôte Linux ISO-TP CAN (J1979)
Recette indépendante depuis l'hôte Python avec adaptateur SocketCAN compatible, explicitement configuré et sélectionné. Voir `connectors/README.md` pour la commande. Utiliser d'abord Camaro 2012, puis les autres voitures du parc dont le profil réglementaire et la paire physique sont confirmés. Export JSON individuel. Lecture seule, avec Mode 08 inventaire uniquement ; les opérations 04/08 mutatrices et le Mode 05 legacy sont non exécutés. Le firmware de découverte ne constitue pas un adaptateur CAN validé. La confirmation opérateur n'est pas une preuve indépendante de véhicule réel.
Independent Linux host ISO-TP CAN recipe and JSON export; use a verified compatible external CAN interface and physical ECU profile. No broadcast, writes or claims of physical verification. Firmware USB discovery capability unchanged.

### Windows — recette séparée Mode 08 EVAP (TID 01)
Sur un ECM CAN ISO 15765-4 compatible, sélectionner le profil physique principal, saisir le VIN documenté et confirmer le profil. Le bouton Mode 08 — EVAP est indépendant du scan en lecture seule. Respecter la procédure constructeur, véhicule immobilisé, moteur arrêté et conditions de sécurité réunies. Deux confirmations distinctes (OK puis Oui) ; contrôle du VIN réellement lu, du bitmap TID et des PID vitesse/régime à zéro immédiatement avant une seule demande. Le calculateur peut refuser les conditions de test. Le rapport JSON séparé distingue NOT_TESTED, ACKNOWLEDGED, REFUSED et UNCERTAIN ; aucun acquittement ne prouve la fin du test ni son résultat. Pas de retry automatique. Les autres TID nécessitent des définitions et parcours distincts ; aucune commande générique n'est autorisée. Priorité Camaro 2012 si l'ECM annonce TID 01, puis autres véhicules thermiques compatibles du parc ; ne pas présumer la compatibilité d'un véhicule électrique.
Reference: ISO 15031-5:2006 §7.8.4.2, https://studylib.net/doc/25827573/iso-15031-5-2006. The independent local Windows EVAP recipe requires a documented physical ECM/VIN, two confirmations, advertised test and actual zero speed/RPM. It never runs automatically and does not establish monitor completion.

### Android ELM/STN et Linux — Mode 08 EVAP indépendant

Android : tests fonctionnels privés, SAE J1979, profil de véhicule contenant le VIN exact, interface ELM/STN compatible avec le protocole CAN effectivement négocié. Confirmer le calculateur principal, sélectionner la recette Mode 08 — EVAP, acquitter l'avertissement puis confirmer séparément. Le transport réserve la session et configure une paire CAN physique primaire ; la lecture du VIN réel, du bitmap de contrôle, de la vitesse et du régime doit être cohérente. La session restaure le format ELM habituel en fin de parcours. Export JSON distinct, acquittement et fin du moniteur séparés. Pas de repli J2534 réseau ; utiliser la recette Windows locale avec J2534.

Linux : recette `camarodiagconnectors-j1979-evap`, sur adaptateur SocketCAN externe configuré, ECM physique principal et VIN explicites. Double confirmation interactive et export JSON exclusif. Réf. ISO 15031-5:2006 §7.8.4.2, tables 177–178 (CAN). Respecter la procédure constructeur, les conditions de sécurité et l'arrêt moteur ; ne pas relancer automatiquement après un état incertain. Priorité Camaro 2012 puis Peugeot 207 CC, Corolla et BMW X1 thermiques compatibles, seulement si TID 01 annoncé et procédure applicable.

Android and Linux have independent CAN EVAP recipes with positive live preconditions, physical ECU routing and separate JSON. The Android network J2534 path is not supported for this action. Acknowledgment never establishes monitor completion. Legacy variants and further supported control definitions are not implemented here.
