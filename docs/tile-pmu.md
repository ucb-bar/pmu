# Tile PMU

The tile PMU is a bank of counters inside the tile, one per event. Software
controls and reads it through a 4 KiB TileLink register region.

```scala
// package pmu, sbt project pmu
final case class TilePMUParams(baseAddress: BigInt, counterBits: Int = 64)

object TilePMU {
  def build(events: PMUEvents, counterBits: Int = 64): PMUAttachment
}

final case class PMUAttachment(
  enable: Bool,                // input, level: count while high
  clear: Bool,                 // input, pulse: zero the running counters
  snapshot: Bool,              // input, pulse: capture all counters
  values: Vec[UInt],           // output: running counters, in event-ID order
  snapshot_values: Vec[UInt],  // output: captured counters, in event-ID order
  snapshot_valid: Bool,        // output: a snapshot has been taken since reset
  manifest: PMUManifest,
  counterBits: Int
)

// package pmu.tilelink, sbt project pmu_tilelink
final class PMUTileLink(val params: TilePMUParams, beatBytes: Int, val source: String)
                       (implicit p: Parameters) {
  val node: TLRegisterNode
  def attach(tilePMU: PMUAttachment): Unit
}
```

## Configuration

A tile builds a tile PMU when its `pmu` parameter has `tilePMU` set:

```scala
pmu = Some(PMUParams(tilePMU = Some(TilePMUParams(baseAddress = 0x40000000))))
```

| Parameter | Meaning |
|---|---|
| `baseAddress` | Base of the tile's 4 KiB register region. 4 KiB aligned. Each tile with a tile PMU uses its own region. |
| `counterBits` | Width of each counter, at most 64. Counters wrap modulo 2^`counterBits`. |

## Building the tile PMU

1. In the tile's LazyModule constructor, create the register interface and
   connect its node to a TileLink bus in the tile's clock domain, for example
   the tile's slave port:

   ```scala
   // pmu: the tile's Option[PMUParams]
   val pmuMMIO = pmu.flatMap(_.tilePMU).map { params =>
     val mmio = new PMUTileLink(params, beatBytes, tileParams.uniqueName)
     connectTLSlave(mmio.node, beatBytes)
     mmio
   }
   ```

2. At the end of the tile's module body, after `registry.build()`, build the
   counters and attach them to the register interface:

   ```scala
   val tilePMU = for (events <- pmuEvents; mmio <- outer.pmuMMIO) yield {
     val attachment = TilePMU.build(events, mmio.params.counterBits)
     mmio.attach(attachment)
     attachment
   }
   ```

`TilePMU.build` instantiates the counters in the clock and reset recorded in
`events`. It throws during elaboration if an increment's width is known and
exceeds `counterBits`. `attach` drives `enable`, `clear` and `snapshot` from the
registers. It may be called once, with an attachment whose `manifest.source` and
`counterBits` match the interface's `source` and `params.counterBits`.

`TilePMU.build` can also be used without `PMUTileLink`. The caller then drives
`enable`, `clear` and `snapshot` and reads `values` or `snapshot_values` directly.

## Counting

All counters share one enable, so they always cover the same window.

On each clock edge, with `increment[i]` the event's increment on that cycle:

```text
next[i] = running[i] + (enable ? increment[i] : 0)    (mod 2^counterBits)
```

- **snapshot**: all `next[i]` are captured together into `snapshot_values`, and
  `snapshot_valid` is set.
- **clear**: all running counters become zero. Otherwise they become `next[i]`.
- **snapshot and clear together**: the snapshot captures `next[i]`, including that
  cycle's increments, and the running counters become zero. Consecutive
  snapshot-and-clear commands produce back-to-back windows.
- Clear leaves `enable`, `snapshot_values` and `snapshot_valid` unchanged.
- Captured values stay unchanged until the next snapshot or reset.
- **Reset**: running and captured counters become zero, `snapshot_valid` is
  cleared, and the register interface's enable becomes 0.

## Register map

Offsets are relative to `baseAddress`. The interface works on 32-bit and 64-bit
TileLink register buses.

| Offset | Register | Access | Contents |
|---|---|---|---|
| `0x000` | CONTROL | RW | Bit 0: enable. |
| `0x008` | COMMAND | W, reads 0 | Write 1 to bit 0 to clear, to bit 1 to snapshot. Writing both snapshots, then clears. |
| `0x010` | STATUS | RO | Bit 0: enabled. Bit 1: snapshot valid. |
| `0x018` | INFO | RO | Bits 15:0: register-map version (1). Bits 31:16: counter count. Bits 47:32: counter width. |
| `0x100 + 8*i` | COUNTER[i] | RO | Captured value of the event with ID `i`. Low 32 bits at `+0`, high 32 bits at `+4`. |

- Bits not listed read as zero and ignore writes. Offsets not listed read as
  zero and ignore writes.
- COUNTER[i] returns the snapshot, so a value read as two 32-bit halves is
  consistent. Counters narrower than 64 bits are zero-extended.
- A region holds at most 480 counters.
- Each COMMAND write takes effect exactly once, on the cycle the write
  completes. An enable write takes effect from the following cycle.
- `snapshot_valid` stays set until reset.

## Device tree

Each tile PMU appears in the device tree:

```text
pmu@40000000 {
  compatible = "ucbbar,pmu-v1";
  reg = <0x40000000 0x1000>;
  pmu,source = "boom_tile_0";
  pmu,counter-bits = <64>;
};
```

`pmu,source` matches the `source` of the tile's event list, which gives the name,
unit and description of each counter ID. The register descriptions also appear
in Chipyard's `*.regmap.json` output for the region.

## Software sequence

1. Read INFO and check the version, count and width.
2. While disabled, write COMMAND = clear.
3. Write CONTROL = enable.
4. At the end of a window, write COMMAND = snapshot, or snapshot | clear for
   consecutive windows.
5. Read COUNTER[0] to COUNTER[count - 1].
6. Write CONTROL = 0 to stop counting.

Order the reads after the COMMAND write with an I/O fence (on RISC-V,
`fence iorw, iorw`). When several software contexts share a tile PMU, serialize
their commands. A snapshot replaces the captured values that another context may
be reading. [Software](software.md) provides accessors that apply these fences.
