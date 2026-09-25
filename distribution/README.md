# C-plus Distribution

`cpc` launches the bundled CLI and discovers the adjacent `stdlib/` directory automatically. Run `install.sh` on Linux, `install.zsh` on macOS, or `install.cmd` on Windows. The matching uninstall script removes only the selected C-plus version. Windows installers register the user's `.cmdrc` through the Command Processor AutoRun key, update the user PATH, and create a `C+ Developer Console` Start Menu shortcut. Uninstall removes only C-plus-owned `.cmdrc`, AutoRun, PATH, and shortcut entries.

The two application distributions are:

- `cplus-VERSION-cross-no-sysroots.zip`: contains the six TinyCC host runtimes, but no sysroot. Native compiles use a system toolchain so host headers and libraries are used; cross-target builds need external target headers/libraries.
- `cplus-VERSION-bare.zip`: contains no TinyCC runtime and uses an external compiler.

Both archives contain the CLI, standard library, documentation, examples, launchers, and install/uninstall scripts for Linux, macOS, and Windows. Linux installation registers a user-level `.cp`/`.c+` MIME icon without changing the default editor; macOS registers a C-plus source UTI and icon as an alternate Viewer; Windows registers the matching icon for the current user while preserving existing open-with defaults.

```sh
./gradlew -Prelease=VERSION -Ptarget=cross bundleDist
./gradlew -Prelease=VERSION -Ptarget=none bundleDist
```

Run both commands to write the two versioned ZIPs under `dist/`. Each `bundleDist` invocation builds the distribution selected by `-Ptarget`. The standalone `bundleJar` task remains available when only a JAR is wanted. Editor plugin bundles are separate release assets.

The cross JAR's host drivers support TinyCC's six payload names: `linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`, `windows-x86_64`, and `windows-aarch64`. It does not include libc/sysroot files. Native builds use system `tcc` when the selected host payload has no sysroot; foreign-target builds need externally provided target headers, libraries, and any required sysroot. `-Ptarget=all` and `-Ptarget=crossbuild` remain aliases for `cross`.

CI refreshes `tinycc-cli.jar` and `tinycc-cross-cli-no-sysroots.jar` from the latest TinyCC release, tests both application distributions, and publishes the two application ZIPs and IDE plugin artifacts on tagged runs. Locally, use `bash .github/scripts/download-tinycc.sh latest tinycc-cross-cli-no-sysroots.jar` to refresh the ignored TinyCC inputs.
