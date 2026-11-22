#!/usr/bin/env python3
"""Package/link the opt-in kernel from immutable, hashed source snapshots."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import zipfile


def sha(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--target", choices=("ip-only", "dma-sim", "kernel-synth", "xo", "hw_emu", "hw"), default="xo")
    parser.add_argument("--platform", default="xilinx_u280_gen3x16_xdma_1_202211_1")
    parser.add_argument("--validation", type=pathlib.Path)
    parser.add_argument("--reuse-xo-from", type=pathlib.Path, help="relink an immutable, source-verified XO from an earlier build")
    parser.add_argument("--floorplan", choices=("auto", "pipeline-slrs", "physical-slrs"), default="auto")
    parser.add_argument("--place-directive", help="Vivado place_design directive for the hw link (default: tool default)")
    parser.add_argument("--route-directive", help="Vivado route_design directive for the hw link (default: tool default)")
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    if output.exists():
        parser.error("refusing to overwrite kernel build")
    if args.reuse_xo_from and args.target not in ("hw", "hw_emu"):
        parser.error("XO reuse is supported only for platform links")
    if args.floorplan != "auto" and args.target != "hw":
        parser.error("SLR floorplanning requires a hardware platform link")
    if args.target in ("hw", "hw_emu"):
        if not args.validation:
            parser.error("linking requires a passing full encrypted DMA-engine RTL result")
        result = json.loads(args.validation.read_text())
        if not result.get("passed") or not result.get("snapshot_inputs_unchanged"):
            parser.error("RTL validation did not pass")
        case = result.get("cases", {}).get("dma-engine586-bram128", {})
        if (case.get("test_exit_code") != 0 or "-GSTEPS=586" not in case.get("command", [])
                or "-GBRAM_STATE_LANES=128" not in case.get("command", [])):
            parser.error("linking requires the full 586-step encrypted DMA-engine case with 128 BRAM state lanes")
        if not any("PaperStreamingDmaEngine.sv" in path for path in result["inputs"]):
            parser.error("validation does not cover the complete custom DMA engine")
        if not any("encrypted-fixture" in path for path in result["snapshots"].values()):
            parser.error("validation did not use ordinary encrypted fixtures")
        for path, value in result["inputs"].items():
            if str(root / "chisel/src/main/resources") in path and sha(path) != value:
                parser.error(f"validated RTL has changed: {path}")
    backend = root / "vitis/paper_streaming"
    reused = None
    if args.reuse_xo_from:
        previous_dir=args.reuse_xo_from.resolve()
        previous=json.loads((previous_dir / "manifest.json").read_text())
        if previous.get("package_exit_code")!=0 or not previous.get("snapshot_inputs_unchanged") or previous.get("platform")!=args.platform:
            parser.error("previous packaged kernel lacks immutable source/platform evidence")
        for path,value in previous["inputs"].items():
            if sha(previous["snapshots"][path])!=value:
                parser.error(f"previous kernel snapshot changed: {path}")
        hdl={}
        for path,value in previous["inputs"].items():
            source=pathlib.Path(path)
            if source.suffix not in (".sv", ".svh") or not (source.is_relative_to(root / "chisel/src/main/resources") or source.is_relative_to(backend / "rtl")):
                continue
            if sha(source)!=value:
                parser.error(f"reused kernel RTL has changed: {path}")
            hdl[source.name]=value
        prior_xo=previous_dir / "build/FptPaperStreamingKernel.xo"
        xo_hash=sha(prior_xo)
        verified=[]
        with zipfile.ZipFile(prior_xo) as archive:
            for member in archive.namelist():
                name=pathlib.PurePosixPath(member).name
                if name in hdl:
                    if hashlib.sha256(archive.read(member)).hexdigest()!=hdl[name]:
                        parser.error(f"XO HDL differs from the validated source: {name}")
                    verified.append(name)
            required={"FptPaperStreamingKernel.sv", "FptPaperStreamingKernel_control_s_axi.sv", "PaperStreamingDmaEngine.sv",
                      "PaperStreamingDmaSequencer.sv", "PaperStreamingRawBlindRotate.sv", "PaperStreamingMixedCoefficientStore17.sv", "PaperStreamingTwiddles.svh"}
            if not required.issubset(verified):
                parser.error("XO is missing required selected-backend sources")
            xml=archive.read("FptPaperStreamingKernel/kernel.xml")
            if hashlib.sha256(xml).hexdigest()!=sha(backend / "xml/FptPaperStreamingKernel.xml"):
                parser.error("reused XO kernel argument metadata has changed")
        reused=dict(source_build=str(previous_dir),xo_path=str(prior_xo),xo_sha256=xo_hash,
                    source_manifest_sha256=sha(previous_dir / "manifest.json"),verified_hdl_members=sorted(verified))
    sources = list((root / "chisel/src/main/resources").glob("PaperStreaming*.sv"))
    sources += [root / "chisel/src/main/resources/PaperStreamingTwiddles.svh"]
    sources += [path for path in backend.rglob("*") if path.is_file()]
    sources += [pathlib.Path(__file__).resolve()]
    output.mkdir(parents=True)
    hashes, snapshots = {}, {}
    for source in sources:
        destination = output / "inputs" / source.relative_to(root)
        destination.parent.mkdir(parents=True, exist_ok=True)
        hashes[str(source)] = sha(source)
        shutil.copyfile(source, destination)
        if sha(destination) != hashes[str(source)]:
            raise RuntimeError("kernel source changed while snapshotting")
        snapshots[str(source)] = str(destination)
    snapshot_backend = output / "inputs/vitis/paper_streaming"
    vivado = shutil.which("vivado")
    if not vivado:
        parser.error("Vivado not found")
    command = [vivado, "-mode", "batch", "-nojournal", "-nolog", "-source",
               str(snapshot_backend / "scripts/build_kernel_xo.tcl"), "-tclargs", str(output / "build")]
    if args.target in ("ip-only", "dma-sim", "kernel-synth"):
        command.append(args.target)
    manifest = dict(target=args.target, platform=args.platform, inputs=hashes, snapshots=snapshots,
                    source_execution="frozen_snapshot", commands=[] if reused else [command], hardware_qualified=False,
                    complete_kernel_qualified=False, numerical_qualified=False, state_bram_lanes=128,
                    phase="packaging", launcher_pid=os.getpid(),
                    boot_id=pathlib.Path("/proc/sys/kernel/random/boot_id").read_text().strip(),
                    started_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    manifest["floorplan"] = args.floorplan
    manifest["place_directive"] = args.place_directive
    manifest["route_directive"] = args.route_directive
    manifest_path = output / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    if reused:
        (output / "build").mkdir()
        destination=output / "build/FptPaperStreamingKernel.xo"
        shutil.copyfile(reused["xo_path"],destination)
        if sha(destination)!=reused["xo_sha256"] or sha(reused["xo_path"])!=reused["xo_sha256"]:
            raise RuntimeError("reused XO changed while copying")
        manifest["reused_xo"]=reused
        manifest["source_execution"]="frozen_snapshot_and_verified_xo"
        (output / "package.log").write_text("Reused source-verified XO; packaging and kernel synthesis were not rerun by this stage.\n")
        manifest["package_exit_code"]=0
        passed=True
    else:
        with (output / "package.log").open("w") as log:
            package = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT)
        manifest["package_exit_code"] = package.returncode
        passed = package.returncode == 0
    manifest["xo_sha256"]=sha(output / "build/FptPaperStreamingKernel.xo") if (output / "build/FptPaperStreamingKernel.xo").is_file() else None
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    if passed and args.target in ("hw", "hw_emu"):
        config = output / "link.cfg"
        content = (snapshot_backend / "cfg/link_config.cfg").read_text()
        if args.target == "hw":
            content += ("\n[vivado]\nprop=run.impl_1.STEPS.OPT_DESIGN.TCL.POST=" +
                        str(snapshot_backend / "scripts/check_kernel_resources.tcl") +
                        "\nprop=run.impl_1.STEPS.ROUTE_DESIGN.TCL.POST=" +
                        str(snapshot_backend / "scripts/check_fpt_post_route.tcl") + "\n")
            if args.floorplan != "auto":
                hook = {"pipeline-slrs": "place_pipeline_slrs.tcl", "physical-slrs": "place_physical_slrs.tcl"}[args.floorplan]
                content += ("prop=run.impl_1.STEPS.PLACE_DESIGN.TCL.PRE=" +
                            str(snapshot_backend / "scripts" / hook) + "\n")
                if args.floorplan == "physical-slrs":
                    content += ("prop=run.impl_1.STEPS.ROUTE_DESIGN.TCL.PRE=" +
                                str(snapshot_backend / "scripts/route_platform_margin.tcl") + "\n")
            if args.place_directive:
                content += "prop=run.impl_1.STEPS.PLACE_DESIGN.ARGS.DIRECTIVE=" + args.place_directive + "\n"
            if args.route_directive:
                content += "prop=run.impl_1.STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE=" + args.route_directive + "\n"
        config.write_text(content)
        vpp = shutil.which("v++")
        if not vpp:
            raise RuntimeError("Vitis v++ not found")
        # --save-temps: v++ otherwise deletes the implementation run, and with it
        # the routed checkpoint the audits below need, after a successful link.
        link_command = [vpp, "-l", "--save-temps", "-t", args.target, "--platform", args.platform,
                        "--config", str(config), "--kernel_frequency", "200", "-o",
                        str(output / "FptPaperStreamingKernel.xclbin"),
                        str(output / "build/FptPaperStreamingKernel.xo"),
                        "--log_dir", str(output / "vpp_log"), "--temp_dir", str(output / "vpp_temp"),
                        "--report_dir", str(output / "vpp_report")]
        manifest["commands"].append(link_command)
        manifest["phase"] = "linking"
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
        env = os.environ.copy()
        env["FPT_POST_ROUTE_METRICS"] = str(output / "post_route.tsv")
        env["FPT_POST_ROUTE_DIAGNOSTICS"] = "1"
        env["FPT_KERNEL_METRICS_PREFIX"] = str(output / "kernel_post_opt")
        if args.floorplan != "auto":
            env["FPT_FLOORPLAN_DIAGNOSTICS_PREFIX"] = str(output / "kernel_floorplan")
        with (output / "link.log").open("w") as log:
            link = subprocess.run(link_command, stdout=log, stderr=subprocess.STDOUT, env=env)
        manifest["link_exit_code"] = link.returncode
        passed = link.returncode == 0 and (output / "FptPaperStreamingKernel.xclbin").is_file()
        if passed:
            manifest["xclbin_sha256"] = sha(output / "FptPaperStreamingKernel.xclbin")
        if passed and args.target == "hw":
            checkpoints = list((output / "vpp_temp").glob("**/impl_1/*_routed.dcp"))
            manifest["routed_checkpoint_candidates"] = [str(path) for path in checkpoints]
            if len(checkpoints) != 1:
                manifest["audit_error"] = "cannot uniquely identify the final platform routed checkpoint"
                passed = False
            else:
                manifest["routed_checkpoint_sha256"] = sha(checkpoints[0])
                audit_command = [vivado, "-mode", "batch", "-nojournal", "-nolog", "-source",
                                 str(snapshot_backend / "scripts/audit_fpt_unwaived_drc.tcl"),
                                 "-tclargs", str(checkpoints[0]), str(output / "unwaived_drc")]
                manifest["commands"].append(audit_command)
                with (output / "unwaived_drc.log").open("w") as log:
                    audit = subprocess.run(audit_command, stdout=log, stderr=subprocess.STDOUT)
                manifest["unwaived_drc_exit_code"] = audit.returncode
                passed = audit.returncode == 0 and sha(checkpoints[0]) == manifest["routed_checkpoint_sha256"]
                if passed:
                    resources_command = [vivado, "-mode", "batch", "-nojournal", "-nolog", "-source",
                                         str(snapshot_backend / "scripts/audit_kernel_resources.tcl"),
                                         "-tclargs", str(checkpoints[0]), str(output / "kernel_post_route")]
                    manifest["commands"].append(resources_command)
                    with (output / "post_route_resources.log").open("w") as log:
                        resources = subprocess.run(resources_command, stdout=log, stderr=subprocess.STDOUT)
                    manifest["post_route_resource_exit_code"] = resources.returncode
                    passed = resources.returncode == 0 and sha(checkpoints[0]) == manifest["routed_checkpoint_sha256"]
        if passed:
            passed = sha(output / "FptPaperStreamingKernel.xclbin") == manifest["xclbin_sha256"]
    elif passed and args.target == "xo":
        passed = (output / "build/FptPaperStreamingKernel.xo").is_file()
    elif passed and args.target == "kernel-synth":
        passed = (output / "build/kernel_post_synth_resources.tsv").is_file()
    elif passed and args.target == "dma-sim":
        logs = list((output / "build").glob("**/xsim/simulate.log"))
        passed = len(logs) == 1 and "PASS_PAPER_VENDOR_DMA" in logs[0].read_text()
        manifest["vendor_simulation_logs"] = [str(path) for path in logs]
    elif passed:
        passed = all((output / "build" / (name + "_utilization.rpt")).is_file()
                     for name in ("axi_datamover_mm2s", "axi_datamover_s2mm"))
    manifest["inputs_unchanged"] = all(sha(path) == value for path, value in hashes.items())
    manifest["snapshot_inputs_unchanged"] = all(sha(snapshots[path]) == value for path, value in hashes.items())
    manifest["passed"] = passed and manifest["snapshot_inputs_unchanged"]
    manifest["phase"] = "closed"
    manifest["finished_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"{'PASS' if manifest['passed'] else 'FAIL'} {args.target} kernel build: {output}")
    raise SystemExit(0 if manifest["passed"] else 1)


if __name__ == "__main__":
    main()
