package cache

import chisel3._
import chisel3.util._

class DCacheMissFSM extends Module {
  import CacheParams._

  val io = IO(new Bundle {
    val missValid  = Input(Bool())
    val missTag    = Input(UInt(tagBits.W))
    val missIsStore = Input(Bool())

    val evictIdx   = Input(UInt(idxBits.W))
    val evictWay   = Input(UInt(2.W))
    val evictTag   = Input(UInt(tagBits.W))
    val evictDirty = Input(Bool())
    val evictLine  = Input(Vec(lineWords, UInt(32.W)))

    val mem = new MemBusIO

    val refillEn      = Output(Bool())
    val refillWay     = Output(UInt(2.W))
    val refillIdx     = Output(UInt(idxBits.W))
    val refillWord    = Output(UInt(4.W))
    val refillData    = Output(UInt(32.W))
    val refillTag     = Output(UInt(tagBits.W))
    val refillDone    = Output(Bool())
    val refillIsStore = Output(Bool())

    val stall = Output(Bool())
  })

  val sIdle :: sCheck :: sWbReq :: sWbResp :: sRefillReq :: sRefillResp :: sDone :: Nil = Enum(7)

  val state     = RegInit(sIdle)
  val nextState = WireDefault(state)

  val missTagLatch    = RegInit(0.U(tagBits.W))
  val evictTagLatch   = RegInit(0.U(tagBits.W))
  val idxLatch        = RegInit(0.U(idxBits.W))
  val wayLatch        = RegInit(0.U(2.W))
  val dirtyLatch      = RegInit(false.B)
  val isStoreLatch    = RegInit(false.B)
  val wordCnt         = RegInit(0.U(4.W))
  val lineBuf         = Reg(Vec(lineWords, UInt(32.W)))

  val lastWord = wordCnt === (lineWords - 1).U

  switch(state) {
    is(sIdle) {
      when(io.missValid) {
        nextState := sCheck
      }
    }
    is(sCheck) {
      nextState := Mux(dirtyLatch, sWbReq, sRefillReq)
    }
    is(sWbReq) {
      when(io.mem.req.fire) {
        nextState := sWbResp
      }
    }
    is(sWbResp) {
      when(io.mem.resp.fire) {
        nextState := Mux(lastWord, sRefillReq, sWbReq)
      }
    }
    is(sRefillReq) {
      when(io.mem.req.fire) {
        nextState := sRefillResp
      }
    }
    is(sRefillResp) {
      when(io.mem.resp.fire) {
        nextState := Mux(lastWord, sDone, sRefillReq)
      }
    }
    is(sDone) {
      nextState := sIdle
    }
  }

  state := nextState

  when(state === sIdle && io.missValid) {
    missTagLatch  := io.missTag
    evictTagLatch := io.evictTag
    idxLatch      := io.evictIdx
    wayLatch      := io.evictWay
    dirtyLatch    := io.evictDirty
    isStoreLatch  := io.missIsStore
    wordCnt       := 0.U
    for (i <- 0 until lineWords) {
      lineBuf(i) := io.evictLine(i)
    }
  }.elsewhen((state === sWbResp || state === sRefillResp) && io.mem.resp.fire) {
    wordCnt := Mux(lastWord, 0.U, wordCnt + 1.U)
  }

  val wbAddr     = Cat(evictTagLatch, idxLatch, wordCnt, 0.U(2.W))
  val refillAddr = Cat(missTagLatch, idxLatch, wordCnt, 0.U(2.W))
  val isWbReq    = state === sWbReq
  val isRefillReq = state === sRefillReq
  val isRefillResp = state === sRefillResp

  io.mem.req.valid      := isWbReq || isRefillReq
  io.mem.req.bits.addr  := Mux(isWbReq, wbAddr, refillAddr)
  io.mem.req.bits.wdata := Mux(isWbReq, lineBuf(wordCnt), 0.U)
  io.mem.req.bits.wen   := isWbReq
  io.mem.req.bits.wmask := Mux(isWbReq, "b1111".U, 0.U)

  io.mem.resp.ready := state === sWbResp || state === sRefillResp

  io.refillEn      := isRefillResp && io.mem.resp.fire
  io.refillWay     := wayLatch
  io.refillIdx     := idxLatch
  io.refillWord    := wordCnt
  io.refillData    := io.mem.resp.bits.rdata
  io.refillTag     := missTagLatch
  io.refillDone    := state === sDone
  io.refillIsStore := isStoreLatch

  io.stall := state =/= sIdle
}
