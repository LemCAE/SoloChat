#!/usr/bin/env node
/*
 * 下载 Gradle 发行版。
 *
 * 为什么用 Node 而不是 PowerShell 的 curl / HttpClient：这台机器上
 * PowerShell 子进程的任何 https 请求都在 TLS 握手阶段失败
 * （schannel 报 SEC_E_NO_CREDENTIALS，.NET 报 SSL connection could not be established），
 * 而 **Node 的 https 正常**（实测 npmmirror 200、腾讯镜像 200）。
 * 所以下载统一走 Node。
 *
 * 用法：node tools/download-gradle.mjs [目标zip路径]
 */
import fs from 'node:fs';
import path from 'node:path';
import https from 'node:https';
import { createHash } from 'node:crypto';

const VERSION = '8.9';
const DEST = process.argv[2]
    ?? path.resolve(process.cwd(), '.bootstrap', `gradle-${VERSION}-bin.zip`);

// 国内镜像优先（实测腾讯有完整的 136MB 文件），官方站兜底。
// 注意：不校验官方 SHA256 —— 多个镜像的归档字节可能不同（重新打包），
// 硬校验会让"镜像可用但哈希不同"变成死路。改为记录实际哈希，方便人工比对。
const SOURCES = [
    `https://mirrors.cloud.tencent.com/gradle/gradle-${VERSION}-bin.zip`,
    `https://downloads.gradle.org/distributions/gradle-${VERSION}-bin.zip`,
    `https://services.gradle.org/distributions/gradle-${VERSION}-bin.zip`
];

const MIN_BYTES = 80 * 1024 * 1024;   // 完整的 bin 包约 136MB；小于 80MB 一定是错误页

function head(url) {
    return new Promise((resolve) => {
        const req = https.request(url, { method: 'HEAD', timeout: 20000 }, (res) => {
            const len = Number(res.headers['content-length'] || 0);
            res.resume();
            resolve({ url, status: res.statusCode, len, location: res.headers.location });
        });
        req.on('timeout', () => { req.destroy(); resolve({ url, status: 0, len: 0 }); });
        req.on('error', (e) => resolve({ url, status: -1, len: 0, err: e.code || e.message }));
        req.end();
    });
}

function download(url, dest, depth = 0) {
    return new Promise((resolve, reject) => {
        if (depth > 5) return reject(new Error('重定向层数过多'));
        https.get(url, { timeout: 60000 }, (res) => {
            if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
                res.destroy();
                const next = new URL(res.headers.location, url).toString();
                console.log(`  重定向 -> ${next}`);
                return download(next, dest, depth + 1).then(resolve, reject);
            }
            if (res.statusCode !== 200) {
                res.destroy();
                return reject(new Error(`HTTP ${res.statusCode}`));
            }
            const total = Number(res.headers['content-length'] || 0);
            let got = 0;
            let lastTick = 0;
            const out = fs.createWriteStream(dest);
            res.on('data', (chunk) => {
                got += chunk.length;
                const pct = total > 0 ? Math.floor((got / total) * 100) : -1;
                if (pct >= 0 && pct >= lastTick + 10) {
                    lastTick = pct;
                    process.stdout.write(`\r  下载中 ${pct}%  (${(got / 1048576).toFixed(1)} MB)`);
                }
            });
            res.pipe(out);
            out.on('finish', () => {
                process.stdout.write('\n');
                out.close(() => resolve({ bytes: got, url }));
            });
            out.on('error', reject);
            res.on('error', reject);
        }).on('error', reject);
    });
}

async function main() {
    fs.mkdirSync(path.dirname(DEST), { recursive: true });

    if (fs.existsSync(DEST) && fs.statSync(DEST).size >= MIN_BYTES) {
        console.log(`[gradle] 已存在可用归档：${DEST}（${(fs.statSync(DEST).size / 1048576).toFixed(1)} MB）`);
        return;
    }

    console.log(`[gradle] 目标：Gradle ${VERSION} -> ${DEST}`);
    console.log('[gradle] 探测各来源：');
    const probes = await Promise.all(SOURCES.map(head));
    for (const p of probes) {
        console.log(`  ${p.status}  ${p.len ? (p.len / 1048576).toFixed(1) + ' MB' : '-'}  ${p.url}`);
    }

    const usable = probes
        .filter((p) => p.len >= MIN_BYTES)
        .sort((a, b) => b.len - a.len);

    if (usable.length === 0) {
        throw new Error('没有任何来源提供完整的归档（都小于 80MB）');
    }

    let done = null;
    for (const candidate of usable) {
        try {
            console.log(`[gradle] 开始从 ${candidate.url} 下载`);
            const r = await download(candidate.url, DEST);
            if (r.bytes >= MIN_BYTES) { done = r; break; }
            console.log(`  文件偏小（${r.bytes} 字节），换下一个源`);
        } catch (e) {
            console.log(`  失败：${e.message}，换下一个源`);
        }
    }
    if (!done) throw new Error('所有来源下载失败');

    const hash = createHash('sha256').update(fs.readFileSync(DEST)).digest('hex');
    console.log(`[gradle] 完成：${(done.bytes / 1048576).toFixed(1)} MB`);
    console.log(`[gradle] sha256 = ${hash}`);
    console.log(`[gradle] 来源 = ${done.url}`);
}

main().catch((e) => {
    console.error('[gradle] 失败：' + e.message);
    process.exit(1);
});
