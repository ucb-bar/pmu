package pmu

/** A tile's PMU setting. Present: the tile collects events. */
final case class PMUParams(tilePMU: Option[TilePMUParams] = None)

/** A tile PMU: counters for the tile's events, read through a 4 KiB register region. */
final case class TilePMUParams(baseAddress: BigInt, counterBits: Int = 64) {
  require(baseAddress >= 0 && baseAddress % 4096 == 0, "Tile PMU baseAddress must be nonnegative and 4 KiB aligned")
  require(counterBits > 0 && counterBits <= 64, "Tile PMU counterBits must be in [1, 64]")
}
