#!/usr/bin/env python3
"""Join a tile's PMU event list and Chipyard's JSON device tree into a C header."""
import argparse
import json
import re
from pathlib import Path


def generate(manifest, platform):
    if manifest.get('schemaVersion') != 2:
        raise ValueError('a schema-v2 PMU event list is required')
    source = manifest['source']
    events = manifest['events']
    if not events or [e['id'] for e in events] != list(range(len(events))):
        raise ValueError('event IDs must equal their positions')
    names = [e['name'] for e in events]
    if any(not name for name in names):
        raise ValueError('event names must be nonempty')
    symbols = [re.sub(r'[^A-Za-z0-9_]', '_', name).upper() for name in names]
    if len(set(symbols)) != len(symbols):
        raise ValueError('event names collide after conversion to C identifiers')
    # Chipyard ResourceBinding JSON represents addresses as base/size objects;
    # this is not a generic flattened-device-tree parser or bus translator.
    soc = platform['soc']
    if soc.get('ranges') != []:
        raise ValueError('expected a directly mapped Chipyard soc bus')
    devices = [d for d in soc.values() if isinstance(d, dict) and
               d.get('pmu,source') == [source] and
               'ucbbar,pmu-v1' in d.get('compatible', [])]
    if len(devices) != 1 or len(devices[0]['reg']) != 1:
        raise ValueError(f'expected exactly one tile PMU for {source}')
    device = devices[0]
    counter_bits = device.get('pmu,counter-bits')
    if not (isinstance(counter_bits, list) and len(counter_bits) == 1 and
            isinstance(counter_bits[0], int) and 1 <= counter_bits[0] <= 64):
        raise ValueError(f'invalid pmu,counter-bits for {source}')
    region = device['reg'][0]
    base = region['base']
    if base < 0 or base % 4096 or region['size'] != 4096 or 0x100 + 8 * len(events) > 4096:
        raise ValueError('invalid PMU MMIO region or counter count')
    lines = [
        '/* Generated from this elaboration; do not edit. */',
        '#ifndef TILE_PMU_PLATFORM_H', '#define TILE_PMU_PLATFORM_H',
        '#include "tile_pmu.h"',
        f'#define TILE_PMU_SOURCE {json.dumps(source)}',
        f'#define TILE_PMU_BASE ((uintptr_t)UINT64_C(0x{base:x}))',
        f'#define TILE_PMU_COUNTER_COUNT {len(events)}u',
        f'#define TILE_PMU_COUNTER_BITS {counter_bits[0]}u',
        '', '/* X(symbol, id, name, unit) */', '#define TILE_PMU_EVENTS(X) \\',
    ]
    for i, (event, symbol) in enumerate(zip(events, symbols)):
        suffix = ' \\' if i + 1 < len(events) else ''
        lines.append(f'  X({symbol}, {event["id"]}, {json.dumps(event["name"])}, '
                     f'{json.dumps(event["unit"])}){suffix}')
    lines += [
        '', '#define TILE_PMU_ENUM(symbol, id, name, unit) TILE_PMU_EVENT_##symbol = id,',
        'enum tile_pmu_event_id { TILE_PMU_EVENTS(TILE_PMU_ENUM) };',
        '#undef TILE_PMU_ENUM', '', '#endif', '',
    ]
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True,
                        help="the tile's event list, <config>.<source>.pmu.json")
    parser.add_argument('--platform', type=Path, required=True,
                        help="Chipyard's JSON device tree, <config>.json")
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        header = generate(json.loads(args.manifest.read_text()),
                          json.loads(args.platform.read_text()))
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(header)


if __name__ == '__main__':
    main()
