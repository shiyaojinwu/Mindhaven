package com.mindhaven.model.vo;

import java.util.List;

public record VideoConfigResponse(long maxBytes, List<String> formats, String storage) {
}
