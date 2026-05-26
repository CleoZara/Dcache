package cache

import chisel3._
import chisel3.util._

class TagEntry extends Bundle {
  val valid = Bool()
  val dirty = Bool()
  val tag   = UInt(CacheParams.tagBits.W)
}

class TagArray extends Module {
  import CacheParams._
  val io = IO(new Bundle {
    val flush       = Input(Bool())
    val idx         = Input(UInt(idxBits.W))
    val tagData     = Output(Vec(nWays, new TagEntry))
    val refillTagEn = Input(Bool())
    val refillWay   = Input(UInt(2.W))
    val refillIdx   = Input(UInt(idxBits.W))
    val refillTag   = Input(UInt(tagBits.W))
    val refillDirty = Input(Bool())
    val setDirtyEn  = Input(Bool())
    val setDirtyWay = Input(UInt(2.W))
  })
  val tArray = RegInit(VecInit(Seq.fill(nSets)(
    VecInit(Seq.fill(nWays)(0.U.asTypeOf(new TagEntry)))
  )))
  io.tagData := tArray(io.idx)
  when(io.flush) {
    for (s <- 0 until nSets; w <- 0 until nWays) {
      tArray(s)(w).valid := false.B
      tArray(s)(w).dirty := false.B
    }
  }.elsewhen(io.refillTagEn) {
    tArray(io.refillIdx)(io.refillWay).valid := true.B
    tArray(io.refillIdx)(io.refillWay).dirty := io.refillDirty
    tArray(io.refillIdx)(io.refillWay).tag   := io.refillTag
  }.elsewhen(io.setDirtyEn) {
    tArray(io.idx)(io.setDirtyWay).dirty := true.B
  }
}
