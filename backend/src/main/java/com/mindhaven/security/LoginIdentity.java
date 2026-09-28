package com.mindhaven.security;


public record LoginIdentity(String tenantId, String tenantSlug, String tenantName, String userId, String username,
                            String role) {
    public boolean admin() {
        return role.equals("ADMIN");
    }
}
