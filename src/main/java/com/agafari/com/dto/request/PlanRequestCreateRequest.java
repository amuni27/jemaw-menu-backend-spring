package com.agafari.com.dto.request;

/** AGF E4-M4: the owner's plan request for their own restaurant (the restaurant comes from the token). */
public record PlanRequestCreateRequest(String planCode, Integer expectedConfigVersion) {
}
