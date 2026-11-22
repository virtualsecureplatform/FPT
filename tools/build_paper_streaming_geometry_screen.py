#!/usr/bin/env python3
"""Build the isolated 36-geometry experiment from a frozen dependency tree."""
import argparse
import hashlib
import json
import pathlib
import shlex
import shutil
import subprocess


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reference-source", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--entrypoint", choices=("geometry", "board-fixture"), default="geometry")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    reference = args.reference_source.resolve(strict=True)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    entry = "geometry" if args.entrypoint=="geometry" else "board_fixture"
    sources = [root / f"tests/paper_streaming_{entry}_pilot.cpp", reference / "src/fft.cpp"]
    tfhepp = reference / "third_party/TFHEpp"
    includes = [root / "third_party/TFHEpp/thirdparties/cereal/include", tfhepp / "include",
                tfhepp / "thirdparties/spqlios", tfhepp / "thirdparties/HLS_arbitrary_Precision_Types/include",
                reference / "include", root / "include"]
    compiler = shutil.which("g++")
    flags = [compiler, "-std=c++20", "-O2", "-DUSE_FPT"]
    dependencies = set(sources + [pathlib.Path(__file__).resolve()])
    commands = []
    for index, source in enumerate(sources):
        command = flags + ["-I" + str(path) for path in includes] + ["-MM", "-MT", f"source{index}", str(source)]
        commands.append(command)
        discovered = subprocess.run(command, text=True, capture_output=True, check=True)
        (output / f"source{index}.discovery.d").write_text(discovered.stdout)
        dependencies.update(pathlib.Path(path).resolve() for path in shlex.split(discovered.stdout.replace("\\\n", " ").split(":", 1)[1]))

    def frozen(path):
        path = path.resolve()
        if path.is_relative_to(reference):
            return output / "inputs/reference" / path.relative_to(reference)
        if path.is_relative_to(root):
            return output / "inputs/root" / path.relative_to(root)
        raise ValueError(f"unmapped non-system dependency: {path}")

    hashes, snapshots = {}, {}
    for path in dependencies:
        destination = frozen(path)
        destination.parent.mkdir(parents=True, exist_ok=True)
        hashes[str(path)] = sha(path)
        shutil.copyfile(path, destination)
        if sha(destination) != hashes[str(path)]:
            raise RuntimeError("geometry source changed while snapshotting")
        snapshots[str(path)] = str(destination)
    frozen_flags = flags + ["-I" + str(frozen(path)) for path in includes]
    manifest = dict(inputs=hashes, snapshots=snapshots, source_execution="frozen_snapshot",
                    commands=commands, numerical_qualified=False, hardware_qualified=False, entrypoint=args.entrypoint)
    manifest_path = output / "build-inputs.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    objects = []
    passed = True
    for index, source in enumerate(sources):
        obj = output / f"source{index}.o"
        command = frozen_flags + ["-c", str(frozen(source)), "-o", str(obj)]
        commands.append(command)
        with (output / f"source{index}.build.log").open("w") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        manifest[f"source{index}_exit_code"] = result.returncode
        passed &= result.returncode == 0
        if not passed:
            break
        objects.append(obj)
    executable = output / ("geometry-screen" if args.entrypoint=="geometry" else "board-fixture-screen")
    if passed:
        command = [compiler] + [str(path) for path in objects] + ["-o", str(executable)]
        commands.append(command)
        with (output / "link.log").open("w") as log:
            result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        manifest["link_exit_code"] = result.returncode
        passed = result.returncode == 0
    if passed:
        manifest["executable_sha256"] = sha(executable)
    manifest["inputs_unchanged"] = all(sha(path) == value for path, value in hashes.items())
    manifest["snapshot_inputs_unchanged"] = all(sha(snapshots[path]) == value for path, value in hashes.items())
    manifest["passed"] = passed and manifest["snapshot_inputs_unchanged"]
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"{'PASS' if manifest['passed'] else 'FAIL'} isolated geometry screen build: {output}")
    return 0 if manifest["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
