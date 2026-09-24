# C-plus Distribution

`cpc` launches the bundled CLI and discovers the adjacent `stdlib/` directory automatically. Run `install.sh` on Linux, `install.zsh` on macOS, or `install.cmd` on Windows. The matching uninstall script removes only the selected C-plus version. Windows installers register the user's `.cmdrc` through the Command Processor AutoRun key, update the user PATH, and create a `C+ Developer Console` Start Menu shortcut. Uninstall removes only C-plus-owned `.cmdrc`, AutoRun, PATH, and shortcut entries.

With `-Ptarget` omitted (or set to `none`), the CLI JAR contains no TinyCC native binaries or sysroots; `compile` and `run` require an externally installed `tcc` on `PATH` (or the executable named by `TCC`). The cross-host JAR contains six host drivers but no sysroots:

```sh
./gradlew -Prelease=0.3.3 -Ptarget=none bundleJar
./gradlew -Prelease=0.3.3 -Ptarget=cross bundleJar
```

`bundleJar` produces `dist/cplus-VERSION-bare.jar` or `dist/cplus-VERSION-cross-no-sysroots.jar`. `bundleDist` remains available for optional local platform ZIP bundles, but CI currently builds and publishes no C-plus platform ZIPs. Editor plugin ZIPs remain separate deployable IDE artifacts.

The cross JAR's host drivers support TinyCC's six payload names: `linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`, `windows-x86_64`, and `windows-aarch64`. It does not include libc/sysroot files. Native builds use system `tcc` when the selected host payload has no sysroot; foreign-target builds need externally provided target headers, libraries, and any required sysroot. `-Ptarget=all` and `-Ptarget=crossbuild` remain aliases for `cross`.

CI refreshes `tinycc-cli.jar` and `tinycc-cross-cli-no-sysroots.jar` from the latest TinyCC release, tests the CLI against system TinyCC, and publishes the bare JAR, cross-host JAR, and IDE plugin artifacts on tagged runs. Locally, use `bash .github/scripts/download-tinycc.sh latest tinycc-cross-cli-no-sysroots.jar` to refresh the ignored TinyCC inputs.
