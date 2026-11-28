# STM32G474 development firmware

Prototype only, all proposed target pins high impedance, no actuator, SPI bus or vehicle connection. Use only an isolated NUCLEO-G474RE board via ST-LINK USB. Build: `pio run -d firmware/stm32g4`. The serial output is a diagnostic heartbeat, not proof of physical interlock or authenticated device state. The board must be electrically qualified before any interprocessor or ECU cable is attached. PlatformIO board reference: https://docs.platformio.org/en/stable/boards/ststm32/nucleo_g474re.html.

## J1979 driver integration / Intégration du pilote

The FDCAN and SPI HAL adapters and one-shot session coordinator are compiled separately from discovery. A qualified board owner must provide local admission, the exact CAN profile, independent TX disable and quiescence verification, READY, NSS and an exact SPI clock-count proof. No GPIO assignment or transceiver enable is supplied by these adapters. The default main remains isolated and high impedance.

Les adaptateurs HAL FDCAN/SPI et le coordinateur de session sont séparés du firmware de découverte. Le propriétaire matériel doit fournir les preuves locales, le profil CAN exact, l'interverrouillage TX indépendant, la vérification d'arrêt et les signaux READY/NSS avec comptage exact des horloges SPI. Aucun brochage ni activation du transceiver n'est ajouté. Référence HAL : https://github.com/STMicroelectronics/stm32g4xx-hal-driver.

The qualified HAL board lifecycle configures a single exact RX filter, rejects non-matching and remote frames, starts FDCAN with TX independently inhibited, and requires measured gate state before admission. Shutdown first inhibits the physical gate, aborts pending transfers and checks HAL/physical quiescence. A generation change, failed cutoff or uncertain stop latches recovery. The A0 candidate wiring does not provide the required verified interlock; default firmware remains disabled.

Le cycle HAL sélectionne un filtre RX exact, bloque les autres trames et démarre avec TX inhibé physiquement. L'arrêt coupe d'abord TX, annule les transferts puis vérifie les états HAL et physiques. Aucun câblage Rev A0 supposé qualifié.
