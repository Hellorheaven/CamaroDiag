# Nouveautés — Sniffer et console CAN

## 2026-11-19
- Ajout de l'analyse des captures CAN et de l'historique des échanges OBD-II / UDS.
- Amélioration du décodage des trames et des messages de diagnostic, y compris les défauts moteur (DTC).
- Ajout des filtres RX/TX, de la recherche dans les trames et de l'export des captures.
- Amélioration de la console de diagnostic et des commandes de lecture disponibles.
- Amélioration de la prise en charge des adaptateurs Bluetooth, BLE, USB et Wi-Fi.

## 2026-11-20
- Regroupement de la capture et des échanges dans une console CAN avec historique chronologique.
- Ajout de l'envoi de commandes et de trames depuis la console, dans les limites prises en charge par l'adaptateur.
- Amélioration de l'affichage des réponses de l'adaptateur et des trames reçues.
- Amélioration de l'export de l'historique, y compris lorsque l'analyse ne détecte pas encore de trames.
- Amélioration de la stabilité des connexions et des échanges avec les adaptateurs BLE.
- Après une écriture BLE interrompue ou expirée, la connexion concernée est abandonnée avant toute nouvelle tentative ; une ancienne réponse tardive ne peut plus valider une commande suivante.
- Amélioration de la gestion des captures interrompues et des reconnexions.
- Lorsqu’une session BLE est fermée ou interrompue, une lecture en attente est réveillée immédiatement au lieu d’attendre l’expiration de son délai.
- Le contrôle de session BLE est également effectué après l'enregistrement du lecteur en attente, afin qu'une fermeture survenue juste avant cet enregistrement ne laisse pas la lecture attendre inutilement.
- Les reconnexions USB et Wi-Fi libèrent mieux les anciennes connexions et ignorent les erreurs tardives provenant d'une session remplacée.
- Les échecs d'ouverture ou de configuration d'un adaptateur USB libèrent également son accès au périphérique.
- Un échec de connexion Bluetooth classique libère désormais la connexion créée pour la tentative.
- Le statut du pont PC J2534 reflète la connexion réseau réelle après une interruption.
- Une coupure pendant les diagnostics, la lecture des défauts, les informations ECU ou la télémétrie interrompt désormais les opérations concernées et remonte l'alerte de connexion perdue.
- La télémétrie n'efface plus cette alerte par un simple message d'échec de lecture et ne relance pas automatiquement les mesures après déconnexion.
- Le statut du pont PC est également remis à zéro lors d'un changement d'adaptateur pour éviter un affichage de connexion périmée.
- Les commandes USB et Wi-Fi sont désormais immédiatement refusées après une coupure détectée, sans tenter de continuer sur une liaison perdue.
- L'historique chronologique RX/TX et le texte déjà capturé restent consultables après une déconnexion ou un changement d'adaptateur ; seul l'effacement demandé par l'utilisateur les supprime.
- Les échanges Bluetooth classiques signalent explicitement une connexion fermée ou une fin de flux, au lieu de traiter ces situations comme une réponse valide.
- Le message de connexion perdue précise désormais si la liaison concernée est Bluetooth, USB, Wi-Fi ou pont PC.

- Ajout de la connexion utilisateur avec Google ou e-mail/mot de passe (évolution #53, intégration en cours).
- Ajout de la création de compte, du lien de vérification e-mail, de la récupération du mot de passe et de la déconnexion.
- Accès à CamaroDiag conditionné à la présence d'une session authentifiée ; configuration Firebase Release et Debug enregistrée.
- Validation sur appareil et empreintes SHA Google encore nécessaires ; aucun build ni test d'authentification confirmé à ce stade.
- Les commandes interrompues par une reconnexion rapide ne peuvent plus traiter une réponse de l'ancienne session comme celle du nouvel adaptateur, ni signaler à tort que cette nouvelle connexion est perdue.
- Préparation de l'affichage des empreintes SHA-1/SHA-256 du certificat de signature lors d'une future publication, sans exposer les secrets de signature. Empreintes encore non récupérées.
- Une tentative de connexion au pont PC qui échoue libère désormais la connexion TCP créée, afin de permettre une nouvelle tentative propre.
- Les instructions d'installation Android et du pont Windows ont été simplifiées autour des installateurs publiés, sans manipulation d'archives de développement.

- Après récupération ponctuelle des empreintes, suppression de leur affichage dans les futures publications. Le workflow dédié a été retiré de `main` ; l'exécution GitHub #44712915798 doit être supprimée manuellement pour masquer ses journaux historiques.
- Une ancienne lecture du pont PC, interrompue par une fermeture volontaire, ne provoque plus une fausse alerte de perte du pont reconnecté.
- Les trames CAN reçues sans octet de données (DLC 0) sont désormais conservées et identifiées comme RX dans l'historique, sans assouplir les règles d'envoi.
- Configuration Firebase actualisée pour les versions normale et Debug après ajout des certificats Google ; l'authentification doit encore être validée sur un APK.
- La vérification préalable à l'envoi CAN brut rejette aussi les réponses incohérentes sur la longueur des données, afin de ne pas activer l'émission à partir d'une réponse mal formée.
