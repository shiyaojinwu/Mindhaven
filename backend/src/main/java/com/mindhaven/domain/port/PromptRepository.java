package com.mindhaven.domain.port;

public interface PromptRepository {
  record Template(String name, String text, String hash) {}

  Template get(String name);
}
