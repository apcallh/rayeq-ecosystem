#!/usr/bin/env node
import crypto from "node:crypto";

const password = process.argv[2];
if (!password) {
  console.error("Usage: node tools/hash-pass.mjs '<password>'");
  process.exit(1);
}
const iterations = 100000;
const salt = crypto.randomBytes(16);
const hash = crypto.pbkdf2Sync(Buffer.from(password, "utf-8"), salt, iterations, 32, "sha256");
console.log(`pbkdf2$sha256$${iterations}$${salt.toString("base64")}$${hash.toString("base64")}`);
