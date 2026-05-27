# DCacheCore 结构说明

## 1. 对外接口

### 与 CPU 接口说明

| 信号名 | 位宽 | 类型 | 说明 |
|--------|------|------|------|
| `addr` | 32 | input | MEM 级计算所得有效地址 |
| `flush` | 1 | input | 复位清理 Cache 信号 |
| `wen` | 1 | input | 写使能，Store 指令发出时置高 |
| `wmask` | 4 | input | 写操作字节使能（掩码） |
| `wdata` | 32 | input | Store 指令发出的数据 |
| `memRen` | 1 | input | 访存读使能，Load 指令发出时置高 |
| `memWd` | 2 | input | 访存宽度（`2'd0`=字，`2'd1`=半字，`2'd2`=字节） |
| `signed` | 1 | input | Load 是否符号扩展 |
| `rdata` | 32 | output | Load 访存获得的数据 |
| `missOut` | 1 | output | Cache 未命中信号，高电平期间流水线应 stall |
| `mtimeLo` | 32 | input | mtime 计数器低 32 位，由外部 mcycle 或独立硬件计数器驱动 |
| `mtimeHi` | 32 | input | mtime 计数器高 32 位，32 位实现固定接 `0.U` |
| `printChar.valid` | 1 | output | 本拍为 printf Store Byte 时置高 |
| `printChar.bits` | 8 | output | printf 输出字符，取 `wdata[7:0]` |

#### ***addr 地址划分如下***

```plaintext
addr[31:0] 物理地址划分：
+-----------------------------------------+---------------+-------------------+
| 31                                   13 | 12          6 | 5               0 |
|                   Tag                   |     Index     |       offset      |
|                (19 bits)                |   (7 bits)    |      (6 bits)     |
+-----------------------------------------+---------------+---------+---------+
                                                          | 5     2 | 1     0 |
                                                          | wordsoff| byteoff |
                                                          | (4 bits)| (2 bits)|
                                                          +---------+---------+
```

#### ***Load 数据处理如下***

| `memWd` | `signed` | 返回数据处理 |
| :--- | :--- | :--- |
| 字（`2'd0`） | 任意 | 直接返回 32 位原始数据 |
| 半字（`2'd1`） | 1（有符号） | `{{16{data[15]}}, data[15:0]}` |
| 半字（`2'd1`） | 0（无符号） | `{16'b0, data[15:0]}` |
| 字节（`2'd2`） | 1（有符号） | `{{24{data[7]}}, data[7:0]}` |
| 字节（`2'd2`） | 0（无符号） | `{24'b0, data[7:0]}` |

字节/半字的实际位置由地址低位 `addr[1:0]` 确定，`LoadExtend` 模块从返回的 32 位字中提取对应字节/半字后做扩展。

---

### 外部内存总线接口（MemBusIO）

`DCacheTop` 将 `DCacheMissFSM` 的内存总线直接透传到顶层（`io.mem <> missFsm.io.mem`），所有片外访问（写回脏行、回填新行）均经由此接口。协议采用 Chisel `Decoupled` 标准（Ready-Valid），握手条件为 `valid && ready` 同时为高的时钟上升沿。

#### **请求通道（req，DCacheTop → MEM）**

| 信号名 | 位宽 | 类型 | 说明 |
|--------|------|------|------|
| `mem.req.valid` | 1 | output | FSM 发出请求时置高；握手成立前不得撤回 |
| `mem.req.ready` | 1 | input | 内存接受请求，握手成立 |
| `mem.req.bits.addr` | 32 | output | 访问地址（写回时为旧行地址，回填时为新行地址） |
| `mem.req.bits.wdata` | 32 | output | 写数据（读请求时忽略） |
| `mem.req.bits.wen` | 1 | output | 写使能；`1` = 写回脏行，`0` = 回填读取 |
| `mem.req.bits.wmask` | 4 | output | 字节写掩码；写回时固定 `4'b1111`，回填读请求时为 `0` |

#### **响应通道（resp，MEM → DCacheTop）**

| 信号名 | 位宽 | 类型 | 说明 |
|--------|------|------|------|
| `mem.resp.valid` | 1 | input | 内存返回数据有效 |
| `mem.resp.ready` | 1 | output | FSM 在 `sWbResp` 或 `sRefillResp` 状态时置高，其余状态为 `0` |
| `mem.resp.bits.rdata` | 32 | input | 读返回数据（写请求响应时忽略） |

#### **时序约束**

内存总线延迟固定为 **10 个时钟周期**（自 `req` 握手起至 `resp.valid` 有效止）。FSM 每次传输一个字（32 bit），写回和回填各需 16 次握手，共 32 次总线事务。

```
sWbReq:    req.valid=1, wen=1, addr={evictTag,idx,wordCnt,0}
              ↓ req.fire（ready=1）
sWbResp:   等待 resp.fire，wordCnt+1；wordCnt==15 → sRefillReq
              ↓ ×16
sRefillReq: req.valid=1, wen=0, addr={missTag,idx,wordCnt,0}
              ↓ req.fire
sRefillResp: 等待 resp.fire，refillEn=1，写 DataArray；wordCnt==15 → sDone
              ↓ ×16
sDone:      refillDone=1，TagArray 更新，stall 解除
```



#### **cachecore → miss fsm 信号 成员 A 到 B**

| 信号名 | 位宽 | 产生来源 | 作用 |
|--------|------|---------|------|
| `missValid` | 1 | `HitTest.missValid` | miss 触发脉冲，为 1 时以下所有字段有效；已由 `memRen \| wen` 门控，且旁路地址强制为 0，不会虚触发 |
| `evictIdx` | 7 | `addr[12:6]` | miss 发生的组索引 |
| `evictWay` | 2 | `TreePLRU.evictWay` | PLRU 选出的被驱逐路号 |
| `evictTag` | 19 | `TagArray.tagData(evictWay).tag` | 被驱逐路旧 tag，用于构造写回地址：`{evictTag, evictIdx, wordCnt, 2'b0}` |
| `evictDirty` | 1 | `TagArray.tagData(evictWay).dirty` | 被驱逐路是否脏：为 1 时需先写回再回填，为 0 时跳过写回 |
| `evictLine` | Vec(16, 32) | `DataArray.evictLine` | 被驱逐路的完整 64B 数据；`DataArray` 为组合读，在 `missValid=1` 的当拍即有效，B 应在该拍锁存 |

#### **miss fsm → cachecore 信号 成员 B 到 A**

| 信号名 | 位宽 | 产生来源 | 作用 |
|--------|------|---------|------|
| `refillEn` | 1 | FSM（`sRefillResp` 状态，`resp.fire` 同拍）| 为 1 时当拍 `refillData` 有效，A 应将其写入 `DataArray` |
| `refillWay` | 2 | FSM（锁存自 `evictWay`） | 指定写入 `DataArray` 的路号，与 `evictWay` 始终相同 |
| `refillIdx` | 7 | FSM（锁存自 `evictIdx`） | 指定写入 `DataArray` 的组号，与 `evictIdx` 始终相同 |
| `refillWord` | 4 | FSM（字计数器） | 行内字偏移（0–15），指定写入 `DataArray` 的字位置 |
| `refillData` | 32 | 内存总线 | 本拍从内存返回的 32 位字数据，A 直接写入 `DataArray` |
| `refillTag` | 19 | FSM（锁存自 miss 地址） | 新写入行的标签值，A 写入 `TagArray`；`valid` 位始终置 1，`dirty` 初值由 `refillIsStore` 决定 |
| `refillDone` | 1 | FSM（sDone 状态） | 单拍脉冲，为 1 时回填全部完成；A 在本拍更新 `TagArray` 并由 B 解除 stall |
| `refillIsStore` | 1 | FSM（锁存自 miss 类型） | 原始 miss 是否为 Store；为 1 时 `TagArray` 对应行 dirty 初值为 1 |

---

## 2. 总体结构

### **Cache 结构框图**

```
Cache Data 结构示意图

                         由 Index 索引
                               |
                               v
                      +---------+                                               +---------+
                      |  set 0  |                                               | set 127 |
                      +---------+                                               +---------+
Tag 索引
   |
   v       64B (16 words, 每个 word [31:0])
         +----+----+----+-------------------+----+----+            +--------------------------------------------+
way 0    |  0 |  1 |  2 | ................. | 14 | 15 |   ......   |                                            |
         +----+----+----+-------------------+----+----+            +--------------------------------------------+
            |                                  ^
            |                                  |
byteoff 索引-+                             wordsoff 索引
(B0,B1,B2,B3)

         +--------------------------------------------+            +--------------------------------------------+
way 1    |                                            |   ......   |                                            |
         +--------------------------------------------+            +--------------------------------------------+

         +--------------------------------------------+            +--------------------------------------------+
way 2    |                                            |   ......   |                                            |
         +--------------------------------------------+            +--------------------------------------------+

         +--------------------------------------------+            +--------------------------------------------+
way 3    |                                            |   ......   |                                            |
         +--------------------------------------------+            +--------------------------------------------+
```

### **CacheCore 运行流程**

**Cache 主要包括以下四种行为**

- **命中读**：`TagArray`、`DataArray` 模块取出对应 `idx`、`wordsoff` 的四路所有 tag 和 data，`HitTest` 模块比较地址和 tag 阵列存储的 tag 值获得命中的路索引，接着更新 `PLRU`，同时在 `LoadExtend` 模块根据 `byteoff`、`memWd` 以及 `signed` 信号处理读出的数据，送入 `rdata`。
- **命中写**：`TagArray` 模块取出对应 `idx` 的四路所有 tag，`HitTest` 模块比较地址和 tag 阵列存储的 tag 值获得命中的路索引，接着更新 PLRU，最后在 `DataArray` 中根据 `wmask` 将 `wdata` 写入，并同步更新 `dirty` 信息。
- **未命中回填**：`HitTest` 检测发现未命中，流水线停顿，MissHandler FSM 经 `sIdle→sCheck→sRefillReq→sRefillResp（×16）→sDone` 流程，根据 `PLRU` 选中的 `evictWay` 在 `DataArray` 中将对应 CacheLine 数据从内存中逐字搬运进对应路，`sDone` 拍更新 TagArray 并解除 stall。
- **未命中写回**：`HitTest` 检测发现未命中，`sCheck` 拍检测 `dirty` 位，若为高则先经 `sWbReq→sWbResp（×16）` 将脏行数据逐字写回内存，再进入回填流程。

---

## 3. 内部模块

### HitTest

#### **IO 端口说明**

| 信号名 | 位宽 | 类型 | 说明 |
|--------|------|------|------|
| `tagData` | 4 × TagEntry | input | TagArray 输出的当前组四路 tag 信息 |
| `tag` | 19 | input | 地址中提取的标签位 |
| `memRen` | 1 | input | 访存读使能，来自 CPU 接口，用于门控 miss 信号 |
| `wen` | 1 | input | 写使能，来自 CPU 接口，用于门控 miss 信号 |
| `isHit` | 1 | output | 命中时置高 |
| `hitWay` | 2 | output | 命中的路索引 |
| `missValid` | 1 | output | miss 触发脉冲，逻辑上为 `~isHit & (memRen \| wen)`；仅在有实际访存时有效，不会因流水线空泡虚触发 |

#### **行为说明**

- **命中检测**：将 `tag` 与当前组四路 `tagData` 中 `valid=1` 的条目逐一比对，有匹配则置 `isHit=1` 并输出对应 `hitWay`。
- **miss 信号生成**：`missValid = ~isHit & (memRen | wen)`；无实际访存（`memRen=0` 且 `wen=0`）时 `missValid` 强制为 0，避免虚触发 MissHandler FSM。
- **DCacheCore 层路由**：`DCacheCore` 将 `missValid` 同时连接到对外 CPU 接口的 `missOut` 和 A→B 接口的 `missValid`，两者本质上是同一根信号，无需在 HitTest 内部输出两路。注意 DCacheCore 顶层还会对旁路地址进行额外屏蔽：`cachemiss = hitTest.missValid && !isBypass`，最终 `missOut` 和 `missValid` 均输出 `cachemiss`。

---

### TagArray

#### **IO 端口说明**

| 信号名 | 位宽 | 类型 | 说明 |
|--------|------|------|------|
| `flush` | 1 | input | 复位清理 Cache 信号 |
| `idx` | 7 | input | 地址中提取的 Index 位，用于组合读 |
| `refillTagEn` | 1 | input | 为 1 时本拍执行回填写入，优先级高于 `setDirtyEn` |
| `refillWay` | 2 | input | 回填写入的路索引 |
| `refillIdx` | 7 | input | 回填写入的组索引 |
| `refillTag` | 19 | input | 回填行的标签值（19 位，不含 valid/dirty）；TagArray 内部将 valid 置 1，dirty 由 `refillDirty` 决定 |
| `refillDirty` | 1 | input | 回填行 dirty 初值，等于 B 侧传入的 `refillIsStore`；Store miss 时置 1 |
| `setDirtyEn` | 1 | input | 置高 dirty 位使能，逻辑上为 `isHit & wen`；当 `refillTagEn=1` 时本信号被屏蔽 |
| `setDirtyWay` | 2 | input | 置高 dirty 位的路索引，逻辑上为 `hitWay` |
| `tagData` | 4 × TagEntry | output | 当前组四路的完整 tag 信息（组合输出） |

#### **内部结构**

TagArray 内部定义了一个 `TagEntry` 类：

```scala
class TagEntry extends Bundle {
  val valid = Bool()
  val dirty = Bool()
  val tag   = UInt(CacheParams.tagBits.W)
}
```

TagArray 内部定义了一个 128 × 4 × TagEntry 大小的标签阵列：

```scala
val tArray = RegInit(VecInit(Seq.fill(nSets)(
    VecInit(Seq.fill(nWays)(0.U.asTypeOf(new TagEntry))))))
```

#### **行为说明**

- **flush**：将所有 `valid` 和 `dirty` 位置为 false（数据字段不清零）。
- **读逻辑**：根据 `idx` 信号组合输出当前组四路的全部 `tagData`，送入 `HitTest` 模块。
- **Miss 写入逻辑（refillTagEn=1，优先级最高）**：在 `refillDone` 那一拍（由 `refillTagEn` 使能），将 `tArray[refillIdx][refillWay]` 写为 `TagEntry(valid=true, dirty=refillDirty, tag=refillTag)`。此优先级高于 `setDirtyEn`，两者不应同拍并发（miss 期间流水线 stall，故命中写不可能同时发生），但实现时仍须显式用 `when(refillTagEn)...elsewhen(setDirtyEn)...` 保证优先级正确。
- **setDirty（命中写，仅当 refillTagEn=0 时有效）**：Cache 命中且为写操作时，将 `tArray[idx][setDirtyWay].dirty` 置 true。

---

### DataArray

#### **IO 端口说明**

| 信号名 | 位宽 | 类型 | 说明 |
|--------|------|------|------|
| `idx` | 7 | input | 地址中提取的 Index 位 |
| `wordsoff` | 4 | input | 地址中提取的字偏移位 |
| `hitWen` | 1 | input | 命中写使能，逻辑上为 `isHit & wen`；为 1 时执行 Hit Store 写入 |
| `hitWay` | 2 | input | 命中写的路索引，逻辑上为 `HitTest.hitWay` |
| `wdata` | 32 | input | Store 指令发出的数据 |
| `wmask` | 4 | input | 写操作字节使能（掩码） |
| `refillDataEn` | 1 | input | 为 1 时当拍 `refillData` 有效，执行回填逐字写入 |
| `refillWay` | 2 | input | 回填写入的路索引 |
| `refillIdx` | 7 | input | 回填写入的组索引 |
| `refillWord` | 4 | input | 回填行内字偏移（0–15） |
| `refillData` | 32 | input | 本拍从内存返回的 32 位字数据 |
| `evictIdx` | 7 | input | 待写回内存 CacheLine 的组索引 |
| `evictWay` | 2 | input | 待写回内存 CacheLine 的路索引 |
| `evictLine` | Vec(16, 32) | output | 待写回内存的完整 64B 数据（组合输出，`missValid=1` 当拍即有效） |
| `rawData` | 4 × 32 | output | 当前 `idx` 和 `wordsoff` 对应的四路单字数据（组合输出） |

#### **内部结构**

DataArray 内部定义了一个 4 × 128 × 16 × 32 的数据阵列：

```scala
val dArray = Reg(Vec(nWays, Vec(nSets, Vec(lineWords, UInt(32.W)))))
```

寄存器直接索引即为组合读（当拍可见），无需额外时钟沿。

#### **行为说明**

- **读逻辑**：根据 `idx` 和 `wordsoff`，组合输出四路各自该字位置的数据，作为 `rawData[0..3]` 送入 `LoadExtend`；根据 `evictIdx` 和 `evictWay` 组合输出整行 16 字数据作为 `evictLine`，在 `missValid=1` 当拍即有效，B 侧应在该拍锁存。
- **Hit 写逻辑（hitWen=1）**：按 `wmask` 字节掩码将 `wdata` 写入 `dArray(hitWay)(idx)(wordsoff)` 中对应字节，未被掩码覆盖的字节保持不变。
- **Miss 回填写逻辑（refillDataEn=1，优先级高于 hitWen）**：将 `refillData` 写入 `dArray(refillWay)(refillIdx)(refillWord)`，每拍写一个字，由 B 侧 FSM 驱动 `refillWord` 从 0 计数至 15。实现时应使用 `when(refillDataEn)...elsewhen(hitWen)...` 保证显式优先级（miss 期间流水线 stall，hitWen 理论上不会为高，但防御性编程仍须显式）。
- **Miss 写回读逻辑**：`evictLine` 始终组合输出 `dArray(evictWay)(evictIdx)` 的全部 16 个字，供 B 侧 FSM 在写回阶段逐字取用。

---

### PLRU

#### **IO 端口说明**

| 信号名 | 位宽 | 类型 | 说明 |
|--------|------|------|------|
| `idx` | 7 | input | 地址中提取的 Index 位 |
| `updateEn` | 1 | input | 更新二叉树使能；由 DCacheCore 按如下逻辑驱动：命中时为 `isHit & (memRen \| wen)`，回填完成时为 `refillDone`；旁路访问时强制为 0 |
| `updateWay` | 2 | input | 本次更新对应的路：命中时为 `hitWay`，回填完成时为 `refillWay` |
| `evictWay` | 2 | output | 最久未被访问路的索引（组合输出） |

#### **内部结构**

PLRU 内部定义一个 128 × 3 的二叉树阵列：

```scala
val treeArray = RegInit(VecInit(Seq.fill(nSets)(0.U(3.W))))
```

#### **行为说明**

- **驱逐路计算**：根据 `treeArray(idx)` 当前状态，组合输出 `evictWay`（最久未访问的路）。
- **命中后更新（updateEn=1，updateWay=hitWay）**：沿二叉树路径将各 bit 翻转为远离被命中路的方向，以记录最近访问信息。
- **回填完成后更新（updateEn=1，updateWay=refillWay）**：回填全部完成（`refillDone=1`）时由 DCacheCore 拉高 `updateEn`，将新回填路标记为最近访问，避免其立即被驱逐。注意：`refillEn` 逐字写入期间**不**更新 PLRU，仅在 `refillDone` 那一拍更新一次。
- **空泡周期**：`memRen=0` 且 `wen=0` 时，DCacheCore 不拉高 `updateEn`，PLRU 状态保持不变。
- **旁路周期**：`isBypass=1` 时，DCacheCore 强制 `updateEn=0`，旁路访问不影响 PLRU 树状态。

---

### LoadExtend

#### **IO 端口说明**

| 信号名 | 位宽 | 类型 | 说明 |
|--------|------|------|------|
| `hitWay` | 2 | input | 命中的路索引，用于从 `rawData` 中选出命中路数据 |
| `byteoff` | 2 | input | 地址中提取的字节偏移位 |
| `memWd` | 2 | input | 访存宽度（`2'd0`=字，`2'd1`=半字，`2'd2`=字节） |
| `signed` | 1 | input | Load 是否符号扩展（0=零扩展，1=符号扩展） |
| `rawData` | 4 × 32 | input | DataArray 输出的四路单字数据 |
| `rdata` | 32 | output | Load 访存获得的数据，返回 CPU |

#### **行为说明**

- **路选择**：`selectedWord = rawData(hitWay)`，从四路数据中按命中路索引选出 32 位字。
- **字节/半字提取**：根据 `byteoff` 从 `selectedWord` 中提取目标字节或半字：
  - 字节：`data[7:0] = selectedWord >> (byteoff * 8)`
  - 半字：`data[15:0] = selectedWord >> (byteoff[1] * 16)`（`byteoff` 只取 bit[1]）
- **符号/零扩展**：按对外 CPU 接口的 Load 数据处理表，根据 `memWd` 和 `signed` 组合生成最终 32 位 `rdata`；字访问直接输出 `selectedWord`，不受 `byteoff` 和 `signed` 影响。

---

## 4. MissHandler FSM

### 状态说明

FSM 共 7 个状态，处理一次 miss 的完整写回 + 回填流程：

| 状态 | 行为 |
|------|------|
| `sIdle` | 等待 `missValid=1`；触发时锁存 `missTag / evictTag / evictIdx / evictWay / evictDirty / missIsStore / evictLine`，并重置 `wordCnt=0` |
| `sCheck` | 单拍判断：`dirtyLatch=1` → 跳 `sWbReq`；否则直接跳 `sRefillReq` |
| `sWbReq` | 向总线发出脏行写请求（`req.valid=1, wen=1`）；等待 `req.fire`（ready=1）后进 `sWbResp` |
| `sWbResp` | 等待 `resp.fire`；每次 `resp.fire` 后 `wordCnt+1`；`wordCnt==15` 时跳 `sRefillReq`，否则回 `sWbReq` |
| `sRefillReq` | 向总线发出回填读请求（`req.valid=1, wen=0`）；等待 `req.fire` 后进 `sRefillResp` |
| `sRefillResp` | 等待 `resp.fire`；同拍拉高 `refillEn`，将 `resp.rdata` 逐字写入 DataArray；`wordCnt==15` 时跳 `sDone`，否则回 `sRefillReq` |
| `sDone` | 单拍：`refillDone=1`，A 侧在本拍更新 TagArray；FSM 下一拍自动跳回 `sIdle`，stall 解除 |

### 地址构造

```
写回地址（sWbReq）：{evictTagLatch, idxLatch, wordCnt, 2'b00}  ← 旧行地址
回填地址（sRefillReq）：{missTagLatch,  idxLatch, wordCnt, 2'b00}  ← 新行地址
```

两套地址使用独立的 latch，`Mux(isWbReq, wbAddr, refillAddr)` 选择，避免 clean miss 时用错旧 tag 回填。

### stall 信号

`stall = (state =/= sIdle)`，在 `sDone` 拍仍为 1（TagArray 正在写入），`sDone` 之后的下一拍 state 回到 `sIdle`，stall 变 0，流水线解冻。

---

## 5. PMA 旁路逻辑

本设计在 MEM 级对两类特殊物理地址进行 PMA 检查，命中时访问直接在 DCacheCore 顶层截获，不进入 Cache 流程，不触发 MissHandler FSM，不产生 stall。

### 旁路地址表

| 地址 | 类型 | 说明 |
|------|------|------|
| `0x10001FF1` | printf 输出寄存器 | Store Byte 写入时输出字符；Load 行为未定义，返回 0 |
| `0x0000BFF8` | mtime 低 32 位 | Load Word 返回计数器低位；Store 被静默忽略 |
| `0x0000BFFC` | mtime 高 32 位 | Load Word 返回计数器高位（32 位实现固定为 0）；Store 被静默忽略 |

### 旁路触发规则

旁路的核心设计原则是**由操作类型精确控制**，不同地址的旁路条件不同：

```
printf 地址（0x10001FF1）：
  · SB（wen=1 且 memWd=2）→ 旁路，printChar.valid=1，printChar.bits=wdata[7:0]
  · Load（memRen=1）       → 旁路，rdata=0，不 stall
  · SW / SH（wen=1 且 memWd≠2）→ 不旁路，走正常 Cache miss 流程

mtime 地址（0xBFF8 / 0xBFFC）：
  · Load（memRen=1）  → 旁路，rdata 返回 mtimeLo / mtimeHi
  · Store（wen=1）    → 旁路，静默忽略，不 stall，不修改计数器
```

上述规则体现为 DCacheCore 中以下三个组合信号：

```scala
val isPrintf     = (addr === 0x10001FF1) && wen && (memWd === 2.U)
val addrIsPrintf = (addr === 0x10001FF1) && (isPrintf || memRen)  // SB 或 Load 才旁路
val addrIsMtimeLo = (addr === 0x0000BFF8)                         // 地址命中即旁路
val addrIsMtimeHi = (addr === 0x0000BFFC)
val isBypass      = addrIsPrintf || addrIsMtimeLo || addrIsMtimeHi
```

### 旁路期间的副作用屏蔽

`isBypass=1` 时，以下所有写使能在 DCacheCore 顶层被门控为 0，旁路访问对 Cache 内部状态没有任何副作用：

| 信号 | 门控逻辑 |
|------|---------|
| `isHit`（用于后续所有写使能） | `hitTest.isHit && !isBypass` |
| `tagArray.setDirtyEn` | `isHit && wen && !isBypass` |
| `dataArray.hitWen` | `isHit && wen && !isBypass` |
| `plru.updateEn`（命中部分） | `(isHit && (memRen \| wen)) && !isBypass` |
| `missOut` / `missValid` | `hitTest.missValid && !isBypass` |

### rdata 选择优先级

```
优先级 1（最高）：isMtimeLo  → mtimeLo
优先级 2         isMtimeHi  → mtimeHi
优先级 3         addrIsPrintf && memRen → 0（printf 误 Load）
优先级 4（默认）：loadExt.rdata（正常 Cache 命中路径）
```

---

## 6. 仿真验证说明

以下 21 个测试用例覆盖 DCacheTop 的全部功能路径，采用 Verilator 后端加速仿真（每个测试添加 `VerilatorBackendAnnotation`）。内存模型（`DRAMSim`）固定延迟 10 个周期，always-ready。

**地址构造**：`mkAddr(tag, idx, word=0, byte=0)` = `{tag[31:13], idx[12:6], word[5:2], byte[1:0]}`，即 `(tag << 13) | (idx << 6) | (word << 2) | byte`。

---

### T01 — 冷 Load Miss 返回正确数据

**场景**：对空 Cache 发出 Load，DRAM 中预设目标字为 `0xCAFEBABE`。

**验证目标**：冷 miss 触发完整 FSM 流程，`stall` 全程为 1，回填完成后 `rdata` 返回 DRAM 预设值，耗时多于 1 拍。

**关键断言**：`rdata == 0xCAFEBABE`；`elapsed > 1`。

---

### T02 — 命中：第二次访问同一行在第一拍命中

**场景**：先触发 miss 完成回填；之后以相同地址再次 Load。

**验证目标**：回填后 TagArray valid 已置位，第二次访问在第 1 拍即 `stall=0`，无需进入 FSM。

**关键断言**：第二次访问第一拍 `stall=0`；`rdata == 0x12345678`。

---

### T03 — Store Miss 触发 write-allocate；reload 返回写入值

**场景**：对空 Cache 地址发出全字 Store（miss）；回填完成后同拍 hit-store 提交；紧接 Load 同地址。

**验证目标**：write-allocate 流程正确；`stall=0` 那拍 `hitWen` 生效，DataArray 在 clock edge 更新；reload 无需额外 stall 即命中并返回 Store 写入的值。

**关键断言**：reload `stall=0`；`rdata == 0x12345678`。

---

### T04 — 命中 Store 字节掩码合并

**场景**：Load miss 填充初始值 `0x11223344`；命中 Store `wmask=0x3`（低 2 字节）写入 `0xAABBCCDD`；再次 Load。

**验证目标**：`DataArray` 字节掩码逻辑正确，低 2 字节（`byte[1:0]`）被覆盖，高 2 字节保留原值。

**关键断言**：reload `rdata == 0x1122CCDD`。

---

### T05 — 脏行驱逐：写回正确数据后回填新行

**场景**：向 set=50 依次填充 tagA/B/C/D 四路，对 tagA word=0 执行 Store（写 `0xDEADBEEF`，way0 变脏）；再 Load tagE 触发驱逐 way0。

**验证目标**：FSM 在 `sWbReq/sWbResp` 阶段对 way0 word=0 发出写请求，地址为 `{tagA, idx, 0, 0}`，数据为 `0xDEADBEEF`；写回完成后回填 tagE 成功。

**关键断言**：监测 `mem.req.wen=1` 且地址匹配时 `wdata == 0xDEADBEEF`；Load tagE 命中且 `rdata == (tE<<8)|0`。

---

### T06 — Flush 清零 valid；后续访问变 miss

**场景**：Load miss 填充一条 CacheLine，验证命中；拉高 `flush` 1 拍；再次 Load 同地址。

**验证目标**：`flush` 信号使 TagArray 中所有 valid/dirty 位清零，之后访问必然 miss；回填后数据仍正确。

**关键断言**：flush 前命中；flush 后首拍 `stall=1`；重新回填后 `rdata == 0xBEEFCAFE`。

---

### T07 — printf Bypass：SB 触发 printChar；SW 不触发；均不 stall

**场景**：向 `0x10001FF1` 分别发出 SB（`memWd=2`，写 `0x41`='A'）和 SW（`memWd=0`）。

**验证目标**：SB 满足旁路条件，`printChar.valid=1`，`bits=0x41`，`stall=0`；SW 不满足（`memWd≠2`），`printChar.valid=0`。

**关键断言**：SB 时 `printChar.valid=1`，`bits=0x41`，`stall=0`；SW 时 `printChar.valid=0`。

---

### T08 — mtime MMIO Bypass：0xBFF8/0xBFFC 立即返回，不 stall

**场景**：设置 `mtimeLo=0xDEAD0000`，`mtimeHi=0x0000BEEF`；分别 LW `0xBFF8` 和 `0xBFFC`。

**验证目标**：mtime 地址被旁路截获，当拍返回外部输入值，不触发 FSM，`stall=0`。

**关键断言**：`0xBFF8` 读 `rdata == 0xDEAD0000`；`0xBFFC` 读 `rdata == 0x0000BEEF`；均 `stall=0`。

---

### T09 — 字节 Load 符号/零扩展（LB/LBU）

**场景**：DRAM 预设 word=`0xABCDEF80`，LB byte=0（有符号）和 LBU byte=0（无符号）。

**验证目标**：`LoadExtend` 符号扩展逻辑正确，`0x80` 的最高位为 1，LB 结果为 `0xFFFFFF80`，LBU 为 `0x00000080`。

**关键断言**：LB `rdata == 0xFFFFFF80`；LBU `rdata == 0x00000080`。

---

### T10 — 半字 Load 符号/零扩展（LH/LHU）

**场景**：DRAM 预设 word=`0x80001234`，LH byte=2（高半字，有符号）和 LHU byte=2（无符号）。

**验证目标**：`LoadExtend` 半字提取逻辑正确，高半字 `0x8000` 的最高位为 1，LH 结果为 `0xFFFF8000`，LHU 为 `0x00008000`。

**关键断言**：LH `rdata == 0xFFFF8000`；LHU `rdata == 0x00008000`。

---

### T11 — 同行不同 word offset：一次 miss 填充整行，各 word 命中

**场景**：对 tag=11, idx=90 的行触发冷 miss（word=0），DRAM 中该行 word `w` 预设为 `0xF0000000|w`；回填完成后依次 Load word=1/7/15。

**验证目标**：FSM 回填时逐字写入全部 16 个字，`refillWord` 从 0 计至 15；之后各 word 均命中并返回正确数据。

**关键断言**：word=1 `rdata==0xF0000001`；word=7 `rdata==0xF0000007`；word=15 `rdata==0xF000000F`；均 `stall=0`。

---

### T12 — stall 时序：miss 全程 stall=1，命中拍恰好 stall=0

**场景**：对空 Cache 发出 Load，逐拍检查 stall 状态，记录 stall=1 的周期数和 stall=0 时的 rdata；之后立即发出相同地址第二次 Load。

**验证目标**：miss 期间每拍 `stall=1`；命中拍恰好 `stall=0` 且 `rdata` 正确；随后第二次访问也立即命中。

**关键断言**：`stallCnt > 1`；命中拍 `rdata == 0x55AA55AA`；下一拍再访问 `stall=0`。

---

### T13 — PLRU 驱逐顺序：way0→way2→way1→way3 循环，第 5 次 miss 驱逐 way0

**场景**：向 set=110 依次 Load tag=200/300/400/500（填满 4 路）；再 Load tag=600 触发第 5 次 miss。

**验证目标**：PLRU 从初始 `000` 出发，依次填 way0/way2/way1/way3 后 evictWay 回到 0；第 5 次 miss 驱逐 way0（tag=200），驱逐后再次访问 tag=200 应 miss。

**关键断言**：第 5 次 miss `stall=1`；Load tag=600 成功后，Load tag=200 `stall=1`（已被驱逐）。

---

### T14 — Store Miss 回填 dirty=1；驱逐时写回正确数据

**场景**：Store miss 到 set=120（填 way0，dirty=1）；填满 way2/way1/way3；Load 新 tag 触发驱逐 way0（dirty）。

**验证目标**：`refillIsStore=1` 使 TagArray 的 dirty 初值为 1；驱逐时 FSM 进入写回流程，写回 word=0 的数据为 Store 写入的 `0xFACEFACE`。

**关键断言**：监测 WB 请求中 word=0 地址对应 `wdata == 0xFACEFACE`；`wbSeen=true`。

---

### T15 — LB/LBU 在 byte offset 0–3 全部正确提取

**场景**：DRAM 预设 word=`0xAABBCCDD`；填充后对 byte=0/1/2/3 分别执行 LBU 和 LB（有符号）。

**验证目标**：`LoadExtend` 的 `byteShift = Cat(byteoff, 0.U(3.W))` 逻辑对 4 个 byte offset 均正确提取，`0xAA/0xBB/0xCC/0xDD` 均为负字节，LB 均产生符号扩展。

**关键断言**：LBU byte=0/1/2/3 分别返回 `0xDD/0xCC/0xBB/0xAA`；LB 分别返回 `0xFFFFFFDD/0xFFFFFFCC/0xFFFFFFBB/0xFFFFFFAA`。

---

### T16 — LH/LHU 低半字（byte=0）和高半字（byte=2）均正确

**场景**：DRAM 预设 word=`0x80017FFE`；对 byte=0（低半字 `0x7FFE`）和 byte=2（高半字 `0x8001`）分别执行 LH/LHU。

**验证目标**：半字提取的 `halfShift = Cat(byteoff(1), 0.U(4.W))` 逻辑正确；低半字 `0x7FFE` 是正数，LH 不扩展符号；高半字 `0x8001` 是负数，LH 扩展符号。

**关键断言**：LHU byte=0 `rdata==0x7FFE`；LH byte=0 `rdata==0x7FFE`；LHU byte=2 `rdata==0x8001`；LH byte=2 `rdata==0xFFFF8001`。

---

### T17 — 干净行驱逐：全程无 mem.req.wen 请求

**场景**：向 set=55 依次 Load 5 个不同 tag（全 clean），第 5 次触发 clean eviction。

**验证目标**：FSM `sCheck` 拍检测 `dirtyLatch=0`，直接跳转到 `sRefillReq`，跳过 `sWbReq/sWbResp`，全程 `mem.req.wen` 始终为 0。

**关键断言**：整个第 5 次 miss 过程中 `wbSeen` 始终为 false；第 5 个 tag 最终命中且 rdata 正确。

---

### T18 — NOP 和 mtime-bypass Store 不引发 stall 或 mem 请求

**场景 A**：`memRen=0` 且 `wen=0`（idle）；**场景 B**：SW 到 `0xBFF8`（mtime_lo）；**场景 C**：SW 到 `0xBFFC`（mtime_hi）；**场景 D**：miss 完成后再发 NOP。

**验证目标**：NOP 时 `stall=0`、`mem.req.valid=0`、`printChar.valid=0`；mtime Store 被旁路静默忽略，`stall=0`、`mem.req.valid=0`；mtime 读值不受 Store 影响。

**关键断言**：4 种场景均 `stall=0`；SW 后读 `0xBFF8` 仍返回 `mtimeLo` 外部输入值。

---

### T19 — Store miss 仅覆盖目标 word；邻居 word 保留 DRAM 原值

**场景**：DRAM 预设整行 word `w` 为 `0xD0000000|w`；Store miss 到 word=5 写 `0xFEEDFACE`；回填完成后依次 Load word=5/0/10/15。

**验证目标**：write-allocate 先回填整行再执行 hit-store，只有 word=5 被覆盖，其余 word 保留 DRAM 原值；PLRU 和 TagArray 均正确更新。

**关键断言**：word=5 `rdata==0xFEEDFACE`；word=0 `rdata==0xD0000000`；word=10 `rdata==0xD000000A`；word=15 `rdata==0xD000000F`。

---

### T20 — 脏行写回后 DRAMSim storage 中所有 16 个 word 均正确

**场景**：Load tagA2 填充 way0；Store word=0 写 `0xDEADBEEF`、Store word=8 写 `0xBEEFCAFE`（way0 dirty）；填满 way2/way1/way3；Load tagE2 驱逐 way0（dirty）；等待 stall=0 后读 DRAMSim storage。

**验证目标**：比 T05 更深入——直接在内存模型中读回 16 个 word，验证 FSM 写回时 `lineBuf` 内容与 DataArray 一致，Store 修改的 word=0/8 正确，其余 word 保留回填时的值。

**关键断言**：`dram.readWord(tagA2_word0) == 0xDEADBEEF`；`dram.readWord(tagA2_word8) == 0xBEEFCAFE`；其余 word 等于 `(tA2<<8)|w`。

---

### T21 — Flush 清除多个 set；flush 后所有 set 均 miss

**场景**：向 3 个不同 set（idx=10/50/100）各填充 1 条 CacheLine，验证全部命中；发出 flush 1 拍；依次访问 3 个地址。

**验证目标**：`flush` 通过 TagArray 的 `for (s <- 0 until nSets)` 循环清零全部 128 组的 valid/dirty，不只清当前组；3 个不同 set 的 CacheLine 均被清除。

**关键断言**：flush 前 3 个地址均 `stall=0`；flush 后每个地址首拍均 `stall=1`。
