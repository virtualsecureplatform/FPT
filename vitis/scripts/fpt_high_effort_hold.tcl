# Apply before route_design when building the buffered-output U280 image.
# Vivado 2023.2 recommended the global hold iteration for the HBM Laguna path.
set_param route.enableGlobalHoldIter 1
if {[get_param route.enableGlobalHoldIter] ne "1"} {
  error "high-effort global hold route setting did not take effect"
}
puts "FPT_HIGH_EFFORT_HOLD_ROUTE_ENABLED"
