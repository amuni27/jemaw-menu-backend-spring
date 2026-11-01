package com.agafari.com.dto.request;

import java.util.List;

/** AGF E4-M2: a new configuration. Plain strings so every invalid value gets a field error (and an audit row). */
public record MonetizationConfigRequest(String setting, List<String> models, Integer expectedVersion) {
}
