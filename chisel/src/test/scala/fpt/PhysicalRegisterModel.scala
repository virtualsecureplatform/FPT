package fpt

import firrtl2.ir.{IntParam, Param, Type}
import treadle2.{BlackBoxFactoriesAnnotation, ScalaBlackBox, ScalaBlackBoxFactory}
import treadle2.executable.{PositiveEdge, Transition}

/** Behavioral models for the wide-port control unit tests. The actual inline
  * RTL is independently exercised by BufferedControlLinksSpec (Verilator)
  * and by the full-design numerical and hw_emu gates.
  */
private[fpt] object PhysicalRegisterModel {
  def annotation = BlackBoxFactoriesAnnotation(Seq(new ScalaBlackBoxFactory {
    override def createInstance(instance: String, module: String): Option[ScalaBlackBox] = {
      if (!Set("FptPhysicalCutRegister", "FptPhysicalControlRegister").contains(module)) None
      else Some(add(new ScalaBlackBox {
        override val name = instance
        private var value = BigInt(0)
        private var data = BigInt(0)
        private var enable = false
        private var reset = false
        private var mask = BigInt(0)
        private var width = 1
        override def setParams(params: Seq[Param]): Unit = {
          params.foreach {
            case IntParam("WIDTH", n) => width = n.toInt
            case _ =>
          }
          mask = (BigInt(1) << width) - 1
          params.foreach {
            case IntParam("RESET_MASK", n) => mask = n
            case _ =>
          }
        }
        override def inputChanged(input: String, v: BigInt): Unit = input match {
          case "inputData" => data = v
          case "enable" => enable = v != 0
          case "reset" => reset = v != 0
          case _ =>
        }
        override def clockChange(edge: Transition, clock: String): Unit = {
          if (edge == PositiveEdge) {
            if (module == "FptPhysicalControlRegister") value = if (reset) data & ~mask else data
            else if (enable) value = data
          }
        }
        override def getOutput(inputs: Seq[BigInt], tpe: Type, output: String): BigInt = value
        override def outputDependencies(output: String): Seq[String] = Seq("clock")
      }))
    }
  }))
}
