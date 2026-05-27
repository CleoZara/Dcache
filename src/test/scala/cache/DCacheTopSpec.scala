package cache

import chisel3._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, WriteVcdAnnotation}
import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.mutable

// ================================================================
//  DCacheTop 完整仿真测试（T01–T21）
//
//  配置：32KB（nSets=128, nWays=4, offsetBits=6, idxBits=7, tagBits=19）
//  内存模型：always-ready，每字握手后固定 MEM_LAT 拍返回 resp
//
//  T01  冷 Load Miss（返回正确数据，消耗 > 1 拍）
//  T02  Load 命中（第二次访问同一行，第一拍 stall=0）
//  T03  Store Miss（write-allocate 回填 + hit-store，reload 正确）
//  T04  命中 Store 字节掩码合并
//  T05  Dirty Eviction（写回正确数据后回填新行）
//  T06  Flush → valid 全清，再次访问变 miss
//  T07  printf Bypass（SB 触发 printChar，SW 不触发）
//  T08  mtime MMIO Bypass（0xBFF8/0xBFFC 立即返回，不 stall）
//  T09  字节 Load 符号 / 零扩展（LB / LBU，byte=0）
//  T10  半字 Load 符号 / 零扩展（LH / LHU，高半字）
//  T11  同行不同 word offset（一次 miss 填充整行，之后各 word 命中）
//  T12  stall 时序（miss 全程 stall=1，命中拍恰好 stall=0）
//  T13  PLRU 驱逐顺序（way0→way2→way1→way3→way0 循环）
//  T14  Store Miss 后 dirty=1，驱逐时写回正确数据
//  T15  LB / LBU 在 byte offset 0–3 全部正确提取（含符号扩展）
//  T16  LH / LHU 低半字（byte=0）和高半字（byte=2）均正确
//  T17  干净行驱逐：全程无 mem.req.wen 请求
//  T18  NOP 和 bypass 地址 Store 不引发 stall / mem 请求
//  T19  Store miss 仅覆盖目标 word；邻居 word 保留 DRAM 原值
//  T20  脏行写回后读取 DRAMSim storage 验证所有 16 word 正确
//  T21  Flush 清除多个 set（不同 set 均变 miss）
// ================================================================
class DCacheTopSpec extends AnyFlatSpec with ChiselScalatestTester {
  private val vcdAnnotations = Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)

  // ── 地址参数 ──────────────────────────────────────────────────
  // 32KB：tagBits=19 addr[31:13]，idxBits=7 addr[12:6]，offsetBits=6
  private val TAG_SH  = 13
  private val IDX_SH  = 6
  private val WORD_SH = 2

  /** 构造 32-bit 字节地址 */
  private def mkAddr(tag: Int, idx: Int, word: Int = 0, byte: Int = 0): Long =
    ((tag.toLong << TAG_SH) | (idx.toLong << IDX_SH) |
     (word.toLong << WORD_SH) | byte.toLong) & 0xFFFFFFFFL

  // ── DRAM 仿真模型 ─────────────────────────────────────────────
  //  握手后固定 MEM_LAT 拍返回 resp；req 侧 always-ready。
  //  每仿真拍在 clock.step() 之前调用 tick()。
  private val MEM_LAT = 10

  class DRAMSim {
    private val mem   = mutable.Map[Long, Int]()   // key = 字地址（byte addr >> 2）
    private var pCyc  = -1    // resp 送达拍号（-1 = 无 pending）
    private var pData = 0     // resp 数据

    /** 预设 DRAM 内容（字地址） */
    def preset(wa: Long, data: Int): Unit = mem(wa) = data

    /** 读 DRAMSim 内部 storage（字地址），未写过返回 0 */
    def readWord(wa: Long): Int = mem.getOrElse(wa, 0)

    /**
     * 在 clock.step() 之前调用：
     *   1. 按计划驱动 resp.valid / resp.bits.rdata
     *   2. 设置 req.ready = 1（总线 always-ready）
     *   3. 侦测 req 握手并调度下一条 resp
     */
    def tick(dut: DCacheTop, cycle: Int): Unit = {
      // 驱动 resp
      if (cycle == pCyc) {
        dut.io.mem.resp.valid.poke(true.B)
        dut.io.mem.resp.bits.rdata.poke((pData.toLong & 0xFFFFFFFFL).U)
        if (dut.io.mem.resp.ready.peek().litToBoolean) pCyc = -1
      } else {
        dut.io.mem.resp.valid.poke(false.B)
        dut.io.mem.resp.bits.rdata.poke(0.U)
      }

      // Always ready
      dut.io.mem.req.ready.poke(true.B)

      // 捕获 req（握手 = valid && ready，本模型 ready 恒为 1）
      if (dut.io.mem.req.valid.peek().litToBoolean) {
        val a  = dut.io.mem.req.bits.addr.peek().litValue.toLong
        val we = dut.io.mem.req.bits.wen.peek().litToBoolean
        val wd = dut.io.mem.req.bits.wdata.peek().litValue.toInt
        val wm = dut.io.mem.req.bits.wmask.peek().litValue.toInt
        val wa = a >> 2

        if (we) {
          // 写：按字节掩码更新 storage；调度哑 resp（FSM 需要 resp 推进状态）
          var old = mem.getOrElse(wa, 0)
          for (b <- 0 until 4)
            if ((wm >> b & 1) == 1)
              old = (old & ~(0xFF << (b * 8))) | (((wd >> (b * 8)) & 0xFF) << (b * 8))
          mem(wa) = old
          pData = 0; pCyc = cycle + MEM_LAT
        } else {
          // 读：调度包含 storage 数据的 resp
          pData = mem.getOrElse(wa, 0); pCyc = cycle + MEM_LAT
        }
      }
    }
  }

  // ── CPU 侧请求辅助 ────────────────────────────────────────────
  private def setLoad(dut: DCacheTop, a: Long,
                      memWd: Int = 0, signed: Boolean = false): Unit = {
    dut.io.addr.poke(a.U)
    dut.io.memRen.poke(true.B);  dut.io.wen.poke(false.B)
    dut.io.wmask.poke(0.U);      dut.io.wdata.poke(0.U)
    dut.io.memWd.poke(memWd.U);  dut.io.signed.poke(signed.B)
    dut.io.flush.poke(false.B)
  }

  private def setStore(dut: DCacheTop, a: Long, data: Int,
                       mask: Int = 0xF, memWd: Int = 0): Unit = {
    dut.io.addr.poke(a.U)
    dut.io.memRen.poke(false.B); dut.io.wen.poke(true.B)
    dut.io.wmask.poke(mask.U);   dut.io.wdata.poke((data.toLong & 0xFFFFFFFFL).U)
    dut.io.memWd.poke(memWd.U);  dut.io.signed.poke(false.B)
    dut.io.flush.poke(false.B)
  }

  private def setIdle(dut: DCacheTop): Unit = {
    dut.io.addr.poke(0.U)
    dut.io.memRen.poke(false.B); dut.io.wen.poke(false.B)
    dut.io.wmask.poke(0.U);      dut.io.wdata.poke(0.U)
    dut.io.memWd.poke(0.U);      dut.io.signed.poke(false.B)
    dut.io.flush.poke(false.B)
  }

  private def initMtime(dut: DCacheTop): Unit = {
    dut.io.mtimeLo.poke(0.U); dut.io.mtimeHi.poke(0.U)
  }

  /**
   * 执行一次访存，循环 tick + step 直到 stall=0（含消费 stall=0 那拍）。
   * 调用前需已通过 setLoad/setStore 设置好 DUT 输入，调用期间保持不变。
   *
   * 时序：
   *   stall=0 那拍 → rdata 为组合输出（Load 有效）；hitWen 组合有效（Store）
   *   clock.step() → DataArray / TagArray 寄存器在 clock edge 更新
   *   返回后 → 调用者处于 hit 拍的下一拍，Store 已提交
   *
   * @return (rdata at hit cycle, elapsed cycles including hit cycle step)
   */
  private def doAccess(dut: DCacheTop, dram: DRAMSim,
                       c0: Int, maxWait: Int = 600): (Long, Int) = {
    var c = c0; var done = false; var rd = 0L
    while (!done) {
      assert(c - c0 < maxWait,
        s"doAccess timeout after ${c - c0} cycles (started at cycle $c0)")
      dram.tick(dut, c)
      if (!dut.io.stall.peek().litToBoolean) {
        rd = dut.io.rdata.peek().litValue.toLong & 0xFFFFFFFFL
        done = true
      }
      dut.clock.step(1); c += 1
    }
    (rd, c - c0)
  }

  // ══════════════════════════════════════════════════════════════
  //  T01  冷 Load Miss → 正确返回数据，消耗 > 1 拍
  // ══════════════════════════════════════════════════════════════
  it should "T01 return correct data after cold load miss" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val a = mkAddr(tag = 1, idx = 5, word = 3)
      dram.preset(a >> 2, 0xCAFEBABE)

      setLoad(dut, a)
      var c = 0
      val (rd, elapsed) = doAccess(dut, dram, c); c += elapsed

      assert(rd == 0xCAFEBABEL,
        f"T01 rdata 0x$rd%08X != 0xCAFEBABE")
      assert(elapsed > 1,
        s"T01 cold miss took only $elapsed cycle(s); expected > 1")
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T02  命中：第二次访问同一行，第一拍即 stall=0
  // ══════════════════════════════════════════════════════════════
  it should "T02 hit on second access to same line within 1 cycle" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val a = mkAddr(tag = 2, idx = 10, word = 7)
      dram.preset(a >> 2, 0x12345678)

      setLoad(dut, a)
      var c = 0
      val (_, e1) = doAccess(dut, dram, c); c += e1

      setLoad(dut, a)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean,
        "T02 second access must hit (stall=0 on first cycle)")
      assert(dut.io.rdata.peek().litValue.toLong == 0x12345678L,
        "T02 hit rdata mismatch")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T03  Store Miss -> write-allocate 回填后 hit-store；
  //       紧接 reload 命中并返回 store 写入值
  // ══════════════════════════════════════════════════════════════
  it should "T03 store miss triggers write-allocate; reload returns stored value" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val a = mkAddr(tag = 3, idx = 20, word = 0)
      dram.preset(a >> 2, 0xAAAABBBB)

      setStore(dut, a, 0x12345678)
      var c = 0
      val (_, e1) = doAccess(dut, dram, c); c += e1

      setLoad(dut, a)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean,
        "T03 reload should hit immediately after store miss")
      val rd = dut.io.rdata.peek().litValue.toLong
      assert(rd == 0x12345678L, f"T03 reload 0x$rd%08X != 0x12345678")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T04  命中 Store 字节掩码合并
  //       mask=0x3：只写 byte[1:0]，高 2 字节保持原值
  // ══════════════════════════════════════════════════════════════
  it should "T04 hit store with byte mask merges data correctly" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val a = mkAddr(tag = 4, idx = 30, word = 5)
      dram.preset(a >> 2, 0x11223344)

      setLoad(dut, a)
      var c = 0
      val (_, e1) = doAccess(dut, dram, c); c += e1

      setStore(dut, a, 0xAABBCCDD, mask = 0x3)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T04 hit store should not stall")
      dut.clock.step(1); c += 1

      setLoad(dut, a)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T04 reload should hit")
      val rd = dut.io.rdata.peek().litValue.toLong
      assert(rd == 0x1122CCDDL, f"T04 byte-mask merge 0x$rd%08X != 0x1122CCDD")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T05  Dirty Eviction：写回正确数据，回填新行
  //
  //  PLRU 驱逐顺序（tree 初始全 0）：
  //    Fill way0 -> {1,1,0}；Fill way2 -> {0,1,1}
  //    Fill way1 -> {1,0,1}；Fill way3 -> {0,0,0} -> 下次驱逐 way0
  // ══════════════════════════════════════════════════════════════
  it should "T05 dirty eviction writes back correct data then refills new line" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val S = 50
      val (tA, tB, tC, tD, tE) = (10, 20, 30, 40, 50)
      for (t <- Seq(tA, tB, tC, tD, tE); w <- 0 until 16)
        dram.preset(mkAddr(t, S, word = w) >> 2, (t << 8) | w)

      var c = 0

      // 1. Load A -> fill way0
      setLoad(dut, mkAddr(tA, S))
      val (_, e1) = doAccess(dut, dram, c); c += e1

      // 2. Store A word0 -> way0 dirty
      setStore(dut, mkAddr(tA, S, word = 0), 0xDEADBEEF)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T05 store A must hit")
      dut.clock.step(1); c += 1
      setIdle(dut); dram.tick(dut, c); dut.clock.step(1); c += 1

      // 3~5. Load B/C/D -> fill way2/way1/way3
      for (t <- Seq(tB, tC, tD)) {
        setLoad(dut, mkAddr(t, S))
        val (_, e) = doAccess(dut, dram, c); c += e
        setIdle(dut); dram.tick(dut, c); dut.clock.step(1); c += 1
      }

      // 6. Load E -> evict way0 (tagA, dirty)
      setLoad(dut, mkAddr(tE, S))
      var wbWord0OK = false; var done5 = false

      while (!done5 && c < 1500) {
        dram.tick(dut, c)
        if (dut.io.mem.req.valid.peek().litToBoolean &&
            dut.io.mem.req.bits.wen.peek().litToBoolean) {
          val wa = dut.io.mem.req.bits.addr.peek().litValue.toLong
          if (wa == mkAddr(tA, S, word = 0)) {
            val wd = dut.io.mem.req.bits.wdata.peek().litValue.toLong
            assert(wd == 0xDEADBEEFL,
              f"T05 WB word0 data 0x$wd%08X != 0xDEADBEEF")
            wbWord0OK = true
          }
        }
        if (!dut.io.stall.peek().litToBoolean) done5 = true
        dut.clock.step(1); c += 1
      }
      assert(done5,     "T05 Load E never completed (timeout)")
      assert(wbWord0OK, "T05 dirty eviction for tagA word0 not detected")

      setLoad(dut, mkAddr(tE, S))
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T05 Load E should hit after refill")
      val rdE = dut.io.rdata.peek().litValue.toLong
      assert(rdE == ((tE << 8) | 0).toLong,
        f"T05 Load E word0: 0x$rdE%08X != expected 0x${(tE << 8) | 0}%08X")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T06  Flush -> valid 全清；下次访问触发 miss
  // ══════════════════════════════════════════════════════════════
  it should "T06 flush clears valid bits; subsequent access causes miss" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val a = mkAddr(tag = 7, idx = 60, word = 2)
      dram.preset(a >> 2, 0xBEEFCAFE)

      setLoad(dut, a)
      var c = 0
      val (_, e1) = doAccess(dut, dram, c); c += e1

      setLoad(dut, a)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T06 must hit before flush")
      dut.clock.step(1); c += 1

      setIdle(dut); dut.io.flush.poke(true.B)
      dram.tick(dut, c); dut.clock.step(1); c += 1
      dut.io.flush.poke(false.B)

      setLoad(dut, a)
      dram.tick(dut, c)
      assert(dut.io.stall.peek().litToBoolean,
        "T06 first access after flush must miss (stall=1)")
      val (rd2, _) = doAccess(dut, dram, c)
      assert(rd2 == 0xBEEFCAFEL, f"T06 re-fill data 0x$rd2%08X != 0xBEEFCAFE")
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T07  printf Bypass：SB 触发 printChar，SW 不触发
  // ══════════════════════════════════════════════════════════════
  it should "T07 SB to printf addr fires printChar; SW does not; neither stalls" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val PRINTF = 0x10001FF1L

      setStore(dut, PRINTF, 0x41, mask = 0x1, memWd = 2)
      dram.tick(dut, 0)
      assert(dut.io.printChar.valid.peek().litToBoolean,
        "T07 SB: printChar.valid must be 1")
      assert(dut.io.printChar.bits.peek().litValue.toLong == 0x41L,
        "T07 SB: printChar.bits != 0x41")
      assert(!dut.io.stall.peek().litToBoolean,
        "T07 SB to printf must not stall")
      dut.clock.step(1)

      setStore(dut, PRINTF, 0x42, mask = 0xF, memWd = 0)
      dram.tick(dut, 1)
      assert(!dut.io.printChar.valid.peek().litToBoolean,
        "T07 SW to printf addr must NOT fire printChar")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T08  mtime MMIO Bypass：0xBFF8 / 0xBFFC 立即返回，不 stall
  // ══════════════════════════════════════════════════════════════
  it should "T08 LW to mtime addresses returns correct values without stall" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim()
      dut.io.mtimeLo.poke(0xDEAD0000L.U)
      dut.io.mtimeHi.poke(0x0000BEEFL.U)

      setLoad(dut, 0xBFF8L)
      dram.tick(dut, 0)
      assert(!dut.io.stall.peek().litToBoolean, "T08 mtime_lo load must not stall")
      assert(dut.io.rdata.peek().litValue.toLong == 0xDEAD0000L,
        "T08 mtime_lo value mismatch")
      dut.clock.step(1)

      setLoad(dut, 0xBFFCL)
      dram.tick(dut, 1)
      assert(!dut.io.stall.peek().litToBoolean, "T08 mtime_hi load must not stall")
      assert(dut.io.rdata.peek().litValue.toLong == 0x0000BEEFL,
        "T08 mtime_hi value mismatch")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T09  字节 Load 符号 / 零扩展（byte=0）
  //       word=0xABCDEF80，byte 0 = 0x80
  //       LB -> 0xFFFFFF80；LBU -> 0x00000080
  // ══════════════════════════════════════════════════════════════
  it should "T09 LB=0xFFFFFF80; LBU=0x00000080 for byte value 0x80" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val aWord = mkAddr(tag = 8, idx = 70, word = 4)
      val aByte = mkAddr(tag = 8, idx = 70, word = 4, byte = 0)
      dram.preset(aWord >> 2, 0xABCDEF80)

      setLoad(dut, aByte, memWd = 2, signed = true)
      var c = 0
      val (rdS, e1) = doAccess(dut, dram, c); c += e1
      assert(rdS == 0xFFFFFF80L, f"T09 LB signed: 0x$rdS%08X != 0xFFFFFF80")

      setLoad(dut, aByte, memWd = 2, signed = false)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T09 LBU should hit")
      val rdU = dut.io.rdata.peek().litValue.toLong
      assert(rdU == 0x80L, f"T09 LBU: 0x$rdU%08X != 0x00000080")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T10  半字 Load 符号 / 零扩展（高半字 byte=2）
  //       word=0x80001234，byte=2 -> 高半字 0x8000
  //       LH -> 0xFFFF8000；LHU -> 0x00008000
  // ══════════════════════════════════════════════════════════════
  it should "T10 LH=0xFFFF8000; LHU=0x00008000 for halfword value 0x8000" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val aWord = mkAddr(tag = 9, idx = 80, word = 2)
      val aHalf = mkAddr(tag = 9, idx = 80, word = 2, byte = 2)
      dram.preset(aWord >> 2, 0x80001234)

      setLoad(dut, aHalf, memWd = 1, signed = true)
      var c = 0
      val (rdS, e1) = doAccess(dut, dram, c); c += e1
      assert(rdS == 0xFFFF8000L, f"T10 LH signed: 0x$rdS%08X != 0xFFFF8000")

      setLoad(dut, aHalf, memWd = 1, signed = false)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T10 LHU should hit")
      val rdU = dut.io.rdata.peek().litValue.toLong
      assert(rdU == 0x8000L, f"T10 LHU: 0x$rdU%08X != 0x00008000")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T11  同行不同 word offset：一次 miss 填充整行，
  //       之后 word 1 / 7 / 15 各自命中并返回正确数据
  // ══════════════════════════════════════════════════════════════
  it should "T11 all word offsets in a cache line hit after single miss" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val TAG = 11; val IDX = 90
      for (w <- 0 until 16)
        dram.preset(mkAddr(TAG, IDX, word = w) >> 2, 0xF0000000 | w)

      setLoad(dut, mkAddr(TAG, IDX, word = 0))
      var c = 0
      val (rd0, e0) = doAccess(dut, dram, c); c += e0
      assert(rd0 == 0xF0000000L, f"T11 word0: 0x$rd0%08X != 0xF0000000")

      for (w <- Seq(1, 7, 15)) {
        setLoad(dut, mkAddr(TAG, IDX, word = w))
        dram.tick(dut, c)
        assert(!dut.io.stall.peek().litToBoolean, s"T11 word $w should hit")
        val rd  = dut.io.rdata.peek().litValue.toLong
        val exp = 0xF0000000L | w.toLong
        assert(rd == exp, f"T11 word $w: 0x$rd%08X != 0x$exp%08X")
        dut.clock.step(1); c += 1
      }
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T12  stall 时序：miss 全程 stall=1，命中拍恰好 stall=0
  // ══════════════════════════════════════════════════════════════
  it should "T12 stall stays high during miss and goes low exactly on hit cycle" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val a = mkAddr(tag = 12, idx = 100, word = 0)
      dram.preset(a >> 2, 0x55AA55AA)

      setLoad(dut, a)
      var c = 0; var stallCnt = 0; var rd = 0L; var hitSeen = false

      while (!hitSeen && c < 600) {
        dram.tick(dut, c)
        if (dut.io.stall.peek().litToBoolean) stallCnt += 1
        else { rd = dut.io.rdata.peek().litValue.toLong & 0xFFFFFFFFL; hitSeen = true }
        dut.clock.step(1); c += 1
      }
      assert(hitSeen,       "T12 hit was never seen")
      assert(stallCnt > 1,  s"T12 stall high for only $stallCnt cycle(s); expected > 1")
      assert(rd == 0x55AA55AAL, f"T12 rdata at hit cycle: 0x$rd%08X != 0x55AA55AA")

      setLoad(dut, a)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean,
        "T12 consecutive access must immediately hit (stall=0)")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T13  PLRU 驱逐顺序：way0->way2->way1->way3->way0 循环
  //       填满 4 路后，第 5 次 miss 驱逐 way0；
  //       驱逐后 way0 的原 tag 再次访问应 miss
  // ══════════════════════════════════════════════════════════════
  it should "T13 PLRU evicts way0-way2-way1-way3 cyclically; 5th miss evicts way0" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val S    = 110
      val tags = Seq(200, 300, 400, 500, 600)
      for (t <- tags; w <- 0 until 16)
        dram.preset(mkAddr(t, S, word = w) >> 2, (t << 4) | w)

      var c = 0
      for (t <- tags.take(4)) {
        setLoad(dut, mkAddr(t, S))
        val (_, e) = doAccess(dut, dram, c); c += e
        setIdle(dut); dram.tick(dut, c); dut.clock.step(1); c += 1
      }

      setLoad(dut, mkAddr(tags(4), S))
      dram.tick(dut, c)
      assert(dut.io.stall.peek().litToBoolean, "T13 5th access must miss")
      val (rd5, e5) = doAccess(dut, dram, c); c += e5
      assert(rd5 == ((tags(4) << 4) | 0).toLong, f"T13 5th fill word0: 0x$rd5%08X")

      setLoad(dut, mkAddr(tags(0), S))
      dram.tick(dut, c)
      assert(dut.io.stall.peek().litToBoolean,
        s"T13 tag ${tags(0)} must be evicted and miss again")
      val (rdRecov, _) = doAccess(dut, dram, c)
      assert(rdRecov == ((tags(0) << 4) | 0).toLong,
        "T13 re-filled evicted line data mismatch")
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T14  Store Miss -> refillIsStore=1 -> dirty=1
  //       驱逐时 FSM 执行写回，写回数据为 store 写入的值
  // ══════════════════════════════════════════════════════════════
  it should "T14 store-miss refill sets dirty=1; line is written back on eviction" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val S2 = 120
      val (tX, tB2, tC2, tD2, tE2) = (60, 70, 80, 90, 100)
      val STORE_VAL = 0xFACEFACE
      val STORE_EXP = STORE_VAL.toLong & 0xFFFFFFFFL
      for (t <- Seq(tX, tB2, tC2, tD2, tE2); w <- 0 until 16)
        dram.preset(mkAddr(t, S2, word = w) >> 2,
          if (t == tX) 0 else 0xEEEEEEEE)

      var c = 0

      setStore(dut, mkAddr(tX, S2, word = 0), STORE_VAL)
      val (_, eX) = doAccess(dut, dram, c); c += eX
      setIdle(dut); dram.tick(dut, c); dut.clock.step(1); c += 1

      setLoad(dut, mkAddr(tX, S2, word = 0))
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T14 tX load should hit")
      assert(dut.io.rdata.peek().litValue.toLong == STORE_EXP,
        f"T14 stored value mismatch: expected 0x$STORE_EXP%08X")
      dut.clock.step(1); c += 1

      for (t <- Seq(tB2, tC2, tD2)) {
        setLoad(dut, mkAddr(t, S2))
        val (_, e) = doAccess(dut, dram, c); c += e
        setIdle(dut); dram.tick(dut, c); dut.clock.step(1); c += 1
      }

      setLoad(dut, mkAddr(tE2, S2))
      var wbSeen = false; var done14 = false

      while (!done14 && c < 1500) {
        dram.tick(dut, c)
        if (dut.io.mem.req.valid.peek().litToBoolean &&
            dut.io.mem.req.bits.wen.peek().litToBoolean) {
          val wa = dut.io.mem.req.bits.addr.peek().litValue.toLong
          if (wa == mkAddr(tX, S2, word = 0)) {
            val wd = dut.io.mem.req.bits.wdata.peek().litValue.toLong
            assert(wd == STORE_EXP,
              f"T14 WB word0: 0x$wd%08X != 0x$STORE_EXP%08X")
            wbSeen = true
          }
        }
        if (!dut.io.stall.peek().litToBoolean) done14 = true
        dut.clock.step(1); c += 1
      }
      assert(done14, "T14 Load tE2 never completed (timeout)")
      assert(wbSeen, "T14 dirty line from store-miss was NOT written back on eviction")
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T15  LB / LBU 在 byte offset 0-3 全部正确提取（含符号扩展）
  //
  //  word = 0xAABBCCDD
  //    byte 0=0xDD, 1=0xCC, 2=0xBB, 3=0xAA（全为负数）
  //  LoadExtend：byteShift = Cat(byteoff, 0.U(3.W)) = byteoff * 8
  // ══════════════════════════════════════════════════════════════
  it should "T15 LB/LBU correctly extract all 4 byte offsets with sign extension" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val TAG = 20; val IDX = 3; val WORD = 6
      dram.preset(mkAddr(TAG, IDX, word = WORD) >> 2, 0xAABBCCDD)

      setLoad(dut, mkAddr(TAG, IDX, word = WORD, byte = 0), memWd = 2, signed = false)
      var c = 0
      val (_, e0) = doAccess(dut, dram, c); c += e0

      // LBU（无符号提取）
      val lbuExp = Seq(0xDDL, 0xCCL, 0xBBL, 0xAAL)
      for (off <- 0 to 3) {
        setLoad(dut, mkAddr(TAG, IDX, word = WORD, byte = off), memWd = 2, signed = false)
        dram.tick(dut, c)
        assert(!dut.io.stall.peek().litToBoolean, s"T15 LBU byte=$off should hit")
        val rd = dut.io.rdata.peek().litValue.toLong
        assert(rd == lbuExp(off),
          f"T15 LBU byte=$off: 0x$rd%02X != 0x${lbuExp(off)}%02X")
        dut.clock.step(1); c += 1
      }

      // LB（有符号扩展，全部为负数）
      val lbExp = Seq(0xFFFFFFDDL, 0xFFFFFFCCL, 0xFFFFFFBBL, 0xFFFFFFAAL)
      for (off <- 0 to 3) {
        setLoad(dut, mkAddr(TAG, IDX, word = WORD, byte = off), memWd = 2, signed = true)
        dram.tick(dut, c)
        assert(!dut.io.stall.peek().litToBoolean, s"T15 LB signed byte=$off should hit")
        val rd = dut.io.rdata.peek().litValue.toLong & 0xFFFFFFFFL
        assert(rd == lbExp(off),
          f"T15 LB signed byte=$off: 0x$rd%08X != 0x${lbExp(off)}%08X")
        dut.clock.step(1); c += 1
      }
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T16  LH / LHU 低半字（byte=0）和高半字（byte=2）均正确
  //
  //  word = 0x80017FFE
  //    低半字 0x7FFE（正数）-> LH=0x00007FFE，LHU=0x00007FFE
  //    高半字 0x8001（负数）-> LH=0xFFFF8001，LHU=0x00008001
  //  LoadExtend：halfShift = Cat(byteoff[1], 0.U(4.W)) = byteoff[1] * 16
  // ══════════════════════════════════════════════════════════════
  it should "T16 LH/LHU correctly handle low halfword (byte=0) and high halfword (byte=2)" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val TAG = 21; val IDX = 4; val WORD = 3
      dram.preset(mkAddr(TAG, IDX, word = WORD) >> 2, 0x80017FFE)

      setLoad(dut, mkAddr(TAG, IDX, word = WORD, byte = 0), memWd = 1, signed = false)
      var c = 0
      val (rdLow, e0) = doAccess(dut, dram, c); c += e0
      assert(rdLow == 0x7FFEL,
        f"T16 LHU byte=0(low half): 0x$rdLow%08X != 0x00007FFE")

      // LH signed byte=0（低半字正数，符号扩展后高位为 0）
      setLoad(dut, mkAddr(TAG, IDX, word = WORD, byte = 0), memWd = 1, signed = true)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T16 LH low-half should hit")
      assert(dut.io.rdata.peek().litValue.toLong == 0x7FFEL,
        "T16 LH signed byte=0: positive value should not sign-extend")
      dut.clock.step(1); c += 1

      // LHU byte=2（高半字零扩展）
      setLoad(dut, mkAddr(TAG, IDX, word = WORD, byte = 2), memWd = 1, signed = false)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T16 LHU high-half should hit")
      assert(dut.io.rdata.peek().litValue.toLong == 0x8001L,
        "T16 LHU byte=2: 0x8001 zero-extended")
      dut.clock.step(1); c += 1

      // LH signed byte=2（高半字负数，符号扩展）
      setLoad(dut, mkAddr(TAG, IDX, word = WORD, byte = 2), memWd = 1, signed = true)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T16 LH signed high-half should hit")
      val rdLhHigh = dut.io.rdata.peek().litValue.toLong & 0xFFFFFFFFL
      assert(rdLhHigh == 0xFFFF8001L,
        f"T16 LH signed byte=2: 0x$rdLhHigh%08X != 0xFFFF8001")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T17  干净行驱逐：全程无 mem.req.wen 请求
  //
  //  4 路全为 clean load（无 store），第 5 次 miss 驱逐 way0（dirty=0）
  //  -> FSM 跳过 sWbReq/sWbResp，直接进入 sRefillReq
  //  整个过程 mem.req.wen 必须始终为 0
  // ══════════════════════════════════════════════════════════════
  it should "T17 clean eviction never asserts mem.req.wen (no writeback)" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val S    = 55
      val tags = Seq(11, 22, 33, 44, 55)
      for (t <- tags; w <- 0 until 16)
        dram.preset(mkAddr(t, S, word = w) >> 2, (t << 8) | w)

      var c = 0
      for (t <- tags.take(4)) {
        setLoad(dut, mkAddr(t, S))
        val (_, e) = doAccess(dut, dram, c); c += e
        setIdle(dut); dram.tick(dut, c); dut.clock.step(1); c += 1
      }

      setLoad(dut, mkAddr(tags(4), S))
      var wbSeen = false; var done = false
      val startC = c

      while (!done && c - startC < 600) {
        dram.tick(dut, c)
        if (dut.io.mem.req.valid.peek().litToBoolean &&
            dut.io.mem.req.bits.wen.peek().litToBoolean)
          wbSeen = true
        if (!dut.io.stall.peek().litToBoolean) done = true
        dut.clock.step(1); c += 1
      }
      assert(done,    "T17 5th miss timed out")
      assert(!wbSeen, "T17 clean eviction must NOT generate any mem.req.wen=1 requests")

      setLoad(dut, mkAddr(tags(4), S))
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean,
        "T17 5th tag should hit immediately after refill")
      val rd = dut.io.rdata.peek().litValue.toLong
      assert(rd == ((tags(4) << 8) | 0).toLong, f"T17 refilled word0: 0x$rd%08X")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T18  NOP 和 bypass 地址 Store 不引发 stall 或 mem 请求
  //
  //  场景 A：memRen=wen=0 -> stall=0，mem.req.valid=0
  //  场景 B：SW 到 mtime_lo（0xBFF8）-> isBypass=1，
  //          stall=0，mem.req.valid=0，mtime 读值不变
  //  场景 C：SW 到 mtime_hi（0xBFFC），同上
  //  场景 D：miss 完成后再发 NOP，stall 持续为 0
  // ══════════════════════════════════════════════════════════════
  it should "T18 NOP and mtime-bypass store do not stall or generate mem requests" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim()
      dut.io.mtimeLo.poke(0xABCD1234L.U)
      dut.io.mtimeHi.poke(0x00005678L.U)

      // 场景 A：纯 NOP
      setIdle(dut); dram.tick(dut, 0)
      assert(!dut.io.stall.peek().litToBoolean,
        "T18-A NOP must not stall")
      assert(!dut.io.mem.req.valid.peek().litToBoolean,
        "T18-A NOP must not generate mem req")
      assert(!dut.io.printChar.valid.peek().litToBoolean,
        "T18-A NOP must not fire printChar")
      dut.clock.step(1)

      // 场景 B：SW 到 mtime_lo，静默忽略
      setStore(dut, 0xBFF8L, 0xDEADBEEF); dram.tick(dut, 1)
      assert(!dut.io.stall.peek().litToBoolean,
        "T18-B SW to mtime_lo must not stall")
      assert(!dut.io.mem.req.valid.peek().litToBoolean,
        "T18-B SW to mtime_lo must not gen mem req")
      dut.clock.step(1)
      setLoad(dut, 0xBFF8L); dram.tick(dut, 2)
      assert(dut.io.rdata.peek().litValue.toLong == 0xABCD1234L,
        "T18-B mtime_lo must remain unchanged after ignored SW")
      dut.clock.step(1)

      // 场景 C：SW 到 mtime_hi，静默忽略
      setStore(dut, 0xBFFCL, 0xCAFEBABE); dram.tick(dut, 3)
      assert(!dut.io.stall.peek().litToBoolean,
        "T18-C SW to mtime_hi must not stall")
      assert(!dut.io.mem.req.valid.peek().litToBoolean,
        "T18-C SW to mtime_hi must not gen mem req")
      dut.clock.step(1)
      setLoad(dut, 0xBFFCL); dram.tick(dut, 4)
      assert(dut.io.rdata.peek().litValue.toLong == 0x00005678L,
        "T18-C mtime_hi must remain unchanged after ignored SW")
      dut.clock.step(1)

      // 场景 D：miss 完成后 NOP
      val a = mkAddr(30, 5, word = 0)
      dram.preset(a >> 2, 0x11223344)
      setLoad(dut, a)
      var c = 5
      val (_, e) = doAccess(dut, dram, c); c += e
      setIdle(dut); dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean,
        "T18-D NOP after miss completion must not stall")
      dut.clock.step(1)
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T19  Store miss 仅覆盖目标 word；邻居 word 保留 DRAM 原值
  //
  //  DRAM 整行：word w = 0xD0000000 | w
  //  Store miss 到 word 5 写入 0xFEEDFACE
  //  验证 word 0 / 5 / 10 / 15
  // ══════════════════════════════════════════════════════════════
  it should "T19 store miss only modifies target word; all other words retain DRAM values" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val TAG = 31; val S3 = 65
      for (w <- 0 until 16)
        dram.preset(mkAddr(TAG, S3, word = w) >> 2, 0xD0000000 | w)

      setStore(dut, mkAddr(TAG, S3, word = 5), 0xFEEDFACE)
      var c = 0
      val (_, e1) = doAccess(dut, dram, c); c += e1
      setIdle(dut); dram.tick(dut, c); dut.clock.step(1); c += 1

      // word 5：store 写入值
      setLoad(dut, mkAddr(TAG, S3, word = 5))
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T19 word5 should hit")
      assert(dut.io.rdata.peek().litValue.toLong == 0xFEEDFACEL,
        "T19 word5: expected 0xFEEDFACE")
      dut.clock.step(1); c += 1

      // word 0, 10, 15：DRAM refill 原值
      val checks = Seq(0 -> 0xD0000000L, 10 -> 0xD000000AL, 15 -> 0xD000000FL)
      for ((w, exp) <- checks) {
        setLoad(dut, mkAddr(TAG, S3, word = w))
        dram.tick(dut, c)
        assert(!dut.io.stall.peek().litToBoolean, s"T19 word$w should hit")
        val rd = dut.io.rdata.peek().litValue.toLong
        assert(rd == exp, f"T19 word$w: 0x$rd%08X != 0x$exp%08X (DRAM original)")
        dut.clock.step(1); c += 1
      }
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T20  脏行写回后，DRAMSim storage 中所有 16 word 均正确
  //
  //  比 T05 更严格：直接读取 DRAMSim.storage 验证写回内容，
  //  覆盖 FSM 写回时 lineBuf 捕获是否包含全部已修改 word。
  //
  //  修改了 word0 和 word8；其余 word 应保留 refill 时的 DRAM 原值
  // ══════════════════════════════════════════════════════════════
  it should "T20 all 16 words of dirty eviction line are correctly written back to DRAM" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)
      val S4 = 70
      val (tA2, tB2, tC2, tD2, tE2) = (101, 102, 103, 104, 105)
      for (t <- Seq(tA2, tB2, tC2, tD2, tE2); w <- 0 until 16)
        dram.preset(mkAddr(t, S4, word = w) >> 2, (t << 8) | w)

      var c = 0

      // 1. Load A -> fill way0
      setLoad(dut, mkAddr(tA2, S4))
      val (_, e1) = doAccess(dut, dram, c); c += e1

      // 2. Store A word0 <- 0xDEADBEEF
      setStore(dut, mkAddr(tA2, S4, word = 0), 0xDEADBEEF)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T20 store word0 should hit")
      dut.clock.step(1); c += 1

      // 3. Store A word8 <- 0xBEEFCAFE
      setStore(dut, mkAddr(tA2, S4, word = 8), 0xBEEFCAFE)
      dram.tick(dut, c)
      assert(!dut.io.stall.peek().litToBoolean, "T20 store word8 should hit")
      dut.clock.step(1); c += 1
      setIdle(dut); dram.tick(dut, c); dut.clock.step(1); c += 1

      // 4. Fill way2/way1/way3 使 PLRU 归零
      for (t <- Seq(tB2, tC2, tD2)) {
        setLoad(dut, mkAddr(t, S4))
        val (_, e) = doAccess(dut, dram, c); c += e
        setIdle(dut); dram.tick(dut, c); dut.clock.step(1); c += 1
      }

      // 5. Load E -> evict way0（dirty），等待完成
      setLoad(dut, mkAddr(tE2, S4))
      var done20 = false
      while (!done20 && c < 1500) {
        dram.tick(dut, c)
        if (!dut.io.stall.peek().litToBoolean) done20 = true
        dut.clock.step(1); c += 1
      }
      assert(done20, "T20 Load E timed out")

      // 6. 读 DRAMSim.storage 验证所有 16 word
      val wa0 = dram.readWord(mkAddr(tA2, S4, word = 0) >> 2)
      assert(wa0 == 0xDEADBEEF, f"T20 DRAM word0: 0x$wa0%08X != 0xDEADBEEF")

      val wa8 = dram.readWord(mkAddr(tA2, S4, word = 8) >> 2)
      assert(wa8 == 0xBEEFCAFE, f"T20 DRAM word8: 0x$wa8%08X != 0xBEEFCAFE")

      for (w <- Seq(1, 2, 3, 4, 5, 6, 7, 9, 10, 11, 12, 13, 14, 15)) {
        val raw = dram.readWord(mkAddr(tA2, S4, word = w) >> 2)
        val exp = (tA2 << 8) | w
        assert(raw == exp,
          f"T20 DRAM word$w: 0x$raw%08X != 0x$exp%08X (refill original)")
      }
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  T21  Flush 清除多个 set：3 个不同 set 均变 miss
  //
  //  T06 只在单 set 验证 flush 效果；
  //  本测试在 3 个 set 分别填充数据，flush 后验证
  //  所有 set 的 valid 均被清零（访问均变 miss）
  // ══════════════════════════════════════════════════════════════
  it should "T21 flush clears valid bits across multiple sets; all sets miss after flush" in {
    test(new DCacheTop).withAnnotations(vcdAnnotations) { dut =>
      val dram = new DRAMSim(); initMtime(dut)

      val cases = Seq((40, 10), (40, 50), (40, 100))   // (tag, idx)
      for ((tag, idx) <- cases; w <- 0 until 16)
        dram.preset(mkAddr(tag, idx, word = w) >> 2, w * 0x11111111)

      var c = 0
      // 填充 3 个 set
      for ((tag, idx) <- cases) {
        setLoad(dut, mkAddr(tag, idx, word = 0))
        val (_, e) = doAccess(dut, dram, c); c += e
      }

      // 确认全部命中
      for ((tag, idx) <- cases) {
        setLoad(dut, mkAddr(tag, idx, word = 0))
        dram.tick(dut, c)
        assert(!dut.io.stall.peek().litToBoolean,
          s"T21 set idx=$idx should hit before flush")
        dut.clock.step(1); c += 1
      }

      // Flush 1 拍
      setIdle(dut); dut.io.flush.poke(true.B)
      dram.tick(dut, c); dut.clock.step(1); c += 1
      dut.io.flush.poke(false.B)

      // Flush 后 3 个 set 均应 miss
      for ((tag, idx) <- cases) {
        setLoad(dut, mkAddr(tag, idx, word = 0))
        dram.tick(dut, c)
        assert(dut.io.stall.peek().litToBoolean,
          s"T21 set idx=$idx must miss after flush (stall=1)")
        val (_, e) = doAccess(dut, dram, c); c += e
      }
    }
  }
}
