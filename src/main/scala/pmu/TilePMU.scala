package pmu

import chisel3._
import chisel3.reflect.DataMirror

/** Controls and outputs of a tile PMU; metadata and hardware handles are Scala values. */
final case class PMUAttachment(
  enable: Bool, clear: Bool, snapshot: Bool,
  values: Vec[UInt], snapshot_values: Vec[UInt], snapshot_valid: Bool,
  manifest: PMUManifest, counterBits: Int
)

object TilePMU {
  /** Instantiate a tile PMU for these events, in the clock and reset recorded in them.
    * Call it in the module that holds the events.
    */
  def build(events: PMUEvents, counterBits: Int = 64): PMUAttachment = {
    require(counterBits > 0, "TilePMU counterBits must be positive")
    require((events.increments ++ Vector(events.clock, events.reset)).forall(DataMirror.isVisible),
      "TilePMU.build must be called where the events' wires are visible")
    events.increments.zip(events.manifest.events).foreach { case (increment, event) =>
      require(increment.widthOption.forall(_ <= counterBits),
        s"PMU ${events.manifest.source}/${event.name}: increment width ${increment.getWidth} exceeds counterBits=$counterBits")
    }
    val tile_pmu = withClockAndReset(events.clock, events.reset) {
      Module(new TilePMU(events.manifest.events.size, counterBits))
    }
    events.increments.zipWithIndex.foreach { case (increment, id) =>
      tile_pmu.increments(id) := increment
    }
    PMUAttachment(tile_pmu.enable, tile_pmu.clear, tile_pmu.snapshot,
      tile_pmu.values, tile_pmu.snapshot_values, tile_pmu.snapshot_valid,
      events.manifest, counterBits)
  }
}

/** Cumulative counters with a coherent snapshot, one counter per event. */
private[pmu] final class TilePMU(count: Int, counterBits: Int) extends Module {
  val increments = IO(Input(Vec(count, UInt(counterBits.W))))
  val enable = IO(Input(Bool()))
  val clear = IO(Input(Bool()))
  val snapshot = IO(Input(Bool()))
  val values = IO(Output(Vec(count, UInt(counterBits.W))))
  val snapshot_values = IO(Output(Vec(count, UInt(counterBits.W))))
  val snapshot_valid = IO(Output(Bool()))
  val counters = RegInit(VecInit(Seq.fill(count)(0.U(counterBits.W))))
  val captured = RegInit(VecInit(Seq.fill(count)(0.U(counterBits.W))))
  val captured_valid = RegInit(false.B)
  for (id <- 0 until count) {
    val next = counters(id) + Mux(enable, increments(id), 0.U)
    // Capture before clear, including this edge's increment when enabled.
    when(snapshot) { captured(id) := next }
    when(clear) { counters(id) := 0.U }
      .elsewhen(enable) { counters(id) := next }
  }
  when(snapshot) { captured_valid := true.B }
  values := counters
  snapshot_values := captured
  snapshot_valid := captured_valid
}
