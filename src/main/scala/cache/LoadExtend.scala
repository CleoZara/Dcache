package cache

import chisel3._
import chisel3.util._

class LoadExtend extends Module {
  import CacheParams._
  val io = IO(new Bundle {
    val hitWay  = Input(UInt(2.W))
    val byteoff = Input(UInt(2.W))
    val memWd   = Input(UInt(2.W))
    val signed  = Input(Bool())
    val rawData = Input(Vec(nWays, UInt(32.W)))
    val rdata   = Output(UInt(32.W))
  })
  val selectedWord = io.rawData(io.hitWay)
  val byteShift    = Cat(io.byteoff, 0.U(3.W))
  val byteData     = (selectedWord >> byteShift)(7, 0)
  val halfShift    = Cat(io.byteoff(1), 0.U(4.W))
  val halfData     = (selectedWord >> halfShift)(15, 0)
  io.rdata := MuxCase(selectedWord, Seq(
    (io.memWd === 1.U &&  io.signed) -> Cat(Fill(16, halfData(15)), halfData),
    (io.memWd === 1.U && !io.signed) -> Cat(0.U(16.W),             halfData),
    (io.memWd === 2.U &&  io.signed) -> Cat(Fill(24, byteData(7)), byteData),
    (io.memWd === 2.U && !io.signed) -> Cat(0.U(24.W),             byteData),
  ))
}
