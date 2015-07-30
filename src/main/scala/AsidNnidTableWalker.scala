package dana

import Chisel._

import rocket._
import uncore.constants.MemoryOpConstants._

class AsidNnidTableWalkerInterface extends XFilesBundle {
  val asidUnit = Vec.fill(numCores){ (new AsidUnitANTWInterface).flip }
  val cache = (new CacheMemInterface).flip
  val mem = Vec.fill(numCores){ new HellaCacheIO }
}

class ConfigRobEntry extends XFilesBundle {
  val valid = UInt(width = bitsPerBlock / params(XLen))
  val cacheAddr = UInt(width = log2Up(cacheDataSize * 8 / bitsPerBlock))
  val data = Vec.fill(bitsPerBlock / params(XLen)){UInt(width = params(XLen))}
}

class AsidNnidTableWalker extends XFilesModule {
  val io = new AsidNnidTableWalkerInterface

  val antpReg = Reg(new XFilesBundle {
    val valid = Bool()
    val antp = UInt(width = params(XLen))
    val size = UInt(width = params(XLen))
  })

  val (s_IDLE ::           // 0
    s_CHECK_NNID_WAIT ::   // 1
    s_READ_NNID_POINTER :: // 2
    s_READ_CONFIGSIZE ::   // 3
    s_READ_CONFIGPTR ::    // 4
    s_READ_CONFIG ::       // 5
    s_READ_CONFIG_WAIT ::  // 6
    s_ERROR ::             // 7
    Nil) = Enum(UInt(), 8)
  val state = Reg(UInt(), init = s_IDLE)

  // State used to read a configuration
  val configSize = Reg(UInt(width = log2Up(cacheDataSize * 8 / params(XLen))))
  val configReqCount = Reg(UInt(width = log2Up(cacheDataSize * 8 / params(XLen))))
  val configRespCount = Reg(UInt(width = log2Up(cacheDataSize * 8 / params(XLen))))
  val configPtr = Reg(UInt(width = params(XLen)))
  val configBufSize = bitsPerBlock / params(XLen)
  val configWb = Reg(Bool())
  val configWbCount = Reg(UInt(width = log2Up(cacheDataSize * 8 / bitsPerBlock)))

  // Cache WB reorder cache
  val configRob = Vec.fill(antwRobEntries){ Reg(new ConfigRobEntry) }

  // Queue requests from the cache
  val cacheReqQueue = Module(new Queue(new CacheMemReq, 2))
  val cacheReqCurrent = Reg(new CacheMemReq)

  // Default values
  (0 until numCores).map(i => io.asidUnit(i).req.ready := Bool(true))
  // We can accept new cache requests only if the Cache Request Queue
  // is ready, i.e., the queue isn't full
  io.cache.req.ready := cacheReqQueue.io.enq.ready
  io.cache.resp.valid := Bool(false)
  io.cache.resp.bits.done := Bool(false)
  io.cache.resp.bits.data := UInt(0)
  io.cache.resp.bits.cacheIndex := UInt(0)
  io.cache.resp.bits.addr := UInt(0)
  for (i <- 0 until numCores) {
    io.mem(i).req.valid := Bool(false)
    io.mem(i).invalidate_lr := Bool(false)
    io.mem(i).req.bits.addr := UInt(0)
    io.mem(i).req.bits.tag := UInt(0)
    io.mem(i).req.bits.cmd := UInt(0)
    io.mem(i).req.bits.typ := UInt(0)
  }
  cacheReqQueue.io.enq.valid := Bool(false)
  cacheReqQueue.io.enq.bits.asid := UInt(0)
  cacheReqQueue.io.enq.bits.nnid := UInt(0)
  cacheReqQueue.io.enq.bits.cacheIndex := UInt(0)
  cacheReqQueue.io.enq.bits.coreIndex := UInt(0)
  cacheReqQueue.io.deq.ready := Bool(false)
  configWb := Bool(false)

  def reqValid(x: AsidUnitANTWInterface): Bool = { x.req.valid }
  def respValid(x: HellaCacheIO): Bool = { x.resp.valid }
  val indexReq = io.asidUnit.indexWhere(reqValid(_))
  val indexResp = io.mem.indexWhere(respValid(_))

  def memRead(core: UInt, addr: UInt) {
    io.mem(core).req.valid := Bool(true)
    io.mem(core).req.bits.addr := addr
    // [TODO] This is fragile on the number of tag bits in Rocket Chip!
    io.mem(core).req.bits.tag := addr(9, 0)
    io.mem(core).req.bits.cmd := M_XRD
    io.mem(core).req.bits.typ := MT_D
  }
  def cacheResp(done: Bool, data: UInt, cacheIndex: UInt, addr: UInt) {
    io.cache.resp.valid := Bool(true)
    io.cache.resp.bits.done := done
    io.cache.resp.bits.data := data
    io.cache.resp.bits.cacheIndex := cacheIndex
    io.cache.resp.bits.addr := addr
  }
  def feedCacheRob() {
    // Compute the response index in terms of a logical index into
    // the array that we're reading
    val respIdx = (io.mem(indexResp).resp.bits.addr - configPtr) >>
    UInt(log2Up(params(XLen)/8))
    // Based on this response index, compute the slot and offset
    // in the Config ROB buffer
    val configRobSlot = respIdx(log2Up(antwRobEntries) +
      log2Up(configBufSize) - 1, log2Up(configBufSize))
    val configRobOffset = respIdx(log2Up(configBufSize) - 1, 0)
    // The configRespCount just keeps track of how many responses
    // we've seen. This is used to determine when we've seen all
    // the reads we expecte.
    configRespCount := configRespCount + UInt(1)

    // Write the data to the appropriate slot and offset in the
    // Config ROB setting the valid flags appropriately
    configRob(configRobSlot).valid :=
      configRob(configRobSlot).valid |
      UInt(1, width = configBufSize) << configRobOffset
    configRob(configRobSlot).cacheAddr :=
      respIdx >> UInt(log2Up(configBufSize))
    configRob(configRobSlot).data(configRobOffset) :=
      io.mem(indexResp).resp.bits.data_subword

    // Print out the response [TODO] remove
    printf("[INFO] ANTW: Resp addr/data 0x%x/0x%x\n",
      respIdx, io.mem(indexResp).resp.bits.data_subword)
  }

  // Communication with the ASID Unit
  when (io.asidUnit.exists(reqValid)) {
    antpReg.valid := Bool(true)
    antpReg.antp := io.asidUnit(indexReq).req.bits.antp
    antpReg.size := io.asidUnit(indexReq).req.bits.size
    printf("[INFO] ANTW changing ANTP to 0x%x with size 0x%x\n",
      io.asidUnit(indexReq).req.bits.antp,
      io.asidUnit(indexReq).req.bits.size)
  }

  // New cache requests get entered on the queue
  when (io.cache.req.fire()) {
    printf("[INFO] ANTW: Enqueing new mem request for Core/ASID/NNID/Idx 0x%x/0x%x/0x%x/0x%x\n",
      io.cache.req.bits.coreIndex, io.cache.req.bits.asid,
      io.cache.req.bits.nnid, io.cache.req.bits.cacheIndex)
    cacheReqQueue.io.enq.valid := Bool(true)
    cacheReqQueue.io.enq.bits := io.cache.req.bits
  }

  // [TODO] Need a small controller that determines what to do next.
  // This should support servicing a request on the queue or dealing
  // with a "one-off" request from a PE. I think this should be
  // written as request and response logic.
  val hasCacheRequests = cacheReqQueue.io.count > UInt(0) &&
    antpReg.valid

  switch (state) {
    is (s_IDLE) {
      when (hasCacheRequests) {
        // The base request offset is the ANTP plus the ASID *
        // size_of(asid_nnid_table_entry) which is 24 bytes
        val reqAddr = antpReg.antp + cacheReqQueue.io.deq.bits.asid * UInt(24)
        // Copy the request into the currently processing storage area
        cacheReqCurrent.asid := cacheReqQueue.io.deq.bits.asid
        cacheReqCurrent.nnid := cacheReqQueue.io.deq.bits.nnid
        cacheReqCurrent.cacheIndex := cacheReqQueue.io.deq.bits.cacheIndex
        cacheReqCurrent.coreIndex := cacheReqQueue.io.deq.bits.coreIndex
        cacheReqQueue.io.deq.ready := Bool(true)
        printf("[INFO] ANTW: Dequeuing mem request for Core/ASID/NNID/Idx 0x%x/0x%x/0x%x/0x%x\n",
          cacheReqCurrent.coreIndex, cacheReqCurrent.asid,
          cacheReqCurrent.nnid, cacheReqCurrent.cacheIndex)
        // printf("[INFO] ANTW: New request addr/tag 0x%x/0x%x\n",
        //   reqAddr, reqAddr(9, 0))
        memRead(io.cache.req.bits.coreIndex, reqAddr)
        state := s_CHECK_NNID_WAIT
      }
    }
    is (s_CHECK_NNID_WAIT) {
      when(io.mem.exists(respValid)) {
        // [TODO] Fragile on XLen
        val numConfigs = io.mem(indexResp).resp.bits.data_subword(31, 0)
        val numValid = io.mem(indexResp).resp.bits.data_subword(63, 32)
        printf("[INFO] ANTW: Saw CHECK_NNID resp w/ #configs 0x%x, #valid 0x%x\n",
          numConfigs, numValid)
        when (cacheReqCurrent.nnid < numValid) {
          val reqAddr = antpReg.antp + cacheReqCurrent.asid * UInt(32)
          memRead(io.cache.req.bits.coreIndex, reqAddr)
          state := s_READ_NNID_POINTER
          // printf("[INFO] ANTW: New request addr/tag 0x%x/0x%x\n",
          //   reqAddr, reqAddr(9, 0))
        } .otherwise {
          printf("[ERROR] ANTW: NNID reference would be invalid\n")
          state := s_ERROR
        }
      }
    }
    is (s_READ_NNID_POINTER) {
      when (io.mem.exists(respValid)) {
        val reqAddr = io.mem(indexResp).resp.bits.data_subword +
          cacheReqCurrent.nnid * UInt(16)
        configPtr := io.mem(indexResp).resp.bits.data_subword +
          cacheReqCurrent.nnid * UInt(16) + UInt(8)
        // printf("[INFO] ANTW: New request addr/tag 0x%x/0x%x\n",
        //   reqAddr, reqAddr(9, 0))
        memRead(io.cache.req.bits.coreIndex, reqAddr)
        state := s_READ_CONFIGSIZE
      }
    }
    is (s_READ_CONFIGSIZE) {
      when (io.mem.exists(respValid)) {
        configSize := io.mem(indexResp).resp.bits.data_subword
        // printf("[INFO] ANTW: Setting config size to 0x%x\n",
        //   io.mem(indexResp).resp.bits.data_subword)
        val reqAddr = configPtr
        memRead(io.cache.req.bits.coreIndex, reqAddr)
        state := s_READ_CONFIGPTR
      }
    }
    is (s_READ_CONFIGPTR) {
      when (io.mem.exists(respValid)) {
        configPtr := io.mem(indexResp).resp.bits.data_subword
        configReqCount := UInt(1)
        configRespCount := UInt(0)
        configWbCount := UInt(0)
        val reqAddr = io.mem(indexResp).resp.bits.data_subword
        // printf("[INFO] ANTW: New request addr/tag 0x%x/0x%x\n",
        //   reqAddr, reqAddr(9, 0))
        memRead(io.cache.req.bits.coreIndex, reqAddr)
        state := s_READ_CONFIG
      }
    }
    is (s_READ_CONFIG) {
      // Whenever the cache can accept a new request, send one
      when (io.mem(io.cache.req.bits.coreIndex).req.ready) {
        configReqCount := configReqCount + UInt(1)
        val reqAddr = configPtr + configReqCount * UInt(params(XLen) / 8)
        // printf("[INFO] ANTW: New request addr/tag 0x%x/0x%x\n",
        //   reqAddr, reqAddr(9, 0))
        memRead(io.cache.req.bits.coreIndex, reqAddr)
        when (configReqCount === configSize - UInt(1)) {
          state := s_READ_CONFIG_WAIT
        }
      }
      // If a new response shows up, write it into a buffer and send
      // it along to the Cache if we've filled a buffer
      when (io.mem.exists(respValid)) {
        feedCacheRob()
      }
    }
    is (s_READ_CONFIG_WAIT) {
      when (io.mem.exists(respValid)) {
        feedCacheRob()
      }
      when (configRespCount === configSize) {
        state := s_IDLE
      }
    }
  }

  // We need to look at the Config ROB and determine if anything is
  // valid to write back to the cache. A slot is valid if all its
  // valid bits are asserted.
  def configRobAllValid(x: ConfigRobEntry): Bool = {
    x.valid === ~UInt(0, width = configBufSize)}
  val configRobWb = configRob.exists(configRobAllValid(_))
  val configRobIdx = configRob.indexWhere(configRobAllValid(_))

  // Writeback data to the cache whenever the configWb flag tells us
  // that the configBuf has valid data
  when (configRobWb) {
    cacheResp(
      configWbCount === (configSize >> UInt(log2Up(configBufSize))) - UInt(1),
      configRob(configRobIdx).data.toBits,
      cacheReqCurrent.cacheIndex,
      configRob(configRobIdx).cacheAddr)
    printf("[INFO] ANTW: configWbCount: 0x%x\n", configWbCount)
    configRob(configRobIdx).valid := UInt(0)
    configWbCount := configWbCount + UInt(1)
  }

  // when (io.mem.exists(respValid)) {
  //   printf("[INFO] ANTW (state==%x): Memory response subword 0x%x\n",
  //     state,
  //     io.mem(indexResp).resp.bits.data_subword)
  // }

  // Reset conditions
  when (reset) {
    antpReg.valid := Bool(false)
    (0 until antwRobEntries).map(i => configRob(i).valid := Bool(false))
  }

  // Assertions
  // There should only be at most one valid ANTP update request from
  // all ASID Units
  assert(!(io.asidUnit.count(reqValid(_)) > UInt(1)),
    "Saw more than one simultaneous ANTP request")
  assert(!(io.mem.count(respValid(_)) > UInt(1)),
    "Saw more than one simultaneous ANTP response, dropping all but one...")
  assert(!(io.cache.req.fire() && !io.cache.req.ready),
    "ANTW saw a cache request, but it's cache queue is full")
  // If the ASID is larger than the stored size, then this is an
  // invalid ASID for the stored ASID--NNID table pointer.
  assert(!(io.cache.req.fire() && antpReg.valid &&
    antpReg.size < io.cache.req.bits.asid),
    "ANTW saw cache request with out of bounds ASID")
  assert(!(io.cache.req.fire() && !antpReg.valid),
    "ANTW saw cache request with invalid ASID")
  assert(!(state === s_ERROR),
    "ANTW is in an error state")
  assert(Bool(isPow2(configBufSize)),
    "ANTW derived parameter configBufSize must be a power of 2")
}