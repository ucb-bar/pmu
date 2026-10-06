package pmu

import chisel3._
import chisel3.experimental.requireIsHardware
import chisel3.reflect.DataMirror
import chisel3.util.experimental.BoringUtils
import org.chipsalliance.cde.config.Field
import scala.collection.mutable.ArrayBuffer

/** The tile's registry, present in a tile's Parameters when PMU is enabled for that tile. */
case object PMUScopeKey extends Field[Option[PMURegistry]](None)

/** A tile's events: one increment per event in event-ID order, the clock and reset
  * they belong to, and the event list. Hardware handles are local to one module:
  * the one that called registry.build(), or the one that called bore().
  */
final class PMUEvents private[pmu] (
  val increments: Vector[UInt], val clock: Clock, val reset: Reset,
  val manifest: PMUManifest
) {
  /** Wire these events into the calling module, which encloses the module that holds them. */
  def bore(): PMUEvents = {
    val pmu_clock = Wire(Clock())
    pmu_clock := BoringUtils.bore(clock)
    val pmu_reset = Wire(chiselTypeOf(reset))
    pmu_reset := BoringUtils.bore(reset)
    val local_increments = increments.zip(manifest.events).map { case (increment, event) =>
      val pmu_increment = Wire(chiselTypeOf(increment)).suggestName(PMURegistry.wireName(event.name))
      pmu_increment := BoringUtils.bore(increment)
      pmu_increment
    }
    new PMUEvents(local_increments, pmu_clock, pmu_reset, manifest)
  }
}

/** Implemented by a tile's module class to expose its events to enclosing modules. */
trait CanHavePMUEvents {
  def pmuEvents: Option[PMUEvents]
}

/** One registry per tile. The tile supplies the same instance to all of its producers. */
final class PMURegistry(val source: String) {
  require(source.trim.nonEmpty, "PMU source must be nonempty")
  private case class Entry(name: String, increment: UInt, unit: String, description: String)
  private val entries = ArrayBuffer.empty[Entry]
  private var built = false

  /** Register an event observed on the clock and reset in effect at this call. */
  def register(name: String, increment: UInt, unit: String = "events", description: String = ""): Unit = {
    require(!built, s"PMU $source: registration after build")
    require(name.trim.nonEmpty, s"PMU $source: event name must be nonempty")
    require(!entries.exists(_.name == name), s"PMU $source: duplicate event '$name'")
    requireIsHardware(increment, s"PMU $source/$name increment")
    require(increment.isLit || DataMirror.isVisible(increment),
      s"PMU $source/$name: increment must be visible in the calling module")
    // A plain UInt of the increment's known width, so a Bool increment widens like any other.
    val incrementType = increment.widthOption.map(w => UInt(w.W)).getOrElse(UInt())
    val pmu_increment = Wire(incrementType).suggestName(PMURegistry.wireName(name))
    pmu_increment := increment
    entries += Entry(name, pmu_increment, unit, description)
  }

  /** Close the registry and wire every increment into the calling module, in event-ID order. */
  def build(): PMUEvents = {
    require(!built, s"PMU $source: build called twice")
    require(entries.nonEmpty, s"PMU $source: cannot build an empty registry")
    built = true
    val sorted = entries.sortBy(_.name).toVector
    val manifest = PMUManifest(source, sorted.zipWithIndex.map { case (e, id) =>
      PMUEvent(id, e.name, e.unit, e.description)
    })
    val pmu_clock = WireInit(Module.clock)
    val pmu_reset = WireInit(Module.reset)
    val increments = sorted.map { e =>
      val pmu_increment = Wire(chiselTypeOf(e.increment)).suggestName(PMURegistry.wireName(e.name))
      pmu_increment := BoringUtils.bore(e.increment)
      pmu_increment
    }
    new PMUEvents(increments, pmu_clock, pmu_reset, manifest)
  }
}

object PMURegistry {
  /** Hardware name for an event's increment wire, e.g. rob.instructions_retired -> pmu_rob_instructions_retired. */
  private[pmu] def wireName(eventName: String): String =
    "pmu_" + eventName.map(c => if (c.isLetterOrDigit || c == '_') c else '_')
}
