package com.mindhaven.security;

import com.mindhaven.security.LoginIdentity;

import java.util.*;

public record AuthAccount(LoginIdentity identity, String passwordHash) {
}
