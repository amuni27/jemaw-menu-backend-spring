package com.agafari.com.dto.response;

/** AGF E4-M1: the signed-in Super Admin. */
public record AdminSummary(String id, String email, String fullName, String role) {
}
