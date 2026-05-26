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

### 成员交接端口说明

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
| `refillEn` | 1 | FSM（sRefillWait 状态）| 为 1 时当拍 `refillData` 有效，A 应将其写入 `DataArray` |
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
- **未命中回填**：`HitTest` 检测发现未命中，流水线停顿，根据 `PLRU` 选中的 `evictWay` 在 `DataArray` 中将对应 CacheLine 数据从内存中逐字搬运进对应路，最后更新 tag 信息。
- **未命中写回**：`HitTest` 检测发现未命中，检测 `dirty` 位，若为高需要将数据搬运至 `evictLine` 最终写回内存。

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
- **Miss 回填写逻辑（refillDataEn=1）**：将 `refillData` 写入 `dArray(refillWay)(refillIdx)(refillWord)`，每拍写一个字，由 B 侧 FSM 驱动 `refillWord` 从 0 计数至 15。
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

## 4. PMA 旁路逻辑

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

## 5. 仿真验证说明

以下 21 个测试用例覆盖 DCacheCore 的全部功能路径，采用 Verilator 后端加速仿真。

---

### T01 — Flush 清零逻辑

**行为**：先回填两条 CacheLine（set=0 way=0，set=1 way=0），验证回填后均可命中；随后拉高 `flush` 一拍，再次访问同地址。

**验证目标**：`flush` 触发后，`TagArray` 中所有组所有路的 `valid` 和 `dirty` 位清零，后续访问必然 miss；同时验证 idle 状态（`memRen=0` 且 `wen=0`）下 `missOut` 和 `missValid` 均为 0，不产生虚触发。

**期望结果**：flush 前两次 Load 均命中；flush 后同地址 Load 均 miss，`missOut=1`，`missValid=1`；idle 时两者为 0。

---

### T02 — HIT 读数据处理

**行为**：回填一条包含 `0xDEADBEEF` 的 CacheLine，随后对同一地址分别执行字访问、半字访问（`byteoff=0` 和 `byteoff=2`）、字节访问（全部 4 个 `byteoff`），每种宽度分别测试有符号和无符号扩展。

**验证目标**：`LoadExtend` 模块的路选择、字节/半字提取、符号扩展逻辑全部正确；字访问直通不受 `byteoff` 影响。

**期望结果**：字返回 `0xDEADBEEF`；无符号半字 `boff=0` 返回 `0x0000BEEF`，`boff=2` 返回 `0x0000DEAD`；有符号半字对应 `0xFFFFBEEF` / `0xFFFFDEAD`；4 个字节偏移的无符号/有符号值均与手工计算一致。

---

### T03 — HIT 写字节掩码

**行为**：回填初始值 `0x12345678`，依次用 `wmask=0001`、`wmask=0010`、`wmask=1100`、`wmask=1111` 执行 Store，每次写后立即 Load 读回。

**验证目标**：`DataArray` 的字节掩码写逻辑精确，被掩码覆盖的字节更新，未覆盖的字节保持原值不变。

**期望结果**：每次 Store 后 Load 值与按位计算的预期完全一致（`0x12345699` → `0x1234BB99` → `0xAACCBB99` → `0xDEADFACE`）。

---

### T04 — HIT 写 dirty 位与 flush 清零

**行为**：回填 CacheLine 后执行 Store hit，使 way=0 变脏；之后访问同 idx 不同 tag 地址触发 miss，观察 `evictDirty`；再执行 flush，重新回填后再次触发 miss。

**验证目标**：Store hit 后 `TagArray` 中对应路的 `dirty` 位被置 1；flush 后 dirty 位清零，驱逐时 `evictDirty=0`。

**期望结果**：第一次驱逐时若 `evictWay=0`，`evictDirty=1`；flush 并重新回填后驱逐同路，`evictDirty=0`。

---

### T05 — MISS 回填时序边界

**行为**：对空 Cache 发出 Load 触发 miss；随后模拟 B 侧 FSM 逐字回填 16 拍，在每拍回填的同时检查同地址是否仍 miss；最后发出 `refillDone` 脉冲。

**验证目标**：`TagArray` 的写入仅在 `refillDone` 那一拍 posedge 生效；逐字 `refillEn` 期间 `valid` 位未更新，访问仍产生 miss；`refillDone` 后的下一拍立即命中。

**期望结果**：回填 16 拍中每拍 `missOut=1`；`refillDone` 后首次 Load 命中且返回数据正确。

---

### T06 — MISS 回填数据完整性

**行为**：回填一条 16 字各不相同的 CacheLine，回填完成后对 `wordsoff=0` 到 `wordsoff=15` 逐字执行 Load。

**验证目标**：`DataArray` 的 16 次逐字写入全部正确落位，无覆盖、无偏移。

**期望结果**：16 次 Load 每次返回值均与写入时对应字的预设值完全吻合。

---

### T07 — Store miss 回填 dirty 初值

**行为**：对空 Cache 地址执行 Store（miss），模拟 B 侧以 `refillIsStore=true` 完成回填；之后填满其余三路，再次触发 miss 观察驱逐行的 `evictDirty`。

**验证目标**：Store miss 回填时 `TagArray` 写入的 `dirty` 初值由 `refillIsStore` 决定，为 1；回填后该行在被驱逐时 `evictDirty=1`，通知 B 侧需先写回。

**期望结果**：若驱逐路为 Store miss 回填的路，`evictDirty=1`，`evictTag` 等于 miss 时的 tag 值。

---

### T08 — 脏行驱逐信号时序

**行为**：回填 way=0 后执行两次 Store hit（修改 `wordsoff=0` 和 `wordsoff=3`），使 way=0 变脏；之后访问同 idx 不同 tag 地址触发 miss，在 miss 当拍采样 `evictDirty`、`evictTag`、`evictLine`。

**验证目标**：`evictLine` 是 `DataArray` 的组合输出，必须在 `missOut=1` 的当拍即有效，B 侧无需等待额外时钟沿即可读取并开始写回；`evictDirty` 和 `evictTag` 同样当拍有效。

**期望结果**：若 `evictWay=0`，`evictDirty=1`，`evictTag` 正确，`evictLine(0)=0x12345678`，`evictLine(3)=0xDEADFACE`，均在 miss 当拍可见。

---

### T09 — PLRU 驱逐路序列

**行为**：从全空 Cache（tree=000）开始，依次向 way=0、way=2、way=1、way=3 回填（每次回填触发 `refillDone` 更新 PLRU），每次回填后采样 `evictWay`。

**验证目标**：4 路 Tree-PLRU 的二叉树状态转移严格遵循设计规范，回填顺序 w0→w2→w1→w3 后 `evictWay` 依次为 0→2→1→3→0，循环正确。

**期望结果**：

| 操作 | 预期 tree 状态 | 预期 evictWay |
|------|-------------|-------------|
| 初始 | `000` | 0 |
| refill w0 | `110` | 2 |
| refill w2 | `011` | 1 |
| refill w1 | `101` | 3 |
| refill w3 | `000` | 0（循环） |

---

### T10 — PLRU idle 期间不更新

**行为**：回填 way=0 后记录当前 `evictWay`；随后保持 `memRen=0`、`wen=0`（idle）推进 3 个时钟周期；再次采样 `evictWay`。

**验证目标**：`DCacheCore` 在 idle 拍不拉高 `updateEn`，PLRU 树状态保持不变，避免空泡修改 LRU 历史。

**期望结果**：idle 3 拍前后 `evictWay` 值完全相同。

---

### T11 — PLRU 仅在 refillDone 更新

**行为**：从全空 Cache 触发 miss；模拟 B 侧逐字回填 16 拍，在每一拍 `refillEn=1` 期间采样 `evictWay`；最后发出 `refillDone` 脉冲，再次采样。

**验证目标**：`refillEn` 的每一拍均不应更新 PLRU；PLRU 只在 `refillDone` 那一拍的 posedge 更新一次。

**期望结果**：回填 16 拍中 `evictWay` 保持初始值不变；`refillDone` 后 `evictWay` 变为 2（way=0 被标记为最近访问后，下一驱逐目标为 way=2）。

---

### T12 — missValid 空泡门控

**行为**：将地址指向必然 miss 的空 Cache 位置，但不置 `memRen` 也不置 `wen`（模拟流水线空泡）；采样 `missOut` 和 `missValid`；再置 `memRen=1` 采样。

**验证目标**：`HitTest` 的 `missValid = ~isHit & (memRen | wen)` 门控逻辑正确，流水线空泡不产生虚 miss，不误触发 MissHandler FSM。

**期望结果**：`memRen=0` 且 `wen=0` 时两者均为 0；`memRen=1` 时两者均为 1。

---

### T13 — 多 set 独立性

**行为**：向 set=0、1、2、3 各回填一条 tag 相同但数据不同的 CacheLine，随后交叉读取 4 个 set 的 `wordsoff=8` 位置。

**验证目标**：不同 set（idx）的 `TagArray` 和 `DataArray` 行互相独立，不存在索引混淆或数据污染。

**期望结果**：每个 set 读回的字均与写入时的预设值完全一致，4 组数据互不干扰。

---

### T14 — 同 idx 多 way 路选择

**行为**：向同一 idx 的 way=0、1、2、3 各回填不同 tag 和数据，然后分别以对应 tag 访问，观察 `rdata`。

**验证目标**：`HitTest` 的命中路判断和 `LoadExtend` 的路选择逻辑正确，4 路中仅命中 tag 匹配的路，`rdata` 来自正确的路数据，不发生路混淆。

**期望结果**：每次 Load 均命中且返回对应路预设数据，无跨路串扰。

---

### T15 — evictLine 当拍有效性

**行为**：回填 way=0 的完整 16 字 CacheLine；之后访问同 idx 不同 tag 地址触发 miss，在 miss 当拍逐字读取 `evictLine(0..15)`。

**验证目标**：`DataArray` 的 `evictLine` 是纯组合输出（寄存器直接索引），无需等待时钟沿，在 `missOut=1` 的当拍即全部有效，B 侧可立即锁存整行数据。

**期望结果**：若 `evictWay=0`，`evictLine` 16 个字均与回填时写入的值一致；若驱逐了空路，16 字均为 0。

---

### T16 — printf 旁路 Store Byte

**行为**：先回填一条普通 CacheLine 备用；然后分别向 `0x10001FF1` 发出 SB（写 `'H'=0x48`）和 SB（写 `'!'=0x21`），每次在写操作当拍采样 `printChar.valid`、`printChar.bits`、`missOut`；最后读回普通 CacheLine 验证未被污染。

**验证目标**：SB 到 printf 地址时 `printChar` 输出正确字符；访问为单拍完成，`missOut=0` 不产生 stall；整个旁路过程不修改 Cache 任何状态。

**期望结果**：两次 SB 分别触发 `printChar.valid=1`，`bits=0x48` 和 `bits=0x21`；`missOut=0`；普通 CacheLine 读回值不变。

---

### T17 — printf 地址非 SB 不触发旁路

**行为**：向 `0x10001FF1` 发出 SW（`memWd=0`，字写），采样 `printChar.valid` 和 `missOut`。

**验证目标**：printf 旁路条件严格限定为 SB（`wen=1` 且 `memWd=2`），非 SB 的写操作不应旁路，应走正常 Cache miss 流程；`printChar` 不应有任何输出。

**期望结果**：`printChar.valid=0`；`missOut=1`（Cache 中无对应 tag，正常 miss）。

---

### T18 — printf 地址误 Load

**行为**：向 `0x10001FF1` 发出 LB（`memRen=1`，`memWd=2`），采样 `printChar.valid`、`rdata`、`missOut`。

**验证目标**：Load 到 printf 地址属于旁路路径，不 stall，不产生 miss，`rdata` 返回 0（行为未定义，硬件统一返回 0）；`printChar` 只在 Store 时有效，Load 时不触发。

**期望结果**：`printChar.valid=0`；`rdata=0`；`missOut=0`。

---

### T19 — mtime Load 旁路

**行为**：设置 `mtimeLo=0xCAFEBABE`，向 `0xBFF8` 发出 LW，采样 `rdata` 和 `missOut`；设置 `mtimeHi=0xDEADD00D`，向 `0xBFFC` 发出 LW，采样；最后改变 `mtimeLo` 的值再次读 `0xBFF8`，验证组合输出跟随性。

**验证目标**：mtime 地址的 Load 被旁路截获，`rdata` 直接来自外部输入 `mtimeLo`/`mtimeHi`，是当拍组合输出，随输入变化立即变化；访问不 stall，不触发 FSM，不污染 Cache。

**期望结果**：`rdata` 与对应输入信号完全一致；`missOut=0`；`printChar.valid=0`。

---

### T20 — mtime Store 静默忽略

**行为**：分别向 `0xBFF8` 和 `0xBFFC` 发出 SW，采样 `missOut` 和 `missValid`；之后再次 Load `0xBFF8`，验证 `mtimeLo` 仍由外部输入决定；读回普通 CacheLine 验证未受干扰。

**验证目标**：Store 到 mtime 地址被旁路截获，硬件静默忽略写操作，不产生 stall，不修改任何寄存器，不影响 Cache 状态；计数器值完全由外部 `mtimeLo`/`mtimeHi` 输入决定。

**期望结果**：两次 Store 均 `missOut=0`，`missValid=0`；后续 Load `0xBFF8` 返回外部输入值而非写入值；普通 CacheLine 完好。

---

### T21 — 旁路期间 Cache 内部状态完全不变

**行为**：回填 way=0 后记录 `evictWay` 基准值；连续执行 10 次 printf SB（写不同字符）和 5 次 mtime LW，再次采样 `evictWay`；最后对普通 CacheLine 全部 16 字逐字读回。

**验证目标**：综合验证旁路屏蔽机制的完整性——15 次旁路访问期间 PLRU 树、TagArray、DataArray 均未被修改，`isBypass=1` 时所有写使能的门控逻辑全部有效。

**期望结果**：15 次旁路后 `evictWay` 与基准值完全相同；普通 CacheLine 全部 16 字读回值与回填时一致，无任何数据损坏。
