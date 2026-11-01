package com.agafari.com.dto.request;

/** AGF E4-M4: the owner withdraws their pending request ({@code status = WITHDRAWN}). */
public record PlanRequestWithdrawRequest(String status, Integer rowVersion) {
}
