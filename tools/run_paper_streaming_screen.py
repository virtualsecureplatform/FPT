#!/usr/bin/env python3
"""Run a source-bound observed-decryption screen, never hardware qualification."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import time


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("executable", type=pathlib.Path)
    parser.add_argument("output", type=pathlib.Path)
    parser.add_argument("--seed-start", type=int, default=0)
    parser.add_argument("--keys", type=int, default=16)
    parser.add_argument("--trials", type=int, default=16)
    parser.add_argument("--profile", choices=("compact32", "headroom32", "staged32", "fullstaged32", "guarded32", "tf24full", "tf24guard4", "tf24guard4acc40", "tf26guard4", "tf26guard4acc40"), default="compact32")
    parser.add_argument("--jobs", type=int, default=1)
    args = parser.parse_args()
    if not (0 <= args.seed_start <= 1000000 and 1 <= args.keys <= 256
            and 2 <= args.trials <= 256 and args.trials % 2 == 0 and 1 <= args.jobs <= 16):
        parser.error("invalid screen range")
    root = pathlib.Path(__file__).resolve().parents[1]
    executable = args.executable.resolve(strict=True)
    files = [executable, root / "tests/paper_streaming_encrypted_pilot.cpp"]
    files += sorted((root / "include/fpt").glob("paper_streaming*.hpp"))
    inputs = {str(p): digest(p) for p in files}
    build_manifest = pathlib.Path(str(executable) + ".build-inputs.json")
    if build_manifest.exists():
        build_inputs = json.loads(build_manifest.read_text())["inputs"]
        if any(digest(pathlib.Path(p)) != sha for p, sha in build_inputs.items()):
            parser.error("compiled dependencies changed since the executable was built")
        inputs.update(build_inputs)
        inputs[str(build_manifest)] = digest(build_manifest)
    args.output.mkdir(parents=True, exist_ok=False)

    def write(name, value):
        (args.output / name).write_text(json.dumps(value, indent=2) + "\n")

    write("inputs.json", inputs)
    status = dict(phase="running", numerical_qualified=False,
                  hardware_qualified=False, start_epoch=time.time())
    jobs = min(args.jobs, args.keys)
    commands, processes, logs = [], [], []
    try:
        for job in range(jobs):
            first = args.keys * job // jobs
            count = args.keys * (job + 1) // jobs - first
            command = [str(executable), args.profile, str(args.seed_start + first),
                       str(count), str(args.trials)]
            commands.append(command)
            log = (args.output / f"part{job}.log").open("w")
            logs.append(log)
            processes.append(subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT))
        status["pids"] = [p.pid for p in processes]
        # Keep the single-process field for existing status consumers.
        if jobs == 1: status["pid"] = processes[0].pid
        write("command.json", commands)
        write("status.json", status)
        codes = [process.wait() for process in processes]
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
                process.wait()
        for log in logs: log.close()
    code = next((c for c in codes if c != 0), 0)
    with (args.output / "native.log").open("w") as combined:
        for job in range(jobs):
            combined.write((args.output / f"part{job}.log").read_text())
    records = {}
    for line in (args.output / "native.log").read_text().splitlines():
        match = re.fullmatch(r"seed=(\d+) trial=(\d+) message=(\d+) decrypted=(\d+) phase=(-?\d+) overflows=(\d+)", line)
        if match:
            seed, trial, message, decrypted, phase, overflow = map(int, match.groups())
            if (seed, trial) in records:
                raise ValueError("duplicate numerical record")
            records[seed, trial] = (message, decrypted, phase, overflow)
    expected = {(s, t) for s in range(args.seed_start, args.seed_start + args.keys)
                for t in range(args.trials)}
    unchanged = all(digest(pathlib.Path(p)) == sha for p, sha in inputs.items())
    passed = code == 0 and unchanged and set(records) == expected
    passed = passed and all(m == (t & 1) and d == m and (phase > 0) == bool(m) and overflow == 0
                            for (s, t), (m, d, phase, overflow) in records.items())
    status.update(phase="closed", exit_code=code, exit_codes=codes, inputs_unchanged=unchanged,
                  records=len(records), result="PASS_DECRYPTION_SCREEN_ONLY" if passed else "FAIL_DECRYPTION_SCREEN",
                  independent_reference_validation_pending=True,
                  elapsed_seconds=time.time() - status["start_epoch"])
    write("status.json", status)
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
