# Shell-headroom candidate: inverse/DataMovers SLR0, middle SLR1, forward SLR2.
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
  input_datamover key_low_datamover key_high_datamover output_datamover]]

set forward_sll_registers [get_cells -hierarchical \
  -regexp {^.*/forwardInputBoundary/(outputPayload_)?payload/value_reg.*$}]
set_property USER_SLL_REG TRUE $forward_sll_registers

# The inverse queue tail captures the SLR1 external-product output in SLR0.
set inverse_sll_registers [get_cells -hierarchical \
  -regexp {^.*/inverseBoundary/tail_input_[0-9]+_(real|imag)_reg.*$}]
set_property USER_SLL_REG TRUE $inverse_sll_registers
