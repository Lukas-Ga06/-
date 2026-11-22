package com.travel.agent.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * OpenAPI 契约声明。
 *
 * <p>机器可校验的契约事实来源是 {@code GET /v3/api-docs}，由它生成的
 * {@code docs/api/openapi.json} 才是前端类型与破坏性变更检查的输入；
 * Swagger UI 只是本地阅读入口，业务语义仍以 {@code docs/guide/api-reference.md} 为准。
 *
 * <p>这里只放文档元数据（标题/版本/说明/本地地址），不注册任何业务 schema：
 * schema 一律由 Controller 的签名与 {@code dto/} 下的类型推导，避免出现第二份需要手工同步的定义。
 */
@Configuration
public class OpenApiConfig {

    /** 与 pom.xml 的 project.version 保持一致：契约版本随服务端版本走。 */
    public static final String API_VERSION = "0.1.0";

    @Bean
    public OpenAPI travelAgentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Travel Agent API")
                        .version(API_VERSION)
                        .description("""
                                多模态智能旅行 Agent 服务端 REST 契约。

                                - 机器契约：GET /v3/api-docs（快照见仓库 docs/api/openapi.json）
                                - 前端类型：travel-agent-web/src/api/generated/schema.d.ts（openapi-typescript 生成）
                                - 业务语义：docs/guide/api-reference.md；契约流程：docs/guide/api-contract.md
                                除登录/注册、渠道回调和健康检查外，所有接口都需要登录态（Authorization Bearer 或 httpOnly Cookie）。
                                """))
                .servers(List.of(new Server()
                        .url("http://localhost:8080")
                        .description("本地开发（默认 profile，端口见 server.port）")));
    }
}