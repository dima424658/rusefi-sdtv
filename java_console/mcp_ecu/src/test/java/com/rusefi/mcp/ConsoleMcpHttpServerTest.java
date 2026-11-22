package com.rusefi.mcp;

import com.rusefi.binaryprotocol.BinaryProtocol;
import com.rusefi.io.LinkManager;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsoleMcpHttpServerTest {
    private static final String TOKEN = "test-token-only-abcdefghijklmnopqrstuvwxyz";
    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void authenticatesAndRunsTheStandaloneCatalogThroughTheConsoleConnection() throws Exception {
        LinkManager link = mock(LinkManager.class);
        BinaryProtocol first = mock(BinaryProtocol.class);
        BinaryProtocol second = mock(BinaryProtocol.class);
        first.signature = "first-console-ecu";
        second.signature = "second-console-ecu";
        when(link.getLastTriedPort()).thenReturn("console-port");

        try (ConsoleMcpHttpServer endpoint = new ConsoleMcpHttpServer(link, 0, TOKEN)) {
            assertEquals(401, post(endpoint, null, "{}", null).statusCode());
            assertEquals(401, post(endpoint, "wrong", "{}", null).statusCode());
            assertEquals(403, post(endpoint, TOKEN, "{}", "https://example.com").statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(uri(endpoint))
                    .header("Authorization", "Bearer " + TOKEN).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode());

            JSONObject initialize = result(endpoint, "initialize", "{\"protocolVersion\":\"2025-11-25\"}");
            assertEquals("2025-11-25", initialize.get("protocolVersion"));
            assertEquals(202, post(endpoint, TOKEN,
                    "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", null).statusCode());

            JSONObject catalog = result(endpoint, "tools/list", "{}");
            JSONArray tools = (JSONArray) catalog.get("tools");
            assertTrue(tools.containsAll((JSONArray) EcuMcpServer.toolsList().get("tools")));
            assertTrue(catalog.toJSONString().contains("\"read_tune_fields\""));
            assertTrue(catalog.toJSONString().contains("\"write_map\""));
            assertTrue(catalog.toJSONString().contains("\"update_firmware\""));

            assertEquals(true, call(endpoint, "ecu_info", "{}").get("isError"));
            when(link.isActive()).thenReturn(true);
            when(link.getBinaryProtocol()).thenReturn(first);
            assertEquals("first-console-ecu", body(call(endpoint, "ecu_info", "{}")).get("signature"));
            assertEquals(true, call(endpoint, "read_tune_fields", "{}").get("isError"));
            assertEquals(true, call(endpoint, "connect", "{\"port\":\"different-port\"}").get("isError"));
            when(link.getBinaryProtocol()).thenReturn(second);
            assertEquals("second-console-ecu", body(call(endpoint, "ecu_info", "{}")).get("signature"));
        }

        verify(link, never()).close();
        verify(first, never()).close();
        verify(second, never()).close();
    }

    @Test
    void acceptsLocalRequestsWithoutATokenButRejectsBrowserOrigins() throws Exception {
        LinkManager link = mock(LinkManager.class);
        try (ConsoleMcpHttpServer endpoint = new ConsoleMcpHttpServer(link, 0, null)) {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
            HttpResponse<String> response = post(endpoint, null, request, null);
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("\"write_map\""));
            assertEquals(403, post(endpoint, null, request, "https://example.com").statusCode());
        }
        verify(link, never()).close();
    }

    private static JSONObject body(JSONObject result) {
        return (JSONObject) result.get("structuredContent");
    }

    private JSONObject call(ConsoleMcpHttpServer endpoint, String tool, String args) throws Exception {
        return result(endpoint, "tools/call", "{\"name\":\"" + tool + "\",\"arguments\":" + args + "}");
    }

    private JSONObject result(ConsoleMcpHttpServer endpoint, String method, String params) throws Exception {
        HttpResponse<String> response = post(endpoint, TOKEN, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\""
                + method + "\",\"params\":" + params + "}", null);
        assertEquals(200, response.statusCode());
        JSONObject message = (JSONObject) new JSONParser().parse(response.body());
        assertEquals(1L, message.get("id"));
        assertNotNull(message.get("result"), response.body());
        return (JSONObject) message.get("result");
    }

    private HttpResponse<String> post(ConsoleMcpHttpServer endpoint, String token, String json, String origin)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.POST(HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(ConsoleMcpHttpServer endpoint) {
        return URI.create("http://127.0.0.1:" + endpoint.getPort() + "/mcp");
    }
}
