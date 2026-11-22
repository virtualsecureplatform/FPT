#!/usr/bin/env python3
"""Generate >=4096 distinct input cases for 32 ordinary bootstrapping keys."""
import argparse
import concurrent.futures
import hashlib
import json
import pathlib
import shutil
import subprocess


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--prefix-fixture-set", type=pathlib.Path)
    parser.add_argument("--seed-start", type=int, default=81)
    parser.add_argument("--keys", type=int, default=32)
    parser.add_argument("--batches", type=int, default=11)
    parser.add_argument("--jobs", type=int, default=4)
    args = parser.parse_args()
    if not (0 <= args.seed_start <= 1000000 and 1 <= args.keys <= 32 and 11 <= args.batches <= 256 and 1 <= args.jobs <= 8):
        parser.error("invalid distinct fixture range")
    build = args.build.resolve()
    built = json.loads((build / "build-inputs.json").read_text())
    if not built.get("passed") or not built.get("snapshot_inputs_unchanged") or built.get("entrypoint") != "board-fixture":
        parser.error("frozen distinct-board-fixture build has not passed")
    sources = {built["snapshots"][path]: value for path, value in built["inputs"].items()}
    executable = build / "board-fixture-screen"
    if sha(executable) != built["executable_sha256"]:
        parser.error("fixture executable changed")
    sources[str(executable)] = sha(executable)
    sources[str(build / "build-inputs.json")] = sha(build / "build-inputs.json")
    if any(sha(path) != value for path, value in sources.items()):
        parser.error("fixture build snapshot changed")
    expected_prefix = {}
    if args.prefix_fixture_set:
        prefix = args.prefix_fixture_set.resolve()
        previous = json.loads((prefix / "manifest.json").read_text())
        if not previous.get("passed") or previous.get("profile") != "tf26guard4acc40":
            parser.error("prefix fixture set has not passed")
        sources[str(prefix / "manifest.json")] = sha(prefix / "manifest.json")
        for seed in range(args.seed_start, args.seed_start + args.keys):
            record = previous["fixtures"][str(seed)]
            binding_path = prefix / f"seed{seed}/fixture-binding.json"
            if sha(binding_path) != record["binding_sha256"]:
                parser.error("prefix fixture binding changed")
            binding = json.loads(binding_path.read_text())
            expected_prefix[seed] = {name: binding["files"][name] for name in ("key.bin", "batch-bool.bin", "batch-ramp8.bin")}
            sources[str(binding_path)] = sha(binding_path)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    (output / "inputs").mkdir()
    script_copy = output / "inputs" / pathlib.Path(__file__).name
    shutil.copyfile(__file__, script_copy)
    sources[str(script_copy)] = sha(script_copy)
    seeds = list(range(args.seed_start, args.seed_start + args.keys))
    (output / "seeds.txt").write_text("".join(f"{seed}\n" for seed in seeds))
    manifest = dict(schema_version=2, phase="running", profile="tf26guard4acc40", seeds=seeds, inputs=sources,
                    distinct_batches_per_key=args.batches, fixtures={}, hardware_qualified=False,
                    numerical_qualified=False, scope="distinct ordinary encrypted board fixtures; no board execution")
    manifest_path = output / "manifest.json"

    def save():
        temporary = output / "manifest.tmp"
        temporary.write_text(json.dumps(manifest, indent=2) + "\n")
        temporary.replace(manifest_path)

    def generate(seed):
        directory = output / f"seed{seed}"
        command = [str(executable), str(seed), str(directory), str(args.batches)]
        with (output / f"seed{seed}.generation.log").open("w") as log:
            run = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        frame_index = directory / "frames.txt"
        names = frame_index.read_text().splitlines() if frame_index.is_file() else []
        names_valid = len(names) == args.batches and len(set(names)) == args.batches and all(pathlib.Path(name).name == name for name in names)
        files = {name: sha(directory / name) for name in ["key.bin", "fixture.json", "frames.txt"] + names if (directory / name).is_file()}
        description = json.loads((directory / "fixture.json").read_text()) if (directory / "fixture.json").is_file() else {}
        passed = (run.returncode == 0 and names_valid and len(files) == args.batches + 3
                  and description.get("distinct_inputs") == args.batches * 12
                  and description.get("checked") == args.batches * 12
                  and description.get("fixed_and_independent_reference_passed"))
        prefix_matches = not expected_prefix or all(files.get(name) == value for name, value in expected_prefix[seed].items())
        unchanged = all(sha(path) == value for path, value in sources.items())
        passed = bool(passed and prefix_matches and unchanged)
        binding = dict(command=command, exit_code=run.returncode, passed=passed, source_inputs=sources, files=files,
                       prefix_matches=prefix_matches, distinct_inputs=description.get("distinct_inputs", 0),
                       hardware_qualified=False, numerical_qualified=False)
        binding_path = directory / "fixture-binding.json"
        binding_path.write_text(json.dumps(binding, indent=2) + "\n")
        return seed, dict(command=command, exit_code=run.returncode, passed=passed, directory=str(directory),
                          binding_sha256=sha(binding_path), key_sha256=files.get("key.bin"),
                          distinct_inputs=description.get("distinct_inputs", 0), prefix_matches=prefix_matches)

    save()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for future in concurrent.futures.as_completed([pool.submit(generate, seed) for seed in seeds]):
            seed, record = future.result()
            manifest["fixtures"][str(seed)] = record
            save()
            print(f"{'PASS' if record['passed'] else 'FAIL'} distinct fixture seed={seed} inputs={record['distinct_inputs']}", flush=True)
    unchanged = all(sha(path) == value for path, value in sources.items())
    keys = {record["key_sha256"] for record in manifest["fixtures"].values()}
    passed = unchanged and len(keys) == args.keys and None not in keys and all(record["passed"] for record in manifest["fixtures"].values())
    manifest.update(phase="closed", passed=passed, inputs_unchanged=unchanged, distinct_ordinary_keys=len(keys - {None}),
                    distinct_inputs=sum(record["distinct_inputs"] for record in manifest["fixtures"].values() if record["passed"]))
    save()
    print(f"{'PASS' if passed else 'FAIL'} distinct fixture set; hardware_qualified=false", flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
