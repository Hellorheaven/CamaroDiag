# Pont J2534 Windows — Guide d'installation

Le pont relie CamaroDiag sur Android à un fournisseur SAE J2534 Pass-Thru installé sur un PC Windows. Il peut fonctionner avec des pilotes VXDIAG VCX SE ou VCX Nano lorsque le modèle exact, le pilote et le protocole sont compatibles.

## Fonctionnalités

- Connexion à un fournisseur J2534 Windows enregistré.
- ISO15765 HS-CAN, identifiants 11 ou 29 bits, 250/500 kbit/s.
- SW-ISO15765 GMLAN à 62,5 kbit/s si le fournisseur J2534-2 l'accepte.
- Transmission de charges UDS ; le fournisseur J2534 gère ISO-TP et le contrôle de flux.
- Lecture UDS limitée aux services 0x22, 0x19 et 0x3E.
- Un client Android à la fois, protégé par un jeton d'appairage.

Ce pont est générique J2534. Il ne fournit aucun pilote VXDIAG et ne remplace ni VX Manager ni le DLL PassThru du fabricant.

## Matériel requis

- PC Windows 10 ou 11.
- Interface J2534 avec pilote constructeur installé et enregistré.
- CamaroDiag sur Android 10 ou supérieur.
- PC et Android connectés au même réseau privé.
- Architecture x86/x64 du pont identique à celle du DLL J2534.

## Compatibilité VXDIAG

| Interface | HS-CAN | SW-CAN / GMLAN | Conditions |
|---|---|---|---|
| VCX SE | Oui si exposé par le fournisseur J2534 | Possible si le pilote expose J2534-2 SW-ISO15765 | Pilote correspondant à la référence exacte |
| VCX Nano | Oui si exposé par le fournisseur J2534 | Dépend de la révision, du câblage et du pilote ; non garanti | Vérifier modèle et pilote |

## Installation et connexion

1. Installer le pilote Windows correspondant exactement au VCX ou à l'interface J2534.
2. Pour VXDIAG, installer VX Manager et vérifier la détection de l'interface.
3. Ouvrir les [releases CamaroDiag](https://github.com/Hellorheaven/CamaroDiag/releases/latest).
4. Télécharger l'installateur **Setup Release** correspondant à l'architecture du DLL J2534 :
   - `CamaroDiag-J2534-Bridge-Setup-release-win-x64.exe` pour un fournisseur J2534 64 bits.
   - `CamaroDiag-J2534-Bridge-Setup-release-win-x86.exe` pour un fournisseur J2534 32 bits.
5. Lancer l'installateur. Il installe le pont dans le profil Windows courant, sans droits administrateur, et crée un raccourci dans le menu Démarrer. Le raccourci Bureau est facultatif.
6. Lancer **CamaroDiag J2534 Bridge** depuis le menu Démarrer. Si plusieurs fournisseurs sont présents, sélectionner celui à utiliser.
7. Relever l'adresse IP du PC, le port TCP et le jeton d'appairage affichés.
8. Connecter le PC et Android au même réseau privé.
9. Saisir IP, port, jeton et CAN ID physiques documentés du calculateur dans CamaroDiag.
10. Si Windows Firewall le demande, autoriser le programme uniquement sur les réseaux privés.

Les installateurs **Debug** sont également publiés pour les tests. Debug et Release sont installés dans des emplacements séparés et peuvent coexister. Le pont vérifie les mises à jour au démarrage et ne propose que le même canal et la même architecture que la version installée.

Les archives ZIP portables restent disponibles dans les mêmes releases pour une installation manuelle ou un usage sans installateur. Le programme nécessite toujours le pilote J2534 du fabricant : aucun pilote VXDIAG n'est inclus dans le setup.


## Sécurité réseau

La liaison utilise TCP et un jeton, sans chiffrement TLS. Ne jamais exposer le port à Internet, ne pas configurer de redirection de port et ne pas utiliser le pont sur un Wi-Fi public.

## Fonctions et limites

| Fonction | État |
|---|---|
| UDS ReadDataByIdentifier (0x22) | Autorisé |
| UDS ReadDTCInformation (0x19) | Autorisé |
| TesterPresent (0x3E) | Autorisé |
| Écriture DID, effacement UDS | Bloqués |
| RoutineControl, actionneurs | Bloqués |
| SecurityAccess, lecture/écriture mémoire, flashage | Bloqués |
| CAN FD | Non pris en charge |
| Découverte automatique de tous les calculateurs GM | Non fournie |

Les CAN ID, débits et services doivent provenir d'une documentation fiable pour le VIN et le calculateur. Le pont ne devine aucune adresse ni procédure de programmation.

## Dépannage

- Aucun fournisseur détecté : réinstaller le pilote constructeur et vérifier l'enregistrement J2534.
- Erreur DLL : vérifier que le pont et le DLL fournisseur sont tous deux x86 ou tous deux x64.
- Connexion TCP impossible : vérifier IP, port, réseau et pare-feu.
- Échec d'ouverture du protocole : le pilote peut ne pas prendre en charge le profil sélectionné.
- Aucune réponse ECU : vérifier contact, broches DLC, débit CAN et paire d'identifiants physiques.
- SW-CAN indisponible : le matériel ou le pilote peut ne pas exposer le protocole J2534-2 requis.

Le pont est un transport de diagnostic. Il ne certifie ni la compatibilité d'un calculateur ni l'aptitude d'une interface à programmer un véhicule.
