#!/usr/bin/env bash
set -euo pipefail

if [[ $# == 0 ]]; then
    echo "usage: $0 METRICS_TSV..." >&2
    exit 2
fi

for metrics_file in "$@"; do
    if [[ ! -s $metrics_file ]]; then
        echo "U280 metrics file is missing or empty: $metrics_file" >&2
        exit 1
    fi

    awk -F '\t' -v source="$metrics_file" '
        function reject(message) {
            printf "Unacceptable U280 metrics %s: %s\n", source, message \
                > "/dev/stderr"
            invalid = 1
        }
        function require_metric(metric) {
            if (!(metric in value)) {
                reject("missing " metric)
            }
        }
        function require_nonnegative_integer(metric) {
            require_metric(metric)
            if ((metric in value) && value[metric] !~ /^[0-9]+$/) {
                reject(metric " is not a nonnegative integer: " value[metric])
            }
        }
        NR == 1 {
            if ($1 != "metric" || $2 != "value" || NF != 2) {
                reject("invalid header")
            }
            next
        }
        {
            if (NF != 2 || $1 == "") {
                reject("invalid row " NR)
                next
            }
            if ($1 in value) {
                reject("duplicate metric " $1)
            }
            value[$1] = $2
        }
        END {
            require_metric("clock_period_ns")
            require_metric("implemented_clock_period_ns")
            if (("clock_period_ns" in value) && \
                value["clock_period_ns"] !~ /^[0-9]+([.][0-9]+)?$/) {
                reject("invalid requested clock period")
            }
            if (("implemented_clock_period_ns" in value) && \
                value["implemented_clock_period_ns"] !~ /^[0-9]+([.][0-9]+)?$/) {
                reject("invalid implemented clock period")
            }
            if (("clock_period_ns" in value) && \
                ("implemented_clock_period_ns" in value) && \
                value["clock_period_ns"] ~ /^[0-9]+([.][0-9]+)?$/ && \
                value["implemented_clock_period_ns"] ~ /^[0-9]+([.][0-9]+)?$/) {
                difference = value["clock_period_ns"] - \
                    value["implemented_clock_period_ns"]
                if (difference < 0) difference = -difference
                if (difference > 0.0005) {
                    reject("requested and implemented clock periods differ")
                }
            }

            split("route_fully_routed route_errors route_unrouted_nets route_partial_nets route_unplaced_nets route_gap_nets route_conflict_nets route_antenna_nets route_nodriver_nets drc_violations drc_fatal drc_error drc_critical_warning drc_warning drc_advisory drc_unclassified", required, " ")
            for (i in required) {
                require_nonnegative_integer(required[i])
            }
            if (("route_fully_routed" in value) && \
                value["route_fully_routed"] != 1) {
                reject("route_fully_routed=" value["route_fully_routed"])
            }
            # route_nodriver_nets stays required above but is not required to
            # be zero: generated SGen netlists leave load-less driver-less
            # nets that Vivado counts here without affecting the route.
            split("route_errors route_unrouted_nets route_partial_nets route_unplaced_nets route_gap_nets route_conflict_nets route_antenna_nets drc_fatal drc_error drc_critical_warning drc_unclassified", zero, " ")
            for (i in zero) {
                metric = zero[i]
                if ((metric in value) && value[metric] ~ /^[0-9]+$/ && \
                    value[metric] != 0) {
                    reject(metric "=" value[metric])
                }
            }
            if (("drc_violations" in value) && \
                ("drc_fatal" in value) && ("drc_error" in value) && \
                ("drc_critical_warning" in value) && \
                ("drc_warning" in value) && ("drc_advisory" in value) && \
                ("drc_unclassified" in value) && \
                value["drc_violations"] ~ /^[0-9]+$/ && \
                value["drc_fatal"] ~ /^[0-9]+$/ && \
                value["drc_error"] ~ /^[0-9]+$/ && \
                value["drc_critical_warning"] ~ /^[0-9]+$/ && \
                value["drc_warning"] ~ /^[0-9]+$/ && \
                value["drc_advisory"] ~ /^[0-9]+$/ && \
                value["drc_unclassified"] ~ /^[0-9]+$/) {
                classified = value["drc_fatal"] + value["drc_error"] + \
                    value["drc_critical_warning"] + value["drc_warning"] + \
                    value["drc_advisory"] + value["drc_unclassified"]
                if (value["drc_violations"] != classified) {
                    reject("DRC severity counts do not sum to drc_violations")
                }
            }
            if (invalid) exit 1
        }
    ' "$metrics_file"
done
