package cache

import chisel3._
import chisel3.util._

class HitTest extends Module {
  val io = IO(new Bundle {
    val tagData   = Input(Vec(CacheParams.nWays, new TagEntry))
    val tag       = Input(UInt(CacheParams.tagBits.W))
    val memRen    = Input(Bool())
    val wen       = Input(Bool())
    val isHit     = Output(Bool())
    val hitWay    = Output(UInt(2.W))
    val missValid = Output(Bool())
  })
  val wayHits = VecInit((0 until CacheParams.nWays).map { w =>
    io.tagData(w).valid && (io.tagData(w).tag === io.tag)
  })
  io.isHit     := wayHits.asUInt.orR
  io.hitWay    := PriorityEncoder(wayHits)
  io.missValid := !io.isHit && (io.memRen || io.wen)
}
