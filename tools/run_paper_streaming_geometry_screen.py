#!/usr/bin/env python3
"""Screen all 36 declared geometries; report rejections without promoting RTL."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import re
import subprocess


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--seed-start", type=int, default=0)
    parser.add_argument("--keys", type=int, default=8)
    parser.add_argument("--trials", type=int, default=16)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if not (0 <= args.seed_start <= 63 and 1 <= args.keys <= 64 - args.seed_start
            and 4 <= args.trials <= 256 and args.trials % 4 == 0 and 1 <= args.jobs <= 8):
        parser.error("training seeds 0..63 only; 4..256 trials divisible by four; 1..8 workers")
    build = args.build.resolve()
    original = json.loads((build / "build-inputs.json").read_text())
    if not original.get("passed") or not original.get("snapshot_inputs_unchanged"):
        parser.error("isolated geometry build did not pass")
    for path, value in original["inputs"].items():
        if sha(original["snapshots"][path]) != value:
            parser.error("geometry build snapshot changed")
    executable = build / "geometry-screen"
    if sha(executable) != original["executable_sha256"]:
        parser.error("geometry screen executable changed")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    inputs = {str(executable): sha(executable), str(build / "build-inputs.json"): sha(build / "build-inputs.json"),
              str(pathlib.Path(__file__).resolve()): sha(__file__)}
    manifest = dict(phase="running", build=str(build), inputs=inputs, jobs={}, geometries={},
                    numerical_qualified=False, hardware_qualified=False, complete_kernel_qualified=False,
                    purpose="training geometry screen; not fresh confirmation or hardware qualification",
                    seed_start=args.seed_start, keys=args.keys, trials_per_key=args.trials,
                    twiddle_fraction=26, leg_guard_bits=4,
                    integer_headroom=dict(forward=[9, 11, 13, 15, 17], inverse=[20, 20, 21, 22, 23], key=7, accumulator=26),
                    fresh_confirmation_required="seed113 or later; seeds81..112 reserved for board evidence")
    manifest_path = output / "results.json"

    def save():
        temporary = output / "results.tmp"
        temporary.write_text(json.dumps(manifest, indent=2) + "\n")
        temporary.replace(manifest_path)

    def run(bits, seed):
        name = f"K{bits}-seed{seed}"
        command = [str(executable), str(bits), str(seed), str(args.trials)]
        with (output / (name + ".log")).open("w") as log:
            process = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        return name, dict(command=command, exit_code=process.returncode, log=str(output / (name + ".log")))

    save()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = [pool.submit(run, bits, seed) for bits in (26, 27, 28)
                   for seed in range(args.seed_start, args.seed_start + args.keys)]
        for future in concurrent.futures.as_completed(futures):
            name, record = future.result()
            manifest["jobs"][name] = record
            save()
            print(f"geometry job {name} exit={record['exit_code']}", flush=True)
    pattern = re.compile(r"geometry T=(\d+) K=(\d+) A=(\d+) seed=(\d+) trial=(\d+) lookup=(\d+) message=(\d+) decrypted=(\d+) phase=(-?\d+) reference_phase=(-?\d+) overflows=(\d+) key_overflows=(\d+)")
    expected = {(seed, trial) for seed in range(args.seed_start, args.seed_start + args.keys) for trial in range(args.trials)}
    seen = {}
    for record in manifest["jobs"].values():
        for line in pathlib.Path(record["log"]).read_text().splitlines():
            match = pattern.fullmatch(line)
            if not match:
                continue
            transform, key, accumulator, seed, trial, lookup, message, decrypted, phase, reference, overflow, key_overflow = map(int, match.groups())
            name = f"T{transform}-K{key}-A{accumulator}"
            identity = (seed, trial)
            identities = seen.setdefault(name, set())
            if identity in identities:
                raise RuntimeError("duplicate geometry observation")
            identities.add(identity)
            geometry = manifest["geometries"].setdefault(name, dict(records=0, decrypt_failures=0, reference_failures=0,
                            overflow_records=0, key_overflow_records=0, minimum_phase_margin=2**31))
            geometry["records"] += 1
            geometry["decrypt_failures"] += decrypted != message
            geometry["reference_failures"] += (reference > 0) != bool(message)
            geometry["overflow_records"] += overflow != 0
            geometry["key_overflow_records"] += key_overflow != 0
            geometry["minimum_phase_margin"] = min(geometry["minimum_phase_margin"], phase if message else -phase)
            if lookup != (trial // 2) % 2 or message != trial % 2:
                raise RuntimeError("geometry observation uses wrong input kind")
    declared = {f"T{transform}-K{key}-A{accumulator}" for transform in (29, 30, 32)
                for key in (26, 27, 28) for accumulator in (32, 40, 48, 59)}
    coverage = set(seen) == declared and all(identities == expected for identities in seen.values())
    for name, geometry in manifest["geometries"].items():
        geometry["screen_passed"] = coverage and not any(geometry[field] for field in ("decrypt_failures", "reference_failures", "overflow_records", "key_overflow_records"))
    unchanged = all(sha(path) == value for path, value in inputs.items())
    snapshots_unchanged = all(sha(original["snapshots"][path]) == value for path, value in original["inputs"].items())
    passed = coverage and unchanged and snapshots_unchanged and all(record["exit_code"] == 0 for record in manifest["jobs"].values())
    manifest.update(phase="closed", coverage_complete=coverage, inputs_unchanged=unchanged,
                    snapshot_inputs_unchanged=snapshots_unchanged, passed=passed,
                    accepted_in_training=sorted(name for name, geometry in manifest["geometries"].items() if geometry["screen_passed"]))
    save()
    print(f"{'PASS' if passed else 'FAIL'} geometry coverage; {len(manifest['accepted_in_training'])}/36 passed training only", flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
