# DoIP ISO 13400 — couverture logicielle et recette Release

## Français

Le pont Windows CamaroDiag utilise le transport standard **Diagnostics over IP (DoIP)** pour échanger des requêtes UDS avec une passerelle connue. L'existence du transport DoIP ne garantit ni compatibilité constructeur, ni programmation de calculateur, ni accès aux services protégés.

### Fonctions disponibles dans le code

- Identification UDP ISO 13400 : demandes de découverte **avec version générique 0xFF uniquement pour les types 0x0001/0x0002/0x0003**, avant de connaître la version ECU ; demande unicast vers une adresse IPv4 **ou IPv6** explicite, demande de diffusion IPv4 explicitement sélectionnée, écoute passive IPv4, recherche ciblée VIN ou EID, parsing VIN / adresse logique / EID / GID. Les annonces IPv6 link-local `ff02::1` disposent d'une **écoute passive** (`--listen true --listen-ipv6-ifindex <index>`) et d'une **identification active** (`--multicast-ipv6-ifindex <index>`), sur interface explicitement sélectionnée. L'écoute ne transmet rien ; l'identification active émet **une seule requête UDP de découverte normalisée**, jamais une commande UDS. Seules les sources IPv6 link-local et le port DoIP attendu sont acceptés ; VIN/EID servent aux filtres d'identification. Émission, réception multicast et passerelle réelles à qualifier sur Release. Les réponses aux requêtes unicast sont corrélées à l'adresse IP **et au port UDP** du destinataire ; l'écoute d'une annonce n'authentifie pas un ECU.
- **Version découverte** : chaque réponse d'identification affiche `version=0x02`, `0x03` ou `0x04` dans la sortie de `--doip-discover` ; cette valeur provient de l'en-tête réellement reçu, elle n'est **pas authentifiée**. Choisir ensuite explicitement la version correspondante pour `--doip-status` / `--doip-exchange`, après vérification de la passerelle autorisée.
- Informations de passerelle : état de l'entité, nombre de sockets et mode d'alimentation diagnostique, avec version de trame UDP explicitement sélectionnable (`--doip-version 2|3|4`). La version générique de découverte `0xFF` ne peut pas être utilisée pour interroger l'état ; une réponse sur une version différente est refusée.
- Session TCP : ouverture vers le port DoIP normalisé, activation du routage et confirmation du véhicule, réponses aux demandes AliveCheck **y compris entre deux échanges UDS** grâce à un lecteur réseau unique et à une file de messages bornée. Les écritures TCP/TLS sont sérialisées pour empêcher l'entrelacement d'un ACK AliveCheck et d'une requête UDS. Une réponse diagnostique non sollicitée au repos invalide la session au lieu d'être attribuée à la requête suivante. Les requêtes d'activation de routage peuvent porter un type explicite (`--routing-activation-type`, 1 octet) et un champ OEM facultatif (`--routing-oem-data`, exactement 4 octets) : la demande reste de longueur **7 ou 11 octets**, les 4 octets réservés ISO restent à zéro. Gestion des versions DoIP 0x02/0x03/0x04 selon la passerelle.
- TLS : port sécurisé 3496, authentification du serveur par le système d'exploitation, refus de revenir silencieusement au TCP clair ; certificat client du magasin de certificats Windows si requis et présent.
- Transport UDS bidirectionnel : adresse logique testeur/ECU, ACK ou NACK diagnostiques, NACK d'en-tête et vérification des réponses UDS. Les paramètres P2 et P2* sont disponibles, avec une limite globale séparée. **Les octets de requête sont figés avant le premier envoi asynchrone** : une modification ultérieure du tableau par le client ne change ni l'émission ni la corrélation avec les ACK. Un contrôle du délai P2/P2* est répété après chaque lecture TCP, y compris aux limites temporelles.
- Isolation des opérations : un seul échange diagnostique à la fois sur un socket ; après réponse finale valide, les échanges peuvent se poursuivre séquentiellement. Si la requête a été envoyée mais que l'opération échoue, est annulée ou expire, **la connexion devient inutilisable et doit être recréée**. Un retransfert automatique pourrait répéter une opération modifiant l'ECU ; CamaroDiag n'effectue aucun tel retry.

### Exemples Windows

Les adresses suivantes sont documentaires ; remplacer chacune par la passerelle et l'adresse logique réellement autorisées. L'interface IPv6 d'exemple `3` doit être remplacée par l'index de l'interface système du lien véhicule : elle ne doit pas être devinée.

```powershell
CamaroDiag.J2534Bridge.exe --doip-discover --host 192.0.2.10 --timeout-ms 1500
CamaroDiag.J2534Bridge.exe --doip-discover --host ::1 --timeout-ms 1500
CamaroDiag.J2534Bridge.exe --doip-discover --broadcast-ip 192.168.1.255 --timeout-ms 2000
CamaroDiag.J2534Bridge.exe --doip-discover --listen true --listen-ipv6-ifindex 3 --timeout-ms 2000
CamaroDiag.J2534Bridge.exe --doip-discover --multicast-ipv6-ifindex 3 --timeout-ms 2000
CamaroDiag.J2534Bridge.exe --doip-discover --multicast-ipv6-ifindex 3 --vin 1G1FA1RXXR0123456 --timeout-ms 2000
CamaroDiag.J2534Bridge.exe --doip-status --host 192.0.2.10
CamaroDiag.J2534Bridge.exe --doip-status --host 192.0.2.10 --power-mode true
CamaroDiag.J2534Bridge.exe --doip-status --host 192.0.2.10 --doip-version 3
CamaroDiag.J2534Bridge.exe --doip-exchange --host 192.0.2.10 --tester 0E80 --ecu 1234 --payload 22F190 --timeout-ms 5000 --uds-p2-ms 100 --uds-p2-star-ms 2000
CamaroDiag.J2534Bridge.exe --doip-exchange --host 192.0.2.10 --tester 0E80 --ecu 1234 --payload 22F190 --tls true --doip-version 3
CamaroDiag.J2534Bridge.exe --doip-exchange --host 192.0.2.10 --tester 0E80 --ecu 1234 --payload 22F190 --routing-activation-type 0xE0 --routing-oem-data 01020304
```

Pour une activation personnalisée, la valeur `0xE0` et les octets `01020304` ci-dessus sont des **exemples fictifs de format**, pas des clés OEM ni une combinaison susceptible d'activer un véhicule. Il faut utiliser uniquement les paramètres réellement fournis par le constructeur et respecter ses mécanismes de sécurité.

La commande de diagnostic demande une confirmation explicite avant émission. **Une confirmation seule ne démontre pas l'identité et la sauvegarde des secteurs d'un ECU à programmer.** Les opérations susceptibles de modifier le véhicule doivent respecter l'avertissement OK puis la confirmation indépendante Oui/Non et toutes les vérifications de blocs physiques prévues dans `AGENTS.md`.

### Critères du lot logiciel et limites

La recette automatique du lot couvre la syntaxe des paquets, les réponses de routage, les versions TCP 0x02/0x03/0x04, le diagnostic sur TCP IPv4/IPv6 en boucle locale, la corrélation des adresses, l'ACK/NACK, les temporisations P2/P2*, les annulations, les modifications du buffer de requête par l'appelant et les erreurs. **L'aide Windows `--help` décrit maintenant les commandes DoIP en français et anglais** ; le mélange de `--help` avec une commande de transmission est bloqué sans E/S. Les nouveaux scénarios de passerelle TCP/UDP exécutent de **vraies communications réseau en boucle locale** : TCP fragmenté, ACK obsolète, SID ou version erronés, trames tronquées, confirmations de routage `0x11`/`0x10` avec stabilité de l'adresse logique passerelle, réponse d'activation OEM optionnelle à 13 octets, NRC `0x78` répétés, **service d'écriture UDS `0x2E` avec ACK ou NRC négatif (passerelle fictive uniquement)** et refus du repli TLS vers TCP non sécurisé. Ils n'accèdent à aucun adaptateur, calculateur ou réseau de véhicule.

**Capacité maximale :** la version actuelle de CamaroDiag limite volontairement une PDU DoIP à **1 Mio (1 048 576 octets, incluant les 4 octets d'adresses logiques du diagnostic)**. Le format ISO 13400 porte une longueur de 32 bits, mais ce pont en mémoire **ne prend pas en charge des transferts arbitraires jusqu'à cette limite de format**. Les tests logiciels vérifient l'acceptation exacte au plafond et le refus d'un octet supplémentaire. Le découpage UDS OEM, le streaming hors mémoire et la taille réelle acceptée par la passerelle nécessitent des fonctions et profils spécifiques.

DoIP ne fournit pas de numéro de transaction UDS universel : deux réponses tardives identiques ne peuvent pas toujours être distinguées sur un socket réutilisé. C'est pourquoi un socket est retiré du service après toute incertitude ; des réponses dupliquées malgré la réussite d'une ancienne opération restent une limite à vérifier avec le matériel et les profils OEM. Les profils de sécurité, certificats constructeur, alimentation, mesures de bus et procédures de recovery ne sont pas qualifiés.

**Les essais de passerelle réelle/ECU ne sont prévus que sur la Release**, dans le cahier de tests de l'issue #48. La PR #95 ne requiert aucun test véhicule intermédiaire. Le suivi de la couverture protocolaire est l'issue #110.

---

## English

CamaroDiag Windows Bridge uses **ISO 13400 Diagnostics over IP** to exchange UDS traffic with an explicitly selected gateway. Transport support does not imply vehicle programming support, authorized OEM security access or recovery capability.

Discovery prints the version from each received gateway announcement (`version=0x02`, `0x03` or `0x04`), so the operator can explicitly select the corresponding `--doip-version` for subsequent status/diagnostic operations. UDP announcements are not authenticated; verify the intended gateway.

Implemented software includes UDP IPv4/IPv6 unicast vehicle identification using the standard **0xFF default protocol version solely for discovery request types**, IPv4 broadcast/passive discovery and explicit-interface IPv6 `ff02::1` link-local **passive listening and active identification** (one standard UDP discovery request, no UDS); multicast delivery unverified on vehicle hardware, entity/power status with explicit `--doip-version` 2/3/4 and response-version correlation (0xFF is discovery-only), TCP routing activation and vehicle confirmation, **idle and in-flight AliveCheck handling via one bounded TCP receive pump**, serialized writes and fail-safe retirement on unsolicited idle diagnostics, TLS with operating-system server trust, optional client certificates from the Windows store, diagnostic positive/negative acknowledgements, generic header NACKs, source and target logical-address correlation and configurable P2/P2* deadlines within a separate overall timeout.

Request bytes are defensively captured before asynchronous I/O; later caller mutations cannot change the transmitted UDS payload or ACK correlation. P2/P2* expiry is checked immediately after each TCP read, including boundary races. Local TCP tests include IPv6 loopback and protocol versions 0x02/0x03/0x04; Windows `--help` now describes DoIP in both French and English and refuses mixed help/network invocations.

**Payload limit:** this bridge intentionally caps an in-memory DoIP PDU at **1 MiB (1,048,576 bytes, including the four diagnostic logical-address bytes)**. The DoIP wire header uses a 32-bit length, but this implementation does **not** claim arbitrary maximum-length streaming. Offline tests check the exact in-memory limit and reject oversized diagnostic PDUs; vendor gateway maximum data sizes and OEM transfer strategies require separate qualification.

Only **one diagnostic request per TCP connection can be active at a time**. A successful final UDS response permits another sequential exchange; cancellation, timeout or an uncertain transmission permanently retires the existing connection. No automatic re-send of a potentially mutating ECU operation occurs. Active UDP unicast replies must match both the selected IP address and UDP source port.

Only an explicitly supplied routing activation type (`--routing-activation-type`) and optional four-byte OEM field (`--routing-oem-data`) change the standard 7-byte request into an 11-byte request. The ISO-reserved bytes remain zero. These values do not invent or bypass manufacturer authentication.

Use `--doip-discover --listen true --listen-ipv6-ifindex 3` for passive multicast, or `--doip-discover --multicast-ipv6-ifindex 3` to send a single UDP identification request to IPv6 `ff02::1`. The network interface index is illustrative and must be selected explicitly. Use `--doip-discover` for unicast/broadcast discovery, `--doip-status` for gateway status and `--doip-exchange` for a confirmed diagnostic exchange; `--uds-p2-ms` and `--uds-p2-star-ms` select independent UDS response windows. TLS never silently falls back to clear TCP.

Automated TCP/UDP tests cover fragmented frames, stale acknowledgments, incorrect SID/protocol version, truncated responses, consistent gateway logical addresses across routing-confirmation replies, optional 13-byte OEM routing replies, repeated pending responses, **read and simulated UDS WriteDataByIdentifier (0x2E) with ACK or negative NRC**, and rejection of plaintext when TLS is selected. They use local simulated gateways and **do not validate any real vehicle, VCI, OEM authentication, BRS, or ECU writing/recovery**. Real-world evidence belongs to the owner-authorized Release acceptance plan (issue #48), after PR #95 closes.

### Références de normalisation / Standards

- [ISO 13400-2:2025](https://www.iso.org/standard/13400-2) : définition du protocole de transport et des services réseau ; l'accès à la version normative complète dépend du détenteur de la licence.
- [Eclipse OpenSOVD — DoIP communication](https://eclipse-opensovd.github.io/classic-diagnostic-adapter/03_architecture/03_communication/01_doip_communication.html) : notes publiques d'interopérabilité, notamment version générique 0xFF de découverte.

Les comportements et éditions ISO 13400 selon les constructeurs et les certificats OEM doivent encore être vérifiés lors de la recette Release ; les résultats simulés ne sont pas une certification.
