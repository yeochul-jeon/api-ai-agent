package com.apiagent.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import tools.jackson.databind.ObjectMapper;

/**
 * 멀티턴 에이전트 루프 (ChatClient 기반).
 * OpenAI Agents SDK의 Runner.run(max_turns=30)에 해당.
 */
public class AgentRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatClient.Builder chatClientBuilder;

    public AgentRunner(ChatClient.Builder chatClientBuilder) {
        this.chatClientBuilder = chatClientBuilder;
    }

    /**
     * 에이전트 루프 결과.
     */
    public record AgentResult(
            String finalOutput,
            boolean directReturn,
            boolean maxTurnsExceeded
    ) {}

    /**
     * 멀티턴 에이전트 루프 실행.
     *
     * @param systemPrompt  시스템 프롬프트
     * @param userMessage   사용자 질의
     * @param maxTurns      최대 턴 수
     * @param toolCallbacks 도구 콜백 맵 (도구명 → 실행 함수)
     * @return 에이전트 결과
     */
    public AgentResult run(String systemPrompt, String userMessage, int maxTurns,
                           Map<String, Function<Map<String, Object>, String>> toolCallbacks) {

        var tracker = new TurnTracker(maxTurns);
        var messages = new ArrayList<Message>();
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(userMessage));

        boolean directReturn = false;

        while (tracker.hasRemaining()) {
            tracker.increment();

            try {
                var chatClient = chatClientBuilder.build();
                var response = chatClient.prompt()
                        .messages(messages)
                        .call()
                        .chatResponse();

                if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
                    log.warn("빈 응답 수신 (turn {})", tracker.current());
                    break;
                }

                var generation = response.getResults().getFirst();
                var assistantMsg = generation.getOutput();

                // 도구 호출이 있는지 확인
                var toolCalls = assistantMsg.getToolCalls();
                if (toolCalls == null || toolCalls.isEmpty()) {
                    // 도구 호출 없음 → 최종 텍스트 응답
                    messages.add(assistantMsg);
                    return new AgentResult(
                            assistantMsg.getText(),
                            directReturn,
                            false
                    );
                }

                // 도구 호출 처리
                messages.add(assistantMsg);
                var toolResponses = new ArrayList<ToolResponseMessage.ToolResponse>();

                for (var toolCall : toolCalls) {
                    var toolName = toolCall.name();
                    var callback = toolCallbacks.get(toolName);

                    String result;
                    if (callback != null) {
                        try {
                            @SuppressWarnings("unchecked")
                            var args = MAPPER.readValue(toolCall.arguments(), Map.class);
                            result = callback.apply(args);

                            // return_directly 플래그 확인
                            if (args.containsKey("return_directly") && Boolean.TRUE.equals(args.get("return_directly"))) {
                                directReturn = true;
                            }
                        } catch (Exception e) {
                            log.error("도구 실행 오류: {}", toolName, e);
                            result = "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}";
                        }
                    } else {
                        result = "{\"success\": false, \"error\": \"Unknown tool: " + toolName + "\"}";
                    }

                    toolResponses.add(new ToolResponseMessage.ToolResponse(
                            toolCall.id(), toolCall.name(), result));
                }

                messages.add(ToolResponseMessage.builder()
                        .responses(toolResponses)
                        .metadata(Map.of())
                        .build());

            } catch (Exception e) {
                log.error("에이전트 루프 오류 (turn {})", tracker.current(), e);
                return new AgentResult(
                        "Error: " + e.getMessage(),
                        directReturn,
                        false
                );
            }
        }

        return new AgentResult(null, directReturn, true);
    }
}
