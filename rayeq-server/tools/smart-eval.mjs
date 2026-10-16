#!/usr/bin/env node
// أداة تقييم البحث الذكي عبر ترويسة X-Smart-Test (بدون تضمين أي عناوين أو أسرار مدمجة)
const baseUrl = process.env.RAYEQ_SERVER_URL;
const smartTestSecret = process.env.SMART_TEST;
const query = process.argv[2];

if (!baseUrl || !smartTestSecret || !query) {
  console.error("Usage: RAYEQ_SERVER_URL=<YOUR_WORKER_URL> SMART_TEST=<SECRET> node tools/smart-eval.mjs '<query>'");
  process.exit(1);
}

const url = `${baseUrl.replace(/\/+$/, "")}/v1/smart-search?q=${encodeURIComponent(query)}&type=multi`;
const res = await fetch(url, {
  headers: { "X-Smart-Test": smartTestSecret }
});
const body = await res.json();
console.log(JSON.stringify({ status: res.status, body }, null, 2));
