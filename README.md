# PMU

A performance-monitoring library for Chisel tiles. Hardware modules report
per-cycle events through a per-tile registry. The tile can count them in a bank
of counters that software reads over MMIO, and other hardware, such as a
simulation bridge, can use the same events.

## Concepts

| Term | Meaning |
|---|---|
| Event | One quantity being counted, for example retired instructions. |
| Increment | The amount an event's counter advances on a given clock cycle. Zero when the event does not occur. |
| Producer | A module that computes an event's increment and registers it. |
| Registry | A `PMURegistry`. One per tile, collecting that tile's events during elaboration. |
| Source | The registry's name. Tiles use their unique name, for example `boom_tile_0`. |
| Event list | A `PMUManifest`: the source and, for each event, its ID, name, unit and description. Written as `<config>.<source>.pmu.json`. |
| Tile PMU | A bank of counters inside the tile, one per event, with enable, clear and snapshot controls, read by software over MMIO. |

## Components

| Directory | sbt project | Contents |
|---|---|---|
| `schema/` | `pmu_schema` | `PMUEvent`, `PMUManifest` and their JSON format. Depends on ujson only. |
| `src/` | `pmu` | `PMURegistry`, `PMUEvents`, `CanHavePMUEvents`, `PMUParams`, `TilePMUParams` and the `TilePMU` counter bank. Depends on Chisel and CDE. |
| `tilelink/` | `pmu_tilelink` | `PMUTileLink`, the TileLink register interface for the tile PMU. Depends on rocket-chip. |
| `software/` | | C register definitions, MMIO accessors and a header generator for bare-metal software. |

The sbt projects are defined by the enclosing Chipyard build. The library
targets Chisel 6.

## Example

A producer registers its events when PMU is enabled for its tile:

```scala
if (pmu.isDefined) {  // the tile's PMU setting
  val registry = p(PMUScopeKey).get
  registry.register("rob.instructions_retired", PopCount(io.commit.arch_valids),
    unit = "instructions", description = "Architecturally retired instructions")
}
```

The tile creates the registry before building its children, and builds it at the
end of its module body:

```scala
// Tile constructor, before any child is built
val registry = new PMURegistry(tileParams.uniqueName)
val tileP = p.alterPartial { case PMUScopeKey => Some(registry) }

// End of the tile's module body (the module mixes in CanHavePMUEvents)
val pmuEvents = if (pmu.isDefined) Some(p(PMUScopeKey).get.build()) else None
pmuEvents.foreach { events =>
  ElaborationArtefacts.add(s"${events.manifest.source}.pmu.json", events.manifest.toJson)
}
```

When the tile's `PMUParams` select a tile PMU, the tile also builds the counters
and their register interface from `pmuEvents`, as described in
[Tile PMU](docs/tile-pmu.md).

Hardware outside the tile gets the same events through `CanHavePMUEvents`:

```scala
tile.module match {
  case m: CanHavePMUEvents => m.pmuEvents.map(_.bore())  // wires local to this module
  case _ => None
}
```

## Documentation

- [Events API](docs/events.md): registering events, tile integration, and access
  from outside the tile.
- [Event list format](docs/event-list.md): `PMUManifest` and `.pmu.json`.
- [Tile PMU](docs/tile-pmu.md): counter semantics, the TileLink register map and
  the device-tree node.
- [Software](docs/software.md): C headers and the platform header generator.
