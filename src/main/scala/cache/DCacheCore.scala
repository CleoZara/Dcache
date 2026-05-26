// =============================================================================
//  DCacheCore.scala
// =============================================================================

package cache

import chisel3._
import chisel3.util._

object CacheParams {
  val nSets     = 128
  val nWays     = 4
  val lineWords = 16
  val offsetBits = 6
  val idxBits    = log2Ceil(nSets)
  val tagBits    = 32 - offsetBits - idxBits
}

// =============================================================================
//  DCacheCore 顶层
// =============================================================================
class DCacheCore extends Module {
  import CacheParams._

  // ── 旁路地址常量 ────────────────────────────────────────────────────────────
  val ADDR_PRINTF   = "h10001FF1".U(32.W)
  val ADDR_MTIME_LO = "h0000BFF8".U(32.W)
  val ADDR_MTIME_HI = "h0000BFFC".U(32.W)

  val io = IO(new Bundle {
    // CPU 接口
    val addr    = Input(UInt(32.W))
    val flush   = Input(Bool())
    val wen     = Input(Bool())
    val wmask   = Input(UInt(4.W))
    val wdata   = Input(UInt(32.W))
    val memRen  = Input(Bool())
    val memWd   = Input(UInt(2.W))
    val signed  = Input(Bool())
    val rdata   = Output(UInt(32.W))
    val missOut = Output(Bool())
    // mtime 计数器输入
    val mtimeLo = Input(UInt(32.W))
    val mtimeHi = Input(UInt(32.W))
    // printf 旁路输出
    val printChar = Output(Valid(UInt(8.W)))
    // A→B 接口
    val missValid  = Output(Bool())
    val evictIdx   = Output(UInt(idxBits.W))
    val evictWay   = Output(UInt(2.W))
    val evictTag   = Output(UInt(tagBits.W))
    val evictDirty = Output(Bool())
    val evictLine  = Output(Vec(lineWords, UInt(32.W)))
    // B→A 接口
    val refillEn      = Input(Bool())
    val refillWay     = Input(UInt(2.W))
    val refillIdx     = Input(UInt(idxBits.W))
    val refillWord    = Input(UInt(4.W))
    val refillData    = Input(UInt(32.W))
    val refillTag     = Input(UInt(tagBits.W))
    val refillDone    = Input(Bool())
    val refillIsStore = Input(Bool())
  })

  // ── 地址分解 ────────────────────────────────────────────────────────────────
  val addrTag  = io.addr(31, offsetBits + idxBits)
  val addrIdx  = io.addr(offsetBits + idxBits - 1, offsetBits)
  val addrWoff = io.addr( 5,  2)
  val addrBoff = io.addr( 1,  0)

  // ── 旁路地址检测 ─────────────────────────────────────────────────────────────
  //
  //  设计原则：旁路由【地址】决定，不由操作类型决定。
  //    - printf 地址（0x10001FF1）：无论 Load / Store 均旁路，Cache 不介入。
  //      printChar 输出仅在 wen && memWd==2（SB）时有效。
  //    - mtime 地址（0xBFF8 / 0xBFFC）：无论 Load / Store 均旁路。
  //      Store 被静默忽略；Load 返回计数器值。
  //
  // printf 旁路触发条件：
  //   · SB（wen=1 且 memWd=2）→ 旁路，printChar 输出有效
  //   · Load（memRen=1）      → 旁路，rdata 返回 0
  //   · SW / SH（wen=1 且 memWd≠2）→ 不旁路，走正常 miss 流程
  val isPrintf     = (io.addr === ADDR_PRINTF) && io.wen    && (io.memWd === 2.U)
  val addrIsPrintf = (io.addr === ADDR_PRINTF) && (isPrintf || io.memRen)

  // mtime 旁路触发条件：地址命中即旁路，无论 Load / Store
  //   Load  → rdata 返回计数器值
  //   Store → 静默忽略（不 stall，不写寄存器）
  val addrIsMtimeLo = (io.addr === ADDR_MTIME_LO)
  val addrIsMtimeHi = (io.addr === ADDR_MTIME_HI)

  // 任意旁路地址命中时，Cache 子模块写使能全部屏蔽，missOut 强制为 0
  val isBypass = addrIsPrintf || addrIsMtimeLo || addrIsMtimeHi

  // mtime Load 条件：用于 rdata 选择
  val isMtimeLo = addrIsMtimeLo && io.memRen
  val isMtimeHi = addrIsMtimeHi && io.memRen

  // ── 子模块实例化 ─────────────────────────────────────────────────────────────
  val tagArray  = Module(new TagArray)
  val dataArray = Module(new DataArray)
  val plru      = Module(new TreePLRU)
  val hitTest   = Module(new HitTest)
  val loadExt   = Module(new LoadExtend)

  // ── HitTest ──────────────────────────────────────────────────────────────────
  hitTest.io.tagData := tagArray.io.tagData
  hitTest.io.tag     := addrTag
  hitTest.io.memRen  := io.memRen
  hitTest.io.wen     := io.wen

  val isHit  = hitTest.io.isHit && !isBypass
  val hitWay = hitTest.io.hitWay

  // ── TagArray ─────────────────────────────────────────────────────────────────
  tagArray.io.flush       := io.flush
  tagArray.io.idx         := addrIdx
  tagArray.io.refillTagEn := io.refillDone
  tagArray.io.refillWay   := io.refillWay
  tagArray.io.refillIdx   := io.refillIdx
  tagArray.io.refillTag   := io.refillTag
  tagArray.io.refillDirty := io.refillIsStore
  tagArray.io.setDirtyEn  := isHit && io.wen && !isBypass
  tagArray.io.setDirtyWay := hitWay

  // ── TreePLRU ─────────────────────────────────────────────────────────────────
  plru.io.idx       := addrIdx
  plru.io.updateEn  := ((isHit && (io.memRen || io.wen)) || io.refillDone) && !isBypass
  plru.io.updateWay := Mux(io.refillDone, io.refillWay, hitWay)

  // ── DataArray ────────────────────────────────────────────────────────────────
  dataArray.io.idx          := addrIdx
  dataArray.io.wordsoff     := addrWoff
  dataArray.io.hitWen       := isHit && io.wen && !isBypass
  dataArray.io.hitWay       := hitWay
  dataArray.io.wdata        := io.wdata
  dataArray.io.wmask        := io.wmask
  dataArray.io.refillDataEn := io.refillEn
  dataArray.io.refillWay    := io.refillWay
  dataArray.io.refillIdx    := io.refillIdx
  dataArray.io.refillWord   := io.refillWord
  dataArray.io.refillData   := io.refillData
  dataArray.io.evictIdx     := addrIdx
  dataArray.io.evictWay     := plru.io.evictWay

  // ── LoadExtend ───────────────────────────────────────────────────────────────
  loadExt.io.hitWay  := hitWay
  loadExt.io.byteoff := addrBoff
  loadExt.io.memWd   := io.memWd
  loadExt.io.signed  := io.signed
  loadExt.io.rawData := dataArray.io.rawData

  // ── printf 旁路输出 ───────────────────────────────────────────────────────────
  io.printChar.valid := isPrintf           // 仅 SB 时有效
  io.printChar.bits  := io.wdata(7, 0)

  // ── rdata 选择 ────────────────────────────────────────────────────────────────
  //  优先级：mtime Load > printf 误 Load（返回 0）> 正常 Cache 路径
  io.rdata := MuxCase(loadExt.io.rdata, Seq(
    isMtimeLo               -> io.mtimeLo,
    isMtimeHi               -> io.mtimeHi,
    (addrIsPrintf && io.memRen) -> 0.U,
  ))

  // ── missOut / missValid ───────────────────────────────────────────────────────
  //  isBypass=1 时强制为 0：旁路地址单拍完成，不 stall，不触发 MissHandler FSM
  val cachemiss  = hitTest.io.missValid && !isBypass
  io.missOut     := cachemiss
  io.missValid   := cachemiss

  // ── A→B 接口 ──────────────────────────────────────────────────────────────────
  io.evictIdx   := addrIdx
  io.evictWay   := plru.io.evictWay
  io.evictTag   := tagArray.io.tagData(plru.io.evictWay).tag
  io.evictDirty := tagArray.io.tagData(plru.io.evictWay).dirty
  io.evictLine  := dataArray.io.evictLine
}
