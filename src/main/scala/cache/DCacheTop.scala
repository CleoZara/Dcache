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
