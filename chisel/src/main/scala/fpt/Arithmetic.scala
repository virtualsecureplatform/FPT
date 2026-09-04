package fpt

import chisel3._
import chisel3.experimental.IntParam
import chisel3.util._

final class ComplexSInt(val componentWidth: Int) extends Bundle {
  val real = SInt(componentWidth.W)
  val imag = SInt(componentWidth.W)
}

/** An explicitly preserved FPGA pipeline cut.
  *
  * Chisel's `dontTouch` is an optimization barrier for Chisel and FIRRTL,
  * but it does not ask Vivado to keep a register during global synthesis
  * retiming.  Use this narrow BlackBox only where a register is part of the
  * physical architecture, such as directly in front of a wide RAM port.
  */
private[fpt] final class PhysicalCutRegister(val width: Int)
    extends BlackBox(Map("WIDTH" -> IntParam(width)))
    with HasBlackBoxInline {
  require(width >= 1)
  override def desiredName: String = "FptPhysicalCutRegister"

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val enable = Input(Bool())
    val inputData = Input(UInt(width.W))
    val outputData = Output(UInt(width.W))
  })

  setInline(
    "FptPhysicalCutRegister.sv",
    """module FptPhysicalCutRegister #(
      |  parameter integer WIDTH = 1
      |) (
      |  input  wire                 clock,
      |  input  wire                 enable,
      |  input  wire [WIDTH-1:0]     inputData,
      |  output wire [WIDTH-1:0]     outputData
      |);
      |  (* DONT_TOUCH = "yes" *) reg [WIDTH-1:0] value;
      |  always @(posedge clock) begin
      |    if (enable)
      |      value <= inputData;
      |  end
      |  assign outputData = value;
      |endmodule
      |""".stripMargin
  )
}

/** A physically indivisible four-way read-response selector.
  *
  * Registering only replicated selector bits is insufficient: Vivado can
  * merge the following slice muxes back into one full-word cone.  Keeping the
  * selector register and its narrow data mux in this boundary makes every
  * tile an independent placement object without adding a data-cycle delay.
  */
private[fpt] final class PhysicalFourWaySelect(val width: Int)
    extends BlackBox(Map("WIDTH" -> IntParam(width)))
    with HasBlackBoxInline {
  require(width >= 1)
  override def desiredName: String = "FptPhysicalFourWaySelect"

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val enable = Input(Bool())
    val selectorInput = Input(UInt(2.W))
    val input0 = Input(UInt(width.W))
    val input1 = Input(UInt(width.W))
    val input2 = Input(UInt(width.W))
    val input3 = Input(UInt(width.W))
    val outputData = Output(UInt(width.W))
  })

  setInline(
    "FptPhysicalFourWaySelect.sv",
    """(* KEEP_HIERARCHY = "yes", DONT_TOUCH = "yes" *)
      |module FptPhysicalFourWaySelect #(
      |  parameter integer WIDTH = 1
      |) (
      |  input  wire                 clock,
      |  input  wire                 enable,
      |  input  wire [1:0]           selectorInput,
      |  input  wire [WIDTH-1:0]     input0,
      |  input  wire [WIDTH-1:0]     input1,
      |  input  wire [WIDTH-1:0]     input2,
      |  input  wire [WIDTH-1:0]     input3,
      |  output reg  [WIDTH-1:0]     outputData
      |);
      |  (* DONT_TOUCH = "yes" *) reg [1:0] selector;
      |  always @(posedge clock) begin
      |    if (enable)
      |      selector <= selectorInput;
      |  end
      |  always @* begin
      |    case (selector)
      |      2'd0: outputData = input0;
      |      2'd1: outputData = input1;
      |      2'd2: outputData = input2;
      |      2'd3: outputData = input3;
      |    endcase
      |  end
      |endmodule
      |""".stripMargin
  )
}

/** Four-deep synchronous bank used by the final External Product image.
  * Keeping the two physical implementations as distinct modules prevents
  * Vivado from rebuilding one 15,360-bit memory and lets each component live
  * close to its consumer muxes.
  */
private[fpt] abstract class FinalAccumulatorBank(
    val width: Int,
    style: String,
    addressRegisteredRead: Boolean
)
    extends BlackBox(Map("WIDTH" -> IntParam(width)))
    with HasBlackBoxInline {
  require(width >= 1)
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val writeEnable = Input(Bool())
    val writeAddress = Input(UInt(2.W))
    val inputData = Input(UInt(width.W))
    val readEnable = Input(Bool())
    val readAddress = Input(UInt(2.W))
    val outputData = Output(UInt(width.W))
  })

  protected def inline(moduleName: String): Unit = {
    val outputDeclaration = if (addressRegisteredRead) "wire" else "reg"
    val readState = if (addressRegisteredRead) "reg [1:0] readAddressCut;" else ""
    val readOperation = if (addressRegisteredRead) {
      "if (readEnable) readAddressCut <= readAddress;"
    } else {
      "if (readEnable) outputData <= memory[readAddress];"
    }
    val outputAssignment = if (addressRegisteredRead) {
      "assign outputData = memory[readAddressCut];"
    } else ""
    setInline(
      s"$moduleName.sv",
      s"""(* KEEP_HIERARCHY = "yes" *)
       |module $moduleName #(parameter integer WIDTH = 1) (
       |  input wire clock, input wire writeEnable,
       |  input wire [1:0] writeAddress, input wire [WIDTH-1:0] inputData,
       |  input wire readEnable, input wire [1:0] readAddress,
       |  output $outputDeclaration [WIDTH-1:0] outputData
       |);
       |  (* ram_style = "$style" *) reg [WIDTH-1:0] memory [0:3];
       |  $readState
       |  always @(posedge clock) begin
       |    if (writeEnable) memory[writeAddress] <= inputData;
       |    $readOperation
       |  end
       |  $outputAssignment
       |endmodule
       |""".stripMargin
    )
  }
}

private[fpt] final class FinalAccumulatorUltraBank(width: Int)
    extends FinalAccumulatorBank(width, "ultra", false) {
  override def desiredName = "FptFinalAccumulatorUltraBank"
  inline(desiredName)
}

private[fpt] final class FinalAccumulatorDistributedBank(width: Int)
    extends FinalAccumulatorBank(width, "distributed", true) {
  override def desiredName = "FptFinalAccumulatorDistributedBank"
  inline(desiredName)
}

final class GaussTwiddle(val twiddleWidth: Int) extends Bundle {
  val c = SInt(twiddleWidth.W)
  val cMinusD = SInt(twiddleWidth.W)
  val cPlusD = SInt(twiddleWidth.W)
}

private[fpt] object FixedPointBits {
  def lowSigned(value: SInt, width: Int): SInt =
    value.asUInt(width - 1, 0).asSInt

  def shiftedLowSigned(value: SInt, shift: Int, width: Int): SInt = {
    require(shift >= 0)
    value.asUInt(shift + width - 1, shift).asSInt
  }
}

/** Exact three-product complex multiplier with DSP48E2-sized real products.
  *
  * CIRCT normally lowers an `A`-by-`B` signed Chisel multiplication to an
  * unsigned `(A+B)`-by-`(A+B)` multiplication over sign-extended operands.
  * The generated SystemVerilog BlackBox below keeps the signed operand widths
  * explicit and splits the wider operand into 18-bit and signed-high chunks.
  * Each real product then maps to two DSP48E2s at the paper's 30-by-27-bit
  * format.
  *
  * The Gauss pre-additions wrap to their input widths for the third real
  * product. Overflow correction terms reconstruct the full widened sum using
  * shifts and additions, so the outputs are bit-exact with the conventional
  * four-product complex multiplication for every input bit pattern.
  */
final class ExactGaussComplexMultiply(
    val aWidth: Int,
    val bWidth: Int
) extends Module {
  require(aWidth > 27 && aWidth <= 35)
  require(bWidth >= 2 && bWidth <= 27)

  val io = IO(new Bundle {
    val a = Input(new ComplexSInt(aWidth))
    val b = Input(new ComplexSInt(bWidth))
    val productReal = Output(SInt((aWidth + bWidth + 1).W))
    val productImag = Output(SInt((aWidth + bWidth + 1).W))
  })

  private val multiplier = Module(
    new ExactGaussComplexMultiplyBlackBox(aWidth, bWidth)
  )
  multiplier.io.aReal := io.a.real
  multiplier.io.aImag := io.a.imag
  multiplier.io.bReal := io.b.real
  multiplier.io.bImag := io.b.imag
  io.productReal := multiplier.io.productReal
  io.productImag := multiplier.io.productImag
}

private final class ExactGaussComplexMultiplyBlackBox(
    aWidth: Int,
    bWidth: Int
) extends BlackBox(
      Map("A_WIDTH" -> IntParam(aWidth), "B_WIDTH" -> IntParam(bWidth))
    )
    with HasBlackBoxInline {
  override def desiredName: String = "FptExactGaussComplexMultiply"

  val io = IO(new Bundle {
    val aReal = Input(SInt(aWidth.W))
    val aImag = Input(SInt(aWidth.W))
    val bReal = Input(SInt(bWidth.W))
    val bImag = Input(SInt(bWidth.W))
    val productReal = Output(SInt((aWidth + bWidth + 1).W))
    val productImag = Output(SInt((aWidth + bWidth + 1).W))
  })

  setInline(
    "FptExactGaussComplexMultiply.sv",
    """module FptSignedSplitMultiply #(
      |  parameter integer A_WIDTH = 30,
      |  parameter integer B_WIDTH = 27
      |) (
      |  input  wire [A_WIDTH-1:0] a,
      |  input  wire [B_WIDTH-1:0] b,
      |  output wire [A_WIDTH+B_WIDTH-1:0] product
      |);
      |  localparam integer LOW_WIDTH = 18;
      |  localparam integer HIGH_WIDTH = A_WIDTH - LOW_WIDTH;
      |  localparam integer PRODUCT_WIDTH = A_WIDTH + B_WIDTH;
      |  localparam integer SUM_WIDTH = PRODUCT_WIDTH + 1;
      |
      |  wire signed [LOW_WIDTH-1:0] low = $signed(a[LOW_WIDTH-1:0]);
      |  (* use_dsp = "no" *)
      |  wire signed [HIGH_WIDTH:0] adjusted_high =
      |    $signed({a[A_WIDTH-1], a[A_WIDTH-1:LOW_WIDTH]}) +
      |    $signed({{HIGH_WIDTH{1'b0}}, a[LOW_WIDTH-1]});
      |  wire signed [B_WIDTH-1:0] signed_b = $signed(b);
      |  wire signed [LOW_WIDTH+B_WIDTH-1:0] low_product =
      |    low * signed_b;
      |  wire signed [HIGH_WIDTH+B_WIDTH:0] high_product =
      |    adjusted_high * signed_b;
      |  wire signed [SUM_WIDTH-1:0] extended_low_product =
      |    {{(HIGH_WIDTH+1){low_product[LOW_WIDTH+B_WIDTH-1]}},
      |      low_product};
      |  wire signed [SUM_WIDTH-1:0] shifted_high_product =
      |    {high_product, {LOW_WIDTH{1'b0}}};
      |  wire signed [SUM_WIDTH-1:0] full_product =
      |    extended_low_product + shifted_high_product;
      |
      |  assign product = full_product[PRODUCT_WIDTH-1:0];
      |endmodule
      |
      |module FptExactGaussComplexMultiply #(
      |  parameter integer A_WIDTH = 30,
      |  parameter integer B_WIDTH = 27
      |) (
      |  input  wire signed [A_WIDTH-1:0] aReal,
      |  input  wire signed [A_WIDTH-1:0] aImag,
      |  input  wire signed [B_WIDTH-1:0] bReal,
      |  input  wire signed [B_WIDTH-1:0] bImag,
      |  output wire signed [A_WIDTH+B_WIDTH:0] productReal,
      |  output wire signed [A_WIDTH+B_WIDTH:0] productImag
      |);
      |  localparam integer PRODUCT_WIDTH = A_WIDTH + B_WIDTH;
      |  localparam integer EXT_WIDTH = PRODUCT_WIDTH + 2;
      |
      |  wire signed [A_WIDTH:0] sum_a_full =
      |    $signed({aReal[A_WIDTH-1], aReal}) +
      |    $signed({aImag[A_WIDTH-1], aImag});
      |  wire signed [B_WIDTH:0] sum_b_full =
      |    $signed({bReal[B_WIDTH-1], bReal}) +
      |    $signed({bImag[B_WIDTH-1], bImag});
      |  wire signed [A_WIDTH-1:0] sum_a = sum_a_full[A_WIDTH-1:0];
      |  wire signed [B_WIDTH-1:0] sum_b = sum_b_full[B_WIDTH-1:0];
      |
      |  wire sum_a_positive_overflow =
      |    ~aReal[A_WIDTH-1] & ~aImag[A_WIDTH-1] & sum_a[A_WIDTH-1];
      |  wire sum_a_negative_overflow =
      |    aReal[A_WIDTH-1] & aImag[A_WIDTH-1] & ~sum_a[A_WIDTH-1];
      |  wire sum_b_positive_overflow =
      |    ~bReal[B_WIDTH-1] & ~bImag[B_WIDTH-1] & sum_b[B_WIDTH-1];
      |  wire sum_b_negative_overflow =
      |    bReal[B_WIDTH-1] & bImag[B_WIDTH-1] & ~sum_b[B_WIDTH-1];
      |
      |  wire signed [PRODUCT_WIDTH-1:0] product_ac;
      |  wire signed [PRODUCT_WIDTH-1:0] product_bd;
      |  wire signed [PRODUCT_WIDTH-1:0] product_sum;
      |  FptSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_ac (
      |    .a(aReal), .b(bReal), .product(product_ac)
      |  );
      |  FptSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_bd (
      |    .a(aImag), .b(bImag), .product(product_bd)
      |  );
      |  FptSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_sum (
      |    .a(sum_a), .b(sum_b), .product(product_sum)
      |  );
      |
      |  wire signed [EXT_WIDTH-1:0] ac_extended =
      |    {{2{product_ac[PRODUCT_WIDTH-1]}}, product_ac};
      |  wire signed [EXT_WIDTH-1:0] bd_extended =
      |    {{2{product_bd[PRODUCT_WIDTH-1]}}, product_bd};
      |  wire signed [EXT_WIDTH-1:0] sum_product_extended =
      |    {{2{product_sum[PRODUCT_WIDTH-1]}}, product_sum};
      |  wire signed [EXT_WIDTH-1:0] sum_a_extended =
      |    {{(EXT_WIDTH-A_WIDTH){sum_a[A_WIDTH-1]}}, sum_a};
      |  wire signed [EXT_WIDTH-1:0] sum_b_extended =
      |    {{(EXT_WIDTH-B_WIDTH){sum_b[B_WIDTH-1]}}, sum_b};
      |  wire signed [EXT_WIDTH-1:0] correction_a_magnitude =
      |    sum_b_extended <<< A_WIDTH;
      |  wire signed [EXT_WIDTH-1:0] correction_b_magnitude =
      |    sum_a_extended <<< B_WIDTH;
      |  wire signed [EXT_WIDTH-1:0] correction_a =
      |    sum_a_positive_overflow ? correction_a_magnitude :
      |    sum_a_negative_overflow ? -correction_a_magnitude :
      |    {EXT_WIDTH{1'b0}};
      |  wire signed [EXT_WIDTH-1:0] correction_b =
      |    sum_b_positive_overflow ? correction_b_magnitude :
      |    sum_b_negative_overflow ? -correction_b_magnitude :
      |    {EXT_WIDTH{1'b0}};
      |  wire correction_ab_positive =
      |    (sum_a_positive_overflow & sum_b_positive_overflow) |
      |    (sum_a_negative_overflow & sum_b_negative_overflow);
      |  wire correction_ab_negative =
      |    (sum_a_positive_overflow & sum_b_negative_overflow) |
      |    (sum_a_negative_overflow & sum_b_positive_overflow);
      |  wire signed [EXT_WIDTH-1:0] correction_ab_magnitude =
      |    {1'b0, 1'b1, {PRODUCT_WIDTH{1'b0}}};
      |  wire signed [EXT_WIDTH-1:0] correction_ab =
      |    correction_ab_positive ? correction_ab_magnitude :
      |    correction_ab_negative ? -correction_ab_magnitude :
      |    {EXT_WIDTH{1'b0}};
      |
      |  wire signed [EXT_WIDTH-1:0] full_sum_product =
      |    sum_product_extended + correction_a + correction_b + correction_ab;
      |  wire signed [EXT_WIDTH-1:0] full_real = ac_extended - bd_extended;
      |  wire signed [EXT_WIDTH-1:0] full_imag =
      |    full_sum_product - ac_extended - bd_extended;
      |
      |  assign productReal = full_real[PRODUCT_WIDTH:0];
      |  assign productImag = full_imag[PRODUCT_WIDTH:0];
      |endmodule
      |""".stripMargin
  )
}

/** Three-stage exact schoolbook complex multiplier for the U280 datapath.
  *
  * Each 30-by-27-bit real product is split into two DSP48E2-sized products.
  * The first stage registers the complex operands at the integration
  * boundary, the second registers the eight partial products, and the third
  * reconstructs the four full real products while performing the complex
  * add/subtract. It uses eight DSPs per complex product: more than an ideal
  * six-DSP split Gauss product, but fewer than the ten slices Vivado used
  * after mapping that product's wide overflow-correction network. It also
  * removes the unregistered BRAM-to-product path.
  */
final class PipelinedExactSchoolbookComplexMultiply(
    val aWidth: Int,
    val bWidth: Int
) extends Module {
  require(aWidth > 27 && aWidth <= 35)
  require(bWidth >= 2 && bWidth <= 27)

  val io = IO(new Bundle {
    val a = Input(new ComplexSInt(aWidth))
    val b = Input(new ComplexSInt(bWidth))
    val productReal = Output(SInt((aWidth + bWidth + 1).W))
    val productImag = Output(SInt((aWidth + bWidth + 1).W))
  })

  private val multiplier = Module(
    new PipelinedExactSchoolbookComplexMultiplyBlackBox(aWidth, bWidth)
  )
  multiplier.io.clock := clock
  multiplier.io.aReal := io.a.real
  multiplier.io.aImag := io.a.imag
  multiplier.io.bReal := io.b.real
  multiplier.io.bImag := io.b.imag
  io.productReal := multiplier.io.productReal
  io.productImag := multiplier.io.productImag
}

object PipelinedExactSchoolbookComplexMultiply {
  val latency: Int = 3
}

private final class PipelinedExactSchoolbookComplexMultiplyBlackBox(
    aWidth: Int,
    bWidth: Int
) extends BlackBox(
      Map("A_WIDTH" -> IntParam(aWidth), "B_WIDTH" -> IntParam(bWidth))
    )
    with HasBlackBoxInline {
  override def desiredName: String =
    "FptPipelinedExactSchoolbookComplexMultiply"

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val aReal = Input(SInt(aWidth.W))
    val aImag = Input(SInt(aWidth.W))
    val bReal = Input(SInt(bWidth.W))
    val bImag = Input(SInt(bWidth.W))
    val productReal = Output(SInt((aWidth + bWidth + 1).W))
    val productImag = Output(SInt((aWidth + bWidth + 1).W))
  })

  setInline(
    "FptPipelinedExactSchoolbookComplexMultiply.sv",
    """module FptPipelinedSignedSplitMultiply #(
      |  parameter integer A_WIDTH = 30,
      |  parameter integer B_WIDTH = 27
      |) (
      |  input  wire                         clock,
      |  input  wire signed [A_WIDTH-1:0]    a,
      |  input  wire signed [B_WIDTH-1:0]    b,
      |  output wire signed [A_WIDTH+B_WIDTH-1:0] product
      |);
      |  // Reserve the low DSP operand's sign bit so the radix-2^17 low limb
      |  // is non-negative.  This avoids a carry-correction adder/DSP.
      |  localparam integer LOW_WIDTH = 17;
      |  localparam integer HIGH_WIDTH = A_WIDTH - LOW_WIDTH;
      |  localparam integer PRODUCT_WIDTH = A_WIDTH + B_WIDTH;
      |  localparam integer SUM_WIDTH = PRODUCT_WIDTH + 1;
      |  localparam integer LOW_PRODUCT_WIDTH = LOW_WIDTH + 1 + B_WIDTH;
      |  localparam integer HIGH_PRODUCT_WIDTH = HIGH_WIDTH + B_WIDTH;
      |  wire signed [LOW_WIDTH:0] low =
      |    $signed({1'b0, a[LOW_WIDTH-1:0]});
      |  wire signed [HIGH_WIDTH-1:0] high =
      |    $signed(a[A_WIDTH-1:LOW_WIDTH]);
      |  wire signed [B_WIDTH-1:0] signed_b = $signed(b);
      |
      |  (* use_dsp = "yes", extract_enable = "no" *)
      |  reg signed [LOW_PRODUCT_WIDTH-1:0] low_product;
      |  (* use_dsp = "yes", extract_enable = "no" *)
      |  reg signed [HIGH_PRODUCT_WIDTH-1:0] high_product;
      |  wire signed [SUM_WIDTH-1:0] extended_low_product =
      |    {{(SUM_WIDTH-LOW_PRODUCT_WIDTH){low_product[LOW_PRODUCT_WIDTH-1]}},
      |      low_product};
      |  wire signed [SUM_WIDTH-1:0] shifted_high_product =
      |    {{(SUM_WIDTH-HIGH_PRODUCT_WIDTH-LOW_WIDTH){
      |        high_product[HIGH_PRODUCT_WIDTH-1]}},
      |      high_product, {LOW_WIDTH{1'b0}}};
      |  wire signed [SUM_WIDTH-1:0] full_product =
      |    extended_low_product + shifted_high_product;
      |  assign product = full_product[PRODUCT_WIDTH-1:0];
      |
      |  always @(posedge clock) begin
      |    low_product <= low * signed_b;
      |    high_product <= high * signed_b;
      |  end
      |endmodule
      |
      |module FptPipelinedExactSchoolbookComplexMultiply #(
      |  parameter integer A_WIDTH = 30,
      |  parameter integer B_WIDTH = 27
      |) (
      |  input  wire                              clock,
      |  input  wire signed [A_WIDTH-1:0]         aReal,
      |  input  wire signed [A_WIDTH-1:0]         aImag,
      |  input  wire signed [B_WIDTH-1:0]         bReal,
      |  input  wire signed [B_WIDTH-1:0]         bImag,
      |  output wire signed [A_WIDTH+B_WIDTH:0]   productReal,
      |  output wire signed [A_WIDTH+B_WIDTH:0]   productImag
      |);
      |  localparam integer PRODUCT_WIDTH = A_WIDTH + B_WIDTH;
      |  // These registers isolate the key-buffer and transform selection
      |  // networks from the DSP input paths in the physical accelerator.
      |  (* keep = "yes", extract_enable = "no" *)
      |  reg signed [A_WIDTH-1:0] a_real_reg;
      |  (* keep = "yes", extract_enable = "no" *)
      |  reg signed [A_WIDTH-1:0] a_imag_reg;
      |  (* keep = "yes", extract_enable = "no" *)
      |  reg signed [B_WIDTH-1:0] b_real_reg;
      |  (* keep = "yes", extract_enable = "no" *)
      |  reg signed [B_WIDTH-1:0] b_imag_reg;
      |  (* use_dsp = "no", extract_enable = "no" *)
      |  reg signed [PRODUCT_WIDTH:0] product_real_reg;
      |  (* use_dsp = "no", extract_enable = "no" *)
      |  reg signed [PRODUCT_WIDTH:0] product_imag_reg;
      |  assign productReal = product_real_reg;
      |  assign productImag = product_imag_reg;
      |
      |  wire signed [PRODUCT_WIDTH-1:0] product_ac;
      |  wire signed [PRODUCT_WIDTH-1:0] product_bd;
      |  wire signed [PRODUCT_WIDTH-1:0] product_ad;
      |  wire signed [PRODUCT_WIDTH-1:0] product_bc;
      |  FptPipelinedSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_ac (
      |    .clock(clock), .a(a_real_reg), .b(b_real_reg), .product(product_ac)
      |  );
      |  FptPipelinedSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_bd (
      |    .clock(clock), .a(a_imag_reg), .b(b_imag_reg), .product(product_bd)
      |  );
      |  FptPipelinedSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_ad (
      |    .clock(clock), .a(a_real_reg), .b(b_imag_reg), .product(product_ad)
      |  );
      |  FptPipelinedSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_bc (
      |    .clock(clock), .a(a_imag_reg), .b(b_real_reg), .product(product_bc)
      |  );
      |
      |  wire signed [PRODUCT_WIDTH:0] ac_extended =
      |    {product_ac[PRODUCT_WIDTH-1], product_ac};
      |  wire signed [PRODUCT_WIDTH:0] bd_extended =
      |    {product_bd[PRODUCT_WIDTH-1], product_bd};
      |  wire signed [PRODUCT_WIDTH:0] ad_extended =
      |    {product_ad[PRODUCT_WIDTH-1], product_ad};
      |  wire signed [PRODUCT_WIDTH:0] bc_extended =
      |    {product_bc[PRODUCT_WIDTH-1], product_bc};
      |
      |  always @(posedge clock) begin
      |    a_real_reg <= aReal;
      |    a_imag_reg <= aImag;
      |    b_real_reg <= bReal;
      |    b_imag_reg <= bImag;
      |    product_real_reg <= ac_extended - bd_extended;
      |    product_imag_reg <= ad_extended + bc_extended;
      |  end
      |endmodule
      |""".stripMargin
  )
}

/** Three-stage, bit-exact quantized Gauss multiplier for External Product.
  *
  * External Product only consumes a fixed slice of each full complex product.
  * This implementation reconstructs the exact wrapped Gauss result, including
  * both pre-adder overflow corrections, and registers that slice directly.
  * Three split real products use six DSP48E2s instead of the schoolbook path's
  * eight while retaining the same three-cycle latency and one-result-per-cycle
  * throughput.
  */
final class PipelinedQuantizedGaussComplexMultiply(
    val aWidth: Int,
    val bWidth: Int,
    val productShift: Int,
    val outputWidth: Int
) extends Module {
  require(aWidth > 27 && aWidth <= 35)
  require(bWidth >= 2 && bWidth <= 27)
  require(productShift >= 0)
  require(outputWidth >= 1)
  require(productShift + outputWidth <= aWidth + bWidth + 1)

  val io = IO(new Bundle {
    val a = Input(new ComplexSInt(aWidth))
    val b = Input(new ComplexSInt(bWidth))
    val productReal = Output(SInt(outputWidth.W))
    val productImag = Output(SInt(outputWidth.W))
  })

  private val multiplier = Module(
    new PipelinedQuantizedGaussComplexMultiplyBlackBox(
      aWidth,
      bWidth,
      productShift,
      outputWidth
    )
  )
  multiplier.io.clock := clock
  multiplier.io.aReal := io.a.real
  multiplier.io.aImag := io.a.imag
  multiplier.io.bReal := io.b.real
  multiplier.io.bImag := io.b.imag
  io.productReal := multiplier.io.productReal
  io.productImag := multiplier.io.productImag
}

object PipelinedQuantizedGaussComplexMultiply {
  val latency: Int = 3
}

private final class PipelinedQuantizedGaussComplexMultiplyBlackBox(
    aWidth: Int,
    bWidth: Int,
    productShift: Int,
    outputWidth: Int
) extends BlackBox(
      Map(
        "A_WIDTH" -> IntParam(aWidth),
        "B_WIDTH" -> IntParam(bWidth),
        "PRODUCT_SHIFT" -> IntParam(productShift),
        "OUTPUT_WIDTH" -> IntParam(outputWidth)
      )
    )
    with HasBlackBoxInline {
  override def desiredName: String =
    "FptPipelinedQuantizedGaussComplexMultiply"

  val io = IO(new Bundle {
    val clock = Input(Clock())
    val aReal = Input(SInt(aWidth.W))
    val aImag = Input(SInt(aWidth.W))
    val bReal = Input(SInt(bWidth.W))
    val bImag = Input(SInt(bWidth.W))
    val productReal = Output(SInt(outputWidth.W))
    val productImag = Output(SInt(outputWidth.W))
  })

  setInline(
    "FptPipelinedQuantizedGaussComplexMultiply.sv",
    """module FptQuantizedGaussSignedSplitMultiply #(
      |  parameter integer A_WIDTH = 30,
      |  parameter integer B_WIDTH = 27
      |) (
      |  input  wire                              clock,
      |  input  wire signed [A_WIDTH-1:0]         a,
      |  input  wire signed [B_WIDTH-1:0]         b,
      |  output wire signed [A_WIDTH+B_WIDTH-1:0] product
      |);
      |  localparam integer LOW_WIDTH = 17;
      |  localparam integer HIGH_WIDTH = A_WIDTH - LOW_WIDTH;
      |  localparam integer PRODUCT_WIDTH = A_WIDTH + B_WIDTH;
      |  localparam integer SUM_WIDTH = PRODUCT_WIDTH + 1;
      |  localparam integer LOW_PRODUCT_WIDTH = LOW_WIDTH + 1 + B_WIDTH;
      |  localparam integer HIGH_PRODUCT_WIDTH = HIGH_WIDTH + B_WIDTH;
      |  wire signed [LOW_WIDTH:0] low =
      |    $signed({1'b0, a[LOW_WIDTH-1:0]});
      |  wire signed [HIGH_WIDTH-1:0] high =
      |    $signed(a[A_WIDTH-1:LOW_WIDTH]);
      |  wire signed [B_WIDTH-1:0] signed_b = $signed(b);
      |
      |  (* use_dsp = "yes", extract_enable = "no" *)
      |  reg signed [LOW_PRODUCT_WIDTH-1:0] low_product;
      |  (* use_dsp = "yes", extract_enable = "no" *)
      |  reg signed [HIGH_PRODUCT_WIDTH-1:0] high_product;
      |  wire signed [SUM_WIDTH-1:0] extended_low_product =
      |    {{(SUM_WIDTH-LOW_PRODUCT_WIDTH){low_product[LOW_PRODUCT_WIDTH-1]}},
      |      low_product};
      |  wire signed [SUM_WIDTH-1:0] shifted_high_product =
      |    {{(SUM_WIDTH-HIGH_PRODUCT_WIDTH-LOW_WIDTH){
      |        high_product[HIGH_PRODUCT_WIDTH-1]}},
      |      high_product, {LOW_WIDTH{1'b0}}};
      |  wire signed [SUM_WIDTH-1:0] full_product =
      |    extended_low_product + shifted_high_product;
      |  assign product = full_product[PRODUCT_WIDTH-1:0];
      |
      |  always @(posedge clock) begin
      |    low_product <= low * signed_b;
      |    high_product <= high * signed_b;
      |  end
      |endmodule
      |
      |module FptPipelinedQuantizedGaussComplexMultiply #(
      |  parameter integer A_WIDTH = 30,
      |  parameter integer B_WIDTH = 27,
      |  parameter integer PRODUCT_SHIFT = 28,
      |  parameter integer OUTPUT_WIDTH = 30
      |) (
      |  input  wire                              clock,
      |  input  wire signed [A_WIDTH-1:0]         aReal,
      |  input  wire signed [A_WIDTH-1:0]         aImag,
      |  input  wire signed [B_WIDTH-1:0]         bReal,
      |  input  wire signed [B_WIDTH-1:0]         bImag,
      |  output wire signed [OUTPUT_WIDTH-1:0]    productReal,
      |  output wire signed [OUTPUT_WIDTH-1:0]    productImag
      |);
      |  localparam integer PRODUCT_WIDTH = A_WIDTH + B_WIDTH;
      |  localparam integer FULL_WIDTH = PRODUCT_WIDTH + 1;
      |
      |  // Stage one isolates the transform/key memories from the DSP inputs.
      |  (* keep = "yes", extract_enable = "no" *)
      |  reg signed [A_WIDTH-1:0] a_real_reg;
      |  (* keep = "yes", extract_enable = "no" *)
      |  reg signed [A_WIDTH-1:0] a_imag_reg;
      |  (* keep = "yes", extract_enable = "no" *)
      |  reg signed [B_WIDTH-1:0] b_real_reg;
      |  (* keep = "yes", extract_enable = "no" *)
      |  reg signed [B_WIDTH-1:0] b_imag_reg;
      |
      |  wire signed [A_WIDTH:0] sum_a_full =
      |    $signed({a_real_reg[A_WIDTH-1], a_real_reg}) +
      |    $signed({a_imag_reg[A_WIDTH-1], a_imag_reg});
      |  wire signed [B_WIDTH:0] sum_b_full =
      |    $signed({b_real_reg[B_WIDTH-1], b_real_reg}) +
      |    $signed({b_imag_reg[B_WIDTH-1], b_imag_reg});
      |  wire signed [A_WIDTH-1:0] sum_a = sum_a_full[A_WIDTH-1:0];
      |  wire signed [B_WIDTH-1:0] sum_b = sum_b_full[B_WIDTH-1:0];
      |  wire sum_a_positive_overflow =
      |    ~a_real_reg[A_WIDTH-1] & ~a_imag_reg[A_WIDTH-1] & sum_a[A_WIDTH-1];
      |  wire sum_a_negative_overflow =
      |    a_real_reg[A_WIDTH-1] & a_imag_reg[A_WIDTH-1] & ~sum_a[A_WIDTH-1];
      |  wire sum_b_positive_overflow =
      |    ~b_real_reg[B_WIDTH-1] & ~b_imag_reg[B_WIDTH-1] & sum_b[B_WIDTH-1];
      |  wire sum_b_negative_overflow =
      |    b_real_reg[B_WIDTH-1] & b_imag_reg[B_WIDTH-1] & ~sum_b[B_WIDTH-1];
      |
      |  wire signed [PRODUCT_WIDTH-1:0] product_ac;
      |  wire signed [PRODUCT_WIDTH-1:0] product_bd;
      |  wire signed [PRODUCT_WIDTH-1:0] product_sum;
      |  FptQuantizedGaussSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_ac (
      |    .clock(clock), .a(a_real_reg), .b(b_real_reg), .product(product_ac)
      |  );
      |  FptQuantizedGaussSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_bd (
      |    .clock(clock), .a(a_imag_reg), .b(b_imag_reg), .product(product_bd)
      |  );
      |  FptQuantizedGaussSignedSplitMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_sum (
      |    .clock(clock), .a(sum_a), .b(sum_b), .product(product_sum)
      |  );
      |
      |  // Stage two carries the wrapped pre-adders and their correction
      |  // signs alongside the six registered DSP partial products.
      |  reg signed [A_WIDTH-1:0] sum_a_reg;
      |  reg signed [B_WIDTH-1:0] sum_b_reg;
      |  reg sum_a_positive_overflow_reg;
      |  reg sum_a_negative_overflow_reg;
      |  reg sum_b_positive_overflow_reg;
      |  reg sum_b_negative_overflow_reg;
      |
      |  wire signed [FULL_WIDTH-1:0] ac_extended =
      |    {product_ac[PRODUCT_WIDTH-1], product_ac};
      |  wire signed [FULL_WIDTH-1:0] bd_extended =
      |    {product_bd[PRODUCT_WIDTH-1], product_bd};
      |  wire signed [FULL_WIDTH-1:0] sum_product_extended =
      |    {product_sum[PRODUCT_WIDTH-1], product_sum};
      |  wire signed [FULL_WIDTH-1:0] sum_a_extended =
      |    {{(FULL_WIDTH-A_WIDTH){sum_a_reg[A_WIDTH-1]}}, sum_a_reg};
      |  wire signed [FULL_WIDTH-1:0] sum_b_extended =
      |    {{(FULL_WIDTH-B_WIDTH){sum_b_reg[B_WIDTH-1]}}, sum_b_reg};
      |  wire signed [FULL_WIDTH-1:0] correction_a_magnitude =
      |    sum_b_extended <<< A_WIDTH;
      |  wire signed [FULL_WIDTH-1:0] correction_b_magnitude =
      |    sum_a_extended <<< B_WIDTH;
      |  wire signed [FULL_WIDTH-1:0] correction_a =
      |    sum_a_positive_overflow_reg ? correction_a_magnitude :
      |    sum_a_negative_overflow_reg ? -correction_a_magnitude :
      |    {FULL_WIDTH{1'b0}};
      |  wire signed [FULL_WIDTH-1:0] correction_b =
      |    sum_b_positive_overflow_reg ? correction_b_magnitude :
      |    sum_b_negative_overflow_reg ? -correction_b_magnitude :
      |    {FULL_WIDTH{1'b0}};
      |  wire correction_ab =
      |    (sum_a_positive_overflow_reg | sum_a_negative_overflow_reg) &
      |    (sum_b_positive_overflow_reg | sum_b_negative_overflow_reg);
      |  wire signed [FULL_WIDTH-1:0] correction_ab_value =
      |    {correction_ab, {PRODUCT_WIDTH{1'b0}}};
      |
      |  (* use_dsp = "no" *)
      |  wire signed [FULL_WIDTH-1:0] full_real = ac_extended - bd_extended;
      |  (* use_dsp = "no" *)
      |  wire signed [FULL_WIDTH-1:0] full_imag =
      |    sum_product_extended - ac_extended - bd_extended +
      |    correction_a + correction_b + correction_ab_value;
      |
      |  // Stage three captures only the fixed-point bits consumed by the
      |  // accumulator, avoiding unused post-multiply result buses.
      |  (* use_dsp = "no", extract_enable = "no" *)
      |  reg signed [OUTPUT_WIDTH-1:0] product_real_reg;
      |  (* use_dsp = "no", extract_enable = "no" *)
      |  reg signed [OUTPUT_WIDTH-1:0] product_imag_reg;
      |  assign productReal = product_real_reg;
      |  assign productImag = product_imag_reg;
      |
      |  always @(posedge clock) begin
      |    a_real_reg <= aReal;
      |    a_imag_reg <= aImag;
      |    b_real_reg <= bReal;
      |    b_imag_reg <= bImag;
      |    sum_a_reg <= sum_a;
      |    sum_b_reg <= sum_b;
      |    sum_a_positive_overflow_reg <= sum_a_positive_overflow;
      |    sum_a_negative_overflow_reg <= sum_a_negative_overflow;
      |    sum_b_positive_overflow_reg <= sum_b_positive_overflow;
      |    sum_b_negative_overflow_reg <= sum_b_negative_overflow;
      |    product_real_reg <= full_real[PRODUCT_SHIFT +: OUTPUT_WIDTH];
      |    product_imag_reg <= full_imag[PRODUCT_SHIFT +: OUTPUT_WIDTH];
      |  end
      |endmodule
      |""".stripMargin
  )
}

/** Exact Gauss complex multiplier for the wider TFHEpp hardware profile.
  *
  * Each real product decomposes its operands into unsigned low limbs and
  * signed high limbs. The 26-bit and 17-bit limb radices leave one leading
  * zero bit for the unsigned limbs, so all four partial products are signed
  * 27-by-18-bit DSP48E2 multiplies. Widening the Gauss pre-additions avoids
  * the overflow-correction network needed by the paper-width specialization.
  */
final class ExactGaussTwoLimbComplexMultiply(
    val aWidth: Int,
    val bWidth: Int
) extends Module {
  require(aWidth >= 36 && aWidth <= 51)
  require(bWidth >= 28 && bWidth <= 33)

  val io = IO(new Bundle {
    val a = Input(new ComplexSInt(aWidth))
    val b = Input(new ComplexSInt(bWidth))
    val productReal = Output(SInt((aWidth + bWidth + 1).W))
    val productImag = Output(SInt((aWidth + bWidth + 1).W))
  })

  private val multiplier = Module(
    new ExactGaussTwoLimbComplexMultiplyBlackBox(aWidth, bWidth)
  )
  multiplier.io.aReal := io.a.real
  multiplier.io.aImag := io.a.imag
  multiplier.io.bReal := io.b.real
  multiplier.io.bImag := io.b.imag
  io.productReal := multiplier.io.productReal
  io.productImag := multiplier.io.productImag
}

private final class ExactGaussTwoLimbComplexMultiplyBlackBox(
    aWidth: Int,
    bWidth: Int
) extends BlackBox(
      Map("A_WIDTH" -> IntParam(aWidth), "B_WIDTH" -> IntParam(bWidth))
    )
    with HasBlackBoxInline {
  override def desiredName: String = "FptExactGaussTwoLimbComplexMultiply"

  val io = IO(new Bundle {
    val aReal = Input(SInt(aWidth.W))
    val aImag = Input(SInt(aWidth.W))
    val bReal = Input(SInt(bWidth.W))
    val bImag = Input(SInt(bWidth.W))
    val productReal = Output(SInt((aWidth + bWidth + 1).W))
    val productImag = Output(SInt((aWidth + bWidth + 1).W))
  })

  setInline(
    "FptExactGaussTwoLimbComplexMultiply.sv",
    """module FptSignedTwoLimbMultiply #(
      |  parameter integer A_WIDTH = 46,
      |  parameter integer B_WIDTH = 29
      |) (
      |  input  wire signed [A_WIDTH-1:0] a,
      |  input  wire signed [B_WIDTH-1:0] b,
      |  output wire signed [A_WIDTH+B_WIDTH-1:0] product
      |);
      |  localparam integer A_LOW_WIDTH = 26;
      |  localparam integer B_LOW_WIDTH = 17;
      |  localparam integer A_DSP_WIDTH = 27;
      |  localparam integer B_DSP_WIDTH = 18;
      |  localparam integer PARTIAL_WIDTH = A_DSP_WIDTH + B_DSP_WIDTH;
      |  localparam integer PRODUCT_WIDTH = A_WIDTH + B_WIDTH;
      |
      |  wire signed [A_DSP_WIDTH-1:0] a_low =
      |    $signed({1'b0, a[A_LOW_WIDTH-1:0]});
      |  wire signed [A_DSP_WIDTH-1:0] a_high =
      |    $signed({{(A_DSP_WIDTH-(A_WIDTH-A_LOW_WIDTH)){
      |      a[A_WIDTH-1]}}, a[A_WIDTH-1:A_LOW_WIDTH]});
      |  wire signed [B_DSP_WIDTH-1:0] b_low =
      |    $signed({1'b0, b[B_LOW_WIDTH-1:0]});
      |  wire signed [B_DSP_WIDTH-1:0] b_high =
      |    $signed({{(B_DSP_WIDTH-(B_WIDTH-B_LOW_WIDTH)){
      |      b[B_WIDTH-1]}}, b[B_WIDTH-1:B_LOW_WIDTH]});
      |
      |  (* use_dsp = "yes" *)
      |  wire signed [PARTIAL_WIDTH-1:0] partial_low_low = a_low * b_low;
      |  (* use_dsp = "yes" *)
      |  wire signed [PARTIAL_WIDTH-1:0] partial_low_high = a_low * b_high;
      |  (* use_dsp = "yes" *)
      |  wire signed [PARTIAL_WIDTH-1:0] partial_high_low = a_high * b_low;
      |  (* use_dsp = "yes" *)
      |  wire signed [PARTIAL_WIDTH-1:0] partial_high_high = a_high * b_high;
      |
      |  wire signed [PRODUCT_WIDTH-1:0] extended_low_low =
      |    {{(PRODUCT_WIDTH-PARTIAL_WIDTH){partial_low_low[
      |      PARTIAL_WIDTH-1]}}, partial_low_low};
      |  wire signed [PRODUCT_WIDTH-1:0] extended_low_high =
      |    {{(PRODUCT_WIDTH-PARTIAL_WIDTH){partial_low_high[
      |      PARTIAL_WIDTH-1]}}, partial_low_high};
      |  wire signed [PRODUCT_WIDTH-1:0] extended_high_low =
      |    {{(PRODUCT_WIDTH-PARTIAL_WIDTH){partial_high_low[
      |      PARTIAL_WIDTH-1]}}, partial_high_low};
      |  wire signed [PRODUCT_WIDTH-1:0] extended_high_high =
      |    {{(PRODUCT_WIDTH-PARTIAL_WIDTH){partial_high_high[
      |      PARTIAL_WIDTH-1]}}, partial_high_high};
      |
      |  assign product = extended_low_low +
      |    (extended_low_high <<< B_LOW_WIDTH) +
      |    (extended_high_low <<< A_LOW_WIDTH) +
      |    (extended_high_high <<< (A_LOW_WIDTH+B_LOW_WIDTH));
      |endmodule
      |
      |module FptExactGaussTwoLimbComplexMultiply #(
      |  parameter integer A_WIDTH = 46,
      |  parameter integer B_WIDTH = 29
      |) (
      |  input  wire signed [A_WIDTH-1:0] aReal,
      |  input  wire signed [A_WIDTH-1:0] aImag,
      |  input  wire signed [B_WIDTH-1:0] bReal,
      |  input  wire signed [B_WIDTH-1:0] bImag,
      |  output wire signed [A_WIDTH+B_WIDTH:0] productReal,
      |  output wire signed [A_WIDTH+B_WIDTH:0] productImag
      |);
      |  localparam integer PRODUCT_WIDTH = A_WIDTH + B_WIDTH;
      |  localparam integer EXT_WIDTH = PRODUCT_WIDTH + 2;
      |
      |  wire signed [A_WIDTH:0] sum_a =
      |    $signed({aReal[A_WIDTH-1], aReal}) +
      |    $signed({aImag[A_WIDTH-1], aImag});
      |  wire signed [B_WIDTH:0] sum_b =
      |    $signed({bReal[B_WIDTH-1], bReal}) +
      |    $signed({bImag[B_WIDTH-1], bImag});
      |  wire signed [PRODUCT_WIDTH-1:0] product_ac;
      |  wire signed [PRODUCT_WIDTH-1:0] product_bd;
      |  wire signed [EXT_WIDTH-1:0] product_sum;
      |
      |  FptSignedTwoLimbMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_ac (
      |    .a(aReal), .b(bReal), .product(product_ac)
      |  );
      |  FptSignedTwoLimbMultiply #(
      |    .A_WIDTH(A_WIDTH), .B_WIDTH(B_WIDTH)
      |  ) multiply_bd (
      |    .a(aImag), .b(bImag), .product(product_bd)
      |  );
      |  FptSignedTwoLimbMultiply #(
      |    .A_WIDTH(A_WIDTH+1), .B_WIDTH(B_WIDTH+1)
      |  ) multiply_sum (
      |    .a(sum_a), .b(sum_b), .product(product_sum)
      |  );
      |
      |  wire signed [EXT_WIDTH-1:0] ac_extended =
      |    {{2{product_ac[PRODUCT_WIDTH-1]}}, product_ac};
      |  wire signed [EXT_WIDTH-1:0] bd_extended =
      |    {{2{product_bd[PRODUCT_WIDTH-1]}}, product_bd};
      |  wire signed [EXT_WIDTH-1:0] full_real = ac_extended - bd_extended;
      |  wire signed [EXT_WIDTH-1:0] full_imag =
      |    product_sum - ac_extended - bd_extended;
      |
      |  assign productReal = full_real[PRODUCT_WIDTH:0];
      |  assign productImag = full_imag[PRODUCT_WIDTH:0];
      |endmodule
      |""".stripMargin
  )
}

/** Equation (6) from the FPT paper, with wrap and product truncation matching
  * the C++ reference. This is combinational so a caller can place registers at
  * architecture-specific boundaries.
  */
final class GaussMultiply(
    val dataWidth: Int,
    val twiddleWidth: Int,
    val twiddleFractionalBits: Int
) extends Module {
  require(dataWidth >= 2)
  require(twiddleWidth >= 2)
  require(twiddleFractionalBits >= 0)
  require(twiddleFractionalBits + dataWidth <= dataWidth + twiddleWidth)

  val io = IO(new Bundle {
    val value = Input(new ComplexSInt(dataWidth))
    val twiddle = Input(new GaussTwiddle(twiddleWidth))
    val result = Output(new ComplexSInt(dataWidth))
  })

  val aMinusB = Wire(SInt(dataWidth.W))
  aMinusB := io.value.real - io.value.imag

  val productZ = aMinusB * io.twiddle.c
  val productX = io.value.imag * io.twiddle.cMinusD
  val productY = io.value.real * io.twiddle.cPlusD

  val z = FixedPointBits.shiftedLowSigned(
    productZ, twiddleFractionalBits, dataWidth
  )
  val xPart = FixedPointBits.shiftedLowSigned(
    productX, twiddleFractionalBits, dataWidth
  )
  val yPart = FixedPointBits.shiftedLowSigned(
    productY, twiddleFractionalBits, dataWidth
  )

  io.result.real := FixedPointBits.lowSigned(xPart + z, dataWidth)
  io.result.imag := FixedPointBits.lowSigned(yPart - z, dataWidth)
}

/** Registered radix-2 butterfly. The scale control performs an arithmetic
  * divide-by-two after the widened add/subtract and before wrapping.
  */
final class FptButterfly(
    val dataWidth: Int,
    val twiddleWidth: Int,
    val twiddleFractionalBits: Int
) extends Module {
  val io = IO(new Bundle {
    val validIn = Input(Bool())
    val scale = Input(Bool())
    val even = Input(new ComplexSInt(dataWidth))
    val odd = Input(new ComplexSInt(dataWidth))
    val twiddle = Input(new GaussTwiddle(twiddleWidth))
    val validOut = Output(Bool())
    val upper = Output(new ComplexSInt(dataWidth))
    val lower = Output(new ComplexSInt(dataWidth))
  })

  val multiplier = Module(
    new GaussMultiply(dataWidth, twiddleWidth, twiddleFractionalBits)
  )
  multiplier.io.value := io.odd
  multiplier.io.twiddle := io.twiddle

  def combine(lhs: SInt, rhs: SInt, subtract: Boolean): SInt = {
    val wide = if (subtract) lhs -& rhs else lhs +& rhs
    val selected = Mux(io.scale, (wide >> 1).asSInt, wide)
    FixedPointBits.lowSigned(selected, dataWidth)
  }

  val upperReal = combine(io.even.real, multiplier.io.result.real, false)
  val upperImag = combine(io.even.imag, multiplier.io.result.imag, false)
  val lowerReal = combine(io.even.real, multiplier.io.result.real, true)
  val lowerImag = combine(io.even.imag, multiplier.io.result.imag, true)

  io.validOut := RegNext(io.validIn, false.B)
  val upperReg = Reg(new ComplexSInt(dataWidth))
  val lowerReg = Reg(new ComplexSInt(dataWidth))
  when(io.validIn) {
    upperReg.real := upperReal
    upperReg.imag := upperImag
    lowerReg.real := lowerReal
    lowerReg.imag := lowerImag
  }
  io.upper := upperReg
  io.lower := lowerReg
}

/** Mixed-format complex MAC for the frequency-domain External Product. Four
  * real products are combined at full precision, requantized once, and added
  * to a wrapped accumulator.
  */
final class ComplexMac(
    val aWidth: Int,
    val aFractionalBits: Int,
    val bWidth: Int,
    val bFractionalBits: Int,
    val accumulatorWidth: Int,
    val accumulatorFractionalBits: Int
) extends Module {
  private val productFractionalBits = aFractionalBits + bFractionalBits
  private val shift = productFractionalBits - accumulatorFractionalBits
  require(shift >= 0)

  val io = IO(new Bundle {
    val validIn = Input(Bool())
    val a = Input(new ComplexSInt(aWidth))
    val b = Input(new ComplexSInt(bWidth))
    val accumulator = Input(new ComplexSInt(accumulatorWidth))
    val validOut = Output(Bool())
    val result = Output(new ComplexSInt(accumulatorWidth))
  })

  val ac = io.a.real * io.b.real
  val bd = io.a.imag * io.b.imag
  val ad = io.a.real * io.b.imag
  val bc = io.a.imag * io.b.real
  val productReal = ac -& bd
  val productImag = ad +& bc
  val quantizedReal = FixedPointBits.shiftedLowSigned(
    productReal, shift, accumulatorWidth
  )
  val quantizedImag = FixedPointBits.shiftedLowSigned(
    productImag, shift, accumulatorWidth
  )
  val accumulatedReal = FixedPointBits.lowSigned(
    io.accumulator.real + quantizedReal, accumulatorWidth
  )
  val accumulatedImag = FixedPointBits.lowSigned(
    io.accumulator.imag + quantizedImag, accumulatorWidth
  )

  io.validOut := RegNext(io.validIn, false.B)
  val resultReg = Reg(new ComplexSInt(accumulatorWidth))
  when(io.validIn) {
    resultReg.real := accumulatedReal
    resultReg.imag := accumulatedImag
  }
  io.result := resultReg
}
