# ISO-TP sur CAN classique — procédure de qualification CamaroDiag

Cette procédure permet de **vérifier sur un banc autorisé** le transport ISO 15765-2 (CAN 11 et 29 bits) de CamaroDiag, via un pilote SAE J2534. Elle concerne le *transport*, pas la compatibilité avec tous les calculateurs ni une autorisation constructeur de reprogrammation.

## Capacités logicielles déjà démontrées par les autotests

Le pont Windows assure : échanges SF/FF/CF/FC, adresses normales/étendues/mixtes, contrôle des numéros de séquence, Flow Control CTS/WAIT/OVFLW, Block Size, STmin, temporisations N_Bs/N_Cr, longueur First Frame 12/32 bits, trames de 7/8 et 4095/4096 octets, budget temporel global, filtrage d'identité et annulation. Les sorties J2534 sont encodées comme un **identifiant CAN sur quatre octets suivi de données brutes**. Ne jamais utiliser un canal J2534 `ISO15765` avec ce moteur : le pilote y effectue déjà la segmentation.

Les tests Windows exécutés sur la PR #95 sont **des simulations**, pas une preuve que les DLL d'un fabricant, les transceivers ou un véhicule respectent les timings ISO-TP. Le délai entre deux CF est mesuré côté hôte depuis l'appel de transmission précédent. Un horodatage du pilote ou du bus est indispensable pour vérifier l'intervalle effectivement observé sur CAN.

## Conditions préalables au banc

1. Utiliser une alimentation stabilisée, un calculateur ou simulateur ECU autorisé, des terminaisons CAN correctes et un appareil de capture CAN horodaté indépendant. Identifier le modèle, le firmware et la version exacte de la DLL SAE J2534, sa prise en charge de `CAN (0x05)` et le débit choisi.
2. Relever **avant tout essai** l'identité ECU, les vrais identifiants CAN TX/RX, le format 11 ou 29 bits et le débit configuré. Ne pas déduire ces données du VIN seul.
3. Réserver les injections de trames aberrantes, essais de déconnexion et dépassements de taille à un **simulateur ou calculateur de laboratoire**. Ne pas provoquer de bus-off ni envoyer des charges arbitraires sur un véhicule de production.
4. Tous les essais de lecture sur un ECU réel restent soumis à ses autorisations. Toute écriture passe par l'avertissement **OK puis la confirmation Oui/Non**, suivi des vérifications d'identité, cartographie et sauvegarde physique prévues pour l'ECU. Une trame ISO-TP bien formée ne prouve aucune possibilité de flash ou restauration.

## Essai non destructif de lecture

Sur un appareil **Windows** muni d'un J2534 opérationnel, depuis la racine du dépôt, remplacer l'index d'adaptateur et les ID par ceux vérifiés sur le banc. Exemple illustratif **CAN 11 bits, 500 kbit/s**, si l'ECU prend en charge la lecture UDS du VIN (DID F190) :

```powershell
dotnet run --project pc-bridge/CamaroDiag.J2534Bridge/CamaroDiag.J2534Bridge.csproj -- --isotp-exchange --adapter-index 0 --tx-id 7E0 --rx-id 7E8 --baud 500000 --fd false --payload 22F190 --uds-validate true --n-bs-ms 1000 --n-cr-ms 1000 --timeout-ms 5000
```

La CLI demande volontairement les **deux confirmations opérateur** avant tout échange actif, même si la charge utile illustrative est une lecture. L'absence de réponse ou un rejet ECU ne doit jamais être présenté comme une preuve d'écriture ou de lecture universelle. Pour un ECU 29 bits, utiliser les vrais ID préalablement identifiés et `--extended true`. Ne pas inventer une paire 29 bits à partir de l'exemple 11 bits.

## Matrice de recette avec équipement réel

| Scénario | Moyen de preuve | Critère de réussite |
| --- | --- | --- |
| J2534 CAN 11 bits, lecture UDS approuvée | Traces DLL et capture CAN indépendante | ID TX/RX, bitrates, octets et réponse finale concordants |
| J2534 CAN 29 bits et adressages étendu/mixte | Simulateur ECU compatible + capture | Adresses logiques / N_AE et identifiants 29 bits correctement différenciés |
| Segmentation 7/8 et 4095/4096 octets | Simulateur ISO-TP uniquement | FF_DL, CF, compteur modulo 16 et taille finale exacts |
| Plusieurs fenêtres BS et FC WAIT/OVFLW | Simulateur avec scénarios contrôlés | Aucune CF après Overflow ou expiration, FC et numéros conformes |
| STmin 0, 5 ms, 100 µs et valeur réservée conservée jusqu'à la fin du transfert | Analyseur CAN horodaté | Intervalle **entre CF réellement émises** compatible avec le délai choisi ; les limites de l'OS/VCI sont mesurées et signalées |
| Délais N_Bs, N_Cr et budget total | Simulateur induisant pertes et retards | Timeout correct, aucun faux succès et aucune émission après annulation |
| CAN étranger, DLC/ID erroné, trame dupliquée | Simulateur et journal d'erreurs | Trames ignorées/rejetées sans réassemblage incorrect |
| Coupure VCI, panne de driver, bus-off | Banc isolé | Erreur explicite, pas de poursuite silencieuse ; reconnexion exige une nouvelle session |
| Compatibilité sur ECU réel | ECU(s) précisément identifiés | Lecture légitime répétée, captures conservées et identités constatées |
| Diagnostic/programmation avec octets d'écriture | **Procédure ECU/OEM séparée** avec sauvegarde physique, vérification et consentement | Qualification ECU individuelle seulement, jamais déduite des tests de transport |

## Journalisation optionnelle pendant les essais

L'option `--trace-file isotp-essai-01.csv` ajoutée à la commande `--isotp-exchange` enregistre dans un **nouveau fichier seulement** (`CreateNew`) les intentions d'émission (`TX_REQUEST`), les confirmations de mise en file J2534 (`TX_QUEUED`) et les trames reçues (`RX`) avec un horodatage **côté PC**, en microsecondes relatives. Aucun fichier n'est créé sans cette option. Les journaux peuvent contenir VIN et données ECU : les conserver localement.

**Attention :** les durées de ces traces ne sont pas des horodatages physiques du bus. `TX_QUEUED` n'est pas un ACK de l'ECU ; la capture CAN indépendante reste obligatoire pour qualifier STmin. Si l'écriture du journal échoue après une transmission, **ne jamais relancer automatiquement**.

## Attestation et critères de clôture

Consigner pour chaque campagne : commit exact et CI Windows réussie, date, adaptateur/firmware/DLL, type et débit CAN, identité ECU ou simulateur, trames horodatées, essais positifs/négatifs, erreurs, différences et signatures de l'opérateur responsable.

**Statut tant que ces preuves manquent :** couche ISO-TP logicielle testée, **transport physique non qualifié**, validation d'écriture ECU **non établie**. Le protocole n'est déclaré achevé sur un matériel déterminé que lorsque tous les scénarios pertinents ci-dessus sont prouvés, et non sur la seule base d'un pourcentage ou d'une CI verte.
