# CamaroDiag MPC5566 native flash components

Original implementation of the flash block mapping for the MPC5566 family, based on [NXP MPC5566RM, Rev. 2, table 13-4](https://www.nxp.com/docs/pcn_attachments/15160_MPC5566RM.pdf) and [AN3903](https://www.nxp.com/docs/en/application-note/AN3903.pdf). No third-party kernel is included.

These components are not loaded on an ECU by CamaroDiag. The E39/E39A board, watchdog, RAM entry, CAN configuration, silicon revision and recovery path must be validated on a sacrificial bench ECU before a production programming capability is enabled. The map excludes shadow flash and prevents erasing or programming the E39 protected 128 KiB prefix.

Host test: from this directory, run `cc -std=c11 -Wall -Wextra -Werror -pedantic flash_map.c tests/flash_map_test.c -o /tmp/cd-flash-map && /tmp/cd-flash-map`.
