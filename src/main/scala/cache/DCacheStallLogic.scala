package cache

import chisel3._

class DCacheStallLogic extends Module {
  val io = IO(new Bundle {
    val dcacheMiss    = Input(Bool())
    val refillDone    = Input(Bool())
    val pipelineStall = Output(Bool())
  })

  val stallReg = RegInit(false.B)

  when(io.dcacheMiss) {
    stallReg := true.B
  }.elsewhen(io.refillDone) {
    stallReg := false.B
  }

  io.pipelineStall := stallReg
}
