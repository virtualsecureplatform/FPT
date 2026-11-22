#!/usr/bin/env python3
"""Build the experimental screen from source, recording compiler dependencies.

The Set-I TFHEpp adapter currently lives in the source7 development checkout.
Require that checkout explicitly rather than silently linking a cached library.
"""
import argparse
import hashlib
import json
import pathlib
import shlex
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--reference-source", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--compiler", default="/usr/bin/g++")
    parser.add_argument("--entrypoint", choices=("encrypted", "reference", "fixture"), default="encrypted")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    reference = args.reference_source.resolve(strict=True)
    output = args.output.resolve()
    tfhepp = reference / "third_party/TFHEpp"
    includes = [root / "third_party/TFHEpp/thirdparties/cereal/include",
                tfhepp / "include", tfhepp / "thirdparties/spqlios",
                tfhepp / "thirdparties/HLS_arbitrary_Precision_Types/include",
                reference / "include", root / "include"]
    sources = [root / f"tests/paper_streaming_{args.entrypoint}_pilot.cpp", reference / "src/fft.cpp"]
    output.mkdir(parents=True, exist_ok=False)
    commands, objects, dependencies = [], [], []
    flags = [args.compiler, "-std=c++20", "-O2", "-DUSE_FPT"]
    flags += [f"-I{p}" for p in includes]
    for index, source in enumerate(sources):
        obj, dep = output / f"source{index}.o", output / f"source{index}.d"
        command = flags + ["-MMD", "-MF", str(dep), "-c", str(source), "-o", str(obj)]
        commands.append(command)
        with (output / f"source{index}.build.log").open("w") as log:
            subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
        objects.append(obj)
        dependency_text = dep.read_text().replace("\\\n", " ").split(":", 1)[1]
        dependencies += [pathlib.Path(p) if pathlib.Path(p).is_absolute() else root / p
                         for p in shlex.split(dependency_text)]
    executable = output / "encrypted-screen"
    command = [args.compiler] + [str(p) for p in objects] + ["-o", str(executable)]
    commands.append(command)
    with (output / "link.log").open("w") as log:
        subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
    dependencies += [pathlib.Path(__file__).resolve(), executable]
    hashes = {str(p.resolve()): hashlib.sha256(p.read_bytes()).hexdigest() for p in dependencies}
    manifest = dict(commands=commands, inputs=hashes, numerical_qualified=False,
                    hardware_qualified=False)
    pathlib.Path(str(executable) + ".build-inputs.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(executable)


if __name__ == "__main__":
    main()
