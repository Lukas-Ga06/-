package com.travel.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP stdio 传输：本机子进程，按行收发 JSON-RPC（MCP stdio 帧格式）。
 * 读线程把带 id 的响应按 id 配对唤醒等待者；通知与日志行忽略。
 * 用例：npx 启动的高德地图 MCP server 等本机工具。
 */
@Slf4j
public class StdioMcpTransport implements McpTransport {

    private final Process process;
    private final BufferedWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong();
    private final long timeoutMs;
    private final Thread readerThread;

    public StdioMcpTransport(List<String> command, Map<String, String> env, long timeoutMs) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("stdio MCP server 未配置启动命令 (command)");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env);
        }
        // stderr 直接透传到应用控制台，方便排查 server 侧报错；stdout 专用于协议
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        this.process = pb.start();
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.timeoutMs = timeoutMs;
        this.readerThread = new Thread(this::readLoop, "mcp-stdio-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
        log.info("MCP stdio 子进程已启动: {}", command);
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (IOException e) {
                    log.debug("MCP stdio 非 JSON 输出: {}", line);
                    continue;
                }
                if (node.has("id") && node.path("id").canConvertToLong()) {
                    CompletableFuture<JsonNode> future = pending.remove(node.path("id").asLong());
                    if (future != null) {
                        future.complete(node);
                    }
                }
                // 通知（notifications/*）与服务端日志行：忽略
            }
        } catch (IOException e) {
            log.warn("MCP stdio 读线程结束: {}", e.getMessage());
        } finally {
            failAllPending("MCP 子进程已退出");
        }
    }

    private void failAllPending(String message) {
        pending.forEach((id, future) -> future.completeExceptionally(new IOException(message)));
        pending.clear();
    }

    @Override
    public JsonNode request(JsonNode requestBody) throws Exception {
        long id = idSeq.incrementAndGet();
        ((com.fasterxml.jackson.databind.node.ObjectNode) requestBody).put("id", id).put("jsonrpc", "2.0");
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            writeLine(requestBody.toString());
            JsonNode response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (response == null || !response.has("id")) {
                throw new IOException("MCP 子进程返回了不匹配的响应");
            }
            return response;
        } catch (TimeoutException e) {
            pending.remove(id);
            throw new IOException("MCP 子进程响应超时 (" + timeoutMs + "ms)");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(e.getCause() == null ? "MCP 子进程调用失败" : e.getCause().getMessage(), e.getCause());
        } finally {
            pending.remove(id);
        }
    }

    @Override
    public void notify(JsonNode notification) throws Exception {
        ((com.fasterxml.jackson.databind.node.ObjectNode) notification).put("jsonrpc", "2.0");
        writeLine(notification.toString());
    }

    private synchronized void writeLine(String json) throws IOException {
        if (!process.isAlive()) {
            throw new IOException("MCP 子进程已退出 (exit=" + process.exitValue() + ")");
        }
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    @Override
    public void close() {
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        failAllPending("MCP 子进程已关闭");
    }
}
