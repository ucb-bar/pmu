# Event list format

Package `pmu`, sbt project `pmu_schema`.

```scala
final case class PMUEvent(id: Int, name: String, unit: String, description: String)

final case class PMUManifest(
  source: String,
  events: Vector[PMUEvent],
  schemaVersion: Int = PMUManifest.schemaVersion
) {
  def toJson: String
}

object PMUManifest {
  val schemaVersion = 2
  def fromJson(json: String): PMUManifest
}
```

A `PMUManifest` describes one tile's events. `PMURegistry.build()` creates it,
and `PMUEvents.manifest` carries it.

## Fields

| Field | Meaning |
|---|---|
| `schemaVersion` | Format version. This document describes version 2. |
| `source` | The registry's name, which is the tile's unique name, for example `boom_tile_0`. |
| `events` | The tile's events, in ID order. |
| `events[].id` | The event's ID: its position in `events`, from `0` to `n - 1`. IDs follow ascending order of event name. |
| `events[].name` | The name given to `register`. Unique within the list. |
| `events[].unit` | What one increment counts, for example `cycles`. |
| `events[].description` | The event's definition. |

The ID of an event is also its index in `PMUEvents.increments`, its counter index
in the tile PMU and in the [MMIO register map](tile-pmu.md#register-map), and its
value in the generated software header. Two builds with the same set of event
names assign the same IDs. Identify an event by name when comparing different
builds.

`PMUManifest` validates its contents on construction and in `fromJson`: a
supported `schemaVersion`, a nonempty `source`, at least one event, IDs equal to
positions, and nonempty, unique names in ascending order.

## File

Each tile writes its event list to `<config>.<source>.pmu.json`, next to the
other elaboration outputs, for example
`chipyard.harness.TestHarness.MediumBoomV3PMUConfig.boom_tile_0.pmu.json`.

```json
{
  "schemaVersion": 2,
  "source": "boom_tile_0",
  "events": [
    {
      "id": 0,
      "name": "branch.mispredict_redirects",
      "unit": "redirects",
      "description": "Branch recovery redirects"
    },
    {
      "id": 1,
      "name": "frontend.icache_line_misses",
      "unit": "requests",
      "description": "Accepted instruction-cache refill requests"
    }
  ]
}
```
