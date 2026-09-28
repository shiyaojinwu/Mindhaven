package com.mindhaven.security;

import com.mindhaven.security.LoginIdentity;

import java.util.*;

public record LoginSession(String token, LoginIdentity identity) {
}
