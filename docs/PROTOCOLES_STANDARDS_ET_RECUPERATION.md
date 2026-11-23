# Normes automobiles, recette après écriture et récupération des calculateurs

**Chantier #110, PR #95. Ce document décrit l'état du CODE et non une certification sur véhicule.**
Ne pas confondre : norme publiée, codec validé par tests unitaires, transport réellement relié à une interface, service diagnostique ECU, programmation validée sur banc et procédure de récupération éprouvée.

## Standards applicables

| Couche / standard | Déjà en place ou ajouté | Non couvert / condition manquante |
|---|---|---|
| UDS ISO 14229 | Client diagnostics en lecture, session autorisée, parser NRC, constructeur de séquence 0x34/0x36/0x37 pour bootloader compatible, parseurs DoIP | Séquences complètes 0x31 OEM erase/checksum/dependencies, SecurityAccess OEM, installation du loader et reset boot/application par famille |
| ISO-TP ISO 15765-2, CAN classique | Pont J2534 existant avec filtre flow-control et CAN 11/29 bits, observateur/connecteur | Qualification ECU réel, géométrie et bootloader |
| CAN-FD ISO 11898 / ISO 15765-2:2024 | DLC standard 0–15 et identification de la pile, tests de cohérence | Négociation J2534-2/driver constructeur FD, trames ISO-TP FD, FC, segmentation/timings et matériel adapté |
| DoIP ISO 13400-2 / UDS ISO 14229-5 | **Nouveau** encodeur/décodeur normatif, adresse logique, routage 0x0005/0x0006, diagnostic 0x8001 et ACK/NACK ; client TCP 13400 bidirectionnel (lecture et écriture UDS, avec acquittements corrélés) sur IP explicitement fourni | Découverte UDP, TLS DoIP si requis, authentification OEM, diagnostics constructeur, intégration UI et essais sur vraie passerelle. Les services d'écriture sont transmis sans liste noire applicative ; la procédure flash physique et ses paramètres constructeur restent à qualifier. |
| K-Line ISO 9141-2 | J2534-1 : ouverture de canal réel, Tx/Rx de trames et FIVE_BAUD_INIT via ioctl du fabricant ; essais sur véhicule à faire | Activation 5 bauds et synchronisation / timing par adaptateur, validation lecture/trames sur véhicule |
| KWP2000 ISO 14230 | Inspecteur de réponse physique côté Android ; **nouveau** encodage KWP à longueur courte/longue et somme additive côté PC ; numéro protocole J2534 standard | Message sequencing/correlation avancée et sessions physiques K-Line sur pilote réel |
| SAE J1850 VPW / PWM | Identifiants J2534-1 normatifs et sélection de la bonne couche | Le transport générique J2534 gère désormais émission/réception via la DLL ; vérification du driver réel, adresses/CRC et tests physiques nécessaires |
| SAE J2534-1/J2534-2 | Chargement des DLL, validation ABI, pont PassThru sur CAN ISO15765 et SW-CAN | Les canaux bidirectionnels génériques K-Line, J1850, CAN/FD ont été ajoutés ; qualifier chaque DLL fournisseur, ses extensions, filtres et paramètres spécifiques |
| SAE J1979, J1979-2 (OBDonUDS) | OBD-II modes standards en partie, DTC et PID véhicule existants | OBDonUDS complet, couverture historique/non CAN et tests de conformité |
| SAE J1939-21, J1939-22 | **Nouveau** parseur d'identifiant 29 bits, priorité/PGN/adresses, DLC CAN-FD | Gestion des adresses, messages de transport (TP/FD), DTC J1939-73, alimentation 24 V et matériel réel |
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

Autotests : catalogue ISO/SAE, KWP framing, CAN-FD DLC, J1939 PGN/adressage, DoIP header/routing/ACK/NACK/adressage et conservation de tous les SID y compris d'écriture, UDS write DID/write memory/routine/transfer/security avec appariement des réponses, test de verdict post-écriture et falsification de sauvegardes physiques, préparation d'un aperçu y compris après interruption.

**Non livré dans ce premier lot** : qualification physique des pilotes K-Line/J1850/J1939/CAN-FD, ISO-TP FD complet indépendamment d'un driver fournisseur, J1939-21/22 transport multipaquet, DoIP TLS/UDP, adaptation transport Android, routines OEM postflash, automatisation des mesures véhicule, programmation de restauration physique et qualification sur un calculateur réel. Le fait qu'une DLL J2534 fournisse un canal bidirectionnel n'est pas une preuve de compatibilité ECU. Les logiciels doivent afficher « pas qualifié » plutôt que « pris en charge » pour ces fonctions.


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
