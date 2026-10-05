const minimum = [20, 18, 1];
const actual = process.versions.node.split(".").map(Number);

function isSupported() {
  for (let index = 0; index < minimum.length; index += 1) {
    if ((actual[index] || 0) !== minimum[index]) {
      return (actual[index] || 0) > minimum[index];
    }
  }
  return true;
}

if (!isSupported()) {
  console.error(
    `C-plus VS Code packaging requires Node.js >= ${minimum.join(".")}; found ${process.version}. ` +
      "Use Node 22 (the CI toolchain) or a newer supported Node release."
  );
  process.exit(1);
}

