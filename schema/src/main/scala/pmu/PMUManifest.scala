package pmu

/** One event of a tile's event list. No Chisel objects cross this boundary. */
final case class PMUEvent(
  id: Int,
  name: String,
  unit: String,
  description: String
) {
  require(id >= 0, "PMU event ID must be nonnegative")
  require(name.trim.nonEmpty, "PMU event name must be nonempty")
}

/** A tile's event list: events in ID order, with IDs following ascending name order. */
final case class PMUManifest(
  source: String,
  events: Vector[PMUEvent],
  schemaVersion: Int = PMUManifest.schemaVersion
) {
  require(schemaVersion == PMUManifest.schemaVersion, s"Unsupported PMU schema version: $schemaVersion")
  require(source.trim.nonEmpty, "PMU source must be nonempty")
  require(events.nonEmpty, "PMU event list must not be empty")
  require(events.map(_.id) == events.indices.toVector, "PMU event IDs must equal their positions")
  require(events.map(_.name).distinct.size == events.size, "Duplicate PMU event names")
  require(events.map(_.name) == events.map(_.name).sorted, "PMU events must be in ascending name order")

  def toJson: String = ujson.write(ujson.Obj(
    "schemaVersion" -> schemaVersion, "source" -> source,
    "events" -> ujson.Arr.from(events.map(e => ujson.Obj(
      "id" -> e.id, "name" -> e.name, "unit" -> e.unit, "description" -> e.description
    )))
  ), indent = 2)
}

object PMUManifest {
  val schemaVersion = 2

  def fromJson(json: String): PMUManifest = {
    val obj = ujson.read(json)
    def int(v: ujson.Value): Int = {
      val n = v.num
      require(n.isValidInt && n == n.toInt.toDouble, s"Expected an integer, got $n")
      n.toInt
    }
    PMUManifest(obj("source").str, obj("events").arr.map { e =>
      PMUEvent(int(e("id")), e("name").str, e("unit").str, e("description").str)
    }.toVector, int(obj("schemaVersion")))
  }
}
