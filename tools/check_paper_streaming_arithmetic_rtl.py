#!/usr/bin/env python3
"""Reproducible primitive checks; this does not qualify the complete kernel."""
import argparse
import hashlib
import json
import pathlib
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--verilator", default="verilator")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--case", action="append", default=[])
    parser.add_argument("--fixture", type=pathlib.Path, help="source-bound encrypted batch fixture for full blind-rotation tests")
    parser.add_argument("--alternate-fixture", type=pathlib.Path, help="distinct key fixture for consecutive DMA jobs")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    verilator = shutil.which(args.verilator)
    if not verilator:
        parser.error("Verilator executable not found")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    cases = [
        ("product-split32x28", "ComplexProductSplit", {"A_WIDTH":32,"B_WIDTH":28},
         "product", ["-DTEST_SPLIT", "-DTEST_A_WIDTH=32", "-DTEST_B_WIDTH=28"]),
        ("product-split29x26", "ComplexProductSplit", {"A_WIDTH":29,"B_WIDTH":26},
         "product", ["-DTEST_SPLIT", "-DTEST_A_WIDTH=29", "-DTEST_B_WIDTH=26"]),
        ("product29x26", "ComplexProduct", {"A_WIDTH": 29, "B_WIDTH": 26},
         "product", ["-DTEST_A_WIDTH=29", "-DTEST_B_WIDTH=26"]),
        ("product32x28", "ComplexProduct", {"A_WIDTH": 32, "B_WIDTH": 28},
         "product", ["-DTEST_A_WIDTH=32", "-DTEST_B_WIDTH=28"]),
    ]
    cases += [(f"requantize-shift{s}", "Requantize", {"SHIFT": s},
               "requantize", [f"-DTEST_SHIFT={s}"]) for s in (0, 1, 20)]
    cases += [(f"butterfly-inverse{inv}", "Butterfly4", {"INVERSE": inv},
               "butterfly", [f"-DTEST_INVERSE={inv}"]) for inv in (0, 1)]
    cases += [("mixed-coefficient-store17", "MixedCoefficientStore17", {"BRAM_LANES":112}, "mixed_coefficient_store", [])]
    cases += [("bram-coefficient-store17", "MixedCoefficientStore17", {"BRAM_LANES":128}, "mixed_coefficient_store", [])]
    cases += [("exponent-banks", "ExponentBanks", {"STEPS":7}, "exponent_banks", [])]
    cases += [("initial-body-buffer17", "InitialBodyBuffer17", {}, "initial_body_buffer", [])]
    cases += [("dma-sequencer", "DmaSequencer", {}, "dma_sequencer", [])]
    cases += [("input-decoder", "InputDecoder", {}, "input_decoder", [])]
    cases += [("input-prepare17", "InputPrepare17", {}, "input_prepare", [])]
    cases += [("key-cache", "KeyCache", {"STEPS":75}, "key_cache", [])]
    cases += [("sample-stream", "SampleStream", {}, "sample_stream", [])]
    cases += [("sample-buffer17", "SampleBuffer17", {}, "sample_buffer", [])]
    cases += [("key-unpack", "KeyUnpack", {}, "key_unpack", [])]
    cases += [("mac-lane", "MacLane", {}, "mac_lane", [])]
    cases += [("mac-lane-split", "MacLane", {"USE_SPLIT":1}, "mac_lane", [])]
    cases += [("mac-lane-fused40", "MacLane", {"USE_SPLIT":1,"ACC_WIDTH":40,"FUSED_ACCUM_ROUND":1}, "mac_lane", ["-DTEST_ACC_WIDTH=40"])]
    cases += [("mac-lane40", "MacLane", {"USE_SPLIT":1,"ACC_WIDTH":40}, "mac_lane", ["-DTEST_ACC_WIDTH=40"])]
    cases += [("mac-tile", "MacTile", {"LANES":4}, "mac_tile", [])]
    cases += [("mac-tile128-fused40", "MacTile", {"LANES":128,"ACC_WIDTH":40,"FUSED_ACCUM_ROUND":1}, "mac_tile", ["-DTEST_LANES=128","-DTEST_ACC_WIDTH=40"])]
    cases += [("mac-tile128", "MacTile", {"LANES":128}, "mac_tile", ["-DTEST_LANES=128"])]
    cases += [("external-product", "ExternalProduct", {}, "external_product", [])]
    cases += [("coefficient-store17", "CoefficientStore17", {}, "coefficient_store", [])]
    cases += [("decompose17", "Decompose17", {}, "decompose", [])]
    cases += [("coefficient-frontend17", "CoefficientFrontend17", {}, "coefficient_frontend", [])]
    cases += [("coefficient-frontend17-reversed", "CoefficientFrontend17", {"DIGIT_REVERSED":1}, "coefficient_frontend", ["-DTEST_DIGIT_REVERSED=1"])]
    cases += [("transform-native-reversed", "Transform", {"DIGIT_REVERSED":1,"NATIVE":1}, "transform", ["-DTEST_DIGIT_REVERSED=1","-DTEST_NATIVE=1"])]
    cases += [("blind-rotate3-reversed", "BlindRotate", {"STEPS":3,"DIGIT_REVERSED":1}, "blind_rotate", ["-DTEST_STEPS=3"])]
    cases += [("raw-mixed-blind-rotate586", "RawBlindRotate", {"STEPS":586,"BRAM_STATE_LANES":112,"DSP_INVERSE_LAST":1}, "raw_blind_rotate", ["-DTEST_STEPS=586"])]
    cases += [("raw-mixed-blind-rotate128", "RawBlindRotate", {"STEPS":128,"BRAM_STATE_LANES":112,"DSP_INVERSE_LAST":1}, "raw_blind_rotate", ["-DTEST_STEPS=128"])]
    cases += [("raw-blind-rotate586", "RawBlindRotate", {"STEPS":586}, "raw_blind_rotate", ["-DTEST_STEPS=586"])]
    cases += [("raw-blind-rotate128", "RawBlindRotate", {"STEPS":128}, "raw_blind_rotate", ["-DTEST_STEPS=128"])]
    cases += [("keyed-blind-rotate586", "KeyedBlindRotate", {"STEPS":586}, "blind_rotate", ["-DTEST_STEPS=586","-DTEST_BUFFERED=1","-DTEST_STREAMED=1","-DTEST_KEYED=1"])]
    cases += [("keyed-fused-blind-rotate3", "KeyedBlindRotate", {"STEPS":3,"FUSED_ACCUM_ROUND":1}, "blind_rotate", ["-DTEST_STEPS=3","-DTEST_BUFFERED=1","-DTEST_STREAMED=1","-DTEST_KEYED=1"])]
    cases += [("keyed-blind-rotate3", "KeyedBlindRotate", {"STEPS":3}, "blind_rotate", ["-DTEST_STEPS=3","-DTEST_BUFFERED=1","-DTEST_STREAMED=1","-DTEST_KEYED=1"])]
    cases += [("streamed-blind-rotate586", "BufferedBlindRotate", {"STEPS":586,"STREAM_OUTPUT":1,"DSP_FORWARD_PAIRS":1}, "blind_rotate", ["-DTEST_STEPS=586","-DTEST_BUFFERED=1","-DTEST_STREAMED=1"])]
    cases += [("streamed-blind-rotate3", "BufferedBlindRotate", {"STEPS":3,"STREAM_OUTPUT":1}, "blind_rotate", ["-DTEST_STEPS=3","-DTEST_BUFFERED=1","-DTEST_STREAMED=1"])]
    cases += [("buffered-blind-rotate3", "BufferedBlindRotate", {"STEPS":3}, "blind_rotate", ["-DTEST_STEPS=3","-DTEST_BUFFERED=1"])]
    for steps in (3,586):
        cases.append((f"blind-rotate{steps}","BlindRotate",{"STEPS":steps},"blind_rotate",[f"-DTEST_STEPS={steps}"]))
    cases += [("external-product-synchronous-key", "ExternalProduct", {"LEG_GUARD":4,"ACC_WIDTH":40,"FUSED_ROUND":1,"KEY_READ_LATENCY":1}, "external_product", ["-DTEST_LEG_GUARD=4","-DTEST_ACC_WIDTH=40","-DTEST_KEY_READ_LATENCY=1"])]
    cases += [("external-product-guard4acc40", "ExternalProduct", {"LEG_GUARD":4,"ACC_WIDTH":40,"FUSED_ROUND":1},
               "external_product", ["-DTEST_LEG_GUARD=4","-DTEST_ACC_WIDTH=40"])]
    cases += [("fft-stage3-inverse-guard4-dsp", "FFTStage", {"LANES":64,"STAGE":3,"INVERSE":1,"FUSED_ROUND":1,"LEG_GUARD":4,"DSP_PAIRS":1}, "fft_stage", ["-DTEST_LANES=64","-DTEST_STAGE=3","-DTEST_INVERSE=1","-DTEST_LEG_GUARD=4"])]
    cases += [("fft-stage3-guard4-dsp", "FFTStage", {"LANES":128,"STAGE":3,"INVERSE":0,"FUSED_ROUND":1,"LEG_GUARD":4,"DSP_PAIRS":1},
               "fft_stage", ["-DTEST_LANES=128","-DTEST_STAGE=3","-DTEST_INVERSE=0","-DTEST_LEG_GUARD=4"])]
    for name,bw,ow,shift in [("mac48",28,48,14),("mac40",28,40,22),("fftguard4",27,37,22)]:
        cases.append((f"product-rounded-{name}","ComplexProductRounded",{"B_WIDTH":bw,"OUTPUT_WIDTH":ow,"SHIFT":shift},
                      "product_rounded",[f"-DTEST_B_WIDTH={bw}",f"-DTEST_OUTPUT_WIDTH={ow}",f"-DTEST_SHIFT={shift}"]))
    for subtract in (0,1):
        cases.append((f"rounded-add-subtract{subtract}","RoundedAdd",{"SUBTRACT":subtract},"rounded_add",[f"-DTEST_SUBTRACT={subtract}"]))
    for stage,inverse,lanes in [(3,0,128),(3,1,64),(0,1,64)]:
        cases.append((f"fft-fused-stage{stage}-inverse{inverse}","FFTStage",{"LANES":lanes,"STAGE":stage,"INVERSE":inverse,"FUSED_ROUND":1},
                      "fft_stage",[f"-DTEST_LANES={lanes}",f"-DTEST_STAGE={stage}",f"-DTEST_INVERSE={inverse}"]))
    for inverse,lanes in [(0,128),(1,64)]:
        cases.append((f"transform-inverse{inverse}","Transform",{"LANES":lanes,"INVERSE":inverse},
                      "transform",[f"-DTEST_LANES={lanes}",f"-DTEST_INVERSE={inverse}"]))
        cases.append((f"transform-native-inverse{inverse}","Transform",{"LANES":lanes,"INVERSE":inverse,"NATIVE":1},
                      "transform",[f"-DTEST_LANES={lanes}",f"-DTEST_INVERSE={inverse}","-DTEST_NATIVE=1"]))
        for stage in range(4):
            cases.append((f"fft-stage{stage}-inverse{inverse}","FFTStage",
                          {"LANES":lanes,"STAGE":stage,"INVERSE":inverse},"fft_stage",
                          [f"-DTEST_LANES={lanes}",f"-DTEST_STAGE={stage}",f"-DTEST_INVERSE={inverse}"]))
    for name,iw,ow,shift in [("requantize-mac",62,48,14),("requantize-inverse-input",48,32,10)]:
        cases.append((name,"Requantize",{"INPUT_WIDTH":iw,"OUTPUT_WIDTH":ow,"SHIFT":shift},
                      "requantize",[f"-DTEST_INPUT_WIDTH={iw}",f"-DTEST_OUTPUT_WIDTH={ow}",f"-DTEST_SHIFT={shift}"]))
    if args.fixture:
        cases += [("dma-engine586", "DmaEngine", {"STEPS":586}, "dma_engine", [])]
        cases += [("dma-engine586-bram128", "DmaEngine", {"STEPS":586,"BRAM_STATE_LANES":128}, "dma_engine", [])]
    if args.case:
        missing=set(args.case)-{c[0] for c in cases}
        if missing: parser.error(f"unknown cases: {sorted(missing)}")
        cases=[c for c in cases if c[0] in args.case]
    fixture_path = args.fixture.resolve(strict=True) if args.fixture else None
    def verify_fixture(path):
        binding = json.loads((path.parent / "fixture-binding.json").read_text())
        if not binding.get("passed"):
            parser.error("encrypted fixture generation did not pass")
        for source,value in binding["source_inputs"].items():
            if hashlib.sha256(pathlib.Path(source).read_bytes()).hexdigest()!=value:
                parser.error(f"encrypted fixture source has changed: {source}")
        for name,value in binding["files"].items():
            if hashlib.sha256((path.parent/name).read_bytes()).hexdigest()!=value:
                parser.error(f"encrypted fixture file has changed: {name}")
        return binding
    fixture_binding = None
    if fixture_path:
        if any(test not in ("blind_rotate","raw_blind_rotate","dma_engine") or parameters.get("STEPS",586)!=586 for _,_,parameters,test,_ in cases):
            parser.error("encrypted fixture requires an explicitly selected full blind-rotation case")
        fixture_binding = verify_fixture(fixture_path)
    alternate_path = args.alternate_fixture.resolve(strict=True) if args.alternate_fixture else None
    alternate_binding = None
    if alternate_path:
        if not fixture_path or any(test!="dma_engine" for _,_,_,test,_ in cases):
            parser.error("alternate fixture requires an explicitly selected encrypted DMA-engine case")
        alternate_binding=verify_fixture(alternate_path)
        if alternate_binding["files"]["key.bin"]==fixture_binding["files"]["key.bin"]:
            parser.error("alternate fixture must use a different ordinary encrypted key")
    def dependencies(module):
        if module=="DmaEngine":return ["DmaSequencer","InputDecoder","RawBlindRotate"]+dependencies("RawBlindRotate")
        if module=="RawBlindRotate":return ["InputPrepare17","InitialBodyBuffer17","KeyedBlindRotate"]+dependencies("KeyedBlindRotate")
        if module=="KeyedBlindRotate":return ["KeyCache","BufferedBlindRotate"]+dependencies("BufferedBlindRotate")
        if module=="BufferedBlindRotate":return ["BlindRotate","SampleBuffer17","SampleStream"]+dependencies("BlindRotate")
        if module=="BlindRotate":return ["Physical","MixedCoefficientStore17","ExponentBanks","CoefficientStore17","CoefficientFrontend17","Rotate17","Decompose17","ExternalProduct","Transform","MacTile","MacLane","ComplexProduct","FFTStage","TwiddleProduct","ComplexProductSplit","Butterfly4","Requantize","FramePermute","Twist","RoundedAdd","RoundedButterfly4","ComplexProductRounded"]
        if module in ("MacTile","Transform","ExternalProduct"):
            return ["Physical"]+{"MacTile":["MacLane","ComplexProduct","ComplexProductSplit","Requantize"],
                "Transform":["FFTStage","TwiddleProduct","ComplexProductSplit","Butterfly4","Requantize","FramePermute","Twist","RoundedAdd","RoundedButterfly4","ComplexProductRounded"],
                "ExternalProduct":["Transform","MacTile","MacLane","ComplexProduct","FFTStage","TwiddleProduct","ComplexProductSplit","Butterfly4","Requantize","FramePermute","Twist","RoundedAdd","RoundedButterfly4","ComplexProductRounded"]}[module]
        if module=="CoefficientFrontend17":return ["Rotate17","Decompose17"]
        if module=="ComplexProductRounded":return ["ComplexProductSplit","RoundedAdd"]
        if module=="ExternalProduct":return ["Transform","MacTile","MacLane","ComplexProduct","FFTStage","TwiddleProduct","ComplexProductSplit","Butterfly4","Requantize","FramePermute","Twist","RoundedAdd","RoundedButterfly4","ComplexProductRounded"]
        if module=="Transform":return ["FFTStage","TwiddleProduct","ComplexProductSplit","Butterfly4","Requantize","FramePermute","Twist","RoundedAdd","RoundedButterfly4","ComplexProductRounded"]
        if module=="FFTStage":return ["TwiddleProduct","ComplexProductSplit","Butterfly4","Requantize","RoundedAdd","RoundedButterfly4","ComplexProductRounded"]
        if module=="MacTile":return ["MacLane","ComplexProduct","ComplexProductSplit","Requantize"]
        if module=="MacLane":return ["ComplexProduct","ComplexProductSplit","Requantize"]
        return []
    sources = [pathlib.Path(__file__).resolve()]
    for _, module, _, test, _ in cases:
        sources += [root / f"chisel/src/main/resources/PaperStreaming{module}.sv",
                    root / f"tests/paper_streaming_{test}_rtl_test.cpp"]
        sources += [root / f"chisel/src/main/resources/PaperStreaming{dep}.sv" for dep in dependencies(module)]
        if module in ("DmaEngine","FFTStage","Transform","ExternalProduct","BlindRotate","BufferedBlindRotate","KeyedBlindRotate","RawBlindRotate"):
            sources += [root / "chisel/src/main/resources/PaperStreamingTwiddles.svh",
                        root / "tools/generate_paper_streaming_twiddles.cpp"]
        if module in ("DmaEngine","Transform","ExternalProduct","BlindRotate","BufferedBlindRotate","KeyedBlindRotate","RawBlindRotate"):
            sources += sorted((root / "include/fpt").glob("paper_streaming*.hpp"))
            sources += [root / "include/fpt/fixed_point.hpp"]
        if module in ("DmaEngine","ExternalProduct","BlindRotate","BufferedBlindRotate","KeyedBlindRotate","RawBlindRotate"):
            sources.append(root / "include/fpt/paper_key_packing.hpp")
        if module=="DmaEngine":
            sources += [root / "include/fpt" / name for name in ("paper_dma_fixture.hpp","paper_dma_layout.hpp","paper_grouped_key_packing.hpp")]
    hashes = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
    snapshots={}
    for p in set(sources):
        destination=output / "inputs" / p.relative_to(root)
        destination.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(p,destination)
        if hashlib.sha256(destination.read_bytes()).hexdigest()!=hashes[str(p)]:
            raise RuntimeError("source changed while snapshotting RTL test")
        snapshots[str(p)]=str(destination)
    def snapshot_fixture_inputs(path,binding,directory):
        fixture_files=list(binding["files"])+["fixture-binding.json"]
        for name in fixture_files:
            original=path.parent/name
            destination=output / "inputs" / directory / name
            destination.parent.mkdir(parents=True,exist_ok=True)
            hashes[str(original)]=hashlib.sha256(original.read_bytes()).hexdigest()
            shutil.copyfile(original,destination)
            if hashlib.sha256(destination.read_bytes()).hexdigest()!=hashes[str(original)]:
                raise RuntimeError("encrypted fixture changed while snapshotting")
            snapshots[str(original)]=str(destination)
        return output / "inputs" / directory / path.name
    snapshot_fixture = snapshot_fixture_inputs(fixture_path,fixture_binding,"encrypted-fixture") if fixture_path else None
    snapshot_alternate = snapshot_fixture_inputs(alternate_path,alternate_binding,"alternate-encrypted-fixture") if alternate_path else None
    snapshot_root=output / "inputs"
    results = {"complete_kernel_qualified": False, "source_execution":"frozen_snapshot", "inputs": hashes, "snapshots":snapshots, "cases": {}}
    for name, module, parameters, test, flags in cases:
        top = f"PaperStreaming{module}"
        directory = output / name
        # Keep generated translation units separate. Verilator 5.045 groups
        # them into two enormous files when -j 2 is used without this option.
        command = [verilator, "--cc", "--exe", "--build", "-j", "2", "--output-groups", "0",
                   f"-I{snapshot_root / 'chisel/src/main/resources'}",
                   "--top-module", top, "--Mdir", str(directory),
                   "-CFLAGS", " ".join(["-std=c++20", f"-I{snapshot_root / 'include'}"] + flags)]
        command += [f"-G{k}={v}" for k, v in parameters.items()]
        command += [str(snapshot_root / f"chisel/src/main/resources/{top}.sv"),
                    str(snapshot_root / f"tests/paper_streaming_{test}_rtl_test.cpp")]
        command += [str(snapshot_root / f"chisel/src/main/resources/PaperStreaming{dep}.sv") for dep in dependencies(module)]
        with (output / f"{name}.build.log").open("w") as log:
            build = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT)
        record = {"command": command, "build_exit_code": build.returncode}
        if build.returncode == 0:
            run_command=[str(directory / f"V{top}")]
            if snapshot_fixture:run_command.append(str(snapshot_fixture))
            if snapshot_alternate:run_command.append(str(snapshot_alternate))
            record["test_command"]=run_command
            with (output / f"{name}.run.log").open("w") as run_log:
                run = subprocess.run(run_command, stdout=run_log, stderr=subprocess.STDOUT)
            record["test_exit_code"] = run.returncode
        results["cases"][name] = record
        (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    unchanged = all(hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest() == h
                    for p, h in hashes.items())
    snapshot_unchanged=all(hashlib.sha256(pathlib.Path(snapshots[p]).read_bytes()).hexdigest()==h for p,h in hashes.items())
    passed = snapshot_unchanged and all(r.get("test_exit_code") == 0 for r in results["cases"].values())
    results.update(inputs_unchanged=unchanged, snapshot_inputs_unchanged=snapshot_unchanged, passed=passed)
    (output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print(f"{'PASS' if passed else 'FAIL'} {len(cases)} arithmetic RTL configurations: {output}")
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
