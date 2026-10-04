package com.ruyicode.mcp;

import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.HttpHeaders;
import io.modelcontextprotocol.spec.McpClientSession;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportException;
import io.modelcontextprotocol.spec.ProtocolVersions;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 连接单个 MCP 服务器，两代协议都能说。
 *
 * <p>连接流程：先发 {@code server/discover} 按 2026-07-28 无状态协议探测。服务器在
 * supportedVersions 里列出 2026-07-28 就走现代协议，之后每个请求在 {@code params._meta}
 * 里带协议版本、客户端能力和客户端信息，不做握手。探测返回 JSON-RPC 错误、HTTP 4xx
 * 或超时，就回退到 {@code initialize} + {@code notifications/initialized} 握手，按 2025-11-25
 * 及更早的协议走。网络不通、进程起不来这类错误不是协议问题，直接报错。
 *
 * <p>Java SDK 目前只实现到 2025-11-25，高层的 McpSyncClient 在每个请求前都强制握手，
 * 所以这里直接用 SDK 的传输层（stdio / Streamable HTTP）、请求应答配对（McpClientSession）
 * 和 McpSchema 类型，探测和回退自己做。
 */
public final class McpServerClient implements AutoCloseable {

    static final String MODERN_PROTOCOL_VERSION = "2026-07-28";
    static final String LEGACY_PROTOCOL_VERSION = ProtocolVersions.MCP_2025_11_25;

    static final String META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion";
    static final String META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities";
    static final String META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo";

    static final String METHOD_DISCOVER = "server/discover";

    /** 老服务器对不认识的方法可能不回应，探测等这么久没结果就回退握手。 */
    static final Duration DISCOVER_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private static final List<String> LEGACY_VERSIONS = List.of(ProtocolVersions.MCP_2025_11_25,
            ProtocolVersions.MCP_2025_06_18, ProtocolVersions.MCP_2025_03_26, ProtocolVersions.MCP_2024_11_05);

    private static final McpSchema.Implementation CLIENT_INFO = McpSchema.Implementation.builder("ruyicode", "0.1.0").build();

    private static final TypeRef<Map<String, Object>> MAP_TYPE = new TypeRef<>() {};

    private final Function<McpServerClient, McpClientTransport> transportFactory;
    /** HTTP 传输在收到第一个响应时就锁定会话 ID，回退握手要换一个新传输；stdio 必须沿用同一个子进程。 */
    private final boolean freshTransportOnFallback;

    private McpClientTransport transport;
    private McpClientSession session;

    /** 协商出的协议版本，连接前为 null。 */
    private volatile String protocolVersion;
    private String instructions = "";

    private McpServerClient(Function<McpServerClient, McpClientTransport> transportFactory,
                            boolean freshTransportOnFallback) {
        this.transportFactory = transportFactory;
        this.freshTransportOnFallback = freshTransportOnFallback;
    }

    public static McpServerClient stdio(ServerParameters params) {
        return new McpServerClient(c -> new StdioClientTransport(params, McpJsonDefaults.getMapper()), false);
    }

    public static McpServerClient streamableHttp(String url, Map<String, String> headers) {
        return new McpServerClient(c -> {
            var requestBuilder = HttpRequest.newBuilder();
            headers.forEach(requestBuilder::header);
            return HttpClientStreamableHttpTransport.builder(url)
                    .requestBuilder(requestBuilder)
                    .httpRequestCustomizer((builder, method, uri, body, ctx) -> c.stampHeaders(builder, body))
                    .build();
        }, true);
    }

    public void connect() {
        openSession();
        if (tryDiscover()) return;
        if (freshTransportOnFallback) {
            closeSession();
            openSession();
        }
        initialize();
    }

    public boolean isModern() {
        return MODERN_PROTOCOL_VERSION.equals(protocolVersion);
    }

    public String protocolVersion() {
        return protocolVersion;
    }

    public String instructions() {
        return instructions;
    }

    /** 拉取全部工具，自动翻页。 */
    public List<McpSchema.Tool> listTools() {
        var tools = new ArrayList<McpSchema.Tool>();
        String cursor = null;
        do {
            var params = new McpSchema.PaginatedRequest(cursor, requestMeta());
            var page = block(session.sendRequest(McpSchema.METHOD_TOOLS_LIST, params,
                    new TypeRef<McpSchema.ListToolsResult>() {}), REQUEST_TIMEOUT);
            if (page.tools() != null) tools.addAll(page.tools());
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isEmpty());
        return tools;
    }

    public McpSchema.CallToolResult callTool(String name, Map<String, Object> args) {
        var params = new McpSchema.CallToolRequest(name, args, requestMeta());
        var raw = block(session.sendRequest(McpSchema.METHOD_TOOLS_CALL, params, MAP_TYPE), REQUEST_TIMEOUT);
        // 现代服务器需要客户端补充输入（elicitation / sampling）时返回 input_required，RuyiCode 不提供这类能力
        if ("input_required".equals(raw.get("resultType"))) {
            throw new IllegalStateException("MCP server requested client input, which is not supported");
        }
        return transport.unmarshalFrom(raw, new TypeRef<McpSchema.CallToolResult>() {});
    }

    @Override
    public void close() {
        closeSession();
    }

    // ── 连接与协议协商 ─────────────────────────────────────────────────

    private void openSession() {
        transport = transportFactory.apply(this);
        // 老协议服务器可能发 ping 保活，回一个空结果
        Map<String, McpClientSession.RequestHandler<?>> handlers =
                Map.of(McpSchema.METHOD_PING, params -> Mono.just(Map.of()));
        session = new McpClientSession(REQUEST_TIMEOUT, transport, handlers, Map.of(), mono -> mono);
    }

    private void closeSession() {
        try {
            if (session != null) session.close();
            if (transport != null) transport.closeGracefully().block(Duration.ofSeconds(5));
        } catch (Exception ignored) {
        }
    }

    /** 返回 true 表示服务器支持 2026-07-28，已按现代协议就绪；false 表示要回退握手。 */
    private boolean tryDiscover() {
        Map<String, Object> result;
        try {
            result = block(session.sendRequest(METHOD_DISCOVER, Map.of("_meta", modernMeta()), MAP_TYPE),
                    DISCOVER_TIMEOUT);
        } catch (McpError | McpTransportException e) {
            return false;
        } catch (RuntimeException e) {
            if (Exceptions.unwrap(e) instanceof TimeoutException) return false;
            throw e;
        }
        // 能回应 discover 但只列出握手时代的版本，同样按老协议处理
        if (!(result.get("supportedVersions") instanceof List<?> versions)
                || !versions.contains(MODERN_PROTOCOL_VERSION)) {
            return false;
        }
        protocolVersion = MODERN_PROTOCOL_VERSION;
        instructions = result.get("instructions") instanceof String s ? s : "";
        return true;
    }

    private void initialize() {
        var request = McpSchema.InitializeRequest.builder(LEGACY_PROTOCOL_VERSION,
                McpSchema.ClientCapabilities.builder().build(), CLIENT_INFO).build();
        var result = block(session.sendRequest(McpSchema.METHOD_INITIALIZE, request,
                new TypeRef<McpSchema.InitializeResult>() {}), REQUEST_TIMEOUT);
        if (!LEGACY_VERSIONS.contains(result.protocolVersion())) {
            throw new IllegalStateException("Unsupported protocol version from server: " + result.protocolVersion());
        }
        protocolVersion = result.protocolVersion();
        instructions = result.instructions() != null ? result.instructions() : "";
        block(session.sendNotification(McpSchema.METHOD_NOTIFICATION_INITIALIZED, null), REQUEST_TIMEOUT);
    }

    /** 现代协议下每个请求都带的 _meta；老协议返回 null，序列化时整个字段省略。 */
    private Map<String, Object> requestMeta() {
        return isModern() ? modernMeta() : null;
    }

    private static Map<String, Object> modernMeta() {
        var meta = new LinkedHashMap<String, Object>();
        meta.put(META_PROTOCOL_VERSION, MODERN_PROTOCOL_VERSION);
        // RuyiCode 只用 tools，不声明 roots / sampling / elicitation
        meta.put(META_CLIENT_CAPABILITIES, Map.of());
        meta.put(META_CLIENT_INFO, Map.of("name", CLIENT_INFO.name(), "version", CLIENT_INFO.version()));
        return meta;
    }

    /**
     * 按请求体补齐 Streamable HTTP 请求头。现代请求带 MCP-Protocol-Version 和 Mcp-Method，
     * tools/call 再带 Mcp-Name，服务器会拿这些头和请求体核对；老协议请求只带协商出的版本号。
     */
    void stampHeaders(HttpRequest.Builder builder, String body) {
        if (body == null || body.isEmpty()) return;
        Map<String, Object> message;
        try {
            message = McpJsonDefaults.getMapper().readValue(body, MAP_TYPE);
        } catch (Exception e) {
            return;
        }
        var params = message.get("params") instanceof Map<?, ?> p ? p : Map.of();
        var meta = params.get("_meta") instanceof Map<?, ?> m ? m : Map.of();
        if (meta.get(META_PROTOCOL_VERSION) instanceof String version
                && message.get("method") instanceof String method) {
            builder.setHeader(HttpHeaders.PROTOCOL_VERSION, version);
            builder.setHeader("Mcp-Method", method);
            if (McpSchema.METHOD_TOOLS_CALL.equals(method) && params.get("name") instanceof String toolName) {
                builder.setHeader("Mcp-Name", toolName);
            }
        } else if (protocolVersion != null) {
            builder.setHeader(HttpHeaders.PROTOCOL_VERSION, protocolVersion);
        }
    }

    /** 带超时阻塞等结果；超时抛出的异常里包着 TimeoutException。 */
    private static <T> T block(Mono<T> mono, Duration timeout) {
        return mono.timeout(timeout).block();
    }
}
