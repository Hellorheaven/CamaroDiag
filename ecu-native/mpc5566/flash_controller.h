#ifndef CAMARODIAG_MPC5566_FLASH_CONTROLLER_H
#define CAMARODIAG_MPC5566_FLASH_CONTROLLER_H

#include "flash_map.h"

/* All offsets relative to the MPC5566 flash controller, not to flash bytes. */
enum {
    CD_MCR = 0x00, CD_LMLR = 0x04, CD_HLR = 0x08, CD_SLMLR = 0x0C,
    CD_LMSR = 0x10, CD_HSR = 0x14
};
#define CD_MCR_EHV UINT32_C(0x00000001)
#define CD_MCR_ESUS UINT32_C(0x00000002)
#define CD_MCR_ERS UINT32_C(0x00000004)
#define CD_MCR_PSUS UINT32_C(0x00000008)
#define CD_MCR_PGM UINT32_C(0x00000010)
#define CD_MCR_STOP UINT32_C(0x00000040)
#define CD_MCR_PEG UINT32_C(0x00000200)
#define CD_MCR_DONE UINT32_C(0x00000400)
#define CD_MCR_PEAS UINT32_C(0x00000800)
#define CD_MCR_RWE UINT32_C(0x00004000)
#define CD_MCR_EER UINT32_C(0x00008000)

typedef enum {
    CD_FLASH_OK = 0,
    CD_FLASH_INVALID,
    CD_FLASH_LOCKED,
    CD_FLASH_NOT_READY,
    CD_FLASH_NOT_ERASED,
    CD_FLASH_IO_FAILURE,
    CD_FLASH_RECOVERY_REQUIRED
} cd_flash_result;

typedef struct {
    void *context;
    bool (*read_reg)(void *context, uint32_t offset, uint32_t *value);
    bool (*write_reg)(void *context, uint32_t offset, uint32_t value);
    bool (*read_word)(void *context, uint32_t address, uint32_t *value);
    bool (*write_word)(void *context, uint32_t address, uint32_t value);
    /* Must order MMIO and flash bus access. */
    void (*barrier)(void *context);
    /* Services the board watchdog and enforces a qualified deadline. */
    bool (*service)(void *context);
} cd_flash_bus;

typedef struct {
    cd_flash_bus bus;
    bool recovery_required;
} cd_flash_controller;

bool cd_flash_init(cd_flash_controller *controller, const cd_flash_bus *bus);
cd_flash_result cd_flash_probe_program(cd_flash_controller *controller,
    uint32_t address, uint32_t size);
cd_flash_result cd_flash_erase(cd_flash_controller *controller,
    uint32_t address, uint32_t size);
cd_flash_result cd_flash_program(cd_flash_controller *controller,
    uint32_t address, const uint8_t *bytes, uint32_t size);

#endif
