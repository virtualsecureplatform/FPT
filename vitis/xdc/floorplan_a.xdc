# Paper-width orientation selected from the failed-route resource placement:
# keep the complete forward transform in SLR2, the coefficient frontend and
# external product in SLR1, and the complete inverse transform in SLR0.  The
# unconstrained placer chose this orientation but spilled both FFTs into SLR1
# while trying to follow the former opposite assignments.  Matching the
# assignments to the resource-feasible orientation keeps the registered wide
# boundaries on adjacent SLRs and leaves SLR1 for the stateful CMUX datapath.
set fpt_root controller/core/engine/blindRotate/blindRotate/cmux
set_property USER_SLR_ASSIGNMENT SLR2 [get_cells [list \
  ${fpt_root}/forward ${fpt_root}/forwardInputBoundary]]
set_property USER_SLR_ASSIGNMENT SLR1 [get_cells [list \
  ${fpt_root}/coefficients ${fpt_root}/external \
  ${fpt_root}/pendingRequests ${fpt_root}/forwardTags \
  ${fpt_root}/inverseTags \
  ${fpt_root}/externalOutputPipeline \
  controller/core/engine/keyBuffer controller/core/engine/keyReadRequests \
  controller/core/engine/blindRotate/sampleExtract]]
set_property USER_SLR_ASSIGNMENT SLR0 [get_cells [list \
  ${fpt_root}/inverse ${fpt_root}/inverseBoundary]]
set_property USER_SLR_ASSIGNMENT SLR0 [get_cells [list control controller/sequencer \
  input_datamover key_low_datamover key_high_datamover \
  key_low1_datamover key_high1_datamover output_datamover]]

set forward_sll_registers [get_cells -hierarchical \
  -regexp {^.*/forwardInputBoundary/(outputPayload_)?payload/value_reg.*$}]
set_property USER_SLL_REG TRUE $forward_sll_registers

# The inverse queue tail captures the SLR1 external-product output in SLR2.
set inverse_sll_registers [get_cells -hierarchical \
  -regexp {^.*/inverseBoundary/tail_input_[0-9]+_(real|imag)_reg.*$}]
set_property USER_SLL_REG TRUE $inverse_sll_registers
