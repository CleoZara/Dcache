package cache

import chisel3._
import chisel3.util._

class TreePLRU extends Module {
  import CacheParams._
  val io = IO(new Bundle {
    val idx       = Input(UInt(5.W))
    val updateEn  = Input(Bool())
    val updateWay = Input(UInt(2.W))
    val evictWay  = Output(UInt(2.W))
  })
  val treeArray = RegInit(VecInit(Seq.fill(nSets)(0.U(3.W))))
  val tree = treeArray(io.idx)
  io.evictWay := Mux(!tree(2),
    Mux(!tree(1), 0.U, 1.U),
    Mux(!tree(0), 2.U, 3.U)
  )
  when(io.updateEn) {
    val newTree = WireInit(tree)
    switch(io.updateWay) {
      is(0.U) { newTree := Cat(1.U(1.W), 1.U(1.W), tree(0)) }
      is(1.U) { newTree := Cat(1.U(1.W), 0.U(1.W), tree(0)) }
      is(2.U) { newTree := Cat(0.U(1.W), tree(1), 1.U(1.W)) }
      is(3.U) { newTree := Cat(0.U(1.W), tree(1), 0.U(1.W)) }
    }
    treeArray(io.idx) := newTree
  }
}
