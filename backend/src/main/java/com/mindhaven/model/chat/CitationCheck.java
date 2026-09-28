package com.mindhaven.model.chat;

import java.util.List;

/**
 * Checks source identifiers only; it does not establish semantic support for a claim.
 */
public record CitationCheck(Status status, List<String> citedIds, List<String> invalidIds) {
    public enum Status {
        NOT_REQUIRED, MISSING, INVALID, VALID
    }

    public boolean passed() {
        return status == Status.VALID || status == Status.NOT_REQUIRED;
    }
}
