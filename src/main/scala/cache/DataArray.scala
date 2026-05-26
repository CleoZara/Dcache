package cache

import chisel3._
import chisel3.util._

class DataArray extends Module {
  import CacheParams._
  val io = IO(new Bundle {
    val idx          = Input(UInt(5.W))
    val wordsoff     = Input(UInt(4.W))
    val hitWen       = Input(Bool())
    val hitWay       = Input(UInt(2.W))
    val wdata        = Input(UInt(32.W))
    val wmask        = Input(UInt(4.W))
    val refillDataEn = Input(Bool())
    val refillWay    = Input(UInt(2.W))
    val refillIdx    = Input(UInt(5.W))
    val refillWord   = Input(UInt(4.W))
    val refillData   = Input(UInt(32.W))
    val evictIdx     = Input(UInt(5.W))
    val evictWay     = Input(UInt(2.W))
    val evictLine    = Output(Vec(lineWords, UInt(32.W)))
    val rawData      = Output(Vec(nWays, UInt(32.W)))
  })
  val dArray = Reg(Vec(nWays, Vec(nSets, Vec(lineWords, UInt(32.W)))))
  for (w <- 0 until nWays) {
    io.rawData(w) := dArray(w)(io.idx)(io.wordsoff)
  }
  io.evictLine := dArray(io.evictWay)(io.evictIdx)
  when(io.hitWen) {
    val old      = dArray(io.hitWay)(io.idx)(io.wordsoff)
    val byteMask = Cat(
      Fill(8, io.wmask(3)), Fill(8, io.wmask(2)),
      Fill(8, io.wmask(1)), Fill(8, io.wmask(0))
    )
    dArray(io.hitWay)(io.idx)(io.wordsoff) :=
      (io.wdata & byteMask) | (old & ~byteMask)
  }
  when(io.refillDataEn) {
    dArray(io.refillWay)(io.refillIdx)(io.refillWord) := io.refillData
  }
}
