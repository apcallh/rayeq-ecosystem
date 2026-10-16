#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire("/usr/share/nodejs/");
const babel = require("@babel/core");
const presetTs = require("@babel/preset-typescript");

const srcDir = path.resolve("rayeq-server/src");
const outDir = path.resolve("rayeq-server/dist");
fs.mkdirSync(outDir, { recursive: true });

for (const file of fs.readdirSync(srcDir)) {
  if (!file.endsWith(".ts")) continue;
  const inPath = path.join(srcDir, file);
  let code = fs.readFileSync(inPath, "utf-8");
  // Ensure relative imports end with .js for Node ESM execution
  code = code.replace(/from\s+["'](\.\/[^"'.]+)["']/g, 'from "$1.js"');
  const res = babel.transformSync(code, {
    filename: file,
    presets: [presetTs],
    sourceMaps: false,
  });
  const outPath = path.join(outDir, file.replace(/\.ts$/, ".js"));
  fs.writeFileSync(outPath, res.code, "utf-8");
  console.log(`Compiled ${file} -> dist/${path.basename(outPath)}`);
}
