package pmu.tilelink

import chisel3._
import chisel3.util.Cat
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.resources.{Description, ResourceBindings, ResourceInt, ResourceString, SimpleDevice}
import freechips.rocketchip.regmapper.{RegField, RegFieldDesc, RegWriteFn}
import freechips.rocketchip.tilelink.TLRegisterNode
import pmu.{PMUAttachment, TilePMUParams}

/** Construct in the tile's LazyModule; attach in its module body after TilePMU.build(events).
  * The register interface and the counters share the tile's clock and reset.
  */
final class PMUTileLink(val params: TilePMUParams, beatBytes: Int, val source: String)(implicit p: Parameters) {
  require(beatBytes == 4 || beatBytes == 8, "PMU MMIO supports 32- or 64-bit register buses")
  private var attached = false
  val device = new SimpleDevice("pmu", Seq("ucbbar,pmu-v1")) {
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++ Map(
        "pmu,source" -> Seq(ResourceString(source)),
        "pmu,counter-bits" -> Seq(ResourceInt(BigInt(params.counterBits)))))
    }
  }
  val node = TLRegisterNode(Seq(AddressSet(params.baseAddress, 4095)), device,
    beatBytes = beatBytes, concurrency = 1)

  def attach(tilePMU: PMUAttachment): Unit = {
    require(!attached, "PMU MMIO may be attached only once")
    require(tilePMU.manifest.source == source, "PMU MMIO source does not match its tile PMU")
    require(tilePMU.counterBits == params.counterBits, "PMU MMIO counterBits does not match its tile PMU")
    val count = tilePMU.manifest.events.size
    require(0x100 + 8 * count <= 4096, "PMU counter array exceeds its MMIO region")
    attached = true
    val enabled = RegInit(false.B)
    tilePMU.enable := enabled
    tilePMU.clear := false.B
    tilePMU.snapshot := false.B
    val command = RegWriteFn((valid: Bool, data: UInt) => {
      // RegMapper gates valid by the addressed byte mask and response handshake.
      // A queued or backpressured request cannot repeat these one-cycle commands.
      tilePMU.clear := valid && data(0)
      tilePMU.snapshot := valid && data(1)
      true.B
    })
    val control = Seq(RegField(1, enabled,
      RegFieldDesc("enable", "Accumulate while set; reset disables counting")))
    val commands = Seq(RegField.w(2, command,
      RegFieldDesc("command", "Write one: bit 0 clear, bit 1 snapshot; both capture then clear; reads zero")))
    val status = Seq(RegField.r(2, Cat(tilePMU.snapshot_valid, enabled),
      RegFieldDesc("status", "Bit 0 enabled; bit 1 snapshot valid")))
    val info = Seq(
      RegField.r(16, 1.U(16.W), RegFieldDesc("version", "PMU MMIO register-map version")),
      RegField.r(16, count.U(16.W), RegFieldDesc("count", "Number of counters")),
      RegField.r(16, params.counterBits.U(16.W), RegFieldDesc("width", "Counter width in bits")))
    // Split each value into 32-bit fields so both register bus widths work.
    // Snapshot storage remains stable until another snapshot or hardware reset.
    val counters = tilePMU.manifest.events.map { e =>
      val value = tilePMU.snapshot_values(e.id).pad(64)
      (0x100 + 8 * e.id) -> Seq(
        RegField.r(32, value(31, 0), RegFieldDesc(s"counter_${e.id}_lo", s"${e.name}: low word; ${e.description}")),
        RegField.r(32, value(63, 32), RegFieldDesc(s"counter_${e.id}_hi", s"${e.name}: high word")))
    }
    node.regmap((Seq(0x00 -> control, 0x08 -> commands, 0x10 -> status, 0x18 -> info) ++ counters): _*)
  }
}
