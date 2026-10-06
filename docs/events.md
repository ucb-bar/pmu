# Events API

Package `pmu`, sbt project `pmu`.

```scala
case object PMUScopeKey extends Field[Option[PMURegistry]](None)

final class PMURegistry(val source: String) {
  def register(name: String, increment: UInt,
               unit: String = "events", description: String = ""): Unit
  def build(): PMUEvents
}

final class PMUEvents {
  val increments: Vector[UInt]  // in event-ID order
  val clock: Clock
  val reset: Reset
  val manifest: PMUManifest
  def bore(): PMUEvents
}

trait CanHavePMUEvents {
  def pmuEvents: Option[PMUEvents]
}

final case class PMUParams(tilePMU: Option[TilePMUParams] = None)
```

`TilePMUParams` and `TilePMU` are described in [Tile PMU](tile-pmu.md);
`PMUManifest` in [Event list format](event-list.md).

## Producers

A producer is any module inside a tile that reports an event. When PMU is
enabled for its tile, it registers the event with the tile's registry, which it
finds in `Parameters`:

```scala
// pmu: the tile's Option[PMUParams], as seen through the core's parameters
if (pmu.isDefined) {
  val registry = p(PMUScopeKey).get
  registry.register("lsu.dtlb_walk_requests", io.ptw.req.fire,
    unit = "requests", description = "Accepted data-side page-table walk requests")
}
```

Inside the guard, `p(PMUScopeKey).get` returns the tile's registry. It throws
during elaboration if the tile enables PMU without providing a registry. Hardware
built only to compute an event belongs inside the same guard.

### `register(name, increment, unit, description)`

- **`name`**: identifies the event within the tile. Nonempty and unique within
  the registry. BOOM uses names of the form `<component>.<event>`, for example
  `rob.instructions_retired`.
- **`increment`**: any `UInt` hardware value visible in the calling module: an
  expression, wire, port, register or literal. A `Bool` is accepted as a 0/1
  increment. Its value on each clock cycle is the amount the event's counter
  advances on that cycle, and zero when the event does not occur. The producer
  computes any combination itself, for example a `PopCount` over lanes.
  `register` creates a wire for the increment in the calling module, named after
  the event.
- **`unit`**: what one increment counts, for example `cycles`, `instructions` or
  `requests`. Defaults to `events`.
- **`description`**: a human-readable definition of the event. Defaults to empty.

The event is observed on the clock and reset in effect where `register` is
called: the module's implicit clock and reset, or those selected by an enclosing
`withClockAndReset`. All of a tile's events use the tile's clock and reset.

`register` throws during elaboration if the name is empty or already registered,
or if the registry has already been built.

## Tile integration

A tile that supports PMU events has a `pmu: Option[PMUParams]` field in its tile
parameters. When `pmu` is set, the tile:

1. **Creates a registry before building any child.** Use one fresh
   `PMURegistry`, named with the tile's unique name, and place it in the
   `Parameters` the tile and its children are built with:

   ```scala
   val registry = new PMURegistry(tileParams.uniqueName)
   val tileP = p.alterPartial { case PMUScopeKey => Some(registry) }
   ```

   Create the registry outside the `alterPartial` body. CDE evaluates that body
   on every lookup, so a `new PMURegistry` inside it would give each lookup a
   different registry. For a diplomacy tile, the altered `Parameters` must
   already be in place when the tile's base-class constructor runs.

2. **Builds the registry at the end of its module body**, after every producer
   has been constructed, and exposes the result through `CanHavePMUEvents`:

   ```scala
   class MyTileModuleImp(outer: MyTile) extends BaseTileModuleImp(outer)
       with CanHavePMUEvents {
     // ... children and producers ...
     val pmuEvents = if (pmu.isDefined) Some(p(PMUScopeKey).get.build()) else None
   }
   ```

3. **Writes the event list** with rocket-chip's `ElaborationArtefacts`. Chipyard
   writes it as `<config>.<source>.pmu.json`:

   ```scala
   pmuEvents.foreach { events =>
     ElaborationArtefacts.add(s"${events.manifest.source}.pmu.json", events.manifest.toJson)
   }
   ```

4. **Optionally builds a tile PMU** when `pmu.tilePMU` is set. See
   [Tile PMU](tile-pmu.md).

## `build()`

`build()` closes the registry and returns its `PMUEvents`. Call it once, in the
tile's module, after all producers have registered.

- Event IDs are `0` to `n - 1`, assigned in ascending order of event name.
- `increments(i)` carries the increment of the event with ID `i`, wired to the
  module that called `build()`.
- `clock` and `reset` are the clock and reset in effect where `build()` is called,
  which is the tile's.
- `manifest` is the tile's event list.

`build()` throws during elaboration if it is called a second time or if no event
has been registered.

## Using events outside the tile

Code outside the tile reads `pmuEvents` from a tile module that mixes in
`CanHavePMUEvents`, and calls `bore()` to bring the signals into its own module:

```scala
// In a module that encloses the tile, after the tile has been built
val events: Option[PMUEvents] = tile.module match {
  case m: CanHavePMUEvents => m.pmuEvents.map(_.bore())
  case _ => None
}
```

- `pmuEvents` is set when the tile's module body completes. Read it from module
  code that runs after the tile's module has been constructed, for example in an
  enclosing module's body or an `InModuleBody` of an enclosing LazyModule.
- `bore()` returns a `PMUEvents` whose `increments`, `clock` and `reset` are
  wired into the calling module, in event-ID order, with the same `manifest`.
- The increments are synchronous to the tile's clock and reset. A consumer in a
  different clock domain synchronizes them itself.
