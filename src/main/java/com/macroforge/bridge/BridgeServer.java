package com.macroforge.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Tiny localhost-only TCP server speaking newline-delimited JSON.
 * Writes its port + a random token to %APPDATA%\MacroForge\minecraft-bridge.json so only
 * MacroForge (which reads that file) can connect.
 */
public final class BridgeServer {
    public static final int BASE_PORT = 25599;
    private final String token;
    private final Consumer<JsonObject> onCommand;
    private final ConcurrentLinkedQueue<JsonObject> inbox = new ConcurrentLinkedQueue<>();
    private volatile Socket client;
    private volatile BufferedWriter out;
    private volatile boolean authed;
    private ServerSocket server;
    private int port;

    public BridgeServer(Consumer<JsonObject> onCommand) {
        this.onCommand = onCommand;
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        this.token = HexFormat.of().formatHex(b);
    }

    public boolean isConnected() { return client != null && authed; }

    public void start() {
        for (int p = BASE_PORT; p < BASE_PORT + 10; p++) {
            try {
                server = new ServerSocket(p, 1, InetAddress.getLoopbackAddress());
                port = p;
                break;
            } catch (IOException ignored) { }
        }
        if (server == null) { BridgeMod.LOG.error("[MacroForge] no free port for the bridge"); return; }
        writeBridgeFile();
        Thread t = new Thread(this::acceptLoop, "MacroForge-Bridge");
        t.setDaemon(true);
        t.start();
        BridgeMod.LOG.info("[MacroForge] bridge listening on 127.0.0.1:" + port);
    }

    static Path bridgeFile() {
        String appdata = System.getenv("APPDATA");
        Path dir = appdata != null ? Paths.get(appdata, "MacroForge") : Paths.get(System.getProperty("user.home"), ".macroforge");
        return dir.resolve("minecraft-bridge.json");
    }

    private void writeBridgeFile() {
        try {
            Path f = bridgeFile();
            Files.createDirectories(f.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("port", port);
            o.addProperty("token", token);
            o.addProperty("pid", ProcessHandle.current().pid());
            o.addProperty("version", BridgeMod.VERSION);
            Files.writeString(f, o.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            BridgeMod.LOG.error("[MacroForge] could not write bridge file", e);
        }
    }

    private void acceptLoop() {
        while (true) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                closeClient();
                client = s;
                authed = false;
                out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                Thread r = new Thread(() -> readLoop(s), "MacroForge-Bridge-Read");
                r.setDaemon(true);
                r.start();
            } catch (IOException e) {
                if (server.isClosed()) return;
            }
        }
    }

    private void readLoop(Socket s) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject msg;
                try { msg = JsonParser.parseString(line).getAsJsonObject(); } catch (Exception e) { continue; }
                String type = msg.has("type") ? msg.get("type").getAsString() : "";
                if (!authed) {
                    if ("hello".equals(type) && msg.has("token") && token.equals(msg.get("token").getAsString())) {
                        authed = true;
                        JsonObject w = new JsonObject();
                        w.addProperty("type", "welcome");
                        w.addProperty("version", BridgeMod.VERSION);
                        w.addProperty("minecraft", BridgeMod.minecraftVersion());
                        send(w);
                        BridgeMod.onClientConnected();
                    } else {
                        s.close();
                        return;
                    }
                    continue;
                }
                inbox.add(msg);
            }
        } catch (IOException ignored) {
        } finally {
            if (client == s) { client = null; authed = false; }
        }
    }

    /** Called on the client thread every tick: runs queued commands there (Minecraft isn't thread-safe). */
    public void drain() {
        JsonObject m;
        while ((m = inbox.poll()) != null) {
            try { onCommand.accept(m); }
            catch (Exception e) {
                JsonObject r = new JsonObject();
                r.addProperty("type", "result");
                if (m.has("id")) r.add("id", m.get("id"));
                r.addProperty("ok", false);
                r.addProperty("error", String.valueOf(e.getMessage()));
                send(r);
            }
        }
    }

    public synchronized void send(JsonObject o) {
        BufferedWriter w = out;
        if (w == null || client == null) return;
        try {
            w.write(o.toString());
            w.write('\n');
            w.flush();
        } catch (IOException e) {
            closeClient();
        }
    }

    private void closeClient() {
        Socket c = client;
        client = null;
        authed = false;
        if (c != null) try { c.close(); } catch (IOException ignored) { }
    }
}
