package com.travel.agent.exception;

/** HIGH 风险操作触发时抛出，转交给 Human-in-the-loop 确认流程 */
public class HumanConfirmationRequiredException extends RuntimeException {

    public HumanConfirmationRequiredException(String message) {
        super(message);
    }
}
