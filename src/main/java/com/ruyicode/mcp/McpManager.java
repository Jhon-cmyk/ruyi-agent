package com.ruyicode.mcp;

import com.ruyicode.config.McpServerConfig;
import com.ruyicode.tool.McpToolLike;
import com.ruyicode.tool.Tool;
import com.ruyicode.tool.ToolCategory;
import com.ruyicode.tool.ToolRegistry;
import com.ruyicode.tool.ToolResult;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.*;
import java.util.regex.Pattern;

public class McpManager {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-zA-Z0-9_]");

    private static final Pattern ENV_VAR = Pattern.compile("\\$\\{([^}]+)}");

    /** MCP 工具名的固定前缀，权限层和过滤层都靠它认出 MCP 工具。 */
    public static final String MCP_TOOL_PREFIX = "mcp__";

    /** 服务器段和工具段之间的分隔符。 */
    public static final String MCP_NAME_SEP = "__";

    public record ServerInfo(String name, String instructions) {}
    public record ConnectResult(List<Tool> tools, List<ServerInfo> servers, List<String> errors) {}

    private final Map<String, McpServerConfig> configs = new LinkedHashMap<>();
    private final Map<String, McpServerClient> clients = new LinkedHashMap<>();

    public McpManager(List<McpServerConfig> configs) {
        if (configs != null) {
            for (var cfg : configs) this.configs.put(cfg.getName(), cfg);
        }
    }

    public ConnectResult connectAll() {
        var tools = new ArrayList<Tool>();
        var servers = new ArrayList<ServerInfo>();
        var errors = new ArrayList<String>();

        for (var entry : configs.entrySet()) {
            String name = entry.getKey();
            var cfg = entry.getValue();

            try {
                var client = createClient(cfg);
                // 先探测 2026-07-28 现代协议，老服务器自动回退 initialize 握手，见 McpServerClient
                client.connect();
                clients.put(name, client);

                servers.add(new ServerInfo(name, client.instructions()));

                for (var sdkTool : client.listTools()) {
                    tools.add(new McpToolWrapper(name, sdkTool, client));
                }
            } catch (Exception e) {
                errors.add("MCP server '" + name + "': " + e.getMessage());
            }
        }

        return new ConnectResult(List.copyOf(tools), List.copyOf(servers), List.copyOf(errors));
    }

    public List<String> registerAllTools(ToolRegistry registry) {
        var result = connectAll();
        for (var t : result.tools()) registry.register(t);
        return result.errors();
    }

    public void shutdown() {
        for (var client : clients.values()) {
            try { client.close(); } catch (Exception ignored) {}
        }
        clients.clear();
    }

    private McpServerClient createClient(McpServerConfig cfg) {
        if (cfg.getCommand() != null && !cfg.getCommand().isBlank()) {
            var paramsBuilder = ServerParameters.builder(windowsSafe(cfg.getCommand()));
            if (cfg.getArgs() != null) {
                paramsBuilder.args(cfg.getArgs());
            }
            if (cfg.getEnv() != null) {
                var resolvedEnv = new HashMap<String, String>();
                for (var e : cfg.getEnv().entrySet()) {
                    resolvedEnv.put(e.getKey(), resolveEnvVars(e.getValue()));
                }
                paramsBuilder.env(resolvedEnv);
            }
            return McpServerClient.stdio(paramsBuilder.build());
        }
        if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            var headers = new LinkedHashMap<String, String>();
            if (cfg.getHeaders() != null) {
                for (var e : cfg.getHeaders().entrySet()) {
                    headers.put(e.getKey(), resolveEnvVars(e.getValue()));
                }
            }
            return McpServerClient.streamableHttp(cfg.getUrl(), headers);
        }
        throw new IllegalArgumentException("Neither command nor url configured");
    }

    private static final Set<String> WIN_CMD_SUFFIXED = Set.of(
            "npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx");

    static String windowsSafe(String command) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return command;
        String base = command.toLowerCase();
        if (WIN_CMD_SUFFIXED.contains(base)) return command + ".cmd";
        return command;
    }

    public static String sanitizeName(String name) {
        return NON_ALNUM.matcher(name).replaceAll("_");
    }

    /**
     * 某个服务器下所有工具的公共名字前缀。
     *
     * <p>调用方按前缀筛工具时必须走这个方法，不能自己拼 {@code "mcp__" + name + "__"}：
     * 服务器名里的横杠、点在工具名里已经被换成下划线，拿原始配置名去比对会一条也匹配不上。
     */
    public static String mcpToolNamePrefix(String serverName) {
        return MCP_TOOL_PREFIX + sanitizeName(serverName) + MCP_NAME_SEP;
    }

    /** 拼出 MCP 工具在注册表里的完整名字。 */
    public static String buildMcpToolName(String serverName, String toolName) {
        return mcpToolNamePrefix(serverName) + sanitizeName(toolName);
    }

    static String resolveEnvVars(String value) {
        if (value == null) return null;
        return ENV_VAR.matcher(value).replaceAll(m -> {
            String env = System.getenv(m.group(1));
            return env != null ? env : m.group(0);
        });
    }

    // ── MCP Tool Wrapper ────────────────────────────────────────────────

    public static final class McpToolWrapper implements McpToolLike {
        private final String serverName;
        private final McpSchema.Tool sdkTool;
        private final McpServerClient client;

        /** eager 模式下由分流逻辑置为 true，工具因此直接进 tools[]。 */
        private volatile boolean noDefer;

        McpToolWrapper(String serverName, McpSchema.Tool sdkTool, McpServerClient client) {
            this.serverName = serverName;
            this.sdkTool = sdkTool;
            this.client = client;
        }

        @Override public String name() {
            return buildMcpToolName(serverName, sdkTool.name());
        }

        @Override public String description() {
            return sdkTool.description() != null ? sdkTool.description() : "";
        }

        @Override public ToolCategory category() { return ToolCategory.COMMAND; }
        @Override public boolean shouldDefer() { return !noDefer; }

        @Override public String mcpServerName() { return serverName; }

        @Override public void setDeferLoading(boolean on) { this.noDefer = !on; }

        @Override public Map<String, Object> mcpInputSchema() {
            return inputSchemaMap();
        }

        private Map<String, Object> inputSchemaMap() {
            var input = new LinkedHashMap<String, Object>();
            var jsonSchema = sdkTool.inputSchema();
            if (jsonSchema != null) {
                for (var key : List.of("type", "properties", "required")) {
                    if (jsonSchema.get(key) != null) input.put(key, jsonSchema.get(key));
                }
            }
            if (input.isEmpty()) {
                input.put("type", "object");
                input.put("properties", Map.of());
            }
            return input;
        }

        @Override public Map<String, Object> schema() {
            return Map.of("name", name(), "description", description(), "input_schema", inputSchemaMap());
        }

        @Override public ToolResult execute(Map<String, Object> args) {
            try {
                var result = client.callTool(sdkTool.name(), args != null ? args : Map.of());
                String text = extractTextContent(result);
                boolean isError = result.isError() != null && result.isError();
                return isError ? ToolResult.error(text) : ToolResult.success(text);
            } catch (Exception e) {
                return ToolResult.error("MCP tool call failed: " + e.getMessage());
            }
        }
    }

    private static String extractTextContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) return "(no output)";
        var sb = new StringBuilder();
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent tc) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(tc.text());
            }
        }
        return sb.isEmpty() ? "(no output)" : sb.toString();
    }
}
