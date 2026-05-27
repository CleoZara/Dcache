package cache

import chisel3._
import chisel3.util._

class MemBusReq extends Bundle {
  val addr  = UInt(32.W)
  val wdata = UInt(32.W)
  val wen   = Bool()
  val wmask = UInt(4.W)
}

class MemBusResp extends Bundle {
  val rdata = UInt(32.W)
}

class MemBusIO extends Bundle {
  val req  = Decoupled(new MemBusReq)
  val resp = Flipped(Decoupled(new MemBusResp))
}
