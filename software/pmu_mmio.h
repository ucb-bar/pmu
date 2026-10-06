#ifndef PMU_MMIO_H
#define PMU_MMIO_H

#include <stdint.h>

/* Tile PMU register map, version 1. Discover the base address from the platform
 * device tree. Counter IDs come from the tile's generated event list. */
#define PMU_MMIO_VERSION         1u
#define PMU_MMIO_APERTURE        0x1000u
#define PMU_MMIO_CONTROL         0x000u
#define PMU_MMIO_COMMAND         0x008u
#define PMU_MMIO_STATUS          0x010u
#define PMU_MMIO_INFO            0x018u
#define PMU_MMIO_COUNTER_BASE    0x100u
#define PMU_MMIO_COUNTER_STRIDE  8u
#define PMU_MMIO_COUNTER(id)     (PMU_MMIO_COUNTER_BASE + PMU_MMIO_COUNTER_STRIDE * (id))

#define PMU_CONTROL_ENABLE       UINT32_C(1)
#define PMU_COMMAND_CLEAR        UINT32_C(1)
#define PMU_COMMAND_SNAPSHOT     UINT32_C(2)
#define PMU_STATUS_ENABLED       UINT32_C(1)
#define PMU_STATUS_SNAPSHOT_VALID UINT32_C(2)

/* INFO low word: version[15:0], count[31:16]. High word: width[15:0]. */
#define PMU_INFO_VERSION(lo)     ((uint32_t)(lo) & UINT32_C(0xffff))
#define PMU_INFO_COUNT(lo)       (((uint32_t)(lo) >> 16) & UINT32_C(0xffff))
#define PMU_INFO_WIDTH(hi)       ((uint32_t)(hi) & UINT32_C(0xffff))

#endif
