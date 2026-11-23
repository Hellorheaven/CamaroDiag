# CamaroDiag — FlexFuel LFX 2012 (lecture seule)

## À quoi sert ce relevé ?

Dans l'atelier de calibration d'une Camaro de **2012 à moteur LFX**, CamaroDiag peut lire les informations carburant que le calculateur **annonce réellement** au travers du diagnostic OBD-II SAE Mode 01. Le relevé est ponctuel et n'envoie aucune commande de programmation.

## Matériel et connexion

- Un adaptateur ELM/STN compatible et réellement connecté au véhicule, ou un pont Windows J2534 physique avec un canal OBD standard autorisé.
- Une Camaro Gen 5 de millésime 2012, moteur LFX, sélectionnée dans le garage.
- Un calculateur qui répond aux PID demandés. Tous les calculateurs et toutes les calibrations LFX ne sont **pas** présumés compatibles avec le PID éthanol.

## Utilisation

1. Connecter l'interface physique, puis sélectionner le véhicule.
2. Ouvrir **Atelier de calibration → FlexFuel LFX 2012**.
3. Choisir **Lire l'éthanol ECU et les corrections carburant (lecture seule)**.
4. Consulter la valeur de **teneur en éthanol déclarée par l'ECU** (PID 52) et les corrections de carburant à court et long termes des bancs 1 et 2 (PID 06, 07, 08 et 09).
5. Pour un suivi répété si le calculateur le permet, ouvrir **Télémétrie Live** : le relevé de l'atelier n'est pas une acquisition en continu.

Chaque PID est présenté avec une valeur provenant d'une réponse valide, ou comme **non annoncé par l'ECU**, ou **annoncé mais sans réponse valide**. Une absence de résultat **n'est jamais interprétée comme 0 %**. Le système avertit lorsque le VIN sélectionné n'a pas été rapproché d'une identité ECU physique vérifiée.

## Ce que ce relevé ne prouve pas

Une valeur éthanol, même plausible, ne prouve pas l'installation d'un capteur physique, l'activation logicielle FlexFuel, la compatibilité E85, ni la présence d'une cartographie adéquate. Les corrections STFT/LTFT ne mesurent pas la teneur en éthanol. Les références de calculateur ou de pièces ne qualifient pas non plus le firmware réellement installé.

**Aucune activation E85, aucune écriture ECU, aucune modification des cartographies ni restauration ne sont effectuées par ce parcours.** Une vérification sur véhicule équipé reste nécessaire avant d'affirmer une compatibilité matérielle.
