// See LICENSE for license details.
package sifive.blocks.devices.uart

import Chisel._

import freechips.rocketchip.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.prci._
import freechips.rocketchip.regmapper._
import freechips.rocketchip.subsystem.{Attachable, TLBusWrapperLocation, PBUS}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.diplomaticobjectmodel.DiplomaticObjectModelAddressing
import freechips.rocketchip.diplomaticobjectmodel.model.{OMComponent, OMRegister}
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{LogicalModuleTree, LogicalTreeNode}

import sifive.blocks.util._

case class UARTParams(
  address: BigInt,
  dataBits: Int = 8,
  stopBits: Int = 2,
  divisorBits: Int = 16,
  oversample: Int = 4,
  nSamples: Int = 3,
  nTxEntries: Int = 8,
  nRxEntries: Int = 8,
  wire4: Boolean = false,
  parity: Boolean = false,
  indparity: Boolean = false) extends DeviceParams
{
  def oversampleFactor = 1 << oversample
  require(divisorBits > oversample)
  require(oversampleFactor > nSamples)
}

class UARTPortIO(val c: UARTParams) extends Bundle {
  val txd = Bool(OUTPUT)
  val rxd = Bool(INPUT)
  val cts = if (c.wire4) Some(Bool(INPUT)) else None
  val rts = if (c.wire4) Some(Bool(OUTPUT)) else None
}

class UARTInterrupts extends Bundle {
  val rxwm = Bool()
  val txwm = Bool()
}

//abstract class UART(busWidthBytes: Int, val c: UARTParams, divisorInit: Int = 0)
class UART(busWidthBytes: Int, val c: UARTParams, divisorInit: Int = 0)
                   (implicit p: Parameters)
    extends IORegisterRouter(
      RegisterRouterParams(
        name = "serial",
        compat = Seq("sifive,uart0"),
        base = c.address,
        beatBytes = busWidthBytes),
      new UARTPortIO)
    //with HasInterruptSources {
    with HasInterruptSources with HasTLControlRegMap {

  def nInterrupts = 1 + c.parity.toInt

  ResourceBinding {
    Resource(ResourceAnchors.aliases, "uart").bind(ResourceAlias(device.label))
  }

  require(divisorInit != 0, "UART divisor wasn't initialized during instantiation")
  require(divisorInit >> c.divisorBits == 0, s"UART divisor reg (width $c.divisorBits) not wide enough to hold $divisorInit")

  lazy val module = new LazyModuleImp(this) {

  val txm = Module(new UARTTx(c))
  val txq = Module(new Queue(txm.io.in.bits, c.nTxEntries))

  val rxm = Module(new UARTRx(c))
  val rxq = Module(new Queue(rxm.io.out.bits, c.nRxEntries))

  val div = Reg(init = UInt(divisorInit, c.divisorBits))

  private val stopCountBits = log2Up(c.stopBits)
  private val txCountBits = log2Floor(c.nTxEntries) + 1
  private val rxCountBits = log2Floor(c.nRxEntries) + 1

  val txen = Reg(init = Bool(false))
  val rxen = Reg(init = Bool(false))
  val enwire4 = Reg(init = Bool(false))
  val enparity = Reg(init = Bool(false))
  val parity = Reg(init = Bool(false)) // Odd parity - 1 , Even parity - 0 
  val errorparity = Reg(init = Bool(false))
  val errie = Reg(init = Bool(false))
  val txwm = Reg(init = UInt(0, txCountBits))
  val rxwm = Reg(init = UInt(0, rxCountBits))
  val nstop = Reg(init = UInt(0, stopCountBits))

  if (c.wire4) 
    txm.io.en := txen && (!port.cts.get || !enwire4)
  else 
    txm.io.en := txen
  txm.io.in <> txq.io.deq
  txm.io.div := div
  txm.io.nstop := nstop
  port.txd := txm.io.out

  rxm.io.en := rxen
  rxm.io.in := port.rxd
  rxq.io.enq <> rxm.io.out
  rxm.io.div := div
  port.rts.foreach { r => r := !(rxq.io.count < c.nRxEntries.U) }
  if (c.parity) {
    txm.io.enparity.get := enparity
    txm.io.parity.get := parity
    rxm.io.parity.get := parity ^ c.indparity.B // independent parity on tx and rx
    rxm.io.enparity.get := enparity
    errorparity := rxm.io.errorparity.get || errorparity
    interrupts(1) := errorparity && errie
  }

  val ie = Reg(init = new UARTInterrupts().fromBits(Bits(0)))
  val ip = Wire(new UARTInterrupts)

  ip.txwm := (txq.io.count < txwm)
  ip.rxwm := (rxq.io.count > rxwm)
  interrupts(0) := (ip.txwm && ie.txwm) || (ip.rxwm && ie.rxwm)

  val mapping = Seq(
    UARTCtrlRegs.txfifo -> RegFieldGroup("txdata",Some("Transmit data"),
                           NonBlockingEnqueue(txq.io.enq)),
    UARTCtrlRegs.rxfifo -> RegFieldGroup("rxdata",Some("Receive data"),
                           NonBlockingDequeue(rxq.io.deq)),

    UARTCtrlRegs.txctrl -> RegFieldGroup("txctrl",Some("Serial transmit control"),Seq(
      RegField(1, txen,
               RegFieldDesc("txen","Transmit enable", reset=Some(0))),
      RegField(stopCountBits, nstop,
               RegFieldDesc("nstop","Number of stop bits", reset=Some(0))))),
    UARTCtrlRegs.rxctrl -> Seq(RegField(1, rxen,
               RegFieldDesc("rxen","Receive enable", reset=Some(0)))),
    UARTCtrlRegs.txmark -> Seq(RegField(txCountBits, txwm,
               RegFieldDesc("txcnt","Transmit watermark level", reset=Some(0)))),
    UARTCtrlRegs.rxmark -> Seq(RegField(rxCountBits, rxwm,
               RegFieldDesc("rxcnt","Receive watermark level", reset=Some(0)))),

    UARTCtrlRegs.ie -> RegFieldGroup("ie",Some("Serial interrupt enable"),Seq(
      RegField(1, ie.txwm,
               RegFieldDesc("txwm_ie","Transmit watermark interrupt enable", reset=Some(0))),
      RegField(1, ie.rxwm,
               RegFieldDesc("rxwm_ie","Receive watermark interrupt enable", reset=Some(0))))),

    UARTCtrlRegs.ip -> RegFieldGroup("ip",Some("Serial interrupt pending"),Seq(
      RegField.r(1, ip.txwm,
                 RegFieldDesc("txwm_ip","Transmit watermark interrupt pending", volatile=true)),
      RegField.r(1, ip.rxwm,
                 RegFieldDesc("rxwm_ip","Receive watermark interrupt pending", volatile=true)))),

    UARTCtrlRegs.div -> Seq(
      RegField(c.divisorBits, div,
                 RegFieldDesc("div","Baud rate divisor",reset=Some(divisorInit))))
  )

  val optionalparity = if (c.parity) Seq(
    UARTCtrlRegs.parity -> RegFieldGroup("parity",Some("Odd/Even Parity Generation/Checking"),Seq(
      RegField(1, enparity,
               RegFieldDesc("enparity","Enable Parity Generation/Checking", reset=Some(0))),
      RegField(1, parity,
               RegFieldDesc("parity","Odd(1)/Even(0) Parity", reset=Some(0))),
      RegField(1, errorparity,
               RegFieldDesc("errorparity","Parity Status Sticky Bit", reset=Some(0))),
      RegField(1, errie,
               RegFieldDesc("errie","Interrupt on error in parity enable", reset=Some(0)))))) else Nil

  val optionalwire4 = if (c.wire4) Seq(
    UARTCtrlRegs.wire4 -> RegFieldGroup("wire4",Some("Configure Clear-to-send / Request-to-send ports"),Seq(
      RegField(1, enwire4,
               RegFieldDesc("enwire4","Enable CTS/RTS", reset=Some(0)))))) else Nil

  regmap(mapping ++ optionalparity ++ optionalwire4:_*)
  val omRegMap = OMRegister.convert(mapping ++ optionalparity ++ optionalwire4:_*)
}

  val logicalTreeNode = new LogicalTreeNode(() => Some(device)) {
    def getOMComponents(resourceBindings: ResourceBindings, children: Seq[OMComponent] = Nil): Seq[OMComponent] = {
      Seq(
        OMUART(
          divisorWidthBits = c.divisorBits,
          divisorInit = divisorInit,
          nRxEntries = c.nRxEntries,
          nTxEntries = c.nTxEntries,
          dataBits = c.dataBits,
          stopBits = c.stopBits,
          oversample = c.oversample,
          nSamples = c.nSamples,
          memoryRegions = DiplomaticObjectModelAddressing.getOMMemoryRegions("UART", resourceBindings, Some(module.omRegMap)),
          interrupts = DiplomaticObjectModelAddressing.describeGlobalInterrupts(device.describe(resourceBindings).name, resourceBindings),
        )
      )
    }
  }
}
class TLUART(busWidthBytes: Int, params: UARTParams, divinit: Int)(implicit p: Parameters)
  extends UART(busWidthBytes, params, divinit) with HasTLControlRegMap

case class UARTAttachParams(
  device: UARTParams,
  controlWhere: TLBusWrapperLocation = PBUS,
  blockerAddr: Option[BigInt] = None,
  controlXType: ClockCrossingType = NoCrossing,
  intXType: ClockCrossingType = NoCrossing) extends DeviceAttachParams
{
  def attachTo(where: Attachable)(implicit p: Parameters): TLUART = where {
    val name = s"uart_${UART.nextId()}"
    val tlbus = where.locateTLBusWrapper(controlWhere)
    val divinit = (tlbus.dtsFrequency.get / 115200).toInt
    val uartClockDomainWrapper = LazyModule(new ClockSinkDomain(take = None))
    val uart = uartClockDomainWrapper { LazyModule(new TLUART(tlbus.beatBytes, device, divinit)) }
    uart.suggestName(name)

    tlbus.coupleTo(s"device_named_$name") { bus =>

      val blockerOpt = blockerAddr.map { a =>
        val blocker = LazyModule(new TLClockBlocker(BasicBusBlockerParams(a, tlbus.beatBytes, tlbus.beatBytes)))
        tlbus.coupleTo(s"bus_blocker_for_$name") { blocker.controlNode := TLFragmenter(tlbus) := _ }
        blocker
      }

      uartClockDomainWrapper.clockNode := (controlXType match {
        case _: SynchronousCrossing =>
          tlbus.dtsClk.map(_.bind(uart.device))
          tlbus.fixedClockNode
        case _: RationalCrossing =>
          tlbus.clockNode
        case _: AsynchronousCrossing =>
          val uartClockGroup = ClockGroup()
          uartClockGroup := where.asyncClockGroupsNode
          blockerOpt.map { _.clockNode := uartClockGroup } .getOrElse { uartClockGroup }
      })

      (uart.controlXing(controlXType)
        := TLFragmenter(tlbus)
        := blockerOpt.map { _.node := bus } .getOrElse { bus })
    }

    (intXType match {
      case _: SynchronousCrossing => where.ibus.fromSync
      case _: RationalCrossing => where.ibus.fromRational
      case _: AsynchronousCrossing => where.ibus.fromAsync
    }) := uart.intXing(intXType)

    LogicalModuleTree.add(where.logicalTreeNode, uart.logicalTreeNode)

    uart
  }
}

object UART {
  val nextId = { var i = -1; () => { i += 1; i} }

  def makePort(node: BundleBridgeSource[UARTPortIO], name: String)(implicit p: Parameters): ModuleValue[UARTPortIO] = {
    val uartNode = node.makeSink()
    InModuleBody { uartNode.makeIO()(ValName(name)) }
  }

  def tieoff(port: UARTPortIO) {
    port.rxd := UInt(1)
    port.cts.foreach { ct => ct := false.B } // active-low
  }

  def loopback(port: UARTPortIO) {
    port.rxd := port.txd
    if (port.c.wire4) {
      port.cts.get := port.rts.get
    }
  }
}

