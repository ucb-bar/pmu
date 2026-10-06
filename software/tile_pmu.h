#ifndef TILE_PMU_H
#define TILE_PMU_H

#include "pmu_mmio.h"

/* All accesses are ordered against memory and other I/O. Counter reads refer
 * to the frozen snapshot, so split reads need no rollover retry. The caller
 * must serialize snapshot commands if several contexts share a bank. */
static inline void tile_pmu_fence(void) {
  __asm__ volatile ("fence iorw, iorw" ::: "memory");
}

static inline uint32_t tile_pmu_read32(uintptr_t base, unsigned offset) {
  tile_pmu_fence();
  uint32_t value = *(volatile const uint32_t *)(base + offset);
  tile_pmu_fence();
  return value;
}

static inline void tile_pmu_write32(uintptr_t base, unsigned offset, uint32_t value) {
  tile_pmu_fence();
  *(volatile uint32_t *)(base + offset) = value;
  tile_pmu_fence();
}

static inline uint64_t tile_pmu_read_counter(uintptr_t base, unsigned id) {
  unsigned offset = PMU_MMIO_COUNTER(id);
  uint64_t lo = tile_pmu_read32(base, offset);
  uint64_t hi = tile_pmu_read32(base, offset + 4);
  return lo | (hi << 32);
}

static inline void tile_pmu_enable(uintptr_t base, int enabled) {
  tile_pmu_write32(base, PMU_MMIO_CONTROL, enabled ? PMU_CONTROL_ENABLE : 0);
}

static inline void tile_pmu_command(uintptr_t base, uint32_t command) {
  tile_pmu_write32(base, PMU_MMIO_COMMAND, command);
  /* STATUS.snapshot_valid is sticky, not a command-completion toggle.
   * Completion follows from the ordered MMIO transaction. */
}

#endif
