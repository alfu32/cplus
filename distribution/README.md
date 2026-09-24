# C-plus Distribution

`cpc` launches the bundled CLI and discovers the adjacent `stdlib/` directory automatically. Run `install.sh` on Linux, `install.zsh` on macOS, or `install.cmd` on Windows. The matching uninstall script removes only the selected C-plus version. Windows installers register the user's `.cmdrc` through the Command Processor AutoRun key, update the user PATH, and create a `C+ Developer Console` Start Menu shortcut. Uninstall removes only C-plus-owned `.cmdrc`, AutoRun, PATH, and shortcut entries.

With no platform properties, `bundleDist` creates a distribution without TinyCC binaries or sysroots; `compile` and `run` require an externally installed `tcc` on `PATH` (or the executable named by `TCC`). To embed a single target or all supported targets, pass:

```sh
./gradlew -Prelease=0.3.3 -Pos=linux -Parch=x86_64 bundleDist
./gradlew -Prelease=0.3.3 -Pos=all -Parch=all bundleDist
```

`all/all` contains the six supported TinyCC native targets and sysroots. Its root contains all three launchers (`cpc.sh`, `cpc.zsh`, `cpc.cmd`) and all three installer/uninstaller entry points (`install.sh`, `install.zsh`, `install.cmd`, `uninstall.sh`, `uninstall.zsh`, `uninstall.cmd`); platform copies are also under `install/`. Every bundle contains the CLI jar, C-plus standard library, language/compiler documentation, examples, launchers, and platform install scripts. `-Pos` accepts `linux|mac|win|all|none`, and `-Parch` accepts `x86_64|arm64|all|none`; both default to `none`, and `none` must be selected for both properties.

The CI workflow tests the CLI, smoke-tests the Linux bundle and scaffolder, builds each of the six OS/architecture combinations, and uploads a complete `all/all` bundle.
