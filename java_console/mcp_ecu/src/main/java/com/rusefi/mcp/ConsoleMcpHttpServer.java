package com.rusefi.mcp;

import com.devexperts.logging.Logging;
import com.rusefi.io.LinkManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local Streamable HTTP MCP transport for the Console-owned ECU connection. */
@SuppressWarnings("unchecked")
public final class ConsoleMcpHttpServer implements AutoCloseable {
    private static final Logging LOG = Logging.getLogging(ConsoleMcpHttpServer.class);
    private static final String PATH = "/mcp";
    private static final String LATEST = "2025-11-25";
    private static final int MAX_REQUEST_BYTES = 1024 * 1024;

    private final HttpServer http;
    private final ExecutorService worker;
    private final EcuMcpServer ecu;
    private final LinkManager link;
    private ConsoleEcuSession reads;
    private volatile boolean closed;
    private final byte[] authorization;

    public ConsoleMcpHttpServer(LinkManager link, int port, String token) throws IOException {
        if (token != null && !token.matches("[A-Za-z0-9._~+/=-]{32,}")) {
            throw new IllegalArgumentException("Set RUSEFI_CONSOLE_MCP_TOKEN to a random token of at least 32 characters.");
        }
        authorization = token == null ? null : ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 8);
        worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Console MCP");
            thread.setDaemon(true);
            return thread;
        });
        this.link = link;
        ecu = new EcuMcpServer(link);
        http.setExecutor(worker);
        http.createContext(PATH, this::handle);
        http.start();
    }

    public int getPort() {
        return http.getAddress().getPort();
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        http.stop(0);
        worker.shutdownNow();
        if (reads != null) {
            reads.close();
        }
        ecu.shutdown();
    }

    private synchronized ConsoleEcuSession reads() throws IOException {
        if (closed) {
            throw new IOException("Console MCP has closed.");
        }
        if (reads == null || !reads.isCurrent()) {
            if (reads != null) {
                reads.close();
            }
            reads = new ConsoleEcuSession(link);
        }
        return reads;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!PATH.equals(exchange.getRequestURI().getRawPath())) {
                respond(exchange, 404, null);
                return;
            }
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (exchange.getRequestHeaders().containsKey("Origin")
                    || !Arrays.asList("127.0.0.1:" + getPort(), "localhost:" + getPort()).contains(host)) {
                respond(exchange, 403, null);
                return;
            }
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && (header == null
                    || !MessageDigest.isEqual(authorization, header.getBytes(StandardCharsets.US_ASCII)))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                respond(exchange, 401, null);
                return;
            }
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "POST");
                respond(exchange, 405, null);
                return;
            }

            String version = exchange.getRequestHeaders().getFirst("MCP-Protocol-Version");
            if (version != null && !supports(version)) {
                respond(exchange, 400, rpcError(null, -32600, "Unsupported MCP protocol version."));
                return;
            }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !"application/json".equalsIgnoreCase(type.split(";", 2)[0].trim())) {
                respond(exchange, 415, null);
                return;
            }

            Object request;
            try {
                request = new JSONParser().parse(readRequest(exchange.getRequestBody()));
            } catch (ParseException e) {
                respond(exchange, 400, rpcError(null, -32700, "Invalid JSON."));
                return;
            } catch (RequestTooLarge e) {
                respond(exchange, 413, null);
                return;
            }
            if (!(request instanceof JSONObject)) {
                respond(exchange, 400, rpcError(null, -32600, "Expected a JSON-RPC object."));
                return;
            }
            JSONObject message = (JSONObject) request;
            Object id = message.get("id");
            if (!"2.0".equals(message.get("jsonrpc"))
                    || (id != null && !(id instanceof String) && !(id instanceof Number))) {
                respond(exchange, 400, rpcError(null, -32600, "Invalid JSON-RPC message."));
                return;
            }
            Object method = message.get("method");
            boolean notification = !message.containsKey("id") && method instanceof String;
            boolean response = method == null && message.containsKey("id")
                    && (message.containsKey("result") || message.containsKey("error"));
            if (notification || response) {
                respond(exchange, 202, null);
                return;
            }
            if (id == null || !(method instanceof String)) {
                respond(exchange, 400, rpcError(null, -32600, "Invalid JSON-RPC request."));
                return;
            }
            Object params = message.get("params");
            if (params != null && !(params instanceof JSONObject)) {
                respond(exchange, 200, rpcError(id, -32602, "Expected object parameters."));
                return;
            }
            JSONObject arguments = params == null ? new JSONObject() : (JSONObject) params;
            respond(exchange, 200, dispatch(id, (String) method, arguments));
        } finally {
            exchange.close();
        }
    }

    private JSONObject dispatch(Object id, String method, JSONObject params) {
        try {
            JSONObject result;
            switch (method) {
                case "initialize":
                    Object requested = params.get("protocolVersion");
                    result = object("protocolVersion", supports(requested) ? requested : LATEST,
                            "capabilities", object("tools", new JSONObject()),
                            "serverInfo", object("name", "rusefi-console", "version", "1.0"));
                    break;
                case "ping":
                    result = new JSONObject();
                    break;
                case "tools/list":
                    result = EcuMcpServer.toolsList();
                    ConsoleEcuSession.appendAdditionalTools((JSONArray) result.get("tools"));
                    break;
                case "tools/call":
                    Object args = params.get("arguments");
                    if (!(params.get("name") instanceof String) || (args != null && !(args instanceof JSONObject))) {
                        return rpcError(id, -32602, "Expected a tool name and object arguments.");
                    }
                    String name = (String) params.get("name");
                    JSONObject arguments = args == null ? new JSONObject() : (JSONObject) args;
                    result = callTool(name, arguments);
                    break;
                default:
                    return rpcError(id, -32601, "Method not found.");
            }
            return object("jsonrpc", "2.0", "id", id, "result", result);
        } catch (Exception e) {
            LOG.error("Console MCP request failed", e);
            return rpcError(id, -32603, "Console MCP request failed.");
        }
    }

    private JSONObject callTool(String name, JSONObject args) throws Exception {
        if (!ConsoleEcuSession.isAdditionalTool(name)) {
            return ecu.toolsCall(object("name", name, "arguments", args));
        }
        try {
            return EcuMcpServer.wrapToolResult(reads().execute(name, args, () -> {
                if (closed || Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Console MCP has closed.");
                }
            }));
        } catch (IOException | CancellationException e) {
            return EcuMcpServer.toolError(e.getMessage());
        }
    }

    private static boolean supports(Object version) {
        return LATEST.equals(version) || "2025-06-18".equals(version);
    }

    private static String readRequest(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int count;
        while ((count = input.read(chunk)) != -1) {
            if (bytes.size() + count > MAX_REQUEST_BYTES) {
                throw new RequestTooLarge();
            }
            bytes.write(chunk, 0, count);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, JSONObject body) throws IOException {
        if (status >= 400) {
            // A rejected POST can still have an unread body. Don't reuse that connection.
            exchange.getResponseHeaders().set("Connection", "close");
        }
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static JSONObject rpcError(Object id, int code, String message) {
        return object("jsonrpc", "2.0", "id", id, "error", object("code", code, "message", message));
    }

    private static JSONObject object(Object... pairs) {
        JSONObject result = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private static final class RequestTooLarge extends IOException {
    }
}
