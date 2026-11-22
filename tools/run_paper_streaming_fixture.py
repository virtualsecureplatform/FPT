#!/usr/bin/env python3
"""Generate ordinary encrypted RTL fixtures and bind their source provenance."""
import argparse
import hashlib
import json
import pathlib
import subprocess


def digest(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--executable", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--seed", type=int, required=True)
    args = parser.parse_args()
    executable = args.executable.resolve(strict=True)
    output = args.output.resolve()
    if output.exists():
        parser.error("refusing to overwrite fixture directory")
    build_path = pathlib.Path(str(executable) + ".build-inputs.json")
    build = json.loads(build_path.read_text())
    if any(digest(path) != value for path, value in build["inputs"].items()):
        parser.error("fixture executable or its source dependencies have changed")
    output.parent.mkdir(parents=True, exist_ok=True)
    command = [str(executable), str(args.seed), str(output)]
    with pathlib.Path(str(output) + ".generation.log").open("w") as log:
        result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
    unchanged = all(digest(path) == value for path, value in build["inputs"].items())
    output.mkdir(exist_ok=True)
    files = {name: digest(output / name) for name in
             ("key.bin", "batch-bool.bin", "batch-ramp8.bin", "fixture.json")
             if (output / name).exists()}
    passed = result.returncode == 0 and unchanged and len(files) == 4
    binding = dict(command=command, exit_code=result.returncode, inputs_unchanged=unchanged,
                   passed=passed, source_inputs=build["inputs"], files=files,
                   build_manifest_sha256=digest(build_path),
                   runner_sha256=digest(__file__), hardware_qualified=False,
                   numerical_qualified=False)
    (output / "fixture-binding.json").write_text(json.dumps(binding, indent=2) + "\n")
    print(f"{'PASS' if passed else 'FAIL'} encrypted fixture generation: {output}")
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
