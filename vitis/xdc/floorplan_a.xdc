# Resource-balanced paper-width orientation.  The complete coefficient module
# follows the inverse accumulator into SLR0, its existing physical input cut is
# the relay in SLR1, and the complete forward transform occupies SLR2.  Keeping
# the External Product and key-response queues in SLR1 produces two explicit,
# registered wide streams at each SLR seam without splitting a replicated
# memory or an FFT stage.
set fpt_root controller/core/engine/blindRotate/blindRotate/cmux
set_property USER_SLR_ASSIGNMENT SLR2 [get_cells [list \
  ${fpt_root}/forward ${fpt_root}/coefficients \
  ${fpt_root}/componentJoin ${fpt_root}/inverseOutputDestinationRelay]]
set_property USER_SLR_ASSIGNMENT SLR1 [get_cells [list \
  ${fpt_root}/external ${fpt_root}/forwardInputBoundary \
  ${fpt_root}/pendingRequests ${fpt_root}/forwardTags \
  ${fpt_root}/inverseTags \
  ${fpt_root}/externalOutputPipeline ${fpt_root}/inverseBoundary \
  ${fpt_root}/inverseOutputMiddleRelay \
  controller/core/engine/keyBuffer controller/core/engine/keyReadRequests \
  controller/core/engine/blindRotate/sampleExtract]]
set_property USER_SLR_ASSIGNMENT SLR0 [get_cells [list \
  ${fpt_root}/inverse]]
set_property USER_SLR_ASSIGNMENT SLR0 [get_cells [list control controller/sequencer \
  input_datamover key_low_datamover key_high_datamover \
  key_low1_datamover key_high1_datamover output_datamover]]

# The inverse queue is deliberately kept with the SLR1 external product.  Its
# tail registers are the only wide SLR1 -> SLR0 boundary; putting the queue in
# SLR0 merely moves an unregistered 7680-bit input bus across the boundary.
set inverse_sll_registers [get_cells -hierarchical -filter \
  {NAME =~ */inverseOutput*Relay/payloadBoundary/value_reg* && IS_SEQUENTIAL}]
set_property USER_SLL_REG TRUE $inverse_sll_registers
