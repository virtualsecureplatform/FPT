#!/usr/bin/env python3
"""Run source-bound U280 tests only after complete platform build gates pass."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def tsv(path):
    lines = pathlib.Path(path).read_text().splitlines()
    if not lines or lines[0] != "metric\tvalue":
        raise ValueError(f"invalid metrics file: {path}")
    return dict(line.split("\t", 1) for line in lines[1:])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--kernel-build", type=pathlib.Path, required=True)
    parser.add_argument("--fixture-set", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--frames", type=int, default=4096)
    parser.add_argument("--device", type=int, default=0)
    parser.add_argument("--expected-bdf", default="0000:0a:00.1")
    parser.add_argument("--xrt-root", type=pathlib.Path, default=pathlib.Path("/opt/xilinx/xrt"))
    args = parser.parse_args()
    if not 11 <= args.frames <= 4096:
        parser.error("11..4096 frames per key required")
    root = pathlib.Path(__file__).resolve().parents[1]
    build = args.kernel_build.resolve()
    kernel = json.loads((build / "manifest.json").read_text())
    if not (kernel.get("passed") and kernel.get("target") == "hw" and kernel.get("snapshot_inputs_unchanged")
            and kernel.get("link_exit_code") == 0 and kernel.get("unwaived_drc_exit_code") == 0
            and kernel.get("post_route_resource_exit_code") == 0):
        parser.error("complete hardware build, unwaived DRC and routed resource gates have not passed")
    if sha(build / "FptPaperStreamingKernel.xclbin") != kernel.get("xclbin_sha256"):
        parser.error("kernel image is not bound to the completed build")
    for path, value in kernel["inputs"].items():
        if sha(kernel["snapshots"][path]) != value:
            parser.error(f"kernel build snapshot changed: {path}")
    checkpoint = pathlib.Path(kernel["routed_checkpoint_candidates"][0])
    if sha(checkpoint) != kernel["routed_checkpoint_sha256"]:
        parser.error("routed checkpoint changed after qualification audits")
    route = tsv(build / "post_route.tsv")
    if float(route["kernel_clock_period_ns"]) != 5 or float(route["wns_ns"]) < 0 or float(route["whs_ns"]) < 0 or int(route["route_fully_routed"]) != 1:
        parser.error("200 MHz routed setup/hold/route metrics failed")
    resources = tsv(build / "kernel_post_route_resources.tsv")
    for name, limit in dict(total_lut=680000, ff=1099200, dsp=6592, bram_equivalents=896).items():
        if float(resources[name]) > limit:
            parser.error(f"complete routed kernel exceeds {name} limit")
    fixture_set = args.fixture_set.resolve()
    fixtures = json.loads((fixture_set / "manifest.json").read_text())
    if (not fixtures.get("passed") or fixtures.get("distinct_ordinary_keys") != 32
            or fixtures.get("profile") != "tf26guard4acc40" or fixtures.get("distinct_inputs", 0) < 4096
            or fixtures.get("schema_version") != 2):
        parser.error("32-key source-bound generation with >=4096 distinct inputs has not passed")
    seeds = [int(line) for line in (fixture_set / "seeds.txt").read_text().splitlines()]
    if seeds != fixtures["seeds"] or len(set(seeds)) != 32:
        parser.error("fixture seed index mismatch")
    bound = [build / "manifest.json", build / "FptPaperStreamingKernel.xclbin", checkpoint,
             build / "post_route.tsv", build / "kernel_post_route_resources.tsv",
             fixture_set / "manifest.json", fixture_set / "seeds.txt"]
    key_hashes = set()
    for seed in seeds:
        record = fixtures["fixtures"][str(seed)]
        directory = fixture_set / f"seed{seed}"
        binding_path = directory / "fixture-binding.json"
        if sha(binding_path) != record["binding_sha256"]:
            parser.error("fixture binding changed")
        binding = json.loads(binding_path.read_text())
        if not binding.get("passed"):
            parser.error("individual fixture failed")
        for name, value in binding["files"].items():
            if sha(directory / name) != value:
                parser.error("fixture binary changed")
            bound.append(directory / name)
        key_hashes.add(binding["files"]["key.bin"])
        bound.append(binding_path)
    if len(key_hashes) != 32:
        parser.error("board fixture keys are not distinct")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    sources = list((root / "include/fpt").glob("paper*.hpp")) + [root / "include/fpt/fixed_point.hpp"]
    sources += [root / "vitis/paper_streaming/host" / name for name in ("qualify.cpp", "PaperStreamingRuntime.hpp")]
    sources.append(pathlib.Path(__file__).resolve())
    snapshots = {}
    for source in sources:
        destination = output / "inputs" / source.relative_to(root)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, destination)
        snapshots[str(source)] = str(destination)
    bound += [pathlib.Path(path) for path in snapshots.values()]
    bound += [args.xrt_root / "lib" / name for name in ("libxrt_coreutil.so", "libxrt_core.so")]
    hashes = {str(path): sha(path) for path in bound}
    compile_command = [shutil.which("g++"), "-std=c++20", "-O2", "-MMD", "-MF", str(output / "host.d"),
                       "-I" + str(output / "inputs/include"), "-I" + str(args.xrt_root / "include"),
                       str(output / "inputs/vitis/paper_streaming/host/qualify.cpp"),
                       "-L" + str(args.xrt_root / "lib"), "-Wl,-rpath," + str(args.xrt_root / "lib"),
                       "-lxrt_coreutil", "-lxrt_core", "-pthread", "-o", str(output / "paper_qualify")]
    result = dict(phase="compiling", inputs=hashes, snapshots=snapshots, compile_command=compile_command,
                  frames_per_key=args.frames, expected_bdf=args.expected_bdf, hardware_qualified=False,
                  complete_kernel_qualified=False, board_executed=False)
    result_path = output / "results.json"

    def save():
        result_path.write_text(json.dumps(result, indent=2) + "\n")

    save()
    with (output / "compile.log").open("w") as log:
        compiled = subprocess.run(compile_command, stdout=log, stderr=subprocess.STDOUT)
    result["compile_exit_code"] = compiled.returncode
    if compiled.returncode:
        result.update(phase="closed", passed=False)
        save()
        return 1
    # Bind all compiler-discovered XRT and model dependencies as well.
    import shlex
    dependencies = shlex.split((output / "host.d").read_text().replace("\\\n", " ").split(":", 1)[1])
    hashes.update({path: sha(path) for path in dependencies})
    hashes[str(output / "paper_qualify")] = sha(output / "paper_qualify")
    if any(sha(path) != value for path, value in hashes.items()):
        raise RuntimeError("qualification input changed before board execution")
    command = [str(output / "paper_qualify"), str(build / "FptPaperStreamingKernel.xclbin"), str(fixture_set),
               str(args.frames), str(args.device), args.expected_bdf]
    result.update(phase="running", command=command, board_executed=True)
    save()
    with (output / "board.jsonl").open("w") as log, (output / "board.stderr").open("w") as error:
        run = subprocess.run(command, stdout=log, stderr=error)
    records = [json.loads(line) for line in (output / "board.jsonl").read_text().splitlines()]
    keys = [record for record in records if record["type"] == "key"]
    summaries = [record for record in records if record["type"] == "summary"]
    unchanged = all(sha(path) == value for path, value in hashes.items())
    passed = run.returncode == 0 and unchanged and len(keys) == 32 and len(summaries) == 1
    passed = passed and {record["seed"] for record in keys} == set(seeds)
    passed = passed and all(record["words_compared"] == args.frames * 12300 for record in keys)
    if summaries:
        summary = summaries[0]
        expected_distinct=32*12*min(args.frames,fixtures["distinct_batches_per_key"])
        passed = passed and summary["board_test_passed"] and summary["bootstraps"] >= 4096 and summary["distinct_inputs"] == expected_distinct and expected_distinct>=4096
        result["summary"] = summary
        result["kernel_throughput_target_met"] = summary["minimum_key_kernel_pbs_per_ms"] >= 28.4
        result["launch_throughput_target_met"] = summary["launch_pbs_per_ms"] >= 28.4
    result.update(phase="closed", exit_code=run.returncode, inputs_unchanged=unchanged, passed=passed)
    save()
    print(f"{'PASS' if passed else 'FAIL'} board exact-output test; throughput and broader qualification reported separately")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
