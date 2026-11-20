# Guide utilisateur — Signaux spécifiques Chevrolet Camaro

## Deux niveaux d’informations

CamaroDiag distingue les informations OBD-II standard des signaux spécifiques aux Camaro.

### Données OBD-II standard

Elles sont fournies par le calculateur moteur selon les services normalisés. Leur disponibilité dépend du véhicule, de son moteur, de sa calibration et de l’adaptateur connecté.

### Signaux communautaires Camaro

CamaroDiag intègre un catalogue communautaire issu du projet **OBDb Chevrolet Camaro**. La version intégrée comprend des commandes et définitions de signaux qui peuvent aider à consulter des informations supplémentaires lorsque le calculateur et l’interface répondent.

Ce catalogue communautaire n’est ni publié ni certifié par General Motors. Une définition présente dans le catalogue ne garantit pas qu’elle fonctionne sur chaque Camaro.

Source : [OBDb Chevrolet Camaro](https://github.com/OBDb/Chevrolet-Camaro), fichier `signalsets/v3/default.json`, révision source `6771593698dff29cc0c6e8d6219ff360b2891883`. Les données correspondantes sont distribuées sous licence [Creative Commons CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/).

## Informations spécifiques vérifiées

Les données propriétaires GM ne sont pas déclarées compatibles simplement parce qu’un identifiant ou une commande a été trouvé sur Internet ou dans une capture. La compatibilité doit être confirmée pour l’année, le moteur, le calculateur et la configuration concernés.

À ce jour, CamaroDiag ne présente pas de catalogue propriétaire GM complet et validé couvrant toutes les années et toutes les motorisations. Les informations qui ne sont pas suffisamment confirmées restent indisponibles plutôt que d’afficher une valeur supposée.

## Pourquoi un signal peut-il manquer ?

Un signal peut ne pas apparaître ou ne pas répondre pour plusieurs raisons :

- Le moteur ou la version du véhicule ne possède pas cette fonction.
- Le calculateur utilise une calibration différente.
- L’adaptateur ne permet pas l’accès au réseau ou au service requis.
- Le calculateur ne répond pas à cette demande.
- La définition ne s’applique pas à cette année, finition ou transmission.

Une absence de réponse ne signifie donc pas automatiquement qu’un capteur ou un calculateur est défectueux.

## Compatibilité et sécurité

Les fonctions étendues dépendent de l’interface réellement utilisée. Un ELM327 standard ne donne pas automatiquement accès aux données propriétaires GM. Certains accès nécessitent un OBDLink MX+ compatible ou un pont PC utilisant une interface J2534 adaptée.

Le catalogue de signaux sert à la consultation et au diagnostic. Il n’autorise pas l’écriture de paramètres, le codage, le déverrouillage de calculateurs ou le flashage.
