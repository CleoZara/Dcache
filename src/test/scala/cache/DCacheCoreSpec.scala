// =============================================================================
//  DCacheCoreSpec.scala  —  DCacheCore 全功能仿真测试
//
//  后端：Verilator（需在 MSYS2 / WSL2 / Linux 中安装 verilator）
//        若 Verilator 未安装，将 withAnnotations(Seq(VerilatorBackendAnnotation))
//        改为 withAnnotations(Seq(TreadleBackendAnnotation)) 即可退回 Treadle。
//
//  运行单个用例：
//    sbt "testOnly cache.DCacheCoreSpec -- -z T09"
//  运行全部：
//    sbt test
//
//  测试覆盖：
//    T01  Flush 清零 valid/dirty，后续访问均 miss；idle 时不触发 miss
//    T02  HIT 读：字 / 半字（有符号/无符号）/ 字节（有符号/无符号，全部 byteoff）
//    T03  HIT 写：wmask 字节掩码精确写入 + 写后立即读回
//    T04  HIT 写置 dirty 位，flush 后 dirty 清零
//    T05  MISS 回填：16 字期间 TagArray 未更新（仍 miss），refillDone 后命中
//    T06  MISS 回填数据完整性：16 字全部读回正确
//    T07  Store miss → refillIsStore=true → dirty 初值为 1
//    T08  脏行驱逐：evictDirty / evictTag / evictLine 当拍有效且正确
//    T09  PLRU 顺序访问 0→2→1→3→0 的驱逐路变化序列
//    T10  PLRU 在空泡（memRen=0, wen=0）期间不更新
//    T11  PLRU 在 refillEn 逐字拍期间不更新，仅在 refillDone 那一拍更新
//    T12  missValid 门控：memRen=0 且 wen=0 时不触发 miss
//    T13  多 set 独立性：不同 idx 的 set 数据互不干扰
//    T14  同 idx 多 way 命中路选择：正确从 hitWay 返回数据
//    T15  refillDone 后再次 miss：evictLine 当拍有效（组合输出）
//    T16  printf 旁路：Store Byte 到 0x10001FF1 → printChar.valid/bits 正确，无 stall，不污染 Cache
//    T17  printf 非 SB 写（memWd≠2）：不触发旁路，走正常 miss 流程
//    T18  printf 误 Load：printChar.valid=0，rdata=0，无 stall
//    T19  mtime 旁路：Load 0xBFF8 返回 mtimeLo，Load 0xBFFC 返回 mtimeHi，无 stall
//    T20  mtime Store：写入 0xBFF8/0xBFFC 被忽略，Cache 不污染，无 stall
//    T21  旁路期间 PLRU / TagArray / DataArray 保持不变（不污染）
// =============================================================================

package cache

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.{VerilatorBackendAnnotation, WriteVcdAnnotation}
import org.scalatest.flatspec.AnyFlatSpec

class DCacheCoreSpec extends AnyFlatSpec with ChiselScalatestTester {

  // 统一的后端注解，全部测试共用
  // 若 Verilator 未安装，改为：val backend = Seq(TreadleBackendAnnotation)
  val backend = Seq(VerilatorBackendAnnotation, WriteVcdAnnotation)

  // ─────────────────────────────────────────────────────────────────────────
  // 测试辅助工具
  // ─────────────────────────────────────────────────────────────────────────

  /** Build a 32-bit physical address.
   *  addr[31:13]=tag(19b), [12:6]=idx(7b), [5:2]=wordsoff(4b), [1:0]=byteoff(2b)
   */
  def makeAddr(tag: Int, idx: Int, wordsoff: Int, byteoff: Int): BigInt = {
    val tagShift = CacheParams.offsetBits + CacheParams.idxBits
    val idxShift = CacheParams.offsetBits
    (BigInt(tag) << tagShift) | (BigInt(idx) << idxShift) | (BigInt(wordsoff) << 2) | BigInt(byteoff)
  }

  /** 所有 CPU 输入 + B 侧输入置为 idle */
  def setIdle(dut: DCacheCore): Unit = {
    dut.io.addr.poke(0.U)
    dut.io.flush.poke(false.B)
    dut.io.wen.poke(false.B)
    dut.io.wmask.poke(0.U)
    dut.io.wdata.poke(0.U)
    dut.io.memRen.poke(false.B)
    dut.io.memWd.poke(0.U)
    dut.io.signed.poke(false.B)
    dut.io.mtimeLo.poke(0.U)
    dut.io.mtimeHi.poke(0.U)
    // B 侧 idle
    dut.io.refillEn.poke(false.B)
    dut.io.refillDone.poke(false.B)
    dut.io.refillWay.poke(0.U)
    dut.io.refillIdx.poke(0.U)
    dut.io.refillWord.poke(0.U)
    dut.io.refillData.poke(0.U)
    dut.io.refillTag.poke(0.U)
    dut.io.refillIsStore.poke(false.B)
  }

  /** 模拟 B 侧 MissHandler FSM：16 字逐字回填，最后一拍 refillDone 脉冲
   *
   *  时序：
   *    Cycle 0-15：refillEn=1，refillWord=0..15，写 DataArray
   *    Cycle 16  ：refillDone=1，写 TagArray（valid=1, dirty=refillIsStore）
   */
  def doRefill(
    dut      : DCacheCore,
    way      : Int,
    idx      : Int,
    tag      : Int,
    isStore  : Boolean,
    lineData : IndexedSeq[BigInt]
  ): Unit = {
    require(lineData.length == 16, "lineData 必须恰好 16 个字")
    for (word <- 0 until 16) {
      dut.io.refillEn.poke(true.B)
      dut.io.refillWay.poke(way.U)
      dut.io.refillIdx.poke(idx.U)
      dut.io.refillWord.poke(word.U)
      dut.io.refillData.poke(lineData(word).U)
      dut.io.refillDone.poke(false.B)
      dut.clock.step()
    }
    dut.io.refillEn.poke(false.B)
    dut.io.refillDone.poke(true.B)
    dut.io.refillTag.poke(tag.U)
    dut.io.refillWay.poke(way.U)
    dut.io.refillIdx.poke(idx.U)
    dut.io.refillIsStore.poke(isStore.B)
    dut.clock.step()
    dut.io.refillDone.poke(false.B)
  }

  /** 标准 Load：设置地址 + 读参数，step(0) 采样组合输出
   *  返回 rdata 的 BigInt，同时可断言命中 / miss
   */
  def doLoad(
    dut      : DCacheCore,
    tag      : Int,
    idx      : Int,
    woff     : Int,
    boff     : Int,
    memWd    : Int,
    signed   : Boolean,
    expectHit: Boolean = true
  ): BigInt = {
    dut.io.addr.poke(makeAddr(tag, idx, woff, boff).U)
    dut.io.memRen.poke(true.B)
    dut.io.memWd.poke(memWd.U)
    dut.io.signed.poke(signed.B)
    dut.io.wen.poke(false.B)
    dut.clock.step(0)
    if (expectHit) dut.io.missOut.expect(false.B, s"Load tag=0x${tag.toHexString} idx=$idx 应命中")
    else           dut.io.missOut.expect(true.B,  s"Load tag=0x${tag.toHexString} idx=$idx 应 miss")
    dut.io.rdata.peek().litValue
  }

  /** 标准 Store：设置地址 + 写参数，step() 推进一个时钟使寄存器更新生效 */
  def doStore(
    dut   : DCacheCore,
    tag   : Int,
    idx   : Int,
    woff  : Int,
    wdata : BigInt,
    wmask : Int,
    memWd : Int = 0
  ): Unit = {
    dut.io.addr.poke(makeAddr(tag, idx, woff, 0).U)
    dut.io.wen.poke(true.B)
    dut.io.wmask.poke(wmask.U)
    dut.io.wdata.poke(wdata.U)
    dut.io.memWd.poke(memWd.U)
    dut.io.memRen.poke(false.B)
    dut.clock.step()
    setIdle(dut)
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T01  Flush 清零 valid/dirty，后续访问均 miss；idle 时不触发 miss
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T01: flush clears all valid bits, subsequent access misses" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      doRefill(dut, way=0, idx=0, tag=0x1, isStore=false,
        lineData=(0 until 16).map(i => BigInt(0xA000 + i)).toIndexedSeq)
      doRefill(dut, way=0, idx=1, tag=0x2, isStore=false,
        lineData=(0 until 16).map(i => BigInt(0xB000 + i)).toIndexedSeq)
      setIdle(dut)

      assert(doLoad(dut, 0x1, 0, 0, 0, 0, signed=false) == 0xA000L, "flush 前 set=0 命中")
      assert(doLoad(dut, 0x2, 1, 0, 0, 0, signed=false) == 0xB000L, "flush 前 set=1 命中")
      setIdle(dut)

      dut.io.flush.poke(true.B)
      dut.clock.step()
      dut.io.flush.poke(false.B)

      doLoad(dut, 0x1, 0, 0, 0, 0, signed=false, expectHit=false)
      dut.io.missValid.expect(true.B, "missValid 应与 missOut 同高")
      doLoad(dut, 0x2, 1, 0, 0, 0, signed=false, expectHit=false)

      // idle 时两者均为 0
      setIdle(dut)
      dut.io.addr.poke(makeAddr(0x1, 0, 0, 0).U)
      dut.clock.step(0)
      dut.io.missOut.expect(false.B,   "idle missOut=0")
      dut.io.missValid.expect(false.B, "idle missValid=0")

      setIdle(dut)
      println("[T01] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T02  HIT 读：字 / 半字 / 字节，全部 byteoff，有符号/无符号
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T02: hit load word/half/byte with correct sign extension" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val tag = 0x3F; val idx = 5; val woff = 7
      val lineData = (0 until 16).map { i =>
        if (i == woff) BigInt("DEADBEEF", 16) else BigInt(0x1000 + i)
      }.toIndexedSeq

      doRefill(dut, 0, idx, tag, isStore=false, lineData)
      setIdle(dut)

      // 字
      assert(doLoad(dut, tag, idx, woff, 0, 0, false) == BigInt("DEADBEEF",16))

      // 半字 无符号
      assert(doLoad(dut, tag, idx, woff, 0, 1, false) == BigInt("0000BEEF",16))
      assert(doLoad(dut, tag, idx, woff, 2, 1, false) == BigInt("0000DEAD",16))
      // 半字 有符号
      assert(doLoad(dut, tag, idx, woff, 0, 1, true)  == BigInt("FFFFBEEF",16))
      assert(doLoad(dut, tag, idx, woff, 2, 1, true)  == BigInt("FFFFDEAD",16))

      // 字节：0xDEADBEEF → boff0=0xEF, boff1=0xBE, boff2=0xAD, boff3=0xDE
      val byteRaw = Seq(0xEFL, 0xBEL, 0xADL, 0xDEL).map(BigInt(_))
      for (boff <- 0 until 4) {
        val raw = byteRaw(boff)
        assert(doLoad(dut, tag, idx, woff, boff, 2, false) == raw,
          s"无符号字节 boff=$boff")
        val expected =
          if ((raw & 0x80) != 0) BigInt("FFFFFF00",16) | raw else raw
        assert(doLoad(dut, tag, idx, woff, boff, 2, true) == expected,
          s"有符号字节 boff=$boff")
      }

      setIdle(dut)
      println("[T02] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T03  HIT 写：wmask 字节掩码精确写入 + 写后立即读回
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T03: hit store wmask writes correct bytes, others unchanged" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val tag = 0x7; val idx = 3; val woff = 4
      val init = BigInt("12345678", 16)
      val lineData = (0 until 16).map { i =>
        if (i == woff) init else BigInt(0)
      }.toIndexedSeq

      doRefill(dut, 0, idx, tag, isStore=false, lineData)
      setIdle(dut)

      assert(doLoad(dut, tag, idx, woff, 0, 0, false) == init)

      doStore(dut, tag, idx, woff, BigInt("ABCDEF99",16), wmask=0x1)
      assert(doLoad(dut, tag, idx, woff, 0, 0, false) == BigInt("12345699",16),
        "wmask=0001 低字节改为 0x99")

      doStore(dut, tag, idx, woff, BigInt("FFFFBBFF",16), wmask=0x2)
      assert(doLoad(dut, tag, idx, woff, 0, 0, false) == BigInt("1234BB99",16),
        "wmask=0010 byte1 改为 0xBB")

      doStore(dut, tag, idx, woff, BigInt("AACCFFFF",16), wmask=0xC)
      assert(doLoad(dut, tag, idx, woff, 0, 0, false) == BigInt("AACCBB99",16),
        "wmask=1100 高两字节改为 0xAACC")

      doStore(dut, tag, idx, woff, BigInt("DEADFACE",16), wmask=0xF)
      assert(doLoad(dut, tag, idx, woff, 0, 0, false) == BigInt("DEADFACE",16),
        "wmask=1111 全字替换")

      setIdle(dut)
      println("[T03] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T04  HIT 写置 dirty 位，flush 后 dirty 清零
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T04: store hit sets dirty; flush clears dirty" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val tag = 0xAA; val idx = 8
      doRefill(dut, 0, idx, tag, isStore=false,
        (0 until 16).map(i => BigInt(i)).toIndexedSeq)
      setIdle(dut)

      doStore(dut, tag, idx, 0, BigInt("CAFEBABE",16), wmask=0xF)

      // 触发 miss，检查 evictDirty
      setIdle(dut)
      dut.io.addr.poke(makeAddr(0x1, idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.missOut.expect(true.B)
      val ew = dut.io.evictWay.peek().litValue.toInt
      if (ew == 0) dut.io.evictDirty.expect(true.B, "way=0 Store 后 evictDirty=1")
      setIdle(dut)

      // flush → 再回填 → evictDirty 应为 0
      dut.io.flush.poke(true.B); dut.clock.step(); dut.io.flush.poke(false.B)
      doRefill(dut, 0, idx, tag, isStore=false,
        (0 until 16).map(i => BigInt(i * 10)).toIndexedSeq)
      setIdle(dut)

      dut.io.addr.poke(makeAddr(0x1, idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.missOut.expect(true.B)
      val ew2 = dut.io.evictWay.peek().litValue.toInt
      if (ew2 == 0) dut.io.evictDirty.expect(false.B, "flush 后 Load 回填，dirty=0")

      setIdle(dut)
      println("[T04] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T05  MISS 回填：16 字期间仍 miss，refillDone 后立即命中
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T05: miss during refillEn; hit after refillDone" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val tag = 0x55; val idx = 10; val woff = 5
      val lineData = (0 until 16).map(i => BigInt(0xCAFE0000L + i)).toIndexedSeq

      doLoad(dut, tag, idx, woff, 0, 0, false, expectHit=false)
      setIdle(dut)

      // 逐字回填期间每拍仍 miss
      for (word <- 0 until 16) {
        dut.io.refillEn.poke(true.B)
        dut.io.refillWay.poke(0.U)
        dut.io.refillIdx.poke(idx.U)
        dut.io.refillWord.poke(word.U)
        dut.io.refillData.poke(lineData(word).U)
        dut.io.refillDone.poke(false.B)
        dut.io.addr.poke(makeAddr(tag, idx, woff, 0).U)
        dut.io.memRen.poke(true.B)
        dut.clock.step(0)
        dut.io.missOut.expect(true.B, s"word=$word 回填期间应仍 miss")
        dut.clock.step()
      }

      // refillDone
      dut.io.refillEn.poke(false.B)
      dut.io.refillDone.poke(true.B)
      dut.io.refillTag.poke(tag.U)
      dut.io.refillWay.poke(0.U)
      dut.io.refillIdx.poke(idx.U)
      dut.io.refillIsStore.poke(false.B)
      dut.clock.step()
      dut.io.refillDone.poke(false.B)

      assert(doLoad(dut, tag, idx, woff, 0, 0, false) == lineData(woff),
        "refillDone 后应命中且返回正确数据")

      setIdle(dut)
      println("[T05] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T06  MISS 回填数据完整性：16 字全部可读回
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T06: all 16 refilled words readable and correct" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val tag = 0x77; val idx = 20
      val lineData = (0 until 16).map(i => BigInt(0xFEED0000L + i * 0x100)).toIndexedSeq

      doRefill(dut, 0, idx, tag, isStore=false, lineData)
      setIdle(dut)

      for (woff <- 0 until 16)
        assert(doLoad(dut, tag, idx, woff, 0, 0, false) == lineData(woff),
          s"word[$woff] 读回应正确")

      setIdle(dut)
      println("[T06] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T07  Store miss → refillIsStore=true → dirty 初值为 1
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T07: store miss refill sets dirty=1 initially" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val tag = 0x42; val idx = 15

      dut.io.addr.poke(makeAddr(tag, idx, 0, 0).U)
      dut.io.wen.poke(true.B)
      dut.clock.step(0)
      dut.io.missOut.expect(true.B, "Store miss")
      setIdle(dut)

      doRefill(dut, 0, idx, tag, isStore=true,
        (0 until 16).map(i => BigInt(0xF00D0000L + i)).toIndexedSeq)
      setIdle(dut)

      dut.io.addr.poke(makeAddr(tag, idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.missOut.expect(false.B, "Store miss 回填后应命中")
      setIdle(dut)

      // 填满其余三路后触发 miss，检查 way=0 的 dirty
      doRefill(dut, 2, idx, 0x43, false, (0 until 16).map(i=>BigInt(i)).toIndexedSeq)
      doRefill(dut, 1, idx, 0x44, false, (0 until 16).map(i=>BigInt(i)).toIndexedSeq)
      doRefill(dut, 3, idx, 0x45, false, (0 until 16).map(i=>BigInt(i)).toIndexedSeq)

      setIdle(dut)
      dut.io.addr.poke(makeAddr(0x99, idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      val ew = dut.io.evictWay.peek().litValue.toInt
      println(s"[T07] evictWay=$ew, evictDirty=${dut.io.evictDirty.peek().litValue}")
      if (ew == 0) {
        dut.io.evictDirty.expect(true.B, "Store miss 回填后 dirty=1")
        dut.io.evictTag.expect(tag.U,    s"evictTag=0x${tag.toHexString}")
      }

      setIdle(dut)
      println("[T07] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T08  脏行驱逐：evictDirty / evictTag / evictLine 当拍有效
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T08: dirty evict line valid on miss cycle" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val tag0 = 0x10; val idx = 2; val woff = 3
      val initLine = (0 until 16).map(i => BigInt(0xBEEF0000L + i)).toIndexedSeq
      doRefill(dut, 0, idx, tag0, isStore=false, initLine)

      doStore(dut, tag0, idx, woff, BigInt("DEADFACE",16), wmask=0xF)
      doStore(dut, tag0, idx, 0,    BigInt("12345678",16), wmask=0xF)

      setIdle(dut)
      dut.io.addr.poke(makeAddr(0x20, idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.missOut.expect(true.B)
      dut.io.missValid.expect(true.B)
      dut.io.evictIdx.expect(idx.U)

      val ew = dut.io.evictWay.peek().litValue.toInt
      val ed = dut.io.evictDirty.peek().litValue.toInt
      val et = dut.io.evictTag.peek().litValue.toInt
      println(f"[T08] evictWay=$ew dirty=$ed tag=0x$et%X " +
              f"line(0)=0x${dut.io.evictLine(0).peek().litValue}%X " +
              f"line($woff)=0x${dut.io.evictLine(woff).peek().litValue}%X")
      if (ew == 0) {
        assert(ed == 1,                                 "way=0 已写，dirty=1")
        assert(et == tag0,                              s"evictTag=0x${tag0.toHexString}")
        assert(dut.io.evictLine(0).peek().litValue   == BigInt("12345678",16))
        assert(dut.io.evictLine(woff).peek().litValue == BigInt("DEADFACE",16))
        println("[T08] evictLine 验证 ✓")
      } else {
        assert(ed == 0, s"未写路 dirty=0（实际驱逐 way=$ew）")
      }

      setIdle(dut)
      println("[T08] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T09  PLRU 驱逐路序列：回填 w0→w2→w1→w3，evictWay 依次为 0→2→1→3→0
  //
  //  3-bit 树推导（bit[2]=root, bit[1]=left, bit[0]=right）：
  //    初始  tree=000 → evict=w0
  //    访问w0: tree=110 → evict=w2
  //    访问w2: tree=011 → evict=w1
  //    访问w1: tree=101 → evict=w3
  //    访问w3: tree=000 → evict=w0（循环）
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T09: PLRU evictWay sequence 0->2->1->3->0" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val idx  = 0
      val tags = Seq(0x1, 0x2, 0x3, 0x4)
      def mkLine(t: Int) = (0 until 16).map(i => BigInt((t << 16) | i)).toIndexedSeq

      // 初始：tree=000 → evict=w0
      dut.io.addr.poke(makeAddr(tags(0), idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.evictWay.expect(0.U, "初始 evict=w0")
      setIdle(dut)

      // 回填 w0 → refillDone 更新 PLRU(way=0) → tree=110 → evict=w2
      doRefill(dut, 0, idx, tags(0), false, mkLine(tags(0)))
      setIdle(dut)
      dut.io.addr.poke(makeAddr(tags(0), idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.evictWay.expect(2.U, "访问w0后 evict=w2")
      setIdle(dut)

      // 回填 w2 → tree=011 → evict=w1
      doRefill(dut, 2, idx, tags(1), false, mkLine(tags(1)))
      setIdle(dut)
      dut.io.addr.poke(makeAddr(tags(0), idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.evictWay.expect(1.U, "访问w2后 evict=w1")
      setIdle(dut)

      // 回填 w1 → tree=101 → evict=w3
      doRefill(dut, 1, idx, tags(2), false, mkLine(tags(2)))
      setIdle(dut)
      dut.io.addr.poke(makeAddr(tags(0), idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.evictWay.expect(3.U, "访问w1后 evict=w3")
      setIdle(dut)

      // 回填 w3 → tree=000 → 循环回 evict=w0
      doRefill(dut, 3, idx, tags(3), false, mkLine(tags(3)))
      setIdle(dut)
      dut.io.addr.poke(makeAddr(tags(0), idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.evictWay.expect(0.U, "4路均访问后循环回 evict=w0")
      setIdle(dut)

      println("[T09] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T10  PLRU 在 idle 拍（memRen=0, wen=0）不更新
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T10: PLRU tree unchanged during idle cycles" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val idx = 7; val tag = 0xAB
      doRefill(dut, 0, idx, tag, false,
        (0 until 16).map(i=>BigInt(i)).toIndexedSeq)
      setIdle(dut)

      dut.io.addr.poke(makeAddr(tag, idx, 0, 0).U)
      dut.clock.step(0)
      val evictBefore = dut.io.evictWay.peek().litValue

      setIdle(dut)
      dut.clock.step(3)   // 3 拍 idle 足够验证，无需 20 拍

      dut.io.addr.poke(makeAddr(tag, idx, 0, 0).U)
      dut.clock.step(0)
      assert(dut.io.evictWay.peek().litValue == evictBefore,
        s"idle 后 evictWay 应保持 $evictBefore")

      setIdle(dut)
      println("[T10] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T11  PLRU 仅在 refillDone 那拍更新，逐字 refillEn 期间不更新
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T11: PLRU updates only on refillDone, not on each refillEn" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val idx = 11; val tag = 0xCC
      dut.io.addr.poke(makeAddr(tag, idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      val initEvict = dut.io.evictWay.peek().litValue
      setIdle(dut)

      for (word <- 0 until 16) {
        dut.io.refillEn.poke(true.B)
        dut.io.refillWay.poke(0.U)
        dut.io.refillIdx.poke(idx.U)
        dut.io.refillWord.poke(word.U)
        dut.io.refillData.poke(BigInt(word * 0x100).U)
        dut.io.refillDone.poke(false.B)
        dut.io.addr.poke(makeAddr(tag, idx, 0, 0).U)
        dut.clock.step(0)
        assert(dut.io.evictWay.peek().litValue == initEvict,
          s"refillEn word=$word 期间 evictWay 不应变（仍=$initEvict）")
        dut.clock.step()
      }

      dut.io.refillEn.poke(false.B)
      dut.io.refillDone.poke(true.B)
      dut.io.refillTag.poke(tag.U)
      dut.io.refillWay.poke(0.U)
      dut.io.refillIdx.poke(idx.U)
      dut.io.refillIsStore.poke(false.B)
      dut.clock.step()
      dut.io.refillDone.poke(false.B)

      // refillDone 更新 way=0 → tree=110 → evict=w2
      dut.io.addr.poke(makeAddr(tag, idx, 0, 0).U)
      dut.clock.step(0)
      assert(dut.io.evictWay.peek().litValue == 2,
        "refillDone 后 evictWay 应为 2")

      setIdle(dut)
      println("[T11] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T12  missValid 门控：空泡不虚触发
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T12: missValid gated during pipeline bubble" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      dut.io.addr.poke(makeAddr(0x123, 0, 0, 0).U)
      dut.io.memRen.poke(false.B)
      dut.io.wen.poke(false.B)
      dut.clock.step(0)
      dut.io.missOut.expect(false.B,   "空泡 missOut=0")
      dut.io.missValid.expect(false.B, "空泡 missValid=0")

      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.missOut.expect(true.B,   "memRen=1 空 cache 应 miss")
      dut.io.missValid.expect(true.B, "missValid 同高")

      setIdle(dut)
      println("[T12] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T13  多 set 独立性
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T13: different sets are independent" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val tag = 0x99; val woff = 8
      val setData = (0 until 4).map { s =>
        (0 until 16).map(i => BigInt((s << 24) | (i << 16) | 0xAB)).toIndexedSeq
      }
      (0 until 4).foreach { s =>
        doRefill(dut, 0, s, tag, false, setData(s))
      }
      setIdle(dut)

      (0 until 4).foreach { s =>
        assert(doLoad(dut, tag, s, woff, 0, 0, false) == setData(s)(woff),
          s"set=$s word=$woff 读回正确")
      }

      setIdle(dut)
      println("[T13] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T14  同 idx 多 way：hitWay 正确，rdata 来自正确路
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T14: correct way selected on hit" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val idx = 14; val woff = 2
      val configs = Seq(
        (0, 0x10, BigInt("AABBCCDD",16)),
        (2, 0x11, BigInt("11223344",16)),
        (1, 0x12, BigInt("55667788",16)),
        (3, 0x13, BigInt("99AABBCC",16)),
      )
      configs.foreach { case (way, tag, dat) =>
        val line = (0 until 16).map(i => if (i==woff) dat else BigInt(0)).toIndexedSeq
        doRefill(dut, way, idx, tag, false, line)
      }
      setIdle(dut)

      configs.foreach { case (_, tag, expectedDat) =>
        assert(doLoad(dut, tag, idx, woff, 0, 0, false) == expectedDat,
          s"tag=0x${tag.toHexString} 路选择应正确")
      }

      setIdle(dut)
      println("[T14] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T15  refillDone 后再次 miss：evictLine 当拍有效（组合输出）
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T15: evictLine combinationally valid on miss cycle" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val idx = 1; val tag0 = 0xF0
      val line0 = (0 until 16).map(i => BigInt(0xCAFE0000L + i * 4)).toIndexedSeq
      doRefill(dut, 0, idx, tag0, false, line0)
      setIdle(dut)

      dut.io.addr.poke(makeAddr(0x1, idx, 0, 0).U)
      dut.io.memRen.poke(true.B)
      dut.clock.step(0)
      dut.io.missOut.expect(true.B)

      val ew = dut.io.evictWay.peek().litValue.toInt
      if (ew == 0) {
        for (w <- 0 until 16)
          assert(dut.io.evictLine(w).peek().litValue == line0(w),
            s"evictLine($w) 当拍应有效")
        println("[T15] evictLine 全部 16 字验证 ✓")
      } else {
        for (w <- 0 until 16)
          assert(dut.io.evictLine(w).peek().litValue == 0,
            s"空路 evictLine($w) 应为 0")
        println(s"[T15] 空路（way=$ew）evictLine 全 0 ✓")
      }

      setIdle(dut)
      println("[T15] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T16  printf 旁路：Store Byte 到 0x10001FF1
  //      期望：printChar.valid=1, bits=wdata[7:0], missOut=0（不 stall）
  //            Cache 内部状态不变（后续同地址正常 tag 仍可命中/miss 正常）
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T16: printf bypass Store Byte" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      // 先回填一条普通 CacheLine，用于后续验证旁路不污染 Cache
      val normalTag = 0x5A; val normalIdx = 3
      doRefill(dut, 0, normalIdx, normalTag, false,
        (0 until 16).map(i => BigInt(0x11110000L + i)).toIndexedSeq)
      setIdle(dut)

      // ── printf Store Byte：写字符 'H'=0x48 ──
      dut.io.addr.poke("h10001FF1".U(32.W))
      dut.io.wen.poke(true.B)
      dut.io.memWd.poke(2.U)          // SB
      dut.io.wdata.poke(BigInt("ABCD1248",16).U)  // 高位任意，低字节=0x48='H'
      dut.io.memRen.poke(false.B)
      dut.clock.step(0)

      // 验证旁路输出
      dut.io.printChar.valid.expect(true.B, "printf SB：printChar.valid 应为 1")
      dut.io.printChar.bits.expect(0x48.U,  "printChar.bits 应为 0x48（'H'）")
      dut.io.missOut.expect(false.B,        "printf 旁路不 stall，missOut=0")
      dut.io.missValid.expect(false.B,      "missValid=0，不触发 FSM")

      dut.clock.step()
      setIdle(dut)

      // 写入字符 '!' = 0x21，验证 bits 跟随 wdata 变化
      dut.io.addr.poke("h10001FF1".U(32.W))
      dut.io.wen.poke(true.B)
      dut.io.memWd.poke(2.U)
      dut.io.wdata.poke(BigInt("00000021",16).U)
      dut.io.memRen.poke(false.B)
      dut.clock.step(0)
      dut.io.printChar.valid.expect(true.B)
      dut.io.printChar.bits.expect(0x21.U, "printChar.bits 应为 0x21（'!'）")
      dut.io.missOut.expect(false.B)
      dut.clock.step()
      setIdle(dut)

      // 旁路不应污染 Cache：普通 CacheLine 仍可命中
      assert(
        doLoad(dut, normalTag, normalIdx, 0, 0, 0, false) == BigInt(0x11110000L),
        "printf 旁路后，普通 CacheLine 仍可命中且数据正确"
      )

      setIdle(dut)
      println("[T16] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T17  printf 地址但 memWd≠2（非 SB）：不触发旁路，走正常 miss 流程
  //      spec：isPrintf 需同时满足 wen && memWd==2
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T17: printf addr with memWd!=2 does not trigger bypass" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      // SW（memWd=0）写 printf 地址：不是 SB，不触发旁路
      dut.io.addr.poke("h10001FF1".U(32.W))
      dut.io.wen.poke(true.B)
      dut.io.memWd.poke(0.U)           // SW，不满足 memWd==2
      dut.io.wmask.poke(0xF.U)
      dut.io.wdata.poke(BigInt("DEADBEEF",16).U)
      dut.io.memRen.poke(false.B)
      dut.clock.step(0)

      dut.io.printChar.valid.expect(false.B, "SW 不触发 printf 旁路，printChar.valid=0")
      // SW 到该地址：Cache 里没有对应 tag，应产生 miss（走正常 miss 路径）
      dut.io.missOut.expect(true.B,  "非 SB 不旁路，走正常 miss 流程，missOut=1")

      setIdle(dut)
      println("[T17] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T18  printf 误 Load：printChar.valid=0，rdata=0，不 stall
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T18: printf addr Load returns 0, no stall" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      dut.io.addr.poke("h10001FF1".U(32.W))
      dut.io.memRen.poke(true.B)
      dut.io.memWd.poke(2.U)   // LB
      dut.io.signed.poke(false.B)
      dut.io.wen.poke(false.B)
      dut.clock.step(0)

      dut.io.printChar.valid.expect(false.B, "Load 不触发 printf 输出")
      dut.io.rdata.expect(0.U,               "printf 误 Load 返回 0")
      dut.io.missOut.expect(false.B,         "printf 旁路不 stall（Load 也旁路）")

      setIdle(dut)
      println("[T18] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T19  mtime 旁路：Load 0xBFF8 返回 mtimeLo，Load 0xBFFC 返回 mtimeHi，无 stall
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T19: mtime load returns mtimeLo/mtimeHi, no stall" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val loVal = BigInt("CAFEBABE",16)
      val hiVal = BigInt("00000000",16)   // 32 位实现高位为 0

      // ── Load mtime 低 32 位 ──
      dut.io.addr.poke("h0000BFF8".U(32.W))
      dut.io.memRen.poke(true.B)
      dut.io.memWd.poke(0.U)   // LW
      dut.io.wen.poke(false.B)
      dut.io.mtimeLo.poke(loVal.U)
      dut.io.mtimeHi.poke(hiVal.U)
      dut.clock.step(0)

      dut.io.rdata.expect(loVal.U,    "Load 0xBFF8 应返回 mtimeLo")
      dut.io.missOut.expect(false.B,  "mtime 旁路不 stall")
      dut.io.missValid.expect(false.B,"missValid=0")
      dut.io.printChar.valid.expect(false.B, "不触发 printf")
      setIdle(dut)

      // ── Load mtime 高 32 位 ──
      val hiVal2 = BigInt("DEADD00D",16)  // 64 位实现可能非零
      dut.io.addr.poke("h0000BFFC".U(32.W))
      dut.io.memRen.poke(true.B)
      dut.io.memWd.poke(0.U)
      dut.io.wen.poke(false.B)
      dut.io.mtimeLo.poke(loVal.U)
      dut.io.mtimeHi.poke(hiVal2.U)
      dut.clock.step(0)

      dut.io.rdata.expect(hiVal2.U,   "Load 0xBFFC 应返回 mtimeHi")
      dut.io.missOut.expect(false.B,  "mtime 旁路不 stall")
      setIdle(dut)

      // ── mtimeLo 变化时 rdata 跟随（组合输出） ──
      val newLo = BigInt("12345678",16)
      dut.io.addr.poke("h0000BFF8".U(32.W))
      dut.io.memRen.poke(true.B)
      dut.io.memWd.poke(0.U)
      dut.io.mtimeLo.poke(newLo.U)
      dut.clock.step(0)
      dut.io.rdata.expect(newLo.U, "mtimeLo 变化后 rdata 同步跟随")

      setIdle(dut)
      println("[T19] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T20  mtime Store：写入 0xBFF8/0xBFFC 被忽略，missOut=0，Cache 不污染
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T20: mtime store is ignored, no stall, no cache pollution" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      // 先回填普通 CacheLine
      val normalTag = 0x3C; val normalIdx = 6
      doRefill(dut, 0, normalIdx, normalTag, false,
        (0 until 16).map(i => BigInt(0x22220000L + i)).toIndexedSeq)
      setIdle(dut)

      // Store 到 0xBFF8：应被忽略，不 stall
      dut.io.addr.poke("h0000BFF8".U(32.W))
      dut.io.wen.poke(true.B)
      dut.io.wmask.poke(0xF.U)
      dut.io.wdata.poke(BigInt("FFFFFFFF",16).U)
      dut.io.memWd.poke(0.U)
      dut.io.memRen.poke(false.B)
      dut.clock.step(0)

      dut.io.missOut.expect(false.B,  "mtime Store 不 stall")
      dut.io.missValid.expect(false.B,"missValid=0，不触发 FSM")
      dut.io.printChar.valid.expect(false.B, "不触发 printf")
      dut.clock.step()
      setIdle(dut)

      // Store 到 0xBFFC
      dut.io.addr.poke("h0000BFFC".U(32.W))
      dut.io.wen.poke(true.B)
      dut.io.wmask.poke(0xF.U)
      dut.io.wdata.poke(BigInt("FFFFFFFF",16).U)
      dut.io.memWd.poke(0.U)
      dut.clock.step(0)
      dut.io.missOut.expect(false.B,  "mtime 0xBFFC Store 不 stall")
      dut.clock.step()
      setIdle(dut)

      // 旁路 Store 不污染 Cache：mtimeLo 的值应仍由外部 mtimeLo 输入决定
      val loVal = BigInt("AABBCCDD",16)
      dut.io.addr.poke("h0000BFF8".U(32.W))
      dut.io.memRen.poke(true.B)
      dut.io.memWd.poke(0.U)
      dut.io.mtimeLo.poke(loVal.U)
      dut.clock.step(0)
      dut.io.rdata.expect(loVal.U, "mtime Store 后再 Load，仍返回输入的 mtimeLo")
      setIdle(dut)

      // 普通 CacheLine 不受影响
      assert(doLoad(dut, normalTag, normalIdx, 0, 0, 0, false) == BigInt(0x22220000L),
        "普通 CacheLine 不受 mtime Store 影响")

      setIdle(dut)
      println("[T20] PASS")
    }
  }

  // ─────────────────────────────────────────────────────────────────────────
  // T21  旁路期间 PLRU / TagArray / DataArray 保持完全不变
  //      分别对 printf Store Byte 和 mtime Load 验证
  // ─────────────────────────────────────────────────────────────────────────
  "DCacheCore" should "T21: bypass does not update PLRU, TagArray, or DataArray" in {
    test(new DCacheCore).withAnnotations(backend) { dut =>
      setIdle(dut)

      val tag = 0x7B; val idx = 9
      // 回填 way=0，记录回填后 evictWay（此时 PLRU tree=110 → evict=2）
      doRefill(dut, 0, idx, tag, false,
        (0 until 16).map(i => BigInt(0x33330000L + i)).toIndexedSeq)
      setIdle(dut)

      // 记录回填后的 evictWay 基准
      dut.io.addr.poke(makeAddr(tag, idx, 0, 0).U)
      dut.clock.step(0)
      val evictBefore = dut.io.evictWay.peek().litValue
      setIdle(dut)

      // ── 执行 10 次 printf Store Byte ──
      for (i <- 0 until 10) {
        dut.io.addr.poke("h10001FF1".U(32.W))
        dut.io.wen.poke(true.B)
        dut.io.memWd.poke(2.U)
        dut.io.wdata.poke(BigInt(0x41 + i).U)  // 'A'..'J'
        dut.clock.step()
        setIdle(dut)
      }

      // ── 执行 5 次 mtime Load ──
      for (_ <- 0 until 5) {
        dut.io.addr.poke("h0000BFF8".U(32.W))
        dut.io.memRen.poke(true.B)
        dut.io.memWd.poke(0.U)
        dut.io.mtimeLo.poke(BigInt("12345678",16).U)
        dut.clock.step()
        setIdle(dut)
      }

      // PLRU 应保持不变
      dut.io.addr.poke(makeAddr(tag, idx, 0, 0).U)
      dut.clock.step(0)
      assert(dut.io.evictWay.peek().litValue == evictBefore,
        s"旁路后 PLRU evictWay 应仍为 $evictBefore，旁路不应修改树状态")
      setIdle(dut)

      // TagArray / DataArray 应保持不变：普通 CacheLine 仍命中且数据正确
      assert(
        doLoad(dut, tag, idx, 5, 0, 0, false) == BigInt(0x33330000L + 5),
        "旁路后 Cache 命中：TagArray / DataArray 未被旁路污染"
      )

      // DataArray 其他字也应完好
      for (woff <- 0 until 16)
        assert(
          doLoad(dut, tag, idx, woff, 0, 0, false) == BigInt(0x33330000L + woff),
          s"woff=$woff 数据完整"
        )

      setIdle(dut)
      println("[T21] PASS")
    }
  }

}
