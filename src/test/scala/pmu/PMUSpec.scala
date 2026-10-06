package pmu

import chisel3._
import chisel3.util.PopCount
import circt.stage.ChiselStage
import org.chipsalliance.cde.config.Parameters
import org.scalatest.flatspec.AnyFlatSpec

private class LaneProducer(implicit p: Parameters) extends Module {
  val io = IO(new Bundle { val lanes = Input(UInt(4.W)); val flag = Input(Bool()) })
  if (p(PMUScopeKey).isDefined) {
    val registry = p(PMUScopeKey).get
    registry.register("z.lanes", PopCount(io.lanes), unit = "lanes", description = "Set lanes")
    registry.register("a.flag", io.flag)
    registry.register("m.delayed_flag", RegNext(io.flag, false.B))
  }
}

private class SpecTile(source: String, enabled: Boolean) extends Module with CanHavePMUEvents {
  val io = IO(new Bundle { val lanes = Input(UInt(4.W)); val flag = Input(Bool()) })
  val registry = new PMURegistry(source)
  val tileP = Parameters.empty.alterPartial { case PMUScopeKey => if (enabled) Some(registry) else None }
  val producer = Module(new LaneProducer()(tileP))
  producer.io <> io
  if (enabled) registry.register("tile.cycles", 1.U, unit = "cycles")
  val pmuEvents = if (enabled) Some(registry.build()) else None
  val tilePMU = pmuEvents.map(TilePMU.build(_))
  tilePMU.foreach { t => t.enable := true.B; t.clear := false.B; t.snapshot := false.B }
}

private class SpecSystem extends Module {
  val io = IO(new Bundle {
    val lanes = Input(UInt(4.W)); val flag = Input(Bool())
    val bored = Output(Vec(4, UInt(64.W)))
  })
  val tiles = Seq(Module(new SpecTile("tile_0", true)), Module(new SpecTile("tile_1", true)),
    Module(new SpecTile("tile_2", false)))
  tiles.foreach { t => t.io.lanes := io.lanes; t.io.flag := io.flag }
  val found = tiles.map {
    case m: CanHavePMUEvents => m.pmuEvents
    case _ => None
  }
  val local = found.head.get.bore()
  io.bored := VecInit(local.increments.map(_.pad(64)))
  val cycles = withClockAndReset(local.clock, local.reset) { RegInit(0.U(8.W)) }
  cycles := cycles + 1.U
}

private class Misuse(kind: String) extends Module {
  val in = IO(Input(UInt(8.W)))
  val registry = new PMURegistry("misuse")
  kind match {
    case "empty-name" => registry.register(" ", in)
    case "duplicate" => registry.register("x", in); registry.register("x", in)
    case "late" => registry.register("x", in); registry.build(); registry.register("y", in)
    case "twice" => registry.register("x", in); registry.build(); registry.build()
    case "empty" => registry.build()
    case "unbound" => registry.register("x", UInt(1.W))
    case "too-wide" => registry.register("x", in); TilePMU.build(registry.build(), counterBits = 4)
    case "bad-counter-bits" => registry.register("x", in); TilePMU.build(registry.build(), counterBits = 0)
  }
}

class PMUSpec extends AnyFlatSpec {
  private def messages(e: Throwable): String =
    Option(e.getMessage).getOrElse("") + Option(e.getCause).map(c => " | " + messages(c)).getOrElse("")
  private def rejects(message: String)(body: => Any): Unit = {
    val failure = try { body; None } catch { case t: Throwable => Some(t) }
    assert(failure.nonEmpty, "unexpectedly succeeded")
    assert(messages(failure.get).contains(message), messages(failure.get))
  }

  private lazy val elaborated = {
    var tiles: Seq[SpecTile] = Nil
    val fir = ChiselStage.emitCHIRRTL { val s = new SpecSystem; tiles = s.tiles; s }
    (fir, tiles)
  }
  private def tile(i: Int) = elaborated._2(i)

  "build()" should "assign event IDs in ascending name order" in {
    val m = tile(0).pmuEvents.get.manifest
    assert(m.events.map(_.name) == Vector("a.flag", "m.delayed_flag", "tile.cycles", "z.lanes"))
    assert(m.events.map(_.id) == Vector(0, 1, 2, 3))
    assert(m.events.last.unit == "lanes" && m.events.last.description == "Set lanes")
    assert(m.events.head.unit == "events" && m.events.head.description == "")
  }

  it should "give each tile its own registry, and none to a disabled tile" in {
    assert(tile(1).pmuEvents.get.manifest.source == "tile_1")
    assert(tile(2).pmuEvents.isEmpty)
  }

  "register()" should "keep each increment's width as a UInt" in {
    val increments = tile(0).pmuEvents.get.increments
    assert(increments.map(_.widthOption) == Vector(Some(1), Some(1), Some(1), Some(3)))
    assert(increments.forall(i => !i.isInstanceOf[Bool]))
  }

  it should "name each increment wire after its event" in {
    for (n <- Seq("pmu_a_flag", "pmu_m_delayed_flag", "pmu_tile_cycles", "pmu_z_lanes"))
      assert(elaborated._1.contains(s"wire $n"), n)
  }

  it should "reject misuse during elaboration" in {
    rejects("event name must be nonempty") { ChiselStage.emitCHIRRTL(new Misuse("empty-name")) }
    rejects("duplicate event 'x'") { ChiselStage.emitCHIRRTL(new Misuse("duplicate")) }
    rejects("registration after build") { ChiselStage.emitCHIRRTL(new Misuse("late")) }
    rejects("build called twice") { ChiselStage.emitCHIRRTL(new Misuse("twice")) }
    rejects("cannot build an empty registry") { ChiselStage.emitCHIRRTL(new Misuse("empty")) }
    rejects("must be hardware") { ChiselStage.emitCHIRRTL(new Misuse("unbound")) }
    rejects("source must be nonempty") { new PMURegistry(" ") }
  }

  "TilePMU.build()" should "reject invalid counter widths" in {
    rejects("exceeds counterBits=4") { ChiselStage.emitCHIRRTL(new Misuse("too-wide")) }
    rejects("counterBits must be positive") { ChiselStage.emitCHIRRTL(new Misuse("bad-counter-bits")) }
  }

  "bore()" should "wire a tile's increments, clock and reset into an enclosing module" in {
    val fir = elaborated._1
    val system = fir.substring(fir.indexOf("module SpecSystem"))
    for (n <- Seq("pmu_a_flag", "pmu_z_lanes", "pmu_clock", "pmu_reset"))
      assert(system.contains(s"wire $n"), n)
  }

  "PMUManifest" should "round-trip through JSON" in {
    val m = tile(0).pmuEvents.get.manifest
    assert(PMUManifest.fromJson(m.toJson) == m)
    assert(ujson.read(m.toJson).obj.keySet == Set("schemaVersion", "source", "events"))
  }

  it should "reject invalid event lists" in {
    rejects("ascending name order") {
      PMUManifest("s", Vector(PMUEvent(0, "b", "events", ""), PMUEvent(1, "a", "events", "")))
    }
    rejects("IDs must equal their positions") { PMUManifest("s", Vector(PMUEvent(1, "a", "events", ""))) }
    rejects("Unsupported PMU schema version: 1") {
      PMUManifest.fromJson("""{"schemaVersion":1,"source":"s","events":[{"id":0,"name":"a","unit":"events","description":""}]}""")
    }
  }

  "TilePMUParams" should "require an aligned address and a width of at most 64 bits" in {
    rejects("4 KiB aligned") { TilePMUParams(0x1001) }
    rejects("counterBits must be in [1, 64]") { TilePMUParams(0x1000, 65) }
  }
}
