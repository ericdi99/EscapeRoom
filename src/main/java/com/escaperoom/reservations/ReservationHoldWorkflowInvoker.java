package com.escaperoom.reservations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.NotNull;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.StartExecutionRequest;
import software.amazon.awssdk.services.sfn.model.StartExecutionResponse;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;


public class ReservationHoldWorkflowInvoker {

    private static final Logger log = LoggerFactory.getLogger(ReservationHoldWorkflowInvoker.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Step Function ARN and retry parameters defined internally
    private static final String STATE_MACHINE_ARN =
            "arn:aws:states:us-east-1:785716434968:stateMachine:ReservationHoldExpiryWorkflow";
    private static final int MAX_RETRIES = 3;
    private static final long BASE_DELAY_MS = 200L;

    private final SfnClient sfnClient;

    public ReservationHoldWorkflowInvoker() {
        this.sfnClient = SfnClient.create();
    }

    /**
     * Starts the Step Function workflow for the given reservationId with retry on failure.
     */
    public void startHoldExpiryWorkflow(@NotNull String reservationId) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                invokeStepFunction(reservationId);
                log.info("Started hold expiry workflow for reservation {}", reservationId);
                return; // success, exit
            } catch (Exception e) {
                if (attempt == MAX_RETRIES) {
                    log.error("Failed to start hold expiry workflow for reservation {} after {} attempts: {}",
                            reservationId, MAX_RETRIES, e.getMessage(), e);
                } else {
                    long delay = BASE_DELAY_MS * (1L << (attempt - 1));
                    log.warn("Attempt {}/{} failed for reservation {}. Retrying in {} ms: {}",
                            attempt, MAX_RETRIES, reservationId, delay, e.getMessage());
                    sleep(delay);
                }
            }
        }
    }

    /**
     * Internal method to actually invoke the Step Function.
     */
    private void invokeStepFunction(String reservationId) throws Exception {
        // Prepare input JSON
        String inputJson = MAPPER.writeValueAsString(Collections.singletonMap("reservationId", reservationId));

        StartExecutionRequest request = StartExecutionRequest.builder()
                .stateMachineArn(STATE_MACHINE_ARN)
                .input(inputJson)
                .build();

        StartExecutionResponse response = sfnClient.startExecution(request);
        log.debug("ExecutionArn={}", response.executionArn());
    }

    /**
     * Simple helper to sleep.
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("Retry sleep interrupted");
        }
    }
}

