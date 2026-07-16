# Singularity RTL test environment

The FPT Singularity image contains the non-Vivado toolchain needed to rebuild
and test the reference and RTL sources:

- Ubuntu 24.04 C/C++ and CMake packages;
- OpenJDK 21 and the project-pinned sbt 1.11.6 launcher;
- Verilator 5.026 built from its checksum-verified release tag;
- the resolved sbt artifacts for the FPT Chisel, SGen, and HOGE projects.

The source repositories are not copied into the image. They remain pinned Git
submodules and are bound read/write at their original absolute checkout path.
This keeps generated RTL and test results in the normal ignored build
directories and allows an existing CMake build tree to remain valid inside and
outside the container.

Vivado and Vitis are intentionally excluded. The prepared U280 handoff remains
the input to the separately installed Vivado environment on the synthesis
machine.

## Build the image

Initialize the repository and build the default SIF image:

```sh
git submodule update --init --recursive
tools/build_singularity.sh
```

The output is `build/singularity/fpt-verilator.sif`. The image build needs
network access for Ubuntu packages and the checksum-pinned sbt and Verilator
archives. It also resolves the current Scala project artifacts into a cache
seed stored inside the image.

Both Singularity CE and Apptainer are supported. Select a non-default runtime
with, for example:

```sh
FPT_SINGULARITY_RUNTIME=apptainer tools/build_singularity.sh
```

An alternative output path can be supplied as the first argument. A local
Singularity installation must permit definition-file builds. The wrapper uses
fakeroot automatically for a non-root caller; set `FPT_SINGULARITY_FAKEROOT`
to `0` or `1` to override that choice.

At execution time the wrapper probes normal container startup and falls back
to unprivileged `--userns` mode when the runtime has no setuid installation.
Set `FPT_SINGULARITY_USERNS` to `0` or `1` to override this automatic choice.

## Run the tests

Run the standard C++, TFHEpp, Chisel, and Verilator tests with:

```sh
tools/test_in_singularity.sh
```

`FPT_BUILD_JOBS` controls CMake build parallelism and defaults to four. The
physical paper-size numerical regressions retain their existing opt-in entry
points:

```sh
tools/run_singularity.sh tools/test_paper_sgen_numerics.sh
tools/run_singularity.sh \
  tools/test_paper_buffered_blind_rotate_numerics.sh
```

The latter uses a 16 GiB Java heap and therefore needs corresponding host
memory. Arbitrary commands and an interactive shell are also available:

```sh
tools/run_singularity.sh verilator --version
tools/run_singularity.sh bash -lc 'cd chisel && sbt test'
tools/run_singularity.sh
```

The runner copies the image's sbt cache seed once into
`build/singularity/cache`, which remains writable across invocations. Override
the image or cache location with `FPT_SINGULARITY_IMAGE` and
`FPT_SINGULARITY_CACHE_DIR`. It forwards `FPT_*` variables and `MAKEFLAGS` to
the contained command, while the host compiler, Java, sbt, and Verilator paths
are removed with Singularity's clean-environment mode.
