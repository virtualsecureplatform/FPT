#!/usr/bin/env python3
"""Observe fixed-point and independent floating-point decryption on matched keys."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("executable", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--seed-start", type=int, default=0)
    parser.add_argument("--keys", type=int, default=16)
    parser.add_argument("--trials", type=int, default=16)
    parser.add_argument("--jobs", type=int, default=4)
    parser.add_argument("--profile", choices=("guarded32","tf24guard4acc40","tf26guard4","tf26guard4acc40"), default="guarded32")
    args = parser.parse_args()
    if not (0 <= args.seed_start <= 1000000 and 1 <= args.keys <= 256 and
            2 <= args.trials <= 256 and args.trials % 2 == 0 and 1 <= args.jobs <= 16):
        parser.error("invalid reference screen range")
    executable = args.executable.resolve(strict=True)
    manifest = pathlib.Path(str(executable) + ".build-inputs.json")
    inputs = json.loads(manifest.read_text())["inputs"]
    def unchanged():
        return all(hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() == h for p,h in inputs.items())
    if not unchanged(): parser.error("compiled dependencies have changed")
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output / "inputs.json").write_text(json.dumps(inputs, indent=2) + "\n")
    start = time.time()
    status = dict(phase="running", profile=args.profile, numerical_qualified=False,
                  hardware_qualified=False, parent_pid=None)
    status_path = args.output / "status.json"
    status_path.write_text(json.dumps(status, indent=2) + "\n")
    def run(seed):
        command = [str(executable), str(seed), str(args.trials),args.profile]
        with (args.output / f"seed{seed}.log").open("w") as log:
            process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        text = (args.output / f"seed{seed}.log").read_text()
        records = [tuple(map(int,m.groups())) for m in re.finditer(
            r"^reference_compare seed=(\d+) trial=(\d+) message=(\d+) fixed_phase=(-?\d+) reference_phase=(-?\d+) phase_delta=(-?\d+) overflows=(\d+)$", text, re.M)]
        complete = len(records) == args.trials and {(s,t) for s,t,*_ in records} == {(seed,t) for t in range(args.trials)}
        passed = process.returncode == 0 and complete and all(
            m == (t&1) and (fp>0) == bool(m) and (rp>0) == bool(m) and o == 0
            for s,t,m,fp,rp,delta,o in records)
        return dict(seed=seed, command=command, exit_code=process.returncode,
                    records=len(records), passed=passed,
                    maximum_phase_delta=max((abs(r[5]) for r in records),default=None))
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        results = list(pool.map(run, range(args.seed_start,args.seed_start+args.keys)))
    same = unchanged()
    passed = same and all(r["passed"] for r in results)
    status.update(phase="closed", inputs_unchanged=same, keys=results,
                  result="PASS_MATCHED_REFERENCE_SCREEN_ONLY" if passed else "FAIL_MATCHED_REFERENCE_SCREEN",
                  elapsed_seconds=time.time()-start)
    status_path.write_text(json.dumps(status, indent=2) + "\n")
    print(status["result"])
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
