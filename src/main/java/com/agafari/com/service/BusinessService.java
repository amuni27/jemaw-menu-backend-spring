package com.agafari.com.service;

import com.agafari.com.dto.request.UpdateBusinessHoursRequest;
import com.agafari.com.dto.response.BusinessHoursResponse;

import java.util.List;

public interface BusinessService {
    List<BusinessHoursResponse> getBusinessHours(String businessId);
    List<BusinessHoursResponse> updateBusinessHours(String businessId, UpdateBusinessHoursRequest request);
}