package com.mindhaven.application.chat;

import com.mindhaven.domain.model.CitationCheck;
import com.mindhaven.domain.model.Models.Citation;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class CitationVerifier {
  private static final Pattern REFERENCES =
      Pattern.compile(
          "(?<!\\\\)\\[([A-Za-z0-9][A-Za-z0-9_.:\\-]*(?:\\s*[,，、]\\s*[A-Za-z0-9][A-Za-z0-9_.:\\-]*)*)\\](?!\\()");

  public CitationCheck verify(String answer, List<Citation> sources) {
    Set<String> allowed = new HashSet<>();
    sources.forEach(s -> allowed.add(s.id()));
    Set<String> cited = new LinkedHashSet<>(), invalid = new LinkedHashSet<>();
    var matcher = REFERENCES.matcher(answer);
    while (matcher.find()) {
      for (String id : matcher.group(1).split("\\s*[,，、]\\s*")) {
        if (allowed.contains(id)) cited.add(id);
        else invalid.add(id);
      }
    }
    var status =
        !invalid.isEmpty()
            ? CitationCheck.Status.INVALID
            : !cited.isEmpty()
                ? CitationCheck.Status.VALID
                : sources.isEmpty()
                    ? CitationCheck.Status.NOT_REQUIRED
                    : CitationCheck.Status.MISSING;
    return new CitationCheck(status, List.copyOf(cited), List.copyOf(invalid));
  }
}
