#!/usr/bin/env python3
"""Prepare independently keyed, source-bound fixtures for later board checks."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import subprocess
import sys


def digest(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--executable", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--seed-start", type=int, default=81)
    parser.add_argument("--keys", type=int, default=32)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if not (0 <= args.seed_start <= 1000000 and 1 <= args.keys <= 256 and 1 <= args.jobs <= 8):
        parser.error("invalid fixture set range")
    root = pathlib.Path(__file__).resolve().parents[1]
    runner = root / "tools/run_paper_streaming_fixture.py"
    executable = args.executable.resolve(strict=True)
    build_path = pathlib.Path(str(executable) + ".build-inputs.json")
    build = json.loads(build_path.read_text())
    inputs = dict(build["inputs"])
    for path in (runner, pathlib.Path(__file__).resolve(), executable, build_path):
        inputs[str(path)] = digest(path)
    if any(digest(path) != value for path, value in inputs.items()):
        parser.error("fixture build dependencies changed")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    seeds = list(range(args.seed_start, args.seed_start + args.keys))
    (output / "seeds.txt").write_text("".join(f"{seed}\n" for seed in seeds))
    manifest = dict(schema_version=1, phase="running", profile="tf26guard4acc40",
                    seeds=seeds, inputs=inputs, fixtures={}, hardware_qualified=False,
                    numerical_qualified=False, scope="board validation fixture generation only; no board execution")
    manifest_path = output / "manifest.json"

    def save():
        temporary = output / "manifest.tmp"
        temporary.write_text(json.dumps(manifest, indent=2) + "\n")
        temporary.replace(manifest_path)

    def generate(seed):
        directory = output / f"seed{seed}"
        command = [sys.executable, str(runner), "--executable", str(executable),
                   "--output", str(directory), "--seed", str(seed)]
        with (output / f"seed{seed}.runner.log").open("w") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        binding_path = directory / "fixture-binding.json"
        binding = json.loads(binding_path.read_text()) if binding_path.is_file() else {}
        passed = result.returncode == 0 and binding.get("passed", False)
        if passed:
            passed = all(digest(directory / name) == value for name, value in binding["files"].items())
        return seed, dict(command=command, exit_code=result.returncode, passed=passed,
                          directory=str(directory), binding_sha256=digest(binding_path) if binding else None,
                          key_sha256=binding.get("files", {}).get("key.bin"))

    save()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for future in concurrent.futures.as_completed([pool.submit(generate, seed) for seed in seeds]):
            seed, record = future.result()
            manifest["fixtures"][str(seed)] = record
            save()
            print(f"{'PASS' if record['passed'] else 'FAIL'} fixture seed={seed}", flush=True)
    unchanged = all(digest(path) == value for path, value in inputs.items())
    keys = {record["key_sha256"] for record in manifest["fixtures"].values()}
    passed = unchanged and len(keys) == args.keys and None not in keys and all(record["passed"] for record in manifest["fixtures"].values())
    manifest.update(phase="closed", inputs_unchanged=unchanged, passed=passed,
                    distinct_ordinary_keys=len(keys - {None}), distinct_inputs=24 * sum(record["passed"] for record in manifest["fixtures"].values()))
    save()
    print(f"{'PASS' if passed else 'FAIL'} fixture set keys={args.keys} hardware_qualified=false", flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
