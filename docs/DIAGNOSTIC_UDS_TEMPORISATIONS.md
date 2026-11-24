# Diagnostic UDS — délais de réponse P2 et P2* / UDS response timing

## Français

Un calculateur répond normalement dans la fenêtre de temps **P2**. S'il indique que son traitement continue avec le code standard `7F <SID> 78` (ResponsePending), le client peut attendre dans la fenêtre **P2***. Chaque nouveau message d'attente correct renouvelle la fenêtre P2*, **sans prolonger le délai global** choisi pour l'échange.

Le pont CamaroDiag dispose désormais d'une vérification logicielle de ces deux limites : une réponse arrivée trop tard est signalée comme un dépassement de délai, même si un pilote ou un réseau a rendu cette réponse après sa propre attente. Le service, la réponse négative et le SID doivent toujours correspondre à la transaction demandée.

**État actuel :** ce réglage est disponible dans le moteur UDS utilisé par le logiciel. La commande Windows `--isotp-exchange` permet de définir `--uds-p2-ms` et `--uds-p2-star-ms` lorsqu'une validation UDS est demandée (`--uds-validate true`) ou implicite dans une opération UDS normalisée. Ces limites sont exprimées en millisecondes (1 à 30 000), sans modifier `--timeout-ms`. **Le chemin DoIP, les profils OEM et les timings réels des calculateurs restent à qualifier/raccorder.** En l'absence de profil explicite, le délai hôte de compatibilité est de 30 secondes par étape, avec un délai global de transaction séparé. Cette valeur n'est pas une garantie de conformité constructeur.

Aucun test sur voiture n'est requis pendant le développement de la PR #95. Le propriétaire réalisera la recette matérielle du Lot 8 (#48) sur la Release autorisée. Les durées devront être relevées avec l'ECU, l'adaptateur et les versions réellement utilisés. La validation de ces temporisations ne garantit **ni la possibilité d'écrire, ni la restauration d'un ECU**.

## English

An ECU normally answers within the **P2** response window. When it returns the standard `7F <SID> 78` (ResponsePending), the client may wait for a **P2*** response. Each valid new pending response starts another P2* window, but **never restarts the overall exchange deadline**.

CamaroDiag now checks both software-stage deadlines, including a reply delivered late by a device driver. The request/response service correlation and negative responses remain checked.

**Current scope:** P2/P2* configuration exists in the UDS engine. The Windows `--isotp-exchange` CLI accepts `--uds-p2-ms` and `--uds-p2-star-ms` for a validated UDS exchange (for example with `--uds-validate true`), with bounds of 1–30,000 ms. The independent `--timeout-ms` overall deadline is unchanged. **DoIP CLI settings and real ECU/OEM timing profiles are not yet connected or qualified.** Without explicit configuration the host compatibility cap remains 30 seconds per stage, in addition to the separate overall timeout. This is not a manufacturer timing certification.

Real VCI/ECU testing is deferred to the owner-authorized Release and the Lot 8 test plan (#48); no ECU writing or recovery capability is implied.
