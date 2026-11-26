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
