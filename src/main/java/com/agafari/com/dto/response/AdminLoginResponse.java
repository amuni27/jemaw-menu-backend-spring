package com.agafari.com.dto.response;

/** AGF E4-M1: a short-lived Super Admin token and the admin it belongs to. */
public record AdminLoginResponse(String token, AdminSummary admin) {
}
