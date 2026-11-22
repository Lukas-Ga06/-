package com.travel.agent;

import com.travel.agent.agent.AgentContext;
import com.travel.agent.agent.AgentEngine;
import com.travel.agent.dto.AgentDtos;
import com.travel.agent.dto.TripDtos;
import com.travel.agent.entity.Trip;
import com.travel.agent.service.TripService;
import com.travel.agent.support.IntegrationTestSupport;
import com.travel.agent.tool.AgentTool;
import com.travel.agent.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Agent 层的对外契约：工具只读、业务错误原样透出 */
class AgentContractTest extends IntegrationTestSupport {

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private AgentEngine agentEngine;

    @Autowired
    private TripService tripService;

    @Test
    @DisplayName("注册表里只有只读工具，写工具不会出现")
    void registryContainsOnlyReadOnlyTools() {
        assertThat(toolRegistry.all().values()).allMatch(AgentTool::readOnly);
        assertThat(toolRegistry.all().keySet())
                .doesNotContain("add_itinerary_item", "modify_itinerary_item", "undo_operation");
    }

    @Test
    @DisplayName("越权访问他人行程时，返回的是业务提示而不是「服务端异常」")
    void foreignTripSurfacesBusinessMessage() throws Exception {
        Account owner = registerNew();
        Account intruder = registerNew();
        TripDtos.TripRequest request = new TripDtos.TripRequest();
        request.setName("他人的行程");
        request.setDestination("上海");
        request.setBudget(BigDecimal.valueOf(3000));
        Trip trip = tripService.create(owner.id(), request);

        AgentContext ctx = new AgentContext();
        ctx.setUserId(intruder.id());
        ctx.setUsername(intruder.username());
        ctx.setTripId(trip.getId());
        ctx.setSessionId(UUID.randomUUID().toString());
        ctx.setMessage("明天下雨，把户外景点换掉");

        AgentDtos.AgentResult result = agentEngine.handle(ctx);

        assertThat(result.getStatus()).isEqualTo(AgentDtos.STATUS_FAILED);
        assertThat(result.getMessage()).contains("无权访问");
        assertThat(result.getMessage()).doesNotContain("服务端异常");
    }
}
