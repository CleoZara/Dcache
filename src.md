## src/main/scala/cache/DataArray.scala

```scala
package cache

import chisel3._
import chisel3.util._

class DataArray extends Module {
  import CacheParams._
  val io = IO(new Bundle {
    val idx          = Input(UInt(idxBits.W))
    val wordsoff     = Input(UInt(4.W))
    val hitWen       = Input(Bool())
    val hitWay       = Input(UInt(2.W))
    val wdata        = Input(UInt(32.W))
    val wmask        = Input(UInt(4.W))
    val refillDataEn = Input(Bool())
    val refillWay    = Input(UInt(2.W))
    val refillIdx    = Input(UInt(idxBits.W))
    val refillWord   = Input(UInt(4.W))
    val refillData   = Input(UInt(32.W))
    val evictIdx     = Input(UInt(idxBits.W))
    val evictWay     = Input(UInt(2.W))
    val evictLine    = Output(Vec(lineWords, UInt(32.W)))
    val rawData      = Output(Vec(nWays, UInt(32.W)))
  })
  val dArray = Reg(Vec(nWays, Vec(nSets, Vec(lineWords, UInt(32.W)))))
  for (w <- 0 until nWays) {
    io.rawData(w) := dArray(w)(io.idx)(io.wordsoff)
  }
  io.evictLine := dArray(io.evictWay)(io.evictIdx)
  when(io.refillDataEn) {
    dArray(io.refillWay)(io.refillIdx)(io.refillWord) := io.refillData
  }.elsewhen(io.hitWen) {
    val old      = dArray(io.hitWay)(io.idx)(io.wordsoff)
    val byteMask = Cat(
      Fill(8, io.wmask(3)), Fill(8, io.wmask(2)),
      Fill(8, io.wmask(1)), Fill(8, io.wmask(0))
    )
    dArray(io.hitWay)(io.idx)(io.wordsoff) :=
      (io.wdata & byteMask) | (old & ~byteMask)
  }
}
```

## src/main/scala/cache/DCacheMissFSM.scala

```scala
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
```

## src/main/scala/cache/DCacheStallLogic.scala

```scala
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
```

## src/main/scala/cache/DCacheTop.scala

```scala
package cache

import chisel3._
import chisel3.util._

object CacheParams {
  val nSets      = 128
  val nWays      = 4
  val lineWords  = 16
  val offsetBits = 6
  val idxBits    = log2Ceil(nSets)
  val tagBits    = 32 - offsetBits - idxBits
}

class DCacheTop extends Module {
  import CacheParams._

  val ADDR_PRINTF   = "h10001FF1".U(32.W)
  val ADDR_MTIME_LO = "h0000BFF8".U(32.W)
  val ADDR_MTIME_HI = "h0000BFFC".U(32.W)

  val io = IO(new Bundle {
    val addr   = Input(UInt(32.W))
    val flush  = Input(Bool())
    val wen    = Input(Bool())
    val wmask  = Input(UInt(4.W))
    val wdata  = Input(UInt(32.W))
    val memRen = Input(Bool())
    val memWd  = Input(UInt(2.W))
    val signed = Input(Bool())

    val rdata   = Output(UInt(32.W))
    val missOut = Output(Bool())
    val stall   = Output(Bool())

    val mtimeLo = Input(UInt(32.W))
    val mtimeHi = Input(UInt(32.W))

    val printChar = Output(Valid(UInt(8.W)))
    val mem = new MemBusIO
  })

  val addrTag  = io.addr(31, offsetBits + idxBits)
  val addrIdx  = io.addr(offsetBits + idxBits - 1, offsetBits)
  val addrWoff = io.addr(5, 2)
  val addrBoff = io.addr(1, 0)

  val isPrintf     = (io.addr === ADDR_PRINTF) && io.wen && (io.memWd === 2.U)
  val addrIsPrintf = (io.addr === ADDR_PRINTF) && (isPrintf || io.memRen)
  val addrIsMtimeLo = io.addr === ADDR_MTIME_LO
  val addrIsMtimeHi = io.addr === ADDR_MTIME_HI
  val isBypass = addrIsPrintf || addrIsMtimeLo || addrIsMtimeHi

  val isMtimeLo = addrIsMtimeLo && io.memRen
  val isMtimeHi = addrIsMtimeHi && io.memRen

  val tagArray  = Module(new TagArray)
  val dataArray = Module(new DataArray)
  val plru      = Module(new TreePLRU)
  val hitTest   = Module(new HitTest)
  val loadExt   = Module(new LoadExtend)
  val missFsm   = Module(new DCacheMissFSM)

  hitTest.io.tagData := tagArray.io.tagData
  hitTest.io.tag     := addrTag
  hitTest.io.memRen  := io.memRen
  hitTest.io.wen     := io.wen

  val isHit  = hitTest.io.isHit && !isBypass
  val hitWay = hitTest.io.hitWay

  val cacheMiss = hitTest.io.missValid && !isBypass

  tagArray.io.flush       := io.flush
  tagArray.io.idx         := addrIdx
  tagArray.io.refillTagEn := missFsm.io.refillDone
  tagArray.io.refillWay   := missFsm.io.refillWay
  tagArray.io.refillIdx   := missFsm.io.refillIdx
  tagArray.io.refillTag   := missFsm.io.refillTag
  tagArray.io.refillDirty := missFsm.io.refillIsStore
  tagArray.io.setDirtyEn  := isHit && io.wen && !isBypass
  tagArray.io.setDirtyWay := hitWay

  plru.io.idx       := addrIdx
  plru.io.updateEn  := ((isHit && (io.memRen || io.wen)) || missFsm.io.refillDone) && !isBypass
  plru.io.updateWay := Mux(missFsm.io.refillDone, missFsm.io.refillWay, hitWay)

  dataArray.io.idx          := addrIdx
  dataArray.io.wordsoff     := addrWoff
  dataArray.io.hitWen       := isHit && io.wen && !isBypass
  dataArray.io.hitWay       := hitWay
  dataArray.io.wdata        := io.wdata
  dataArray.io.wmask        := io.wmask
  dataArray.io.refillDataEn := missFsm.io.refillEn
  dataArray.io.refillWay    := missFsm.io.refillWay
  dataArray.io.refillIdx    := missFsm.io.refillIdx
  dataArray.io.refillWord   := missFsm.io.refillWord
  dataArray.io.refillData   := missFsm.io.refillData
  dataArray.io.evictIdx     := addrIdx
  dataArray.io.evictWay     := plru.io.evictWay

  loadExt.io.hitWay  := hitWay
  loadExt.io.byteoff := addrBoff
  loadExt.io.memWd   := io.memWd
  loadExt.io.signed  := io.signed
  loadExt.io.rawData := dataArray.io.rawData

  missFsm.io.missValid   := cacheMiss
  missFsm.io.missTag     := addrTag
  missFsm.io.missIsStore := io.wen
  missFsm.io.evictIdx    := addrIdx
  missFsm.io.evictWay    := plru.io.evictWay
  missFsm.io.evictTag    := tagArray.io.tagData(plru.io.evictWay).tag
  missFsm.io.evictDirty  := tagArray.io.tagData(plru.io.evictWay).dirty
  missFsm.io.evictLine   := dataArray.io.evictLine

  io.mem <> missFsm.io.mem

  io.printChar.valid := isPrintf
  io.printChar.bits  := io.wdata(7, 0)

  io.rdata := MuxCase(loadExt.io.rdata, Seq(
    isMtimeLo -> io.mtimeLo,
    isMtimeHi -> io.mtimeHi,
    (addrIsPrintf && io.memRen) -> 0.U,
  ))

  val topStall = cacheMiss || missFsm.io.stall
  io.missOut := topStall
  io.stall   := topStall
}
```

## src/main/scala/cache/HitTest.scala

```scala
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
```

## src/main/scala/cache/LoadExtend.scala

```scala
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
```

## src/main/scala/cache/MemBusIO.scala

```scala
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
```

## src/main/scala/cache/TagArray.scala

```scala
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
```

## src/main/scala/cache/TreePLRU.scala

```scala
package cache

import chisel3._
import chisel3.util._

class TreePLRU extends Module {
  import CacheParams._
  val io = IO(new Bundle {
    val idx       = Input(UInt(idxBits.W))
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
```

