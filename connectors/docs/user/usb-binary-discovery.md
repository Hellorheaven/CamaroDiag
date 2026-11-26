# Vérifier le boîtier USB — prototype binaire

La variante expérimentale cible uniquement l'ESP32-S3 DevKit isolé : aucun faisceau ECU, alimentation automobile ou mémoire connecté. Le firmware normal reste ASCII. Aucune commande CAN, EEPROM, flash ou J2534 n'est disponible dans les deux variantes.

## Démarrage
Compiler la variante binaire avec PlatformIO :
```sh
pio run -d firmware/esp32s3 -e esp32s3-binary-discovery
```
Après flash et reset contrôlés sur carte isolée, installer pyserial et lancer depuis host/python :
```sh
python -m camarodiagconnectors --port COM5 --binary verify
```
Adapter le port. Le client vérifie PING, CAPABILITIES, STATUS dans cet ordre. Toute anomalie (CRC, longueur, séquence, réponse inattendue) ferme la session ; redémarrer uniquement le prototype isolé avant une nouvelle session. Aucune rétrogradation automatique vers ASCII.

Le CRC32 et l'ordre des séquences détectent certaines erreurs de transfert, mais ne prouvent pas l'identité et ne constituent pas de chiffrement. L'authentification mutuelle reste à implémenter (#8). Aucun accès physique ni programmation autorisée. Le modèle anti-brick #9 reste une simulation. La recette physique USB reste à effectuer (#1).

## CU/1 (development only / développement uniquement)

CU/1 is a separate proposed authenticated read-campaign channel, not a CD/1 discovery opcode. It is not enabled in discovery firmware. It binds a locally provisioned credential to fresh host and device nonces and the exact approved J1979 CAN profile, then consumes one challenge. No pairing, key transport, raw CAN, writes or target power commands are provided. A qualified physical owner and USB session binding are required before deployment.

CU/1 est un canal de campagne en lecture distinct de CD/1, non activé dans le firmware de découverte. Il lie une clé provisionnée localement aux nonces hôte/boîtier et au profil CAN approuvé, avec un challenge à usage unique. Aucun appairage, transport de clé, CAN brut, écriture ou commande d'alimentation. Le propriétaire matériel qualifié et la liaison à la session USB restent nécessaires.
