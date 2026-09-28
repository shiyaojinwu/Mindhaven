package com.mindhaven.domain.port;

import com.mindhaven.domain.model.AiUsage;
import java.util.List;

public interface UsageRepository {
  void save(AiUsage usage);

  List<AiUsage> recent(String runId);
}
