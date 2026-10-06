# Software

The `software/` directory provides C definitions for the
[tile PMU register map](tile-pmu.md#register-map) and a generator for
platform-specific headers.

| File | Contents |
|---|---|
| `pmu_mmio.h` | Register offsets, bit fields and INFO decoding macros. |
| `tile_pmu.h` | Ordered MMIO accessors for RISC-V. |
| `generate_platform_header.py` | Generates `tile_pmu_platform.h` for one tile of one build. |

## Accessors

```c
void     tile_pmu_fence(void);                                // fence iorw, iorw
void     tile_pmu_enable(uintptr_t base, int enabled);
void     tile_pmu_command(uintptr_t base, uint32_t command);  // PMU_COMMAND_CLEAR, PMU_COMMAND_SNAPSHOT
uint32_t tile_pmu_read32(uintptr_t base, unsigned offset);
void     tile_pmu_write32(uintptr_t base, unsigned offset, uint32_t value);
uint64_t tile_pmu_read_counter(uintptr_t base, unsigned id);  // captured value of event `id`
```

Every access is surrounded by `fence iorw, iorw`, so a command is ordered before
the reads that follow it. `tile_pmu_read_counter` reads the two 32-bit halves of
the captured value.

## Platform header

The generator joins two outputs of the same build:

- the tile's event list, `<config>.<source>.pmu.json` ([format](event-list.md));
- Chipyard's JSON device tree, `<config>.json`.

```sh
python3 software/generate_platform_header.py \
  --manifest <config>.boom_tile_0.pmu.json \
  --platform <config>.json \
  --output build/tile_pmu_platform.h
```

It finds the device-tree node whose `pmu,source` equals the event list's `source`
and writes:

| Definition | Value |
|---|---|
| `TILE_PMU_SOURCE` | The source string. |
| `TILE_PMU_BASE` | Base address of the tile's register region. |
| `TILE_PMU_COUNTER_COUNT` | Number of events. |
| `TILE_PMU_COUNTER_BITS` | Counter width, from `pmu,counter-bits`. |
| `TILE_PMU_EVENTS(X)` | An X-macro calling `X(symbol, id, name, unit)` for every event, in ID order. |
| `enum tile_pmu_event_id` | `TILE_PMU_EVENT_<SYMBOL> = id` for every event. |

`symbol` is the event name in upper case with every character outside
`[A-Za-z0-9_]` replaced by `_`; `rob.instructions_retired` becomes
`ROB_INSTRUCTIONS_RETIRED`. The generator exits with an error if the device tree
has no matching tile PMU, or if two events map to the same symbol.

The generated header belongs to one build. Generate it again for each build, and
keep it with that build's binaries.

## Example

```c
#include <inttypes.h>
#include <stdio.h>
#include "tile_pmu_platform.h"

static uint64_t values[TILE_PMU_COUNTER_COUNT];

void measure(void (*workload)(void)) {
  tile_pmu_command(TILE_PMU_BASE, PMU_COMMAND_CLEAR);
  tile_pmu_enable(TILE_PMU_BASE, 1);
  workload();
  tile_pmu_enable(TILE_PMU_BASE, 0);
  tile_pmu_command(TILE_PMU_BASE, PMU_COMMAND_SNAPSHOT);

#define READ(symbol, id, name, unit) values[id] = tile_pmu_read_counter(TILE_PMU_BASE, id);
  TILE_PMU_EVENTS(READ)
#undef READ
#define PRINT(symbol, id, name, unit) printf("%s=%" PRIu64 " %s\n", name, values[id], unit);
  TILE_PMU_EVENTS(PRINT)
#undef PRINT
}
```

Counting starts on the cycle after the enable write completes and stops on the
cycle the disable write completes, so the counts include the accessor code that
runs between those two writes as well as the workload.
