const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");

const extensionRoot = path.resolve(__dirname, "..");

function resolveVersion(environment = process.env) {
  const releaseOverride = environment.CPLUS_RELEASE_VERSION?.trim();
  if (releaseOverride) return releaseOverride;

  const generatedVersionPath = path.resolve(
    extensionRoot,
    "../cli/src/main/kotlin/cplus/Version.kt"
  );
  if (fs.existsSync(generatedVersionPath)) {
    const source = fs.readFileSync(generatedVersionPath, "utf8");
    const match = /val version:\s*String\s*=\s*"([^"]+)"/.exec(source);
    if (match) return match[1];
  }

  const repositoryRoot = path.resolve(extensionRoot, "..");
  for (const args of [
    ["describe", "--tags", "--abbrev=0"],
    ["rev-parse", "--short=12", "HEAD"]
  ]) {
    const result = spawnSync("git", ["-C", repositoryRoot, ...args], { encoding: "utf8" });
    const version = result.status === 0 ? result.stdout.trim() : "";
    if (version) return version;
  }

  const manifest = JSON.parse(
    fs.readFileSync(path.join(extensionRoot, "package.json"), "utf8")
  );
  return manifest.version;
}

function packageExtension() {
  const version = resolveVersion();
  const outputPath = path.join("dist", `cplus-language-support-${version}.vsix`);
  fs.mkdirSync(path.dirname(path.join(extensionRoot, outputPath)), { recursive: true });

  const result = spawnSync(
    "vsce",
    [
      "package",
      version,
      "--no-update-package-json",
      "--no-git-tag-version",
      "--no-dependencies",
      "--out",
      outputPath
    ],
    {
      cwd: extensionRoot,
      stdio: "inherit",
      shell: process.platform === "win32"
    }
  );

  if (result.error) {
    console.error(`Failed to run vsce: ${result.error.message}`);
    process.exitCode = 1;
  } else {
    process.exitCode = result.status ?? 1;
  }
}

if (require.main === module) packageExtension();

module.exports = { resolveVersion };
