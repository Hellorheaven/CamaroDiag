# Guide utilisateur — Analyse des traces CAN et UDS

## À quoi sert cette fonction ?

L’analyseur permet d’examiner un fichier de capture CAN déjà enregistré, même lorsque la Camaro et l’adaptateur ne sont plus connectés. Il aide à comprendre les échanges entre un outil de diagnostic et les calculateurs du véhicule.

L’analyse est locale et en lecture seule : l’ouverture d’un fichier ne transmet aucune commande au véhicule.

## Fichiers acceptés

### Capture Vector ASC

Les captures texte au format ASC contenant des trames CAN classiques sont acceptées. Elles peuvent inclure l’horodatage, le canal, l’identifiant CAN, le sens de transmission et les octets de données.

Exemple :

```text
0.000000 1 7E0 Tx d 8 03 22 F1 90 00 00 00 00
0.001000 1 7E8 Rx d 8 07 62 F1 90 31 47 30 31
```

### Texte de surveillance ELM327

L’analyseur accepte les captures texte produites par le mode de surveillance CAN d’un ELM327 lorsque les en-têtes sont activés. Chaque ligne doit contenir l’identifiant CAN, la longueur des données et exactement le nombre d’octets annoncé.

Exemple :

```text
7E0 04 03 22 F1 90
7E8 08 07 62 F1 90 31 47 30 31
```

Certains adaptateurs ELM327 ne proposent pas ce mode ou n’affichent pas des trames complètes. Une capture sans indication TX/RX ou sans horodatage reste analysable, mais le sens et le temps ne peuvent alors pas être déterminés avec certitude.

### Fichier CSV

Le CSV doit contenir une colonne pour l’identifiant CAN et une colonne pour les données. L’horodatage et le sens TX/RX sont facultatifs.

Exemple :

```csv
timestamp,can_id,direction,data
0.000,0x7E0,Tx,"03 22 F1 90 00 00 00 00"
0.010,0x7E8,Rx,"07 62 F1 90 31 47 30 31"
```

Les fichiers séparés par des points-virgules sont également acceptés.

## Préparer et analyser un fichier

1. Enregistrer une capture avec un outil compatible.
2. Exporter la capture au format ASC, CSV ou texte ELM327 accepté ci-dessus.
3. Ouvrir la fonction d’analyse de traces dans CamaroDiag.
4. Sélectionner le fichier à examiner.
5. Consulter les trames, les échanges reconstitués et les informations UDS reconnues.

L’analyseur peut notamment présenter les identifiants CAN, les messages ISO-TP reconstitués, les services UDS reconnus, les réponses positives ou négatives et les identifiants de données observés.

Un identifiant de donnée repéré dans une capture n’est pas automatiquement considéré comme compatible avec une Camaro. Il ne devient pas une commande de diagnostic utilisable sans validation indépendante.

## Limites à connaître

- Les fichiers Vector BLF binaires ne sont pas directement pris en charge. Les exporter d’abord en ASC ou CSV.
- Les captures CAN FD et certains formats ISO-TP étendus ne sont pas entièrement interprétés.
- L’analyseur ne déduit pas à lui seul l’identité d’un calculateur à partir d’un identifiant CAN.
- Il ne décode pas automatiquement les valeurs spécifiques à chaque moteur, année ou calibration.
- Une trame observée ne prouve pas qu’une fonction est disponible sur toutes les Camaro.

## Confidentialité

Une capture peut contenir un VIN, des identifiants de calculateur ou des informations propres au véhicule. Vérifier et masquer ces données avant de transmettre un fichier à une autre personne ou de le publier.

L’analyseur ne modifie pas le fichier d’origine et n’envoie aucune trame CAN ou commande UDS.
