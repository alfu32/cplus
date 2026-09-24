# C-plus Distribution

`cpc` launches the bundled CLI and discovers the adjacent `stdlib/` directory automatically. Run `install.sh` on Linux, `install.zsh` on macOS, or `install.cmd` on Windows. The matching uninstall script removes only the selected C-plus version. Windows installers register the user's `.cmdrc` through the Command Processor AutoRun key, update the user PATH, and create a `C+ Developer Console` Start Menu shortcut. Uninstall removes only C-plus-owned `.cmdrc`, AutoRun, PATH, and shortcut entries.

With `-Ptarget` omitted, `bundleDist` creates a distribution without TinyCC binaries or sysroots; `compile` and `run` require an externally installed `tcc` on `PATH` (or the executable named by `TCC`). Select one host payload or the all-host cross-build bundle with:

```sh
./gradlew -Prelease=0.3.3 -Ptarget=linux-x86_64 bundleDist
./gradlew -Prelease=0.3.3 -Ptarget=crossbuild bundleDist
```

Each `bundleDist` run produces a ZIP distribution and an equivalent standalone executable JAR beside it: `dist/cplus-VERSION-TARGET.zip` and `dist/cplus-VERSION-TARGET.jar`. If ZIPs were built before sidecar JAR output was added, run `./gradlew -Prelease=VERSION bundleJars` to extract a matching JAR for each versioned ZIP already in `dist/`.

Each host-specific bundle includes exactly its selected native TinyCC payload and matching sysroot when available; it is intended for native builds and does not carry other target sysroots. TinyCC does not include a macOS SDK. `-Ptarget=all` and `-Ptarget=crossbuild` both include all six host payloads plus the available Linux/Windows libc sysroots. The all-host bundle's root contains all three launchers (`cpc.sh`, `cpc.zsh`, `cpc.cmd`) and all three installer/uninstaller entry points (`install.sh`, `install.zsh`, `install.cmd`, `uninstall.sh`, `uninstall.zsh`, `uninstall.cmd`); platform copies are also under `install/`. Every bundle contains the CLI jar, C-plus standard library, language/compiler documentation, examples, launchers, and platform install scripts. Supported host target values match TinyCC's payload names: `linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`, `windows-x86_64`, and `windows-aarch64`.

The CI workflow tests the CLI, smoke-tests the Linux bundle and scaffolder, builds each of the six host combinations, and uploads both ZIP and standalone JAR artifacts for each target, including the complete `crossbuild` bundle.
