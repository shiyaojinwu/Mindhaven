package com.mindhaven;

import com.mindhaven.service.ai.RunContext;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RunContextBudgetTest {
    @Test
    void completedCallRefundsUnusedReservation() throws Exception {
        try (var scope = RunContext.open("test", () -> false, 10000)) {
            RunContext.reserve(8400);
            RunContext.settle(8400, 2000, 100);
            assertThat(RunContext.remaining()).isEqualTo(7900);
            RunContext.reserve(7000);
            assertThat(RunContext.remaining()).isEqualTo(900);
        }
    }
    @Test
    void failedCallsRetainReservationAndOverrunsExhaustBudget() throws Exception {
        try (var scope = RunContext.open("test", () -> false, 1000)) {
            RunContext.reserve(800);
            assertThat(RunContext.remaining()).isEqualTo(200);
            RunContext.settle(800, 1100, 100);
            assertThat(RunContext.remaining()).isZero();
            assertThatThrownBy(() -> RunContext.reserve(1)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
