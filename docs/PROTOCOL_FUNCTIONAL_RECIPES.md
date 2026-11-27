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

### Mode 04 — campagne indépendante Android, Windows et hôte Linux

Exporter le scan OBD complet initial avant toute demande d'effacement. Le Mode 04 peut effacer des défauts antipollution, trames figées et états de préparation. Ne pas l'utiliser pour masquer un défaut non résolu. Priorité Camaro 2012, autres véhicules thermiques CAN du parc selon leur documentation et leur compatibilité. Respecter la procédure constructeur avec moteur arrêté et véhicule immobilisé.

Android : tests privés SAE J1979, confirmer le profil/VIN et sélectionner Mode 04 (ELM/STN CAN physique seulement). Windows : tests privés, ECM physique principal sélectionné, VIN explicite et bouton Mode 04 dédié. Linux : `camarodiagconnectors-j1979-clear`, adaptateur SocketCAN externe déjà configuré. Dans chaque cas deux confirmations distinctes, VIN lu comparé, baseline confirmée Mode 03 et lectures positives vitesse/régime à zéro. Une seule demande autorisée, aucune diffusion ni retry. Rapports JSON individuels avec phases BEFORE/AFTER, lectures 03/07/0A, PID 01 (MIL/readiness), réponses brutes, ACK et statut. Un silence, NRC ou trame malformée ne signifie jamais zéro défaut. Les défauts permanents sont observés et non présumés effacés. Un ACK avec relecture partielle ou défauts persistants n'est pas une validation de leur disparition. Le parcours Android vers pont J2534 réseau reste celui du diagnostic ordinaire ; utiliser Windows local pour la campagne indépendante J2534. Variantes legacy non qualifiées dans cette recette.

Independent physical CAN Mode 04 campaign for Android ELM/STN, local Windows J2534 and Linux SocketCAN host. Save the complete original scan, follow manufacturer procedure and use separate confirmations. Baseline and readback evidence never infer zero from missing replies. No automatic retry or generic write access.

### SAE J1979 legacy — cinq recettes distinctes

Android : choisir SAE J1979 — J1850 PWM / J1850 VPW / ISO 9141-2 / KWP slow init / KWP fast init dans les tests privés, avec un ELM/STN compatible et déjà connecté sur cette variante. Le scan vérifie ATDPN et une réponse initiale PID 00, refuse la variante différente sans repli, et produit le JSON propre à cette variante. Les records non-CAN des DTC, Mode 05/06/08 et Mode 09 sont traités séparément du CAN. Le VIN anonyme éventuel est conservé en candidat et non en identité de calculateur ; CALID/CVN et autres fragments restent bruts lorsque l'attribution n'est pas possible.

Windows/Linux : utiliser la recette ELM série `camarodiagconnectors-j1979-legacy` décrite dans `connectors/README.md`. Ce n'est pas encore le parcours natif J2534 legacy. Les campagnes sont indépendantes de l'OBD CAN et des protocoles génériques J1850/ISO9141/KWP, qui ne sont pas clôturés.

| Variante | Candidat prioritaire / alternatif à confirmer |
|---|---|
| VPW | Camaro Gen4, p. ex. Z28 1998 ; pas Camaro 2012 |
| PWM | Mustang thermique ancienne, p. ex. années 1996–2004 ; pas Mach-E 2023 |
| ISO 9141-2 | Toyota/Honda essence début années 2000, variante documentée |
| KWP slow init | Ancienne voiture européenne essence avec OBD ISO 14230-4 slow init confirmé |
| KWP fast init | Ancienne voiture européenne essence avec OBD ISO 14230-4 fast init confirmé |

La Ducati 848 2008 peut être candidate à l'étude K-Line constructeur mais ne prouve pas SAE J1979 ; Peugeot 207 CC 2010 et les voitures récentes du parc ne sont pas des candidats legacy présumés. Utiliser un banc si aucun véhicule compatible n'est disponible. Références : ISO 15031-5:2006 §6.3/6.5/6.6/6.8/6.9, https://studylib.net/doc/25827573/iso-15031-5-2006 ; historique indicatif des véhicules https://www.obdtester.com/obd2_protocols et https://www.blafusel.de/obd/obd2_scanned.php?order=usb . Vérifier année, marché, moteur, adaptateur et vraie réponse, sans déduire le protocole de la seule prise DLC.

Separate legacy read recipes on Android and serial ELM host, with variant-specific JSON and raw anonymous records. Mutating legacy recipes and native J2534 legacy integration remain open. Firmware passive application classifier is not a vehicle transport.

### SAE J1979 legacy — J2534 natif Windows

Le pont Windows dispose maintenant d'une recette locale distincte pour les cinq variantes legacy. Dans l'onglet des tests privés, arrêter le pont et fermer la console, sélectionner l'interface J2534 puis le bouton J1979 legacy. Choisir le protocole documenté. Si la source de réponse ECU est inconnue, utiliser Découvrir les sources (PID 00 uniquement), puis sélectionner explicitement la source observée appropriée lors d'une nouvelle recette. Une source numérique n'identifie pas à elle seule un calculateur constructeur.

En CLI, exemples illustratifs (remplacer index, variante et source par les valeurs vérifiées) :
```powershell
CamaroDiag.J2534Bridge.exe --j1979-legacy --adapter-index 0 --variant 2 --source discover --confirm-legacy-profile --output vpw-sources.json
CamaroDiag.J2534Bridge.exe --j1979-legacy --adapter-index 0 --variant 2 --source 10 --confirm-legacy-profile --output vpw-ecu10.json
```
Variantes 1 PWM, 2 VPW, 3 ISO 9141-2, 4 KWP slow init, 5 KWP fast init. Budget 180 s, option `--budget-seconds` 1–600. Le pilote doit accepter la configuration, le filtre, l'initialisation applicable et CLEAR_RX_BUFFER. Les entêtes sont ceux des requêtes fonctionnelles antipollution, pas un adressage physique de transmission. La sélection de source est un filtre de réponse. PWM : adresse testeur F1 et table fonctionnelle de réponse configurées ; IFR J2534 04.04 séparés des données applicatives. Les autres trames suivent le contrat habituel où le pilote gère CRC/checksum.

La recette conserve les octets TX/RX, source, horodatage pilote, NRC, statut et VIN reconstitué sur cette seule source si les cinq fragments et le compteur concordent. Elle lit 01/02/03/05/06/07/08 inventaire/09/0A si disponibles ; CALID/CVN legacy et DTC décodés quand leur format est vérifié. Une donnée brute de PID à largeur inconnue n'est pas une mesure décodée. Une recette terminée ne signifie pas que tous les services ont répondu. Les exports sont distincts (découverte / source sélectionnée), sans écrasement. Ne pas présenter un silence comme zéro DTC.

Aucun Mode 04 ni actionnement 08 dans cette campagne. La transmission normative legacy fonctionnelle peut affecter plusieurs ECU : les futures recettes mutatrices doivent traiter la portée véhicule entière avec consentements distincts ; ne pas réutiliser la sélection de réponse comme preuve d'adressage physique. Le transport J2534 générique existant reste distinct. Réf. ISO 15031-5:2006 tableau 13 et §6 (https://studylib.net/doc/25827573/iso-15031-5-2006), SAE J2534-1 04.04 §8.5 (https://netcult.ch/elmue/HUD%20ECU%20Hacker/J2534%20PassThrough.pdf). EVAP : l'annexe F définit TID 01 comme préparation des conditions du contrôle de fuite, pas la réalisation du contrôle ; messages de confirmation CAN corrigés en conséquence.

Native Windows J2534 legacy: independent read-only campaign and bounded source discovery on a documented protocol, with source-specific JSON, driver-managed framing and separate PWM IFR evidence. No automatic ECU selection, write, or claim of whole-vehicle coverage. The ELM serial host remains available on Windows/Linux. Hardware/driver validation is separate.

### J1979 — état des recettes legacy mutatrices et du noyau embarqué

Les parcours legacy Windows J2534 local et hôte ELM série comportent désormais une implémentation séparée Mode 04 / préparation EVAP TID 01, avec portée fonctionnelle véhicule, sources documentées, VIN, immobilité, double confirmation et rapport initial exclusif avant émission. Le filtre RX ne limite jamais la portée de la requête. Les calculateurs silencieux peuvent être affectés. Ces parcours ne sont pas validés logiciellement ou physiquement. Les commandes legacy Android restent à compléter ; aucun repli implicite vers CAN ou pont réseau.

Connectors : campagne CAN embarquée de lecture sur une liaison qualifiée, avec flux de preuves et états positifs/négatifs/RAW/délai/défaut. Fin de parcours distincte de la complétude des inventaires. L'adaptateur ESP32-S3 TWAI ne possède pas encore de profil électrique qualifié ni de point d'entrée USB véhicule actif. La recette physique et son export hôte ne sont donc pas disponibles via le firmware de découverte. Priorité Camaro 2012 sur banc qualifié, puis véhicules CAN compatibles du parc.

### J1979 embarqué — export de preuve hors ligne

Le flux CJ/1 contient les étapes de lecture de la campagne embarquée et son résumé. Le convertisseur `camarodiagconnectors-j1979-capture` produit un JSON individuel avec identifiants CAN, durée, réponses, VIN si valide, états RAW/PASSED/NEGATIVE/TIMEOUT/FAILED, inventaires et erreurs. L'intégrité du format ne certifie pas le matériel. Le gestionnaire NativeUsb protège l'exclusivité, l'autorisation et l'arrêt du canal, mais la recette USB physique reste indisponible jusqu'à l'intégration du propriétaire matériel qualifié et du point d'entrée authentifié. Le parcours de découverte public ne change pas.

### Android — SAE J1979 legacy, opérations distinctes

Dans les tests privés Android, choisir la variante legacy effectivement connectée (J1850 PWM/VPW, ISO 9141-2, KWP slow/fast). La recette exige des réponses ELM/STN avec en-têtes et contrôle CRC/checksum ; une liaison sans attribution ne suffit pas. Renseigner la source moteur et les sources antipollution documentées, confirmer le profil/VIN et utiliser le bouton séparé Mode 04 ou préparation EVAP. Les demandes legacy sont fonctionnelles véhicule : la liste des sources de réponse ne limite pas les calculateurs potentiellement affectés. Exporter le scan complet initial et suivre la procédure constructeur, moteur arrêté, véhicule immobilisé. OK puis Oui sont requis ; aucune relance automatique.

La session exclusive vérifie un PID00 positif par source déclarée, ATDPN fixé (pas de mode auto), VIN fragmenté attribué, vitesse/régime et support applicable. Elle sauvegarde un rapport initial dans le stockage privé Android (fichier exclusif synchronisé avant émission), recontrôle les conditions et autorise une seule demande. L'export fonctionnel distinct contient la baseline disponible, les ACK par source, les DTC 03/07/0A avant/après, réponses brutes et verdicts ; absence de réponse = null, pas zéro. Un rapport interrompu peut ne contenir que la baseline. Le TID 01 ne réalise pas le test de fuite. La restauration de configuration ELM est obligatoire ; si elle échoue, déconnexion. Ces parcours ne sont pas encore validés logiciellement ou physiquement. Les candidats véhicules restent ceux des cinq variantes décrites précédemment.

Android legacy : après le test de lecture de la variante réellement négociée, la découverte PID00 à en-têtes vérifiés liste les sources observées et leur bitmap. L'export `legacySourceDiscovery` distingue succès, refus et contradictions, conserve les réponses brutes, et n'établit ni identité OEM ni couverture exhaustive. Utiliser cette liste comme information de préparation, jamais comme sélection automatique de moteur pour une opération fonctionnelle véhicule.

### Connectors — recette USB native CU/1 (développement, pas encore déployée)
Sur firmware et carte qualifiés uniquement, avec clé hôte provisionnée localement, profil physique documenté et véhicule immobilisé. Candidats Camaro 2012 en priorité, puis parc compatible CAN J1979. Commande `camarodiagconnectors-j1979-usb --port COM5 --key-file <cle> --tx <id> --rx <id> --vehicle <vehicule> --ecu <ecu> --connector <profil> --capture <nouveau.cj> --output <nouveau.json>` ; ajouter `--extended` pour un profil 29 bits. Pas de commande mutatrice, pas de repli sur découverte. Le rapport distingue authentification du boîtier, intégrité du flux CJ/1, profil observé et preuve physique non établie. Ne pas utiliser sur le firmware de découverte. / Qualified hardware and provisioned host credential required; preserve raw and independent JSON, no write or simulated fallback.


### USB natif — interprétation des compteurs par mode / native USB service observations
`service_observations` contient les compteurs `attempted`, `passed`, `raw`, `negative`, `timeout`, `failed` pour les modes 01, 02, 03, 06, 07, 08, 09 et 0A. Ils décrivent les étapes enregistrées (y compris le préfixe d'une capture incomplète), non une validation exhaustive du mode ; consulter les flags d'intégrité et la raison d'arrêt. `passed` indique une réponse de forme positive vérifiée, jamais le succès d'un moniteur ni une preuve de véhicule physique. Le Mode 08 reste uniquement un inventaire dans cette campagne. / Counts describe recorded requests, not service coverage or monitor outcomes. Consult integrity, cancellation and stop reason; an absent response is not zero faults.


### Comparaison VIN USB / USB VIN comparison
Les CLI Windows et Python acceptent `--vin` suivi d'un VIN attendu (17 caractères). Les rapports distinguent `declared_vin`, `observed_vin` et `vin_match` (`MATCH`, `MISMATCH`, `UNVERIFIED`, `NOT_REQUESTED`). Une comparaison demandée non confirmée produit un code de sortie non nul ; les traces sont conservées. La comparaison hors ligne n'authentifie pas le boîtier ou la voiture. / Optional expected VIN comparison is separate from campaign integrity and physical provenance.

### Linux — J1979 CAN discovery and selected responder set

La recette `camarodiagconnectors-j1979-network` ajoute une découverte PID 00 sur une seule famille 11/29 bits documentée, puis des campagnes ISO-TP physiques distinctes pour les seules sources sélectionnées. Le rapport conserve toutes les réponses éligibles, contradictoires, négatives, les adresses, budgets et preuves individuelles. Aucun essai physique n'est revendiqué ; voir les commandes dans connectors/README.md. / One bounded functional PID00 discovery, then explicitly selected separate physical scans. No fallback, mutation or coverage claim.

### Windows — CAN network / réseau multicalculateurs

Tests privés : sélectionner l'interface J2534, arrêter le pont et la console, ouvrir Réseau CAN J1979. Découverte seule sur profil 33–36 documenté, puis nouvelle campagne avec les identifiants de réponse documentés (séparés par virgules). CLI `--j1979-network` : voir `--help`. Un VIN facultatif impose une correspondance par ECU. Aucun Mode 04/08, aucun balayage physique implicite. / One functional PID00 discovery, explicit selected physical channels, individual evidence and no whole-vehicle claim.

### J1979 — enregistrement physique des mesures / physical live recording

Linux SocketCAN `camarodiagconnectors-j1979-live`, Windows J2534 `--j1979-live` ou bouton Enregistrement J1979 dans les tests privés. Canal physique et VIN attendus explicites, PID numériques sélectionnés et annoncés, limite de cycles/temps, contrôle VIN initial et final et périodique pour les campagnes longues. Export JSON/CSV : aucune mesure inventée à la suite d'un silence. Les lectures d'un cycle ne sont pas simultanées. Aucune mutation, aucun essai physique revendiqué.

### J1979 legacy — enregistrement continu / legacy live

CLI hôte ELM `camarodiagconnectors-j1979-legacy-live` : cinq variantes fixes, sélection de la source de réponse, contrôle du VIN fragmenté et du protocole, acquisition des seuls PID numériques annoncés, rapport et CSV exclusifs. La source filtre la réponse, non la portée de transmission. Traces originales et représentation applicative normalisée distinctes. Aucun Mode 04/08. Les essais matériels sont à réaliser séparément.

Windows J2534 local : bouton Enregistrement J1979, sélectionner CAN classique physique ou une des cinq variantes legacy. La CLI `--j1979-legacy-live` utilise `--variant`, `--source`, `--confirm-legacy-profile` et `--confirm-functional-scope` au lieu du canal CAN. Même moteur de cycles, rapport des trames gérées par le pilote et VIN fragmenté ; source filtrée, transmission fonctionnelle. / Same live recorder with explicit legacy variant and source, no physical-address claim.

Android : sélectionner la variante SAE J1979 legacy effectivement connectée dans les tests privés, ouvrir Enregistrement J1979 et renseigner source de réponse, PID numériques, VIN du véhicule sélectionné et durée. Le rapport conserve les en-têtes/checksums et fragments VIN d'origine. Réutilise le moteur de mesure, pas les en-têtes ISO-TP CAN. Export JSON/CSV. / Select the negotiated legacy variant and documented response source; no physical-address claim or mutation.


### J1979 — relecture indépendante des preuves USB CJ/1 / independent USB evidence replay

Conserver le fichier binaire CJ/1 et le JSON individuel exporté par Android, Windows ou l'hôte Python. Sur un ordinateur hors véhicule :

```sh
camarodiagconnectors-offline verify-j1979-capture \
  --capture capture.cj --report campaign.json --vin 1G1FA1RXXR0123456 \
  --output inspection.json
```

Remplacer le VIN d'exemple par celui du véhicule documenté ; l'option est facultative. Le vérificateur relit deux fois les fichiers bornés et recalcule les étapes, profils, inventaires, défauts, mesures, données figées, moniteurs, identifiants et résumé à partir des octets CJ/1. Le rapport conserve les deux SHA-256 et les différences. Une campagne interrompue ou un rapport négatif ne devient pas positif ; le code de sortie 2 signale une vérification non concluante ou négative. Le VIN indépendant ne prouve pas l'origine physique. Aucune communication véhicule ni authentification de la capture non signée ; ne pas transmettre publiquement les VIN et traces.

Keep the original CJ/1 binary and individual host JSON. Run the same offline command, optionally with an independently documented VIN. It checks the profile, every recorded row and derived observation, service counters and summary. A partial/cancelled run cannot pass. The output is exclusive, contains hashes and mismatches, and does not authenticate the hardware, capture or vehicle.

### C6 — historique des sauvegardes originales / original-sector snapshot history

Conserver chaque instantané dans un répertoire distinct, avec le journal, son dossier `.blocks` et les métadonnées Windows `.meta.json` le cas échéant. Ne pas reconstruire ni modifier les journaux pour créer un historique. Fournir entre deux et huit instantanés dans l'ordre explicite du plus ancien au plus récent :

```sh
camarodiagconnectors-offline verify-recovery-history --platform windows \
  --journal old/journal.jsonl --journal new/journal.jsonl \
  --vin 1G1FA1RXXR0123456 --output history.json
```

Android : utiliser `--platform android --family E39 --controller ECM --firmware OS-A --vin VIN_DOCUMENTE` avec les journaux TSV correspondants ; remplacer toutes les valeurs d'exemple par les identifiants exacts du manifeste. Windows accepte aussi `--osid` pour comparer un OSID connu. Chaque jeu est réinspecté intégralement ; le journal doit s'étendre strictement octet pour octet, la cible et les enveloppes originales doivent rester identiques. Les duplications, retours arrière et bifurcations sont rejetés. Le dernier état enregistré n'est pas l'état actuel du calculateur. Le rapport n'autorise ni restauration ni écriture, même lorsqu'il établit une continuité locale. Le code de sortie 2 signale un historique non vérifié.

Keep snapshots in separate folders with their original sector envelopes and Windows metadata if applicable. Select two to eight in explicit oldest-to-newest order. Every bundle is independently rechecked; strict journal extension and unchanged target/original envelopes are required. Duplicate, reverted or forked inputs are rejected. The latest recorded disposition is not the current ECU state; no hardware or restore authorization is inferred. The commands above have written regression cases; execution is deferred to the respective protocol/ chantier closure.

### SAE J1979 — Mode 06 engineering values / valeurs physiques

FR : Les résultats CAN Mode 06 sur Android, le pont Windows et le scan Linux conservent les mots bruts et décodent 16 identifiants UASID documentés dans ISO 15031-5:2006, annexe E. Les valeurs signées sont interprétées en complément à deux. Une unité inconnue reste brute. Les limites inversées sont signalées dans la vue structurée ; aucune comparaison numérique ne constitue un verdict du moniteur ni une preuve de conformité antipollution. Les anciennes preuves CJ/1 restent inchangées. Les échelles ne s'appliquent pas aux modes legacy 05/06.

EN: Android, the Windows bridge and Linux scan retain raw CAN Mode 06 words and decode 16 documented UASIDs from ISO 15031-5:2006 Annex E. Signed words use two's complement. Unknown units stay raw. Structured views distinguish inverted limits; numerical comparisons never establish a monitor verdict or emissions compliance. Historical CJ/1 evidence is unchanged. These scales do not apply to legacy Mode 05/06.

Scope: historical subset, not the complete SAE J1979DA catalogue. Regression tests written; execution remains pending the protocol closure gate. Reference: ISO 15031-5:2006 Annex E.

### J1979 — Mode 09 étendu / Extended vehicle information

Périmètre logiciel : InfoTypes 08 (essence) et 0B (diesel), compteurs IUPR non signés, rapports numériques avec dénominateur non nul, identification moteur 0D, homologation 0F, nom ECU legacy 0A. Sur legacy, le compteur 07 est partagé par 08 et 0B ; 0C et 0E annoncent respectivement 0D et 0F. Vérifier les fragments numérotés, leur longueur, la source sélectionnée et les longueurs CAN exactes. Référence de définition : SAE J1979DA, octobre 2011, G8–G15 ; profils et variantes plus récents à confirmer. Ne pas déduire de verdict réglementaire des rapports numériques.

Android : diagnostic OBD CAN, campagnes multi-ECU, recettes legacy et projection de capture native. Windows : console/scan J2534, recettes legacy et USB natif. Linux/Python : scans CAN/legacy, décodage CJ/1 et inspection indépendante. Le champ dérivé `extended_vehicle_info` est facultatif pour les anciens rapports CJ/1 ; si présent, l'inspection hôte le compare aux octets originaux. Une réponse historiquement RAW reste RAW. Les anciennes captures ne sont pas réécrites.

Acceptance: use a confirmed responding ECU, read its Mode 09 inventory and request only advertised InfoTypes; distinguish spark and compression-ignition counter layouts, unavailable ratios, missing fragments and invalid padding. Check CAN/legacy reports against actual bytes and compare Android/Windows/Linux views. Reject forged decoded values in offline inspection. Tests added, not executed yet. No claim of physical legacy support in the connector firmware or regulatory compliance. SAE J1979 remains in progress.

### J1979 — Mode 01 multicanal / Multi-signal measurements

49 définitions explicites (144 champs possibles) : 14–1B, 24–2B, 34–3B, 64, 66–6A, 6C–6E, 70–7C, 7F, 98–99. Exiger une longueur exacte, respecter les masques de présence, conserver les octets d'origine, omettre les valeurs indisponibles (notamment le trim O2 FF), ne pas inventer le banc à partir d'un ordinal O2. Les champs signés et les durées 32 bits sont décodés sans troncature. Référence publique de travail : https://www.dashlogic.com/docs/technical/obdii_pids ; sous-ensemble historique, pas une certification du catalogue SAE J1979DA intégral.

Integration: Android diagnostic, network/legacy/native projection and live recorder; Windows J2534 scan/console, legacy and native reports, live recorder; Linux/Python scan, network/legacy, live recorder, and offline evidence inspection. Legacy live selection limits multi-signal data width to five bytes. Native CJ/1 RAW status is preserved; decoded channels are derived from the original response. Each live channel has its own count, missing count, min/max/mean and last verified sample. CSV adds signal_name and raw_encoded only for multi-signal plans; scalar-only header is unchanged. Independent inspection replays each channel and its statistics, rejecting tampered values.

Acceptance cases written: exact lengths, masks, reserved bits, signed pressure, unsigned runtime, missing second sensor, independent channel mean, CSV and tampered replay. No tests executed yet. Firmware physical transport, remaining Mode 08 controls and full J1979DA coverage remain outside this lot. No release or closure claimed.


### J1979 contextual measurements (Mode 01 equipment configuration)

The ECU-specific interpretation of MAP (0B), MAF (10), commanded equivalence
ratio (44), and wideband oxygen sensor PIDs (24–2B, 34–3B) uses the equipment
configuration advertised by the same ECU (4F and 50). A complete terminating
support page can establish absence; a failed or missing advertised configuration
cannot. Freeze-frame zero uses the correlated ECU's Mode 01 equipment settings.
Reports preserve the original application evidence and include a separate
contextual_measurements projection, with raw encoding, scale source and nullable
engineering value. Legacy anonymous observations cannot establish ECU scope.

Continuous recording on Android, Windows and Linux resolves the required
configuration before acquisition; SCALING_UNVERIFIED prevents publishing
statistics calculated using an assumed scale. Historical exports retain their
original interpretation. Custom current scaling requires qualification against the
applicable J1979 revision and real ECU responses before claiming conformity.

### Mesures contextuelles J1979

Les PID 4F et 50 fournissent les plages propres au calculateur sélectionné.
Une configuration annoncée mais non obtenue reste non vérifiée, contrairement
à une absence démontrée par les bitmaps. Le rapport conserve les octets bruts,
la provenance de l'échelle et les valeurs physiques éventuellement nulles.
L'enregistrement continu ne calcule pas de statistiques sur une échelle supposée.
Les scénarios de régression couvrent les plages spécifiques, l'absence vérifiée,
les réponses tronquées, les doublons et l'isolation des sources. Leur exécution
reste réservée à la validation de clôture du protocole.


### J1979 — vérification legacy et moniteurs / Legacy replay and monitors

La commande hors ligne `verify-legacy --report source.json --vin VIN --output verification.json` relit un rapport de diagnostic à source sélectionnée (ELM Android/Linux ou J2534 Windows). Elle reconstitue les réponses à partir des trames enregistrées, vérifie les requêtes et les chaînes d'inventaire, le VIN fragmenté, l'attribution des réponses, les défauts et les projections numériques/moniteurs prises en charge. Les champs historiques non vérifiés ne sont pas assimilés à des preuves. Le résultat indique précisément les champs de synthèse contrôlés. Le fichier d'entrée n'est pas modifié. Le CRC ou checksum ELM est revérifié ; un pilote J2534 ne fournit pas nécessairement ce byte, donc son rapport ne peut pas attester ce contrôle. Pas de preuve physique ni d'autorisation d'actionnement.

The historical ISO 15031-5:2006 Annex E table now contains 79 supported CAN Mode 06 scaling identifiers. Mode 05 uses the ten historical standard oxygen test definitions from Annex C, with constant versus bounded results kept distinct. Legacy Mode 06 is a separate record layout: component ID, single encoded limit and limit direction; no OEM engineering units are guessed. Numerical relations do not establish monitor completion or emissions compliance. Custom wideband oxygen current uses signed two's-complement when PID 4F specifies a nonzero current range; the default encoding remains offset binary. Source reference: ISO 15031-5:2006 sections 6.5/6.6, B.60 and Annexes C/E (https://studylib.net/doc/25827573/iso-15031-5-2006).

Android, Windows and Linux host reports preserve original raw values and expose derived projections. Native USB scan and network display consume independently decoded contextual values. Regression vectors cover reserved IDs, signed extrema, unknown OEM tests, source substitution, corrupted checksum, unadvertised requests and incomplete VIN. Written, not executed; J1979DA newer-revision coverage, remaining firmware transport/action paths and closing validation remain open. No physical qualification or closure claimed.


## SAE J1979 — acquisition explicite des trames figées / explicit freeze-frame acquisition

Le scan standard conserve sa lecture historique de la trame 00. La recette Linux dédiée sélectionne de 1 à 8 numéros explicites ; elle ne balaie pas les 256 numéros. Utiliser exclusivement le calculateur et la paire CAN physique documentés. La trame 00 utilise les définitions historiques de PID applicables ; les données des trames supplémentaires restent brutes, car leurs définitions peuvent être propres au constructeur (ISO 15031-5:2006, sections 6.2 et 7.2). Aucun nouveau droit dans le pont réseau ou le firmware.

Exemple de syntaxe, avec VIN et adresses fictifs à remplacer par les valeurs vérifiées du véhicule :

```sh
camarodiagconnectors-j1979-freeze --interface can0 --tx 7E0 --rx 7E8 \
  --vin 1G1FA1E30D1234567 --frame 00 --frame 01 --pid 0C --pid 0D \
  --confirm-physical-channel --output freeze.json
camarodiagconnectors-offline verify-freeze --report freeze.json \
  --vin 1G1FA1E30D1234567 --output freeze-verification.json
```

La disponibilité est propre à chaque numéro de trame : PID00, éventuelles pages suivantes, PID02 déclencheur annoncé, puis uniquement les PID de données annoncés. Une valeur de déclenchement nulle empêche la lecture des valeurs. Le déclencheur est revérifié après les mesures et le VIN avant/après la campagne ; cela ne garantit pas une capture atomique de l'ECU. Les erreurs, les limites de temps, les numéros indisponibles, les lectures non envoyées et les réponses brutes restent visibles. L'export n'écrase aucun fichier. Le vérificateur reconstruit la campagne à partir des observations et rejette toute divergence ; l'intégrité logique du rapport ne prouve pas l'identité physique, l'authenticité du matériel ou le minutage réel.

The dedicated Linux recipe selects one to eight explicit frame numbers, uses an independently documented physical CAN channel and an expected VIN. It never guesses additional frame numbers or OEM-specific measurement scales. Each frame has its own support map and trigger. Only advertised measurement PIDs are requested. A zero trigger prevents value reads; a changed trigger or unavailable final VIN prevents a positive overall stable result. The independent offline command checks the exact request order, response echoes, summaries and safety flags. No programming authority is granted. Windows and Android live acquisition for additional frames and firmware coverage remain open. Regression tests written, not executed before the closing protocol gate.

## C6 — historique de récupération multiplateforme / cross-platform recovery history

Android permet de sélectionner et d'ordonner plusieurs dossiers de preuves de récupération via SAF, de comparer leurs journaux exacts et leurs inventaires de secteurs originaux, puis d'exporter un rapport JSON après relecture des sources. Le contrôle exige une identité attendue et un contexte de compte/véhicule inchangé. Les exports restent des constats hors ligne ; ni restauration, ni écriture sur le calculateur.

Le vérificateur Python `verify-recovery-history-report` prend désormais en charge le rapport d'historique natif Windows en plus du format portable utilisé par Python et Android. Les journaux originaux doivent être fournis dans l'ordre déclaré ; les empreintes, secteurs, états, transitions et résultats sont recalculés indépendamment. Les exports unitaires Windows sont aussi interdits dans les répertoires de preuves d'origine.

Android supports explicit ordered selection of recovery evidence folders, byte-prefix and invariant original-sector comparison, source reinspection and portable JSON export. Python independently replays native Windows history reports and the portable Android/Python shape from the actual source journals. These checks are offline, not hardware attestation or permission to recover an ECU. Regression cases are written, not executed. C6 remains open.
